package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ConversationMessage.DelegatedCompletion
import io.github.xxfast.t3code.protocol.generated.MessageContext
import io.github.xxfast.t3code.protocol.generated.GuestMessageAttribution
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class QueuedRunsLogicTest {
  // The harness scenario is the fixture: a running thread with four queued messages, the user's
  // two, a subagent's report and one from T3 Code
  private val view: ThreadView = run {
    val path: Path =
      Path.of(System.getProperty("t3code.scenarios")).resolve("default").resolve("queued.json")
    val scenario: Scenario = Scenario.decode(path.readText())
    ThreadView(
      thread = scenario.threads.single { it.id == THREAD },
      runs = scenario.runs.getValue(THREAD),
      items = scenario.items.getValue(THREAD).map { it.item },
      providerSessions = scenario.providerSessions.getValue(THREAD),
      providerTurns = scenario.providerTurns.getValue(THREAD),
      messages = scenario.messages.getValue(THREAD),
    )
  }

  @Test
  fun `the scenario queues four messages behind a steerable run`() {
    val state: ThreadQueueWorkflowState = deriveThreadQueueWorkflowState(view)
    assertEquals(RUNNING, state.activeRun?.id)
    assertEquals(listOf(FIRST, SECOND, AGENT, T3CODE), state.queuedRuns.map { it.run.id })
    assertTrue(state.queuedRuns.first().text.startsWith("Then run :kstore-file:check"))
    assertEquals(listOf("image"), state.queuedRuns[1].attachments.map(ChatAttachment::type))
    assertTrue(state.canPromoteToSteer)
    assertFalse(state.canSendNow)
    assertFalse(state.promoteSkipsCompaction)
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
    assertEquals(listOf(SECOND, FIRST, AGENT, T3CODE), state.queuedRuns.map { it.run.id })

    val unpositioned: List<Run> = view.runs.map { it.copy(queuePosition = null) }
    val byOrdinal: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(runs = unpositioned))
    assertEquals(listOf(FIRST, SECOND, AGENT, T3CODE), byOrdinal.queuedRuns.map { it.run.id })
  }

  @Test
  fun `automatic completion deliveries stay out of the queue`() {
    val messages: List<ConversationMessage> = view.messages.map { message ->
      if (message.runId != FIRST) return@map message
      val completion = DelegatedCompletion(parentRunId = "p", generation = 1, taskIds = listOf("t"))
      message.copy(delegatedCompletion = completion)
    }
    assertEquals(
      listOf(SECOND, AGENT, T3CODE),
      getUserQueuedThreadRuns(view.runs, messages).map { it.id },
    )
  }

  @Test
  fun `a queued run without its message still shows`() {
    val messages: List<ConversationMessage> = view.messages.filterNot { it.runId == FIRST }
    val state: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(messages = messages))
    assertEquals("Queued message", state.queuedRuns.first().text)
    assertNull(state.queuedRuns.first().message)
  }

  @Test
  fun `a queued run carries its message, for the attribution its sent form shows`() {
    val queued: List<QueuedThreadRun> = deriveThreadQueueWorkflowState(view).queuedRuns
    assertEquals(queued.map { it.messageId }, queued.map { it.message?.id })
    val agent: ConversationMessage = assertNotNull(queued[2].message)
    assertEquals("thr_01JC5L1S2DONE001", agent.senderThreadId)
    assertEquals("agent", resolveUserMessagePresentation(agent).attribution)
    val t3code: ConversationMessage = assertNotNull(queued[3].message)
    assertEquals("t3code", resolveUserMessagePresentation(t3code).attribution)
    assertNull(resolveUserMessagePresentation(assertNotNull(queued[0].message)).attribution)
  }

  @Test
  fun `steering needs a running provider turn on the active attempt`() {
    val noTurns: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(providerTurns = emptyList()))
    assertFalse(noTurns.canPromoteToSteer)
    assertFalse(canSendQueuedRun(noTurns))

    val noSession: ThreadQueueWorkflowState =
      deriveThreadQueueWorkflowState(view.copy(providerSessions = emptyList()))
    assertFalse(noSession.canPromoteToSteer)
  }

  @Test
  fun `a running compaction can always be skipped for a queued message`() {
    val messages: List<ConversationMessage> = view.messages.map { message ->
      if (message.runId == RUNNING) message.copy(text = " /Compact ") else message
    }
    val compacting: ThreadView = view.copy(messages = messages, providerTurns = emptyList())
    val state: ThreadQueueWorkflowState = deriveThreadQueueWorkflowState(compacting)
    assertTrue(state.promoteSkipsCompaction)
    assertTrue(state.canPromoteToSteer)
    assertEquals("Skip compaction and send this message", queuedRunSendTooltip(state, null))

    val image: List<ChatAttachment> =
      view.messages.first { it.attachments.isNotEmpty() }.attachments
    val withImage: ConversationMessage =
      messages.first { it.runId == RUNNING }.copy(attachments = image)
    assertFalse(isCompactionCommandMessage(withImage))
  }

  @Test
  fun `send now starts a turn on an idle thread`() {
    val runs: List<Run> = view.runs.map { run ->
      if (run.id == RUNNING) run.copy(status = "interrupted") else run
    }
    val idle: ThreadView = view.copy(runs = runs)
    val state: ThreadQueueWorkflowState = deriveThreadQueueWorkflowState(idle)
    assertTrue(state.canSendNow)
    assertTrue(canSendQueuedRun(state))
    assertEquals(
      Command.QueuedRunStart(commandId = "c", threadId = THREAD, runId = SECOND),
      sendQueuedMessageCommand(state, THREAD, SECOND, "c"),
    )
    assertEquals("Start a turn with this message", queuedRunSendTooltip(state, null))

    val archived: ThreadView = idle.copy(thread = idle.thread.copy(archivedAt = "2026-10-21"))
    assertFalse(deriveThreadQueueWorkflowState(archived).canSendNow)
    val deleted: ThreadView = idle.copy(thread = idle.thread.copy(deletedAt = "2026-10-21"))
    assertFalse(deriveThreadQueueWorkflowState(deleted).canSendNow)
  }

  @Test
  fun `send now goes into the running turn, or nowhere when it can't`() {
    val state: ThreadQueueWorkflowState = deriveThreadQueueWorkflowState(view)
    assertEquals(
      Command.QueuedMessagePromoteToSteer(
        commandId = "c",
        threadId = THREAD,
        queuedRunId = FIRST,
        targetRunId = RUNNING,
      ),
      sendQueuedMessageCommand(state, THREAD, FIRST, "c"),
    )
    assertEquals("Send into the current turn", queuedRunSendTooltip(state, null))
    assertEquals("Send into the current turn (⇧⌘⏎)", queuedRunSendTooltip(state, "⇧⌘⏎"))

    val stuck: ThreadQueueWorkflowState = state.copy(canPromoteToSteer = false)
    assertFalse(canSendQueuedRun(stuck))
    assertNull(sendQueuedMessageCommand(stuck, THREAD, FIRST, "c"))
  }

  @Test
  fun `a guest's queued message shows the guest's words and can't be edited`() {
    val guest = GuestMessageAttribution(guestMessageId = "g", name = "Sam", text = "Hi there")
    val context = MessageContext(version = 1, guest = guest, records = emptyList())
    val messages: List<ConversationMessage> = view.messages.map { message ->
      if (message.runId == FIRST) message.copy(context = context) else message
    }
    val queued: List<QueuedThreadRun> =
      deriveThreadQueueWorkflowState(view.copy(messages = messages)).queuedRuns
    assertEquals("Hi there", queued.first().text)
    assertFalse(isQueuedRunEditable(queued.first()))
    assertTrue(isQueuedRunEditable(queued.last()))
  }

  @Test
  fun `the edit shortcut follows the row's rule`() {
    assertTrue(canEditQueuedRun(idle = true, editingRunId = null))
    assertFalse(canEditQueuedRun(idle = false, editingRunId = null))
    assertFalse(canEditQueuedRun(idle = true, editingRunId = FIRST))
  }

  @Test
  fun `a held queued run holds the queue`() {
    val runs: List<Run> = view.runs.map { run ->
      if (run.id == SECOND) run.copy(queueHeld = true) else run
    }
    assertTrue(deriveThreadQueueWorkflowState(view.copy(runs = runs)).isHeld)
  }

  @Test
  fun `a long queue folds all but two behind show all`() {
    assertEquals(listOf(1, 2, 3), visibleQueuedRuns(listOf(1, 2, 3), showAll = false))
    assertEquals(listOf(1, 2), visibleQueuedRuns(listOf(1, 2, 3, 4), showAll = false))
    assertEquals(listOf(1, 2, 3, 4), visibleQueuedRuns(listOf(1, 2, 3, 4), showAll = true))
    assertEquals("Show all 4 queued", queuedFoldLabel(4, showAll = false))
    assertEquals("Show fewer", queuedFoldLabel(4, showAll = true))
  }

  @Test
  fun `labels follow upstream`() {
    assertEquals("1 queued message", queuedMessagesLabel(1))
    assertEquals("2 queued messages", queuedMessagesLabel(2))
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
      editing(SECOND, deriveThreadQueueWorkflowState(view).queuedRuns[1].attachments)
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
    const val AGENT = "run_01JC4C3QR5Q"
    const val T3CODE = "run_01JC4C3QR6Q"
  }
}
