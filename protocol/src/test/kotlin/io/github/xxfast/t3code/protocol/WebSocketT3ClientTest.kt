package io.github.xxfast.t3code.protocol

import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class WebSocketT3ClientTest {
  private val http: FakeHttpServer = FakeHttpServer()
  private val rpc: FakeRpcServer = FakeRpcServer().startAndWait()
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @AfterTest
  fun stop() {
    scope.cancel()
    rpc.close()
    http.close()
  }

  private fun client(
    credential: Credential? = Credential("test"),
    socketUri: URI = rpc.uri,
  ): WebSocketT3Client = WebSocketT3Client(
    auth = T3Auth(http.baseUrl),
    socketUri = socketUri,
    credential = { credential },
    scope = scope,
    http = defaultHttpClient(),
  )

  @Test
  fun connectsAndMirrorsTheShell() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val connected: ConnectionState = client.connection.first { it is ConnectionState.Connected }
    assertTrue((connected as ConnectionState.Connected).session.authenticated)

    val threads: List<Thread> =
      client.threads.first { it.firstOrNull()?.title == "Write the real client" }
    assertEquals(listOf("thread-1", "thread-2", "thread-3"), threads.map { it.id })
    assertEquals("completed", threads[0].status)
    assertTrue(threads[2].archived)
    assertEquals(listOf("project-1"), client.projects.value.map { it.id })

    client.disconnect()
    assertEquals(ConnectionState.Disconnected, client.connection.value)
    assertEquals(3, client.threads.value.size)
  }

  @Test
  fun threadFlowAppliesTurnItemUpdates() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val (snapshot: ThreadView, updated: ThreadView) = client.thread("thread-1").take(2).toList()

    assertEquals(listOf("item-1", "item-2", "item-3", "item-4"), snapshot.items.map { it.id })
    assertIs<TurnItem.Unknown>(snapshot.items[2])
    assertTrue((snapshot.items[3] as TurnItem.AssistantMessage).streaming)
    assertEquals(listOf("run-1"), snapshot.runs.map { it.id })
    assertTrue(snapshot.thread.status != "idle", "Shell status carried over")

    assertEquals(snapshot.items.map { it.id }, updated.items.map { it.id })
    val message: TurnItem.AssistantMessage = assertIs(updated.items[3])
    assertEquals("Working on it. Done.", message.text)
    assertEquals(false, message.streaming)
  }

  @Test
  fun readFileAndScopeErrors() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    client.connection.first { it is ConnectionState.Connected }
    assertEquals("# hi", client.readFile("/repo", "README.md").contents)
    assertFailsWith<T3Exception.ScopeRequired> { client.turnDiff("thread-1", 0, 1) }
  }

  @Test
  fun noCredentialIsUnpaired() = realTimeTest {
    val client: WebSocketT3Client = client(credential = null)
    client.connect()
    client.connection.first { it == ConnectionState.Unpaired }
  }

  @Test
  fun rejectedUpgradeIsAuthError() = realTimeTest {
    val client: WebSocketT3Client =
      client(socketUri = URI("${http.baseUrl.replace("http", "ws")}/ws?orchestrationProtocol=2"))
    client.connect()
    client.connection.first { it is ConnectionState.AuthError }
  }
}
