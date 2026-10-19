package io.github.xxfast.t3code.protocol

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One Effect RPC socket. Frames from the JDK listener go through [inbound] to a single
 * dispatcher coroutine, which routes `Chunk` and `Exit` to the inbox of the request they belong
 * to. Each request drives its own inbox, so a slow stream collector only holds back its own Ack.
 */
internal class RpcConnection private constructor(
  private val socket: WebSocket,
  private val inbound: ReceiveChannel<Inbound>,
  parent: CoroutineScope,
) {
  private val job: Job = SupervisorJob(parent.coroutineContext[Job])
  private val scope: CoroutineScope = CoroutineScope(parent.coroutineContext + job)
  private val ids: AtomicLong = AtomicLong(0)
  private val pending: ConcurrentHashMap<String, Channel<JsonObject>> = ConcurrentHashMap()
  private val sending: Mutex = Mutex()

  @Volatile private var failure: T3Exception? = null

  val closed: CompletableDeferred<Unit> = CompletableDeferred()

  init {
    scope.launch { dispatch() }
    scope.launch {
      while (true) {
        delay(PING_INTERVAL_MILLIS)
        try {
          send(frame("Ping"))
        } catch (_: T3Exception) {
        }
      }
    }
  }

  suspend fun call(tag: String, payload: JsonElement): JsonElement = exchange(tag, payload) {}

  fun stream(tag: String, payload: JsonElement): Flow<JsonElement> = flow {
    exchange(tag, payload) { values -> values.forEach { emit(it) } }
  }

  fun close() {
    shutdown(T3Exception.Transport("Connection closed"))
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete { _, _ -> socket.abort() }
    } catch (_: Exception) {
      socket.abort()
    }
  }

  /** Request, then Ack every Chunk once [onChunk] has taken its values, until the Exit. */
  private suspend fun exchange(
    tag: String,
    payload: JsonElement,
    onChunk: suspend (List<JsonElement>) -> Unit,
  ): JsonElement {
    val id: String = ids.getAndIncrement().toString()
    val inbox: Channel<JsonObject> = Channel(Channel.UNLIMITED)
    pending[id] = inbox
    failure?.let { pending.remove(id); throw it }
    var finished = false
    try {
      send(
        buildJsonObject {
          put("_tag", "Request")
          put("id", id)
          put("tag", tag)
          put("payload", payload)
          put("headers", buildJsonArray {})
        },
      )
      while (true) {
        val message: JsonObject = inbox.receive()
        when (message.string("_tag")) {
          "Chunk" -> {
            onChunk(message["values"] as? JsonArray ?: emptyList())
            send(frame("Ack", id))
          }

          "Exit" -> {
            finished = true
            return exitValue(message)
          }
        }
      }
    } finally {
      pending.remove(id)
      if (!finished && failure == null) {
        withContext(NonCancellable) {
          try {
            send(frame("Interrupt", id))
          } catch (_: T3Exception) {
          }
        }
      }
    }
  }

  private suspend fun send(message: JsonObject) {
    val text: String = message.toString()
    withContext(NonCancellable) {
      sending.withLock {
        try {
          socket.sendText(text, true).await()
        } catch (e: Exception) {
          throw T3Exception.Transport("Send failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
      }
    }
  }

  private suspend fun dispatch() {
    for (event in inbound) {
      when (event) {
        is Inbound.Text -> parseFrames(event.text).forEach(::route)
        is Inbound.Closed -> return shutdown(
          T3Exception.Transport("Socket closed (${event.code}) ${event.reason}".trim()),
        )

        is Inbound.Failed -> return shutdown(
          T3Exception.Transport(event.error.message ?: "Socket failed", event.error),
        )
      }
    }
  }

  private fun route(message: JsonObject) {
    when (message.string("_tag")) {
      "Chunk", "Exit" -> message.string("requestId")?.let { pending[it]?.trySend(message) }
      "Defect" -> failPending(T3Exception.Transport("Server defect: ${message["defect"]}"))
    }
  }

  private fun failPending(error: T3Exception) {
    pending.keys.toList().forEach { id -> pending.remove(id)?.close(error) }
  }

  @Synchronized
  private fun shutdown(error: T3Exception) {
    if (failure != null) return
    failure = error
    failPending(error)
    closed.complete(Unit)
    scope.cancel()
  }

  sealed interface Inbound {
    class Text(val text: String) : Inbound
    class Closed(val code: Int, val reason: String) : Inbound
    class Failed(val error: Throwable) : Inbound
  }

  /** The JDK socket is pull-based: ask for the next message after every callback. */
  private class Listener(private val inbound: SendChannel<Inbound>) : WebSocket.Listener {
    private val buffer: StringBuilder = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
      webSocket.request(1)
    }

    override fun onText(
      webSocket: WebSocket,
      data: CharSequence,
      last: Boolean,
    ): CompletionStage<*>? {
      buffer.append(data)
      if (last) {
        inbound.trySend(Inbound.Text(buffer.toString()))
        buffer.setLength(0)
      }
      webSocket.request(1)
      return null
    }

    override fun onClose(
      webSocket: WebSocket,
      statusCode: Int,
      reason: String,
    ): CompletionStage<*>? {
      inbound.trySend(Inbound.Closed(statusCode, reason))
      return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
      inbound.trySend(Inbound.Failed(error))
    }
  }

  companion object {
    private const val PING_INTERVAL_MILLIS: Long = 5_000

    suspend fun open(
      http: HttpClient,
      uri: URI,
      accessToken: String,
      parent: CoroutineScope,
    ): RpcConnection {
      val inbound: Channel<Inbound> = Channel(Channel.UNLIMITED)
      val socket: WebSocket = try {
        http.newWebSocketBuilder()
          .header("Authorization", "Bearer $accessToken")
          .buildAsync(uri, Listener(inbound))
          .await()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw upgradeFailure(e)
      }
      return RpcConnection(socket, inbound, parent)
    }

    private fun upgradeFailure(error: Throwable): T3Exception {
      val handshake: WebSocketHandshakeException = generateSequence(error) { it.cause }
        .filterIsInstance<WebSocketHandshakeException>()
        .firstOrNull()
        ?: return T3Exception.Transport(error.message ?: "Can't open the socket", error)
      val status: Int = handshake.response.statusCode()
      val body: JsonObject? = (handshake.response.body() as? String)?.let(::parseObject)
      if (status == 401) {
        return body?.toT3Exception("Unauthorized") as? T3Exception.AuthInvalid
          ?: T3Exception.AuthInvalid("The server rejected the credential", reason = null)
      }
      return body?.toT3Exception("HTTP $status")
        ?: T3Exception.Transport("Socket upgrade failed with HTTP $status", handshake)
    }

    private fun frame(tag: String, requestId: String? = null): JsonObject = buildJsonObject {
      put("_tag", tag)
      if (requestId != null) put("requestId", requestId)
    }

    private fun exitValue(message: JsonObject): JsonElement {
      val exit: JsonObject = message["exit"] as? JsonObject
        ?: throw T3Exception.Transport("Malformed Exit: $message")
      if (exit.string("_tag") == "Success") return exit["value"] ?: JsonNull
      val causes: List<JsonObject> = when (val cause: JsonElement? = exit["cause"]) {
        is JsonArray -> cause.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(cause)
        else -> emptyList()
      }
      causes.firstOrNull { it.string("_tag") == "Fail" }?.let { fail ->
        val error: JsonObject = fail["error"] as? JsonObject ?: JsonObject(emptyMap())
        throw error.toT3Exception("Request failed")
          ?: T3Exception.Transport("Untagged failure: $error")
      }
      causes.firstOrNull { it.string("_tag") == "Die" }?.let { die ->
        val defect: JsonElement? = die["defect"]
        val text: String = (defect as? JsonObject)?.string("message") ?: defect.toString()
        throw T3Exception.Transport("Server defect: $text")
      }
      throw T3Exception.Transport("Interrupted by the server")
    }

    private fun parseFrames(text: String): List<JsonObject> {
      val element: JsonElement? = runCatching { T3Json.parseToJsonElement(text) }.getOrNull()
      return when (element) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.filterIsInstance<JsonObject>()
        else -> emptyList()
      }
    }
  }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun parseObject(text: String): JsonObject? =
  runCatching { T3Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

/** Upstream's tagged errors, from an HTTP body or an RPC `Fail`. */
internal fun JsonObject.toT3Exception(fallback: String): T3Exception? {
  val tag: String = string("_tag") ?: return null
  val message: String = string("message") ?: string("reason") ?: string("code") ?: fallback
  return when (tag) {
    "EnvironmentAuthInvalidError" -> T3Exception.AuthInvalid(message, string("reason"))
    "EnvironmentAuthorizationError", "EnvironmentScopeRequiredError" -> T3Exception.ScopeRequired(
      message = message,
      requiredScope = string("requiredScope"),
      requiredPermission = string("requiredPermission"),
    )

    else -> T3Exception.Rpc(tag, message)
  }
}
