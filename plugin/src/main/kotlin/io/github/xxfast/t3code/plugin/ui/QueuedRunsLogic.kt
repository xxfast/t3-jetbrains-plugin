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

/** `QueuedThreadRun`, threadWorkflows.ts:23: a queued run and the message it will send. */
data class QueuedThreadRun(
  val run: Run,
  val text: String,
  val attachments: List<ChatAttachment>,
  val messageId: String,
  val context: MessageContext? = null,
)

/** `ThreadQueueWorkflowState`, threadWorkflows.ts:31. */
data class ThreadQueueWorkflowState(
  val activeRun: Run?,
  val queuedRuns: List<QueuedThreadRun>,
  /** Some queued run is held, after an interrupt that held the queue. */
  val isHeld: Boolean,
  val canReorder: Boolean,
  val canPromoteToSteer: Boolean,
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

/** `deriveThreadQueueWorkflowState`, threadWorkflows.ts:126. */
fun deriveThreadQueueWorkflowState(view: ThreadView): ThreadQueueWorkflowState {
  val activeRun: Run? = resolveActiveThreadRun(view.runs)
  val capabilities: TurnCapabilities? = resolveThreadProviderSession(view)?.capabilities?.turns
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
        text = message?.text ?: "Queued message",
        attachments = message?.attachments.orEmpty(),
        messageId = run.userMessageId,
        context = message?.context,
      )
    }
  return ThreadQueueWorkflowState(
    activeRun = activeRun,
    queuedRuns = queuedRuns,
    isHeld = view.runs.any { run -> run.status == "queued" && run.queueHeld == true },
    canReorder = capabilities?.supportsQueuedMessages == true,
    canPromoteToSteer = hasSteerableProviderTurn &&
      (capabilities?.supportsActiveSteering == true ||
        capabilities?.supportsSteeringByInterruptRestart == true),
  )
}

/** A `queued-run.reorder`'s arguments: move [runId] in front of [beforeRunId], or last on null. */
data class QueuedRunMove(val runId: String, val beforeRunId: String?)

/**
 * The grip's ArrowUp, QueuedRunsControl.tsx:330: in front of the run above. Null for the first.
 * The plugin offers it as a button where upstream drags.
 */
fun queuedRunMoveUp(queued: List<QueuedThreadRun>, index: Int): QueuedRunMove? {
  if (index <= 0 || index >= queued.size) return null
  return QueuedRunMove(queued[index].run.id, queued[index - 1].run.id)
}

/** The grip's ArrowDown, QueuedRunsControl.tsx:334: in front of the run two below. */
fun queuedRunMoveDown(queued: List<QueuedThreadRun>, index: Int): QueuedRunMove? {
  if (index < 0 || index >= queued.size - 1) return null
  return QueuedRunMove(queued[index].run.id, queued.getOrNull(index + 2)?.run?.id)
}

/** The header's `aria-label`, QueuedRunsControl.tsx:226. */
fun queuedMessagesLabel(count: Int): String =
  "$count queued message${if (count == 1) "" else "s"}"

/** The Steer button's tooltip, QueuedRunsControl.tsx:410. */
fun queuedRunSteerTooltip(activeRun: Run?): String =
  if (activeRun == null) "There is no active run to steer" else "Send as a steer instead"

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
