package io.github.xxfast.t3code.protocol

import java.net.URI
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    while (true) {
      val rpc: RpcConnection = active.filterNotNull().first()
      try {
        var view: ThreadView? = null
        rpc.stream("orchestration.subscribeThread", payload).collect { item ->
          val next: ThreadView? = (item as? JsonObject)?.let { reduce(view, id, it) }
          if (next != null) {
            view = next
            emit(next)
          }
        }
        return@flow
      } catch (e: T3Exception.Transport) {
        active.first { it !== rpc }
      }
    }
  }

  override suspend fun readFile(cwd: String, path: String): FileContents {
    val payload: JsonObject = buildJsonObject {
      put("cwd", cwd)
      put("relativePath", path)
    }
    return decode(FileContents.serializer(), call("projects.readFile", payload))
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
          _connection.value = ConnectionState.Connected(auth.probe(credential))
          active.value = rpc
          attempt = 0
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

      type.startsWith("thread.") -> shellOf(payload, view.thread)
        ?.let { thread -> view.copy(thread = thread) }

      else -> null
    }
  }

  private suspend fun call(tag: String, payload: JsonObject): JsonElement {
    val rpc: RpcConnection = active.value ?: throw T3Exception.Transport("Not connected")
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
