package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlResult
import io.github.xxfast.t3code.protocol.generated.DispatchCommandResult
import io.github.xxfast.t3code.protocol.generated.PersistChatAttachmentsInput
import io.github.xxfast.t3code.protocol.generated.PersistChatAttachmentsResult
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.future.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The real [T3Client]: HTTP for the handshake, one Effect RPC socket for everything else. */
public class WebSocketT3Client internal constructor(
  private val auth: suspend () -> T3Auth,
  private val socketUri: suspend () -> URI,
  private val credential: suspend () -> Credential?,
  private val scope: CoroutineScope,
  private val http: HttpClient,
) : T3Client {
  /** [baseUrl] is read on every connection attempt, so a settings change lands on reconnect. */
  public constructor(
    baseUrl: suspend () -> String,
    credential: suspend () -> Credential?,
    scope: CoroutineScope,
    http: HttpClient = defaultHttpClient(),
  ) : this(
    auth = { T3Auth(baseUrl(), http) },
    socketUri = { URI("${baseUrl().trimEnd('/').replaceFirst("http", "ws")}$SOCKET_PATH") },
    credential = credential,
    scope = scope,
    http = http,
  )

  private val _connection: MutableStateFlow<ConnectionState> =
    MutableStateFlow(ConnectionState.Disconnected)
  private val _projects: MutableStateFlow<List<Project>> = MutableStateFlow(emptyList())
  private val _threads: MutableStateFlow<List<Thread>> = MutableStateFlow(emptyList())
  private val active: MutableStateFlow<RpcConnection?> = MutableStateFlow(null)
  private var job: Job? = null

  /** [serverInfo] for the connection it was fetched on. */
  @Volatile private var info: Pair<RpcConnection, ServerInfo>? = null

  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
  override val projects: StateFlow<List<Project>> = _projects.asStateFlow()
  override val threads: StateFlow<List<Thread>> = _threads.asStateFlow()

  @Synchronized
  override fun connect() {
    val previous: Job? = job
    job = scope.launch {
      previous?.cancelAndJoin()
      run()
    }
  }

  @Synchronized
  override fun disconnect() {
    job?.cancel()
    job = null
    _connection.value = ConnectionState.Disconnected
  }

  override fun thread(id: String): Flow<ThreadView> = flow {
    val payload: JsonObject = buildJsonObject { put("threadId", id) }
    // A resubscription starts with a snapshot, and reduce() builds a fresh view from any snapshot
    var view: ThreadView? = null
    resubscribing("orchestration.subscribeThread", payload, JsonObject.serializer())
      .collect { item ->
        val next: ThreadView = reduce(view, id, item) ?: return@collect
        view = next
        emit(next)
      }
  }

  override suspend fun readFile(cwd: String, path: String): FileContents {
    val payload: JsonObject = buildJsonObject {
      put("cwd", cwd)
      put("relativePath", path)
    }
    return decode(FileContents.serializer(), call("projects.readFile", payload))
  }

  override suspend fun projectFavicon(cwd: String, path: String?): ByteArray? {
    val payload: JsonObject = buildJsonObject {
      putJsonObject("resource") {
        put("_tag", "project-favicon")
        put("cwd", cwd)
        if (path != null) put("path", path)
      }
    }
    val relative: String =
      (call("assets.createUrl", payload) as? JsonObject)?.string("relativeUrl") ?: return null
    // Upstream's marker for "this project has no favicon", see shared/projectFavicon.ts
    if (relative.substringAfterLast('/') == "project-favicon-missing") return null
    val request: HttpRequest = asset(relative).GET().build()
    return send(request, relative).takeIf { it.isNotEmpty() }
  }

  override suspend fun dispatch(command: OrchestrationV2Command): Long {
    val payload: JsonElement =
      T3Json.encodeToJsonElement(OrchestrationV2Command.serializer(), command)
    val result: JsonElement = call("orchestration.dispatchCommand", payload)
    return decode(DispatchCommandResult.serializer(), result).sequence
  }

  override fun terminal(input: TerminalAttachInput): Flow<TerminalAttachStreamEvent> {
    val payload: JsonElement = T3Json.encodeToJsonElement(TerminalAttachInput.serializer(), input)
    return resubscribing("terminal.attach", payload, TerminalAttachStreamEvent.serializer())
  }

  override suspend fun terminalWrite(threadId: String, terminalId: String, data: String) {
    // Upstream rejects empty writes and caps one at 65,536 chars, so split, never mid-surrogate
    var start = 0
    while (start < data.length) {
      var end: Int = minOf(start + TERMINAL_WRITE_MAX_CHARS, data.length)
      if (end < data.length && data[end - 1].isHighSurrogate()) end--
      val payload: JsonObject = buildJsonObject {
        put("threadId", threadId)
        put("terminalId", terminalId)
        put("data", data.substring(start, end))
      }
      call("terminal.write", payload)
      start = end
    }
  }

  override suspend fun terminalResize(threadId: String, terminalId: String, cols: Int, rows: Int) {
    val payload: JsonObject = buildJsonObject {
      put("threadId", threadId)
      put("terminalId", terminalId)
      put("cols", cols.coerceIn(1, TERMINAL_MAX_COLS))
      put("rows", rows.coerceIn(1, TERMINAL_MAX_ROWS))
    }
    call("terminal.resize", payload)
  }

  override fun vcsStatus(cwd: String): Flow<VcsStatusStreamEvent> {
    val payload: JsonObject = buildJsonObject {
      put("cwd", cwd)
      put("includeRemote", true)
    }
    return resubscribing("subscribeVcsStatus", payload, VcsStatusStreamEvent.serializer())
  }

  override suspend fun createUploadUrl(
    input: AttachmentCreateUploadUrlInput,
  ): AttachmentCreateUploadUrlResult {
    val payload: JsonElement =
      T3Json.encodeToJsonElement(AttachmentCreateUploadUrlInput.serializer(), input)
    val result: JsonElement = call("attachments.createUploadUrl", payload)
    return decode(AttachmentCreateUploadUrlResult.serializer(), result)
  }

  override suspend fun upload(relativeUrl: String, mimeType: String, bytes: ByteArray) {
    val request: HttpRequest = asset(relativeUrl)
      .header("Content-Type", mimeType)
      .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
      .build()
    send(request, relativeUrl)
  }

  override suspend fun persistAttachments(
    input: PersistChatAttachmentsInput,
  ): PersistChatAttachmentsResult {
    val payload: JsonElement =
      T3Json.encodeToJsonElement(PersistChatAttachmentsInput.serializer(), input)
    val result: JsonElement = call("assets.persistChatAttachments", payload)
    return decode(PersistChatAttachmentsResult.serializer(), result)
  }

  override suspend fun serverInfo(): ServerInfo {
    val rpc: RpcConnection = active.value ?: throw T3Exception.Transport("Not connected")
    info?.takeIf { (connection, _) -> connection === rpc }?.let { (_, cached) -> return cached }
    val config: JsonObject = call("server.getConfig", JsonObject(emptyMap()), rpc) as? JsonObject
      ?: throw T3Exception.Transport("No config from server.getConfig")
    val capabilities: JsonObject? =
      (config["environment"] as? JsonObject)?.get("capabilities") as? JsonObject
    val resolved: Boolean? =
      (capabilities?.get("serverResolvedCommandContext") as? JsonPrimitive)?.booleanOrNull
    val fresh = ServerInfo(
      providers = decodeEach(ServerProvider.serializer(), config["providers"]),
      serverResolvedCommandContext = resolved ?: false,
    )
    info = rpc to fresh
    return fresh
  }

  override suspend fun turnDiff(threadId: String, fromTurn: Long, toTurn: Long): String {
    val payload: JsonObject = buildJsonObject {
      put("threadId", threadId)
      put("fromTurnCount", fromTurn)
      put("toTurnCount", toTurn)
    }
    return diffOf(call("orchestration.getTurnDiff", payload))
  }

  override suspend fun fullThreadDiff(threadId: String, toTurn: Long): String {
    val payload: JsonObject = buildJsonObject {
      put("threadId", threadId)
      put("toTurnCount", toTurn)
    }
    return diffOf(call("orchestration.getFullThreadDiff", payload))
  }

  private suspend fun run() {
    var attempt = 0
    while (true) {
      _connection.value = ConnectionState.Connecting
      try {
        val auth: T3Auth = auth()
        auth.probe()
        val credential: Credential = credential()
          ?: return run { _connection.value = ConnectionState.Unpaired }
        val rpc: RpcConnection =
          RpcConnection.open(http, socketUri(), credential.accessToken, scope)
        try {
          val session: ServerSession = auth.probe(credential)
          // The socket goes live before Connected does, so a caller that waits for Connected
          // can dispatch straight away
          info = null
          active.value = rpc
          attempt = 0
          _connection.value = ConnectionState.Connected(session)
          rpc.stream("orchestration.subscribeShell", JsonObject(emptyMap())).collect(::applyShell)
          throw T3Exception.Transport("The server ended the shell subscription")
        } finally {
          active.compareAndSet(rpc, null)
          rpc.close()
        }
      } catch (e: T3Exception.AuthInvalid) {
        _connection.value = ConnectionState.AuthError(e.message ?: "Credential rejected")
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _connection.value = ConnectionState.Error(e.message ?: e.javaClass.simpleName)
      }
      delay(minOf(MAX_BACKOFF_MILLIS, 1_000L shl attempt.coerceAtMost(5)))
      attempt++
    }
  }

  private fun applyShell(element: JsonElement) {
    val item: JsonObject = element as? JsonObject ?: return
    when (item.string("kind")) {
      "snapshot" -> {
        val snapshot: JsonObject = item["snapshot"] as? JsonObject ?: return
        val projects: List<Project> = decodeEach(Project.serializer(), snapshot["projects"])
        val threads: List<Thread> = decodeEach(Thread.serializer(), snapshot["threads"]) +
          decodeEach(Thread.serializer(), snapshot["archivedThreads"])
        if (item.containsKey("resolvedRepositoryIdentityRoots")) {
          // A partial follow-up: only the projects whose repository identity just resolved, and
          // no threads. Replacing state with it would empty the list. Seen live on 2026-10-19.
          _projects.update { current -> current.upsertAll(projects, Project::id) }
          _threads.update { current -> current.upsertAll(threads, Thread::id) }
        } else {
          _projects.value = projects
          _threads.value = threads
        }
      }

      "thread.updated" -> decodeOrNull(Thread.serializer(), item["thread"])
        ?.let { thread -> _threads.update { it.upsert(thread, Thread::id) } }

      "thread.removed" -> item.string("threadId")
        ?.let { id -> _threads.update { threads -> threads.filterNot { it.id == id } } }

      "project.updated" -> decodeOrNull(Project.serializer(), item["project"])
        ?.let { project -> _projects.update { it.upsert(project, Project::id) } }

      "project.removed" -> item.string("projectId")
        ?.let { id -> _projects.update { projects -> projects.filterNot { it.id == id } } }
    }
  }

  /** The next view after [item], or null when [item] changes nothing this client renders. */
  private fun reduce(view: ThreadView?, id: String, item: JsonObject): ThreadView? {
    if (item.string("kind") == "snapshot") {
      val projection: JsonObject = item["projection"] as? JsonObject ?: return null
      val shell: Thread? = _threads.value.find { it.id == id }
      val thread: Thread = shellOf(projection["thread"], shell) ?: return null
      val visible: List<JsonElement> = (projection["visibleTurnItems"] as? JsonArray).orEmpty()
      return ThreadView(
        thread = thread,
        runs = decodeEach(Run.serializer(), projection["runs"]),
        items = visible.mapNotNull { entry ->
          decodeOrNull(TurnItem.serializer(), (entry as? JsonObject)?.get("item"))
        },
        runtimeRequests = decodeEach(RuntimeRequest.serializer(), projection["runtimeRequests"]),
        providerSessions =
          decodeEach(ProviderSession.serializer(), projection["providerSessions"]),
        checkpoints = decodeEach(Checkpoint.serializer(), projection["checkpoints"]),
      )
    }
    if (view == null || item.string("kind") != "event") return null
    val event: JsonObject = item["event"] as? JsonObject ?: return null
    val type: String = event.string("type") ?: return null
    val payload: JsonElement? = event["payload"]
    return when {
      type == "turn-item.updated" -> decodeOrNull(TurnItem.serializer(), payload)
        ?.let { turnItem -> view.copy(items = view.items.upsert(turnItem, TurnItem::id)) }

      type == "run.created" || type == "run.updated" -> decodeOrNull(Run.serializer(), payload)
        ?.let { run -> view.copy(runs = view.runs.upsert(run, Run::id)) }

      // Mirrors client-runtime/src/state/orchestrationV2Projection.ts
      type == "runtime-request.updated" -> decodeOrNull(RuntimeRequest.serializer(), payload)
        ?.let { request ->
          view.copy(runtimeRequests = view.runtimeRequests.upsert(request, RuntimeRequest::id))
        }

      type == "provider-session.attached" || type == "provider-session.updated" ->
        decodeOrNull(ProviderSession.serializer(), payload)?.let { session ->
          view.copy(providerSessions = view.providerSessions.upsert(session, ProviderSession::id))
        }

      type == "provider-session.detached" -> (payload as? JsonObject)?.string("providerSessionId")
        ?.let { id ->
          view.copy(providerSessions = view.providerSessions.filterNot { it.id == id })
        }

      type == "checkpoint.captured" -> decodeOrNull(Checkpoint.serializer(), payload)
        ?.let { checkpoint ->
          view.copy(checkpoints = view.checkpoints.upsert(checkpoint, Checkpoint::id))
        }

      type.startsWith("thread.") -> shellOf(payload, view.thread)
        ?.let { thread -> view.copy(thread = thread) }

      else -> null
    }
  }

  /**
   * [tag]'s stream, decoded with [serializer], undecodable values dropped. When the socket dies
   * under it, it waits for the next connection and subscribes again, so the server replays. Any
   * other failure, a `Die` or an interrupted exit on a live socket, ends the flow with it.
   */
  private fun <T> resubscribing(
    tag: String,
    payload: JsonElement,
    serializer: KSerializer<T>,
  ): Flow<T> = flow {
    while (true) {
      val rpc: RpcConnection = active.filterNotNull().first()
      try {
        rpc.stream(tag, payload).collect { value ->
          decodeOrNull(serializer, value)?.let { emit(it) }
        }
        return@flow
      } catch (e: T3Exception.Transport) {
        if (!rpc.closed.isCompleted) throw e
        active.first { it !== rpc }
      }
    }
  }

  /** A request for one of the server's signed asset URLs, on the socket's host, with the bearer. */
  private suspend fun asset(relativeUrl: String): HttpRequest.Builder {
    val socket: URI = socketUri()
    val scheme: String = if (socket.scheme == "wss") "https" else "http"
    val base: String = URI(scheme, null, socket.host, socket.port, null, null, null).toString()
    val request: HttpRequest.Builder = HttpRequest.newBuilder(URI(base + relativeUrl))
    credential()?.let { request.header("Authorization", "Bearer ${it.accessToken}") }
    return request
  }

  private suspend fun send(request: HttpRequest, relativeUrl: String): ByteArray {
    val response: HttpResponse<ByteArray> = try {
      http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
    } catch (e: IOException) {
      throw T3Exception.Transport("Can't reach $relativeUrl: ${e.message}", e)
    }
    if (response.statusCode() !in 200..299) {
      throw T3Exception.Transport("HTTP ${response.statusCode()} from $relativeUrl")
    }
    return response.body()
  }

  private suspend fun call(
    tag: String,
    payload: JsonElement,
    rpc: RpcConnection = active.value ?: throw T3Exception.Transport("Not connected"),
  ): JsonElement {
    try {
      return rpc.call(tag, payload)
    } catch (e: T3Exception.AuthInvalid) {
      synchronized(this) {
        job?.cancel()
        job = null
        _connection.value = ConnectionState.AuthError(e.message ?: "Credential rejected")
      }
      throw e
    }
  }

  private fun diffOf(result: JsonElement): String =
    (result as? JsonObject)?.string("diff") ?: throw T3Exception.Transport("No diff in $result")

  private companion object {
    const val MAX_BACKOFF_MILLIS: Long = 30_000
    const val SOCKET_PATH: String = "/ws?orchestrationProtocol=2"

    // Upstream's bounds, contracts/src/terminal.ts
    const val TERMINAL_WRITE_MAX_CHARS: Int = 65_536
    const val TERMINAL_MAX_COLS: Int = 1_000
    const val TERMINAL_MAX_ROWS: Int = 500
  }
}

/**
 * The thread view and `thread.*` events carry an `AppThread`, which lacks the shell summaries
 * (status, counts, latest message, pending request). Lay it over the row we already have, or over
 * an idle one when the shell hasn't listed the thread yet.
 */
private fun shellOf(app: JsonElement?, shell: Thread?): Thread? {
  val fields: JsonObject = app as? JsonObject ?: return null
  val base: JsonObject = shell
    ?.let { T3Json.encodeToJsonElement(Thread.serializer(), it) as JsonObject }
    ?: IdleShell
  return decodeOrNull(Thread.serializer(), JsonObject(base + fields))
}

private val IdleShell: JsonObject = buildJsonObject {
  put("status", "idle")
  put("itemCount", 0)
  put("visibleItemCount", 0)
  put("hasActionableProposedPlan", false)
}

private fun <T> List<T>.upsertAll(items: List<T>, id: (T) -> String): List<T> =
  items.fold(this) { acc, item -> acc.upsert(item, id) }

private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
  val index: Int = indexOfFirst { id(it) == id(item) }
  return if (index < 0) this + item else toMutableList().apply { set(index, item) }
}

private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = try {
  T3Json.decodeFromJsonElement(serializer, element)
} catch (e: IllegalArgumentException) {
  throw T3Exception.Transport("Unexpected answer: ${e.message}", e)
}

private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement?): T? =
  element?.let { runCatching { T3Json.decodeFromJsonElement(serializer, it) }.getOrNull() }

private fun <T> decodeEach(serializer: KSerializer<T>, element: JsonElement?): List<T> =
  (element as? JsonArray).orEmpty().mapNotNull { decodeOrNull(serializer, it) }
