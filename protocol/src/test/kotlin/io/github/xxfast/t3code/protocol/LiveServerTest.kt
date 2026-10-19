package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Against a real server, when the environment says so. Skipped otherwise, so CI never sees it.
 *
 * - `T3_LIVE_TOKEN`: a bearer token for `T3_LIVE_URL` (default `http://127.0.0.1:3773`).
 * - `T3_LIVE_THREAD`: a thread the test may pin, unpin, message and open a terminal on.
 * - `T3_LIVE_CWD`: a directory for the vcs status subscription.
 * - `T3_LIVE_SEND=1`: also send "ping" and wait for an assistant reply containing "pong".
 * - `T3_LIVE_TERMINAL=1`: also attach a terminal (starts a shell on the server for that thread).
 */
class LiveServerTest {
  private val token: String? = System.getenv("T3_LIVE_TOKEN")
  private val threadId: String? = System.getenv("T3_LIVE_THREAD")
  private val url: String = System.getenv("T3_LIVE_URL") ?: "http://127.0.0.1:3773"
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun client(): WebSocketT3Client = WebSocketT3Client(
    baseUrl = { url },
    credential = { Credential(accessToken = token!!) },
    scope = scope,
  )

  private fun live(block: suspend CoroutineScope.(WebSocketT3Client) -> Unit) =
    runTest(timeout = 120.seconds) {
      assumeTrue(token != null && threadId != null, "T3_LIVE_TOKEN and T3_LIVE_THREAD unset")
      // Real time: the server doesn't run on the test scheduler's clock
      withContext(Dispatchers.Default) { session(block) }
    }

  private suspend fun CoroutineScope.session(
    block: suspend CoroutineScope.(WebSocketT3Client) -> Unit,
  ) {
      val client: WebSocketT3Client = client()
      try {
        client.connect()
        val connected: ConnectionState.Connected = withTimeout(15.seconds) {
          client.connection.first { it is ConnectionState.Connected } as ConnectionState.Connected
        }
        assertTrue(connected.session.has("orchestration:operate"), "${connected.session}")
        block(client)
      } finally {
        client.disconnect()
        scope.cancel()
      }
  }

  @Test
  fun serverInfoAndPinRoundTrip() = live { client ->
    val info: ServerInfo = client.serverInfo()
    println("live: serverResolvedCommandContext=${info.serverResolvedCommandContext}")
    println("live: providers=${info.providers.map { "${it.instanceId}:${it.models.size}" }}")
    assertTrue(info.providers.isNotEmpty())

    fun row(): Thread? = client.threads.value.find { it.id == threadId }
    withTimeout(15.seconds) { client.threads.first { threads -> threads.any { it.id == threadId } } }
    val wasPinned: Boolean = row()!!.pinned

    val pin: Long = client.dispatch(Command.ThreadPin(commandId = uuid(), threadId = threadId!!))
    println("live: pin sequence=$pin")
    withTimeout(15.seconds) { client.threads.first { row()?.pinned == true } }

    val unpin: Long = client.dispatch(Command.ThreadUnpin(commandId = uuid(), threadId = threadId))
    println("live: unpin sequence=$unpin")
    withTimeout(15.seconds) { client.threads.first { row()?.pinned == false } }
    assertEquals(false, wasPinned, "the scratch thread started unpinned")

    val view: ThreadView = withTimeout(15.seconds) { client.thread(threadId).first() }
    println("live: runs=${view.runs.size} items=${view.items.size} sessions=${view.providerSessions.size} requests=${view.runtimeRequests.size}")
    assertTrue(view.items.any { it is TurnItem.AssistantMessage })
  }

  @Test
  fun vcsStatusSnapshot() = live { client ->
    val cwd: String = System.getenv("T3_LIVE_CWD") ?: return@live
    val snapshot: VcsStatusStreamEvent.Snapshot =
      assertIs(withTimeout(30.seconds) { client.vcsStatus(cwd).first() })
    println("live: branch=${snapshot.local.refName} changed=${snapshot.local.workingTree.files.size} ahead=${snapshot.remote?.aheadCount} behind=${snapshot.remote?.behindCount}")
  }

  @Test
  fun sendPingGetsPong() = live { client ->
    assumeTrue(System.getenv("T3_LIVE_SEND") == "1", "T3_LIVE_SEND unset")
    val messageId: String = uuid()
    val command = Command.MessageDispatch(
      createdBy = "user",
      creationSource = "web",
      commandId = uuid(),
      threadId = threadId!!,
      messageId = messageId,
      text = "ping",
      attachments = emptyList(),
      deliveryIntent = "auto",
      dispatchMode = Command.MessageDispatch.DispatchMode.StartImmediately(),
    )
    val sequence: Long = client.dispatch(command)
    println("live: send sequence=$sequence")
    val view: ThreadView = withTimeout(90.seconds) {
      client.thread(threadId).first { view ->
        val echoed: Boolean =
          view.items.any { it is TurnItem.UserMessage && it.messageId == messageId }
        val replied: Boolean = view.items.any { item ->
          item is TurnItem.AssistantMessage && !item.streaming &&
            item.text.contains("pong", ignoreCase = true) &&
            view.runs.any { run -> run.userMessageId == messageId && run.completedAt != null }
        }
        echoed && replied
      }
    }
    println("live: ${view.items.size} items, last=${(view.items.last() as? TurnItem.AssistantMessage)?.text}")
  }

  @Test
  fun terminalAttachSnapshot() = live { client ->
    assumeTrue(System.getenv("T3_LIVE_TERMINAL") == "1", "T3_LIVE_TERMINAL unset")
    val cwd: String = System.getenv("T3_LIVE_CWD") ?: "."
    val input = TerminalAttachInput(
      threadId = threadId!!,
      terminalId = "term-1",
      cwd = cwd,
      cols = 100,
      rows = 30,
    )
    val first: TerminalAttachStreamEvent = withTimeout(30.seconds) { client.terminal(input).first() }
    val snapshot: TerminalAttachStreamEvent.Snapshot = assertIs(first)
    println("live: terminal status=${snapshot.snapshot.status} pid=${snapshot.snapshot.pid} history=${snapshot.snapshot.history.length} chars")
    client.terminalWrite(threadId, "term-1", "echo live-ok\r")
    val output: TerminalAttachStreamEvent.Output = withTimeout(30.seconds) {
      client.terminal(input).first { it is TerminalAttachStreamEvent.Output } as TerminalAttachStreamEvent.Output
    }
    println("live: terminal output ${output.data.length} chars")
  }

  private fun uuid(): String = UUID.randomUUID().toString()
}
