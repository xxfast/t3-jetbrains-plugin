package io.github.xxfast.t3code.protocol

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

fun fixture(name: String): String {
  val resource = requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { name }
  return resource.readText()
}

/** `runTest`, but on a real dispatcher: these tests talk to sockets, virtual time won't do. */
fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 30.seconds) {
  withContext(Dispatchers.Default) { withTimeout(15.seconds) { block() } }
}

suspend fun eventually(condition: () -> Boolean) {
  while (!condition()) delay(20)
}

/** The HTTP half of a T3 server: session, token exchange, and a `/ws` that always answers 401. */
class FakeHttpServer : AutoCloseable {
  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  val tokenRequests: CopyOnWriteArrayList<Pair<String?, String>> = CopyOnWriteArrayList()

  val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

  init {
    server.createContext("/api/auth/session") { exchange ->
      val authorization: String? = exchange.requestHeaders.getFirst("Authorization")
      val authenticated: Boolean = authorization == "Bearer test"
      val body: String =
        if (authenticated) "session-authenticated.json" else "session-unauthenticated.json"
      exchange.respond(200, fixture(body))
    }
    server.createContext("/oauth/token") { exchange ->
      val body: String = exchange.requestBody.readBytes().decodeToString()
      tokenRequests += exchange.requestHeaders.getFirst("Content-Type") to body
      if ("subject_token=BADCODE" in body) exchange.respond(401, fixture("auth-invalid.json"))
      else exchange.respond(200, fixture("oauth-token.json"))
    }
    server.createContext("/ws") { exchange -> exchange.respond(401, fixture("auth-invalid.json")) }
    server.start()
  }

  override fun close() = server.stop(0)

  private fun HttpExchange.respond(status: Int, body: String) {
    val bytes: ByteArray = body.encodeToByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
  }
}

/** The socket half: replays the recorded chunks, holding each next chunk until the Ack. */
class FakeRpcServer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)), AutoCloseable {
  private val started: CountDownLatch = CountDownLatch(1)
  private val streams: ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> =
    ConcurrentHashMap()
  val received: CopyOnWriteArrayList<JsonObject> = CopyOnWriteArrayList()
  val events: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

  @Volatile var authorization: String? = null
  @Volatile var resource: String? = null

  val uri: URI get() = URI("ws://127.0.0.1:$port/ws?orchestrationProtocol=2")

  init {
    isReuseAddr = true
    connectionLostTimeout = 0
  }

  fun startAndWait(): FakeRpcServer = apply {
    start()
    started.await()
  }

  override fun close() = stop(1_000)

  override fun onStart() = started.countDown()

  override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
    authorization = handshake.getFieldValue("Authorization")
    resource = handshake.resourceDescriptor
  }

  override fun onMessage(conn: WebSocket, message: String) {
    val frame: JsonObject = T3Json.parseToJsonElement(message).jsonObject
    received += frame
    val id: String = frame.string("id") ?: frame.string("requestId") ?: ""
    // A server-side defect on a live socket: the request dies, the connection stays up
    val payload: JsonObject? = frame["payload"] as? JsonObject
    val dies: Boolean =
      payload?.string("threadId") == "thread-die" || payload?.string("cwd") == "/die"
    if (frame.string("_tag") == "Request" && dies) {
      conn.send(
        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":""" +
          """[{"_tag":"Die","defect":{"message":"boom"}}]}}""",
      )
      return
    }
    when (frame.string("_tag")) {
      "Ping" -> conn.send("""{"_tag":"Pong"}""")
      "Request" -> when (frame.string("tag")) {
        "orchestration.subscribeShell" ->
          replay(conn, id, "shell-snapshot-chunk.json", "shell-thread-updated-chunk.json")

        "orchestration.subscribeThread" -> replay(
          conn,
          id,
          "thread-snapshot-chunk.json",
          "turn-item-updated-event.json",
          "runtime-request-updated-event.json",
          "message-and-provider-thread-updated-event.json",
          // A resubscription's snapshot, older than the events before it
          "thread-snapshot-chunk.json",
        )

        "terminal.attach", "terminal.observe" ->
          replay(conn, id, "terminal-snapshot-chunk.json", "terminal-output-chunk.json")

        "subscribeVcsStatus" -> replay(conn, id, "vcs-status-snapshot-chunk.json")

        "orchestration.dispatchCommand" -> conn.send(exit(id, """{"sequence":42}"""))

        "terminal.close", "attachments.delete" -> conn.send(exit(id, "null"))

        "server.getConfig" -> conn.send(exit(id, fixture("server-config.json")))

        "projects.readFile" -> conn.send(
          """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":""" +
            """{"relativePath":"README.md","contents":"# hi","byteLength":4,"truncated":false}}}""",
        )

        else -> conn.send(
          """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail",""" +
            """"error":{"_tag":"EnvironmentAuthorizationError","message":"The authenticated """ +
            """token is missing required scope: filesystem:read.","requiredScope":""" +
            """"orchestration:read","requiredPermission":"filesystem:read"}}]}}""",
        )
      }

      "Ack" -> {
        events += "ack:$id"
        streams[id]?.poll()?.let { next ->
          events += "second:$id"
          conn.send(chunk(next, id))
        }
      }
    }
  }

  /** Requests whose tag is [tag], as received. */
  fun requests(tag: String): List<JsonObject> = received.filter { it.string("tag") == tag }

  /** Sends [first] now and each of [rest] after the Ack of the one before. */
  private fun replay(conn: WebSocket, id: String, first: String, vararg rest: String) {
    streams[id] = ConcurrentLinkedQueue(rest.toList())
    conn.send(chunk(first, id))
  }

  private fun exit(requestId: String, value: String): String =
    """{"_tag":"Exit","requestId":"$requestId","exit":{"_tag":"Success","value":$value}}"""

  override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

  override fun onError(conn: WebSocket?, ex: Exception) = Unit

  private fun chunk(name: String, requestId: String): String {
    val recorded: JsonObject = T3Json.parseToJsonElement(fixture(name)).jsonObject
    return JsonObject(recorded + ("requestId" to JsonPrimitive(requestId))).toString()
  }
}
