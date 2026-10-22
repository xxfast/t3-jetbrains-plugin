package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    auth = { T3Auth(http.baseUrl) },
    socketUri = { socketUri },
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

    // The partial snapshot that follows (resolvedRepositoryIdentityRoots, no threads) updates
    // the project and must not empty the list
    eventually { client.projects.value[0].repositoryIdentity != null }
    assertEquals(
      "github.com/xxfast/t3-jetbrains-plugin",
      client.projects.value[0].repositoryIdentity?.canonicalKey,
    )

    client.disconnect()
    assertEquals(ConnectionState.Disconnected, client.connection.value)
    assertEquals(3, client.threads.value.size)
  }

  @Test
  fun threadFlowAppliesTurnItemUpdates() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    // The shell has to list the thread first, else there's no shell status to carry over
    client.threads.first { threads -> threads.any { it.id == "thread-1" } }
    val (snapshot: ThreadView, updated: ThreadView) = client.thread("thread-1").take(2).toList()

    assertEquals(listOf("item-1", "item-2", "item-3", "item-4"), snapshot.items.map { it.id })
    assertIs<TurnItem.Compaction>(snapshot.items[2])
    assertTrue((snapshot.items[3] as TurnItem.AssistantMessage).streaming)
    assertEquals(listOf("run-1"), snapshot.runs.map { it.id })
    assertTrue(snapshot.thread.status != "idle", "Shell status carried over")

    assertEquals(snapshot.items.map { it.id }, updated.items.map { it.id })
    val message: TurnItem.AssistantMessage = assertIs(updated.items[3])
    assertEquals("Working on it. Done.", message.text)
    assertEquals(false, message.streaming)
  }

  @Test
  fun threadFlowAppliesRuntimeRequestUpdates() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val views: List<ThreadView> = client.thread("thread-1").take(3).toList()

    assertEquals(emptyList(), views.first().runtimeRequests)
    val request: RuntimeRequest = views.last().runtimeRequests.single()
    assertEquals("request-1", request.id)
    assertEquals("pending", request.status)
    assertEquals(views[1].items, views.last().items)
  }

  @Test
  fun threadFlowUpsertsMessagesAndProviderThreads() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val views: List<ThreadView> = client.thread("thread-1").take(5).toList()

    assertEquals(emptyList(), views.first().messages)
    assertEquals(emptyList(), views.first().providerThreads)
    val message: ConversationMessage = views[3].messages.single()
    assertEquals("message-queued-1", message.id)
    assertEquals("user", message.role)
    assertEquals(emptyList(), views[3].providerThreads)
    val thread: ProviderThread = views[4].providerThreads.single()
    assertEquals("session-1", thread.providerSessionId)
    assertEquals(views[3].messages, views[4].messages)
    assertEquals(views[2].runtimeRequests, views[4].runtimeRequests)
  }

  @Test
  fun threadFlowTracksTheSequenceAndASnapshotNeverMovesItBack() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val views: List<ThreadView> = client.thread("thread-1").take(6).toList()

    assertEquals(listOf(100L, 101L, 102L, 103L, 104L, 104L), views.map { it.sequence })
    assertEquals(views.first().items, views.last().items, "The second snapshot still applies")
  }

  @Test
  fun threadFlowUpsertsSubagentsAndContextTransfers() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val views: List<ThreadView> = client.thread("thread-1").take(9).toList()

    assertEquals(emptyList(), views[5].subagents)
    assertEquals(emptyList(), views[5].contextTransfers)
    val started: Subagent = views[6].subagents.single()
    assertEquals("subagent-1", started.id)
    assertEquals("running", started.status)
    val finished: Subagent = views[7].subagents.single()
    assertEquals("completed", finished.status, "The same subagent, updated in place")
    assertEquals("thread-child-1", finished.childThreadId)
    val transfer: ContextTransfer = views[8].contextTransfers.single()
    assertEquals("thread-2", transfer.targetThreadId)
    assertEquals(views[7].subagents, views[8].subagents)
    assertEquals(107L, views[8].sequence)
  }

  @Test
  fun aDieOnALiveSocketFailsTheFlowInsteadOfHanging() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()

    val vcs: T3Exception.Transport = assertFailsWith { client.vcsStatus("/die").first() }
    assertTrue("boom" in vcs.message.orEmpty(), vcs.message)
    assertFailsWith<T3Exception.Transport> { client.thread("thread-die").first() }
    assertIs<ConnectionState.Connected>(client.connection.value, "The socket stays up")
  }

  @Test
  fun dispatchSendsTheCommandWithItsType() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    // No wait for the socket itself: Connected means it's already live
    client.connection.first { it is ConnectionState.Connected }

    val pin = Command.ThreadPin(commandId = "cmd-1", threadId = "thread-1")
    val sequence: Long = client.dispatch(pin)

    assertEquals(42, sequence)
    val payload: JsonObject =
      rpc.requests("orchestration.dispatchCommand").single().getValue("payload").jsonObject
    assertTrue(""""type":"thread.pin"""" in payload.toString(), "$payload")
    assertEquals("thread-1", payload.string("threadId"))
    assertEquals("cmd-1", payload.string("commandId"))
    assertFalse("orderKey" in payload, "Nulls are dropped")
  }

  @Test
  fun terminalYieldsTheSnapshotThenOutput() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    val input = TerminalAttachInput(threadId = "thread-1", terminalId = "default", cwd = "/repo")

    val events: List<TerminalAttachStreamEvent> = client.terminal(input).take(3).toList()

    val snapshot: TerminalAttachStreamEvent.Snapshot = assertIs(events[0])
    assertEquals("$ ", snapshot.snapshot.history)
    val unknown: TerminalAttachStreamEvent.Unknown = assertIs(events[1])
    assertEquals("some-future-event", unknown.type)
    val output: TerminalAttachStreamEvent.Output = assertIs(events[2])
    assertEquals("ls\r\nREADME.md\r\n$ ", output.data)
    val payload: JsonObject =
      rpc.requests("terminal.attach").single().getValue("payload").jsonObject
    assertEquals(setOf("threadId", "terminalId", "cwd"), payload.keys)
  }

  @Test
  fun terminalObserveYieldsTheSameEventsWithoutAttaching() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()

    val events: List<TerminalAttachStreamEvent> =
      client.terminalObserve("thread-1", "default").take(3).toList()

    val snapshot: TerminalAttachStreamEvent.Snapshot = assertIs(events[0])
    assertEquals("$ ", snapshot.snapshot.history)
    val output: TerminalAttachStreamEvent.Output = assertIs(events[2])
    assertEquals("ls\r\nREADME.md\r\n$ ", output.data)
    val payload: JsonObject =
      rpc.requests("terminal.observe").single().getValue("payload").jsonObject
    assertEquals(setOf("threadId", "terminalId"), payload.keys)
    assertEquals("thread-1", payload.string("threadId"))
    assertEquals("default", payload.string("terminalId"))
    assertTrue(rpc.requests("terminal.attach").isEmpty(), "Observing never attaches")
  }

  @Test
  fun deleteAttachmentSendsTheMintedId() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    client.connection.first { it is ConnectionState.Connected }

    client.deleteAttachment("att_123")

    val payload: JsonObject =
      rpc.requests("attachments.delete").single().getValue("payload").jsonObject
    assertEquals(setOf("attachmentId"), payload.keys)
    assertEquals("att_123", payload.string("attachmentId"))
  }

  @Test
  fun terminalCloseSendsTheThreadTerminalAndHistoryChoice() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    client.connection.first { it is ConnectionState.Connected }

    client.terminalClose("thread-1", "term-1", deleteHistory = true)

    val payload: JsonObject =
      rpc.requests("terminal.close").single().getValue("payload").jsonObject
    assertEquals(setOf("threadId", "terminalId", "deleteHistory"), payload.keys)
    assertEquals("thread-1", payload.string("threadId"))
    assertEquals("term-1", payload.string("terminalId"))
    assertEquals("true", payload.string("deleteHistory"))
  }

  @Test
  fun vcsStatusDecodesTheSnapshot() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()

    val snapshot: VcsStatusStreamEvent.Snapshot = assertIs(client.vcsStatus("/repo").first())

    val paths: List<String> = snapshot.local.workingTree.files.map { it.path }
    assertEquals(listOf("src/App.kt", "README.md"), paths)
    assertEquals(2, snapshot.remote?.aheadCount)
    assertEquals(1, snapshot.remote?.behindCount)
    val payload: JsonObject =
      rpc.requests("subscribeVcsStatus").single().getValue("payload").jsonObject
    assertEquals("/repo", payload.string("cwd"))
    assertEquals("true", payload.string("includeRemote"))
  }

  @Test
  fun serverInfoDecodesProvidersAndIsCached() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    client.connection.first { it is ConnectionState.Connected }

    val info: ServerInfo = client.serverInfo()

    assertTrue(info.serverResolvedCommandContext)
    assertEquals("env-1", info.environmentId)
    assertEquals(listOf("codex"), info.providers.map { it.instanceId }, "Bad provider dropped")
    assertEquals(listOf("gpt-5", "gpt-5-mini"), info.providers.single().models.map { it.slug })
    assertSame(info, client.serverInfo())
    assertEquals(1, rpc.requests("server.getConfig").size)
  }

  @Test
  fun readFileAndScopeErrors() = realTimeTest {
    val client: WebSocketT3Client = client()
    client.connect()
    client.connection.first { it is ConnectionState.Connected }
    assertEquals("# hi", client.readFile("/repo", "README.md").contents)
    assertFailsWith<T3Exception.ScopeRequired> { client.turnDiff("thread-1", 0, 1) }
    assertFailsWith<T3Exception.ScopeRequired> { client.projectFavicon("/repo", null) }
    assertFailsWith<T3Exception.ScopeRequired> { client.attachment("att_1") }
    val payload: JsonObject = rpc.requests("assets.createUrl").last()["payload"]!!.jsonObject
    assertEquals(
      """{"resource":{"_tag":"attachment","attachmentId":"att_1"}}""",
      payload.toString(),
    )
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
