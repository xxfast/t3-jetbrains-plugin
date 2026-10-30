package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.MessageContext
import io.github.xxfast.t3code.protocol.generated.TurnCapabilities

// The queue rules of upstream's packages/client-runtime/src/state/threadWorkflows.ts,
// apps/web/src/components/chat/QueuedRunsControl.tsx, useQueuedRunEdit.ts and
// queuedMessageEdit.ts, at the tag in contracts/T3CODE_VERSION.

/** `QueuedThreadRun`, threadWorkflows.ts:24: a queued run and the message it will send. */
data class QueuedThreadRun(
  val run: Run,
  val text: String,
  val attachments: List<ChatAttachment>,
  val messageId: String,
  val context: MessageContext? = null,
  /** Carries the attribution a sent message shows: automation or agent sender. */
  val message: ConversationMessage? = null,
)

/** `ThreadQueueWorkflowState`, threadWorkflows.ts:34. */
data class ThreadQueueWorkflowState(
  val activeRun: Run?,
  val queuedRuns: List<QueuedThreadRun>,
  /** Some queued run is held, after an interrupt that held the queue. */
  val isHeld: Boolean,
  val canPromoteToSteer: Boolean,
  /** The active run is a compaction, so promoting a queued message stops it instead of steering. */
  val promoteSkipsCompaction: Boolean,
  val canSendNow: Boolean,
)

/** `ACTIVE_RUN_STATUSES`, threadWorkflows.ts:14. */
private val ACTIVE_RUN_STATUSES: Set<String> = setOf("preparing", "starting", "running", "waiting")

/** `resolveActiveThreadRun`, threadWorkflows.ts:39. */
fun resolveActiveThreadRun(runs: List<Run>): Run? =
  runs.lastOrNull { run -> run.status in ACTIVE_RUN_STATUSES }

/**
 * `resolveThreadProviderSession`, threadWorkflows.ts:65, the stopped and errored fallback too.
 * Upstream keeps it private to the file; here the queue, the model picker and the Lineage's
 * detach (`canDetachThreadProviderSession`) share this one.
 */
fun resolveThreadProviderSession(view: ThreadView): ProviderSession? {
  val activeRun: Run? = resolveActiveThreadRun(view.runs)
  val providerThreadId: String? =
    activeRun?.providerThreadId ?: view.thread.activeProviderThreadId
  val activeProviderThread: ProviderThread? =
    providerThreadId?.let { id -> view.providerThreads.find { thread -> thread.id == id } }
  val attachedProviderThread: ProviderThread? = activeProviderThread
    ?: view.providerThreads.find { thread ->
      thread.appThreadId == view.thread.id && thread.providerSessionId != null
    }
  val sessionId: String? = attachedProviderThread?.providerSessionId
  if (sessionId != null) return view.providerSessions.find { session -> session.id == sessionId }
  return view.providerSessions.lastOrNull { session ->
    session.status != "stopped" && session.status != "error"
  }
}

/**
 * `getUserQueuedThreadRuns`, threadWorkflows.ts:109: queued runs, less the automatic completion
 * deliveries (a delegated completion or a notification), which aren't the user's to touch.
 */
fun getUserQueuedThreadRuns(runs: List<Run>, messages: List<ConversationMessage>): List<Run> {
  val automaticCompletionMessageIds: Set<String> = messages
    .filter { message -> message.delegatedCompletion != null || message.notification != null }
    .map { message -> message.id }
    .toSet()
  return runs.filter { run ->
    run.status == "queued" && run.userMessageId !in automaticCompletionMessageIds
  }
}

/**
 * `isCompactionCommandMessage`, packages/contracts/src/orchestrationV2.ts:529: a prompt that only
 * asks to compact. Upstream's `IDLE_COMPACTION_TEXT` is [COMPACT_COMMAND].
 */
fun isCompactionCommandMessage(message: ConversationMessage): Boolean =
  message.role == "user" &&
    message.text.trim().lowercase() == COMPACT_COMMAND &&
    message.attachments.isEmpty()

/** `deriveThreadQueueWorkflowState`, threadWorkflows.ts:131. */
fun deriveThreadQueueWorkflowState(view: ThreadView): ThreadQueueWorkflowState {
  val activeRun: Run? = resolveActiveThreadRun(view.runs)
  val capabilities: TurnCapabilities? = resolveThreadProviderSession(view)?.capabilities?.turns
  val activeMessage: ConversationMessage? =
    view.messages.find { message -> message.id == activeRun?.userMessageId }
  val promoteSkipsCompaction: Boolean =
    activeMessage != null && isCompactionCommandMessage(activeMessage)
  val hasSteerableProviderTurn: Boolean = activeRun?.status == "running" &&
    activeRun.activeAttemptId != null &&
    view.providerTurns.any { turn ->
      turn.runAttemptId == activeRun.activeAttemptId && turn.status == "running"
    }
  val queuedRuns: List<QueuedThreadRun> = getUserQueuedThreadRuns(view.runs, view.messages)
    .sortedWith(compareBy<Run>({ run -> run.queuePosition ?: run.ordinal }, { it.ordinal }))
    .map { run ->
      val message: ConversationMessage? = view.messages.find { it.id == run.userMessageId }
      QueuedThreadRun(
        run = run,
        // A queued guest message shows the guest's words; the agent still gets the attributed text
        text = message?.context?.guest?.text ?: message?.text ?: "Queued message",
        attachments = message?.attachments.orEmpty(),
        messageId = run.userMessageId,
        context = message?.context,
        message = message,
      )
    }
  return ThreadQueueWorkflowState(
    activeRun = activeRun,
    queuedRuns = queuedRuns,
    isHeld = view.runs.any { run -> run.status == "queued" && run.queueHeld == true },
    canSendNow = activeRun == null && view.thread.archivedAt == null &&
      view.thread.deletedAt == null,
    canPromoteToSteer = promoteSkipsCompaction ||
      (hasSteerableProviderTurn &&
        (capabilities?.supportsActiveSteering == true ||
          capabilities?.supportsSteeringByInterruptRestart == true)),
    promoteSkipsCompaction = promoteSkipsCompaction,
  )
}

/** `canSend`, QueuedRunsControl.tsx:113: Send now starts a turn or goes into the active one. */
fun canSendQueuedRun(workflow: ThreadQueueWorkflowState): Boolean =
  workflow.canSendNow || workflow.canPromoteToSteer

/**
 * What `sendQueuedMessage` (QueuedRunsControl.tsx:163) dispatches for [queuedRunId]: with the
 * thread idle `resumeThreadQueue` with a run id, a `queued-run.start`; else a
 * `queued-message.promote-to-steer` into the active run. Null when it can't send.
 */
fun sendQueuedMessageCommand(
  workflow: ThreadQueueWorkflowState,
  threadId: String,
  queuedRunId: String,
  commandId: String,
): Command? {
  if (!canSendQueuedRun(workflow)) return null
  if (workflow.canSendNow) {
    return Command.QueuedRunStart(commandId = commandId, threadId = threadId, runId = queuedRunId)
  }
  val activeRun: Run = workflow.activeRun ?: return null
  return Command.QueuedMessagePromoteToSteer(
    commandId = commandId,
    threadId = threadId,
    queuedRunId = queuedRunId,
    targetRunId = activeRun.id,
  )
}

/**
 * Send now's tooltip, QueuedRunsControl.tsx:283, with the steer shortcut on the first row
 * ([shortcut] only when it is).
 */
fun queuedRunSendTooltip(workflow: ThreadQueueWorkflowState, shortcut: String?): String {
  val action: String = when {
    workflow.canSendNow -> "Start a turn with this message"
    workflow.promoteSkipsCompaction -> "Skip compaction and send this message"
    else -> "Send into the current turn"
  }
  return if (shortcut.isNullOrEmpty()) action else "$action ($shortcut)"
}

/**
 * A row's `editable`, QueuedRunsControl.tsx:132: a guest's message keeps the guest's words, the
 * owner can cancel it, not rewrite it.
 */
fun isQueuedRunEditable(queued: QueuedThreadRun): Boolean = queued.context?.guest == null

/**
 * Whether `editLatest` (QueuedRunsControl.tsx:190) is live: the edit button's [idle] rule, and
 * not while a queued message is already being edited.
 */
fun canEditQueuedRun(idle: Boolean, editingRunId: String?): Boolean =
  idle && editingRunId == null

/** The section's `aria-label`, QueuedRunsControl.tsx:230. */
fun queuedMessagesLabel(count: Int): String =
  "$count queued message${if (count == 1) "" else "s"}"

/** `QUEUE_VISIBLE_ROWS`, QueuedRunsControl.tsx:53: a longer queue folds behind "Show all". */
const val QUEUE_VISIBLE_ROWS: Int = 3

/**
 * The rows `visibleItems` (QueuedRunsControl.tsx:226) keeps: a queue longer than
 * [QUEUE_VISIBLE_ROWS] shows one fewer until [showAll], so the fold takes the last row's place.
 */
fun <T> visibleQueuedRuns(queued: List<T>, showAll: Boolean): List<T> =
  if (queued.size > QUEUE_VISIBLE_ROWS && !showAll) queued.take(QUEUE_VISIBLE_ROWS - 1) else queued

/** The fold's label, QueuedRunsControl.tsx:375. */
fun queuedFoldLabel(count: Int, showAll: Boolean): String =
  if (showAll) "Show fewer" else "Show all $count queued"

// A context link as `collectComposerContextReferences` finds it, kind and id in the href
private val CONTEXT_REFERENCE: Regex =
  Regex("(!?)\\[([^\\]\\n]{0,512})\\]\\(t3-context://v1/([a-z][a-z0-9-]{0,39})/[^\\s)]{1,200}\\)")

/**
 * A row's text, `previewText` at QueuedRunsControl.tsx:233: each context link becomes its label,
 * an image's nothing when the row shows [hasThumbnails]. Upstream also flattens assistant
 * citations (`assistantCitationsToPlainText`); the plugin has none to flatten.
 */
fun queuedRunPreviewText(text: String, hasThumbnails: Boolean): String =
  CONTEXT_REFERENCE.replace(text) { match ->
    val kind: String = match.groupValues[3]
    if (kind == "image" && hasThumbnails) "" else match.groupValues[2]
  }.trim()

/** `EditingQueuedRun`, useQueuedRunEdit.ts:46: the queued message the composer is editing. */
data class EditingQueuedRun(
  val threadId: String,
  val runId: String,
  val messageId: String,
  val originalText: String,
  val existingAttachments: List<ChatAttachment>,
)

/** What `recoverQueuedMessageEdit` did with an edit whose run left the queue. */
enum class QueuedEditRecovery { KEPT, DISCARDED, CLEAN }

/**
 * `recoverQueuedMessageEdit`, queuedMessageEdit.ts:14: an edit that changed the text moves into
 * the thread's draft when that is empty, else it's dropped. The plugin's edit is text only.
 */
fun recoverQueuedMessageEdit(
  editText: String,
  originalText: String,
  threadDraftHasContent: Boolean,
): QueuedEditRecovery {
  val dirty: Boolean = editText != originalText
  if (dirty && !threadDraftHasContent) return QueuedEditRecovery.KEPT
  return if (dirty) QueuedEditRecovery.DISCARDED else QueuedEditRecovery.CLEAN
}

/** The toast after a recovery, useQueuedRunEdit.ts:262. Null when there's nothing to say. */
fun queuedEditRecoveryMessage(recovery: QueuedEditRecovery): String? = when (recovery) {
  QueuedEditRecovery.KEPT ->
    "Queued message is no longer queued. Your unsaved edit was kept in the composer."

  QueuedEditRecovery.DISCARDED ->
    "Queued message is no longer queued. Your unsaved edit was discarded."

  QueuedEditRecovery.CLEAN -> null
}

/** The edited run left the queue (it started or was cancelled), useQueuedRunEdit.ts:252. */
fun isQueuedEditStale(view: ThreadView, editing: EditingQueuedRun): Boolean =
  view.runs.find { run -> run.id == editing.runId }?.status != "queued"

/**
 * The `queued-run.edit` a save sends, `saveEdit` and `submitQueuedRunEdit` in
 * useQueuedRunEdit.ts: the trimmed text, or the attachment-only prompt when only attachments
 * are left. Null when there'd be nothing at all. The edit keeps the message's attachments and
 * context, so neither goes along: the server keeps what a `queued-run.edit` leaves out.
 */
fun queuedRunEditCommand(
  editing: EditingQueuedRun,
  prompt: String,
  commandId: String,
): Command.QueuedRunEdit? {
  val text: String = prompt.trim()
  if (text.isEmpty() && editing.existingAttachments.isEmpty()) return null
  return Command.QueuedRunEdit(
    commandId = commandId,
    threadId = editing.threadId,
    runId = editing.runId,
    text = text.ifEmpty { ATTACHMENT_ONLY_BOOTSTRAP_PROMPT },
  )
}
