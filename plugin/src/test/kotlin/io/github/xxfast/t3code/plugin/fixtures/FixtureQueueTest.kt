package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.plugin.ui.ThreadQueueWorkflowState
import io.github.xxfast.t3code.plugin.ui.deriveThreadQueueWorkflowState
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The queue commands against `default/queued.json`, so the harness queue has something to do. */
class FixtureQueueTest {
  private val default: Path = Path.of(System.getProperty("t3code.scenarios")).resolve("default")

  @Test
  fun `reorder, edit and cancel change the queue`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    queue(client) { it.queuedRuns.size == 4 }

    client.dispatch(
      Command.QueuedRunReorder(
        commandId = "c1",
        threadId = THREAD,
        runId = SECOND,
        beforeRunId = FIRST,
      ),
    )
    queue(client) { state ->
      state.queuedRuns.map { it.run.id } == listOf(SECOND, FIRST, AGENT, T3CODE)
    }

    client.dispatch(
      Command.QueuedRunEdit(commandId = "c2", threadId = THREAD, runId = FIRST, text = "Edited"),
    )
    val edited: ThreadQueueWorkflowState = queue(client) { state ->
      state.queuedRuns.any { it.run.id == FIRST && it.text == "Edited" }
    }
    // An edit without attachments keeps the ones the message had
    assertEquals(1, edited.queuedRuns.first { it.run.id == SECOND }.attachments.size)

    client.dispatch(Command.QueuedRunCancel(commandId = "c3", threadId = THREAD, runId = SECOND))
    val left: ThreadQueueWorkflowState = queue(client) { it.queuedRuns.size == 3 }
    assertEquals(listOf(FIRST, AGENT, T3CODE), left.queuedRuns.map { it.run.id })
  }

  @Test
  fun `promoting to steer cancels the queued run and steers its message`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    queue(client) { it.queuedRuns.size == 4 }

    client.dispatch(
      Command.QueuedMessagePromoteToSteer(
        commandId = "c1",
        threadId = THREAD,
        queuedRunId = FIRST,
        targetRunId = RUNNING,
      ),
    )

    queue(client) { it.queuedRuns.size == 3 }
    val view: ThreadView = client.thread(THREAD).first { view ->
      view.items.any { it is TurnItem.UserMessage && it.inputIntent == "promoted_queued_to_steer" }
    }
    val steered: TurnItem.UserMessage = view.items.filterIsInstance<TurnItem.UserMessage>()
      .single { it.inputIntent == "promoted_queued_to_steer" }
    assertEquals(RUNNING, steered.runId)
    assertEquals("cancelled", view.runs.single { it.id == FIRST }.status)
  }

  @Test
  fun `an interrupt holds the queue until resumed, which starts the next run`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    queue(client) { it.queuedRuns.size == 4 }

    client.dispatch(
      Command.RunInterrupt(commandId = "c1", threadId = THREAD, runId = RUNNING, holdQueue = true),
    )
    val held: ThreadQueueWorkflowState = queue(client) { it.isHeld }
    assertEquals(null, held.activeRun)

    client.dispatch(Command.QueueResume(commandId = "c2", threadId = THREAD))
    val resumed: ThreadQueueWorkflowState = queue(client) { it.queuedRuns.size == 3 }
    assertFalse(resumed.isHeld)
    assertEquals(FIRST, resumed.activeRun?.id)
  }

  @Test
  fun `send now on a stopped thread starts that run, out of turn`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val held: ThreadQueueWorkflowState = queue(client, HELD_THREAD) { it.queuedRuns.size == 4 }
    assertTrue(held.isHeld)
    assertTrue(held.canSendNow)
    val third: String = held.queuedRuns[2].run.id

    client.dispatch(Command.QueuedRunStart(commandId = "c1", threadId = HELD_THREAD, runId = third))

    val started: ThreadQueueWorkflowState =
      queue(client, HELD_THREAD) { it.queuedRuns.size == 3 }
    assertEquals(third, started.activeRun?.id)
    assertFalse(started.isHeld)
    val view: ThreadView = client.thread(HELD_THREAD).first()
    assertTrue(view.items.any { it is TurnItem.UserMessage && it.runId == third })
  }

  @Test
  fun `send now can't start a run while another is going`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    queue(client) { it.queuedRuns.size == 4 }

    assertFailsWith<T3Exception.Rpc> {
      client.dispatch(Command.QueuedRunStart(commandId = "c1", threadId = THREAD, runId = FIRST))
    }
    assertEquals(RUNNING, queue(client) { true }.activeRun?.id)
  }

  @Test
  fun `a send queued behind the active run joins the end of the queue`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    queue(client) { it.queuedRuns.size == 4 }

    client.dispatch(
      Command.MessageDispatch(
        createdBy = "user",
        creationSource = "web",
        commandId = "cmd_1",
        threadId = THREAD,
        messageId = "m_queued",
        text = "One more thing",
        attachments = emptyList(),
        dispatchMode = Command.MessageDispatch.DispatchMode.QueueAfterActive(),
      ),
    )

    val state: ThreadQueueWorkflowState = queue(client) { it.queuedRuns.size == 5 }
    assertEquals("One more thing", state.queuedRuns.last().text)
    assertEquals(5L, state.queuedRuns.last().run.queuePosition)
    assertTrue(state.activeRun?.id == RUNNING)
  }

  private suspend fun queue(
    client: FixtureClient,
    threadId: String = THREAD,
    until: (ThreadQueueWorkflowState) -> Boolean,
  ): ThreadQueueWorkflowState = client.thread(threadId)
    .first { view -> until(deriveThreadQueueWorkflowState(view)) }
    .let(::deriveThreadQueueWorkflowState)

  private companion object {
    const val THREAD = "thr_01JC4C3Q8UQUEUED1"
    const val HELD_THREAD = "thr_01JC4C3Q8UQUEUED2"
    const val RUNNING = "run_01JC4C3QR2RUN"
    const val FIRST = "run_01JC4C3QR3Q"
    const val SECOND = "run_01JC4C3QR4Q"
    const val AGENT = "run_01JC4C3QR5Q"
    const val T3CODE = "run_01JC4C3QR6Q"
  }
}
