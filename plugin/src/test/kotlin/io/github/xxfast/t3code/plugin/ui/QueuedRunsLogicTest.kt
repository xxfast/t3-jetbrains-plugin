package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ConversationMessage.DelegatedCompletion
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class QueuedRunsLogicTest {
  // The harness scenario is the fixture: a running thread with two queued messages
  private val view: ThreadView = run {
    val path: Path =
      Path.of(System.getProperty("t3code.scenarios")).resolve("default").resolve("queued.json")
    val scenario: Scenario = Scenario.decode(path.readText())
    ThreadView(
      thread = scenario.threads.single(),
      runs = scenario.runs.getValue(THREAD),
      items = scenario.items.getValue(THREAD).map { it.item },
      providerSessions = scenario.providerSessions.getValue(THREAD),
      providerTurns = scenario.providerTurns.getValue(THREAD),
      messages = scenario.messages.getValue(THREAD),
    )
  }

  @Test
  fun `the scenario queues two messages behind a steerable run`() {
    val state: ThreadQueueWorkflowState = deriveThreadQueueWorkflowState(view)
    assertEquals(RUNNING, state.activeRun?.id)
    assertEquals(listOf(FIRST, SECOND), state.queuedRuns.map { it.run.id })
    assertTrue(state.queuedRuns.first().text.startsWith("Then run :kstore-file:check"))
    assertEquals(listOf("image"), state.queuedRuns.last().attachments.map(ChatAttachment::type))
    assertTrue(state.canReorder)
    assertTrue(state.canPromoteToSteer)
    assertFalse(state.isHeld)
  }

  @Test
  fun `queue position orders the queue, then the ordinal`() {
    val runs: List<Run> = view.runs.map { run ->
      when (run.id) {
        FIRST -> run.copy(queuePosition = 2)
        SECOND -> run.copy(queuePosition = 1)
        else -> run
      }
    }
    val state: ThreadQueueWorkflowState = deriveThreadQueueWorkflowState(view.copy(runs = runs))
    assertEquals(listOf(SECOND, FIRST), state.queuedRuns.map { it.run.id })

    val unpositioned: List<Run> = view.runs.map { it.copy(queuePosition = null) }
    val byOrdinal: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(runs = unpositioned))
    assertEquals(listOf(FIRST, SECOND), byOrdinal.queuedRuns.map { it.run.id })
  }

  @Test
  fun `automatic completion deliveries stay out of the queue`() {
    val messages: List<ConversationMessage> = view.messages.map { message ->
      if (message.runId != FIRST) return@map message
      val completion = DelegatedCompletion(parentRunId = "p", generation = 1, taskIds = listOf("t"))
      message.copy(delegatedCompletion = completion)
    }
    assertEquals(listOf(SECOND), getUserQueuedThreadRuns(view.runs, messages).map { it.id })
  }

  @Test
  fun `a queued run without its message still shows`() {
    val messages: List<ConversationMessage> = view.messages.filterNot { it.runId == FIRST }
    val state: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(messages = messages))
    assertEquals("Queued message", state.queuedRuns.first().text)
  }

  @Test
  fun `steering needs a running provider turn on the active attempt`() {
    val noTurns: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(providerTurns = emptyList()))
    assertFalse(noTurns.canPromoteToSteer)
    assertTrue(noTurns.canReorder)

    val noSession: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(providerSessions = emptyList()))
    assertFalse(noSession.canReorder)
    assertFalse(noSession.canPromoteToSteer)
  }

  @Test
  fun `a held queued run holds the queue`() {
    val runs: List<Run> = view.runs.map { run ->
      if (run.id == SECOND) run.copy(queueHeld = true) else run
    }
    assertTrue(deriveThreadQueueWorkflowState(view.copy(runs = runs)).isHeld)
  }

  @Test
  fun `moves mirror the grip's arrow keys`() {
    val queued: List<QueuedThreadRun> = deriveThreadQueueWorkflowState(view).queuedRuns
    assertNull(queuedRunMoveUp(queued, 0))
    assertEquals(QueuedRunMove(SECOND, FIRST), queuedRunMoveUp(queued, 1))
    assertEquals(QueuedRunMove(FIRST, null), queuedRunMoveDown(queued, 0))
    assertNull(queuedRunMoveDown(queued, 1))
  }

  @Test
  fun `labels follow upstream`() {
    assertEquals("1 queued message", queuedMessagesLabel(1))
    assertEquals("2 queued messages", queuedMessagesLabel(2))
    assertEquals("There is no active run to steer", queuedRunSteerTooltip(null))
    assertEquals("Send as a steer instead", queuedRunSteerTooltip(view.runs.first()))
  }

  @Test
  fun `a preview shows context links by their label`() {
    val text = "Fix [Foo.kt:12](t3-context://v1/review-comment/rc_1) and " +
      "![shot.png](t3-context://v1/image/img_1) please "
    assertEquals("Fix Foo.kt:12 and shot.png please", queuedRunPreviewText(text, false))
    assertEquals("Fix Foo.kt:12 and  please", queuedRunPreviewText(text, true))
  }

  @Test
  fun `an edit whose run left the queue is recovered into an empty draft`() {
    assertEquals(QueuedEditRecovery.CLEAN, recoverQueuedMessageEdit("a", "a", false))
    assertEquals(QueuedEditRecovery.KEPT, recoverQueuedMessageEdit("ab", "a", false))
    assertEquals(QueuedEditRecovery.DISCARDED, recoverQueuedMessageEdit("ab", "a", true))
    assertNull(queuedEditRecoveryMessage(QueuedEditRecovery.CLEAN))
    assertNotNull(queuedEditRecoveryMessage(QueuedEditRecovery.KEPT))
  }

  @Test
  fun `an edit is stale once its run isn't queued`() {
    val editing: EditingQueuedRun = editing(FIRST, attachments = emptyList())
    assertFalse(isQueuedEditStale(view, editing))
    val started: List<Run> = view.runs.map { run ->
      if (run.id == FIRST) run.copy(status = "running") else run
    }
    assertTrue(isQueuedEditStale(view.copy(runs = started), editing))
    assertTrue(isQueuedEditStale(view, editing("run_gone", attachments = emptyList())))
  }

  @Test
  fun `saving sends the trimmed text and keeps attachments and context`() {
    val command: Command.QueuedRunEdit =
      assertNotNull(queuedRunEditCommand(editing(FIRST, emptyList()), "  new text \n", "cmd"))
    assertEquals("new text", command.text)
    assertEquals(FIRST, command.runId)
    assertEquals(THREAD, command.threadId)
    assertNull(command.attachments)
    assertNull(command.context)

    assertNull(queuedRunEditCommand(editing(FIRST, emptyList()), "  ", "cmd"))
    val withImage: EditingQueuedRun =
      editing(SECOND, deriveThreadQueueWorkflowState(view).queuedRuns.last().attachments)
    assertEquals(
      ATTACHMENT_ONLY_BOOTSTRAP_PROMPT,
      queuedRunEditCommand(withImage, "", "cmd")?.text,
    )
  }

  private fun editing(runId: String, attachments: List<ChatAttachment>) = EditingQueuedRun(
    threadId = THREAD,
    runId = runId,
    messageId = "msg",
    originalText = "original",
    existingAttachments = attachments,
  )

  private companion object {
    const val THREAD = "thr_01JC4C3Q8UQUEUED1"
    const val RUNNING = "run_01JC4C3QR2RUN"
    const val FIRST = "run_01JC4C3QR3Q"
    const val SECOND = "run_01JC4C3QR4Q"
  }
}
