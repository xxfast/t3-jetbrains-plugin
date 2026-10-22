package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.EditingQueuedRun
import io.github.xxfast.t3code.plugin.ui.QueuedEditRecovery
import io.github.xxfast.t3code.plugin.ui.QueuedRunMove
import io.github.xxfast.t3code.plugin.ui.QueuedThreadRun
import io.github.xxfast.t3code.plugin.ui.isQueuedEditStale
import io.github.xxfast.t3code.plugin.ui.queuedEditRecoveryMessage
import io.github.xxfast.t3code.plugin.ui.queuedRunEditCommand
import io.github.xxfast.t3code.plugin.ui.recoverQueuedMessageEdit
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The queued messages strip's commands and state for this project's chat views: what
 * `QueuedRunsControl.tsx` dispatches (`reorderQueuedRun`, `promoteQueuedRun`, `cancelQueuedRun`
 * in `packages/client-runtime/src/operations/commands.ts`), `resumeThreadQueue`, and the edit
 * mode of `useQueuedRunEdit.ts`, which points the composer at a draft of its own.
 */
@Service(Service.Level.PROJECT)
class QueuedRuns(private val project: Project, private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  private val _busy: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

  /** Upstream's `busyRunId`, by thread id: the queued run a command is on its way for. */
  val busy: StateFlow<Map<String, String>> = _busy

  private val _editing: MutableStateFlow<Map<String, EditingQueuedRun>> =
    MutableStateFlow(emptyMap())

  /** The queued message each thread's composer is editing, by thread id. */
  val editing: StateFlow<Map<String, EditingQueuedRun>> = _editing

  private val _saving: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

  /** Threads whose edit is being saved, upstream's `isSaving`. */
  val saving: StateFlow<Set<String>> = _saving

  // `queuedEditDraftTarget`: one edit per thread at a time, and a new one starts clean
  private val editDrafts: ConcurrentHashMap<String, TextFieldState> = ConcurrentHashMap()

  /** The composer's draft while [threadId] edits a queued message. */
  fun editDraft(threadId: String): TextFieldState =
    editDrafts.computeIfAbsent(threadId) { TextFieldState() }

  /** `move`, QueuedRunsControl.tsx:148: `queued-run.reorder`. */
  fun reorder(view: ThreadView, move: QueuedRunMove) {
    val command = Command.QueuedRunReorder(
      commandId = UUID.randomUUID().toString(),
      threadId = view.thread.id,
      runId = move.runId,
      beforeRunId = move.beforeRunId,
    )
    dispatching(view.thread.id, move.runId, "Couldn't reorder the queued message", command)
  }

  /** `steer`, QueuedRunsControl.tsx:168: `queued-message.promote-to-steer` into the active run. */
  fun promote(view: ThreadView, queuedRunId: String, targetRunId: String) {
    val command = Command.QueuedMessagePromoteToSteer(
      commandId = UUID.randomUUID().toString(),
      threadId = view.thread.id,
      queuedRunId = queuedRunId,
      targetRunId = targetRunId,
    )
    dispatching(view.thread.id, queuedRunId, "Couldn't steer with the queued message", command)
  }

  /** `remove`, QueuedRunsControl.tsx:211: `queued-run.cancel`. */
  fun cancel(view: ThreadView, runId: String) {
    val command = Command.QueuedRunCancel(
      commandId = UUID.randomUUID().toString(),
      threadId = view.thread.id,
      runId = runId,
    )
    dispatching(view.thread.id, runId, "Couldn't remove the queued message", command)
  }

  /**
   * `onResume`'s held queue path, ChatView.tsx:6129: `queue.resume`. A refusal says why under
   * the composer, as upstream's `setThreadError`.
   */
  fun resume(view: ThreadView) {
    val threadId: String = view.thread.id
    val command = Command.QueueResume(commandId = UUID.randomUUID().toString(), threadId = threadId)
    if (_busy.value.containsKey(threadId)) return
    _busy.update { busy -> busy + (threadId to "") }
    scope.launch {
      try {
        client.dispatch(command)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't resume the queue on $threadId", e)
        ChatController.getInstance(project).blocked(threadId, e.message ?: RESUME_FAILED)
      } finally {
        _busy.update { busy -> busy - threadId }
      }
    }
  }

  /**
   * `beginEdit`, useQueuedRunEdit.ts:207: the composer switches to a fresh draft holding
   * [queued]'s text. The thread's own draft stays as it was.
   */
  fun beginEdit(view: ThreadView, queued: QueuedThreadRun) {
    val threadId: String = view.thread.id
    editDraft(threadId).setTextAndPlaceCursorAtEnd(queued.text)
    val editing = EditingQueuedRun(
      threadId = threadId,
      runId = queued.run.id,
      messageId = queued.messageId,
      originalText = queued.text,
      existingAttachments = queued.attachments,
    )
    _editing.update { all -> all + (threadId to editing) }
  }

  /** `cancelEdit`, useQueuedRunEdit.ts:225: the edit's draft goes, the thread's comes back. */
  fun cancelEdit(threadId: String) {
    if (!_editing.value.containsKey(threadId)) return
    editDraft(threadId).clearText()
    _editing.update { all -> all - threadId }
  }

  /**
   * `saveEdit`, useQueuedRunEdit.ts:289: the composer's send saves the queued message in place.
   * A refusal stays in edit mode and says why under the composer.
   */
  fun saveEdit(threadId: String, prompt: String) {
    val editing: EditingQueuedRun = _editing.value[threadId] ?: return
    if (threadId in _saving.value) return
    val command: Command.QueuedRunEdit =
      queuedRunEditCommand(editing, prompt, UUID.randomUUID().toString()) ?: return
    _saving.update { saving -> saving + threadId }
    scope.launch {
      try {
        client.dispatch(command)
        withContext(Dispatchers.EDT) { editDraft(threadId).clearText() }
        _editing.update { all ->
          if (all[threadId]?.runId == editing.runId) all - threadId else all
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't save the queued message ${editing.runId} on $threadId", e)
        ChatController.getInstance(project).blocked(threadId, SAVE_FAILED)
      } finally {
        _saving.update { saving -> saving - threadId }
      }
    }
  }

  /**
   * The effect at useQueuedRunEdit.ts:249: when the edited run leaves the queue (it started, or
   * another client cancelled it) the edit ends. A changed edit moves into [threadDraft] when
   * that's empty, else it's dropped; either way a balloon says so.
   */
  fun reconcile(view: ThreadView, threadDraft: TextFieldState) {
    val threadId: String = view.thread.id
    val editing: EditingQueuedRun = _editing.value[threadId] ?: return
    if (!isQueuedEditStale(view, editing)) return
    val draft: TextFieldState = editDraft(threadId)
    val recovery: QueuedEditRecovery = recoverQueuedMessageEdit(
      editText = draft.text.toString(),
      originalText = editing.originalText,
      threadDraftHasContent = threadDraft.text.isNotBlank(),
    )
    if (recovery == QueuedEditRecovery.KEPT) {
      threadDraft.setTextAndPlaceCursorAtEnd(draft.text.toString())
    }
    draft.clearText()
    _editing.update { all -> all - threadId }
    queuedEditRecoveryMessage(recovery)?.let { message ->
      val kept: Boolean = recovery == QueuedEditRecovery.KEPT
      notify(project, message, if (kept) NotificationType.INFORMATION else NotificationType.WARNING)
    }
  }

  // Upstream's `setBusyRunId` around each command: one at a time per thread, failures a balloon
  private fun dispatching(
    threadId: String,
    runId: String,
    failure: String,
    command: OrchestrationV2Command,
  ) {
    if (_busy.value.containsKey(threadId)) return
    _busy.update { busy -> busy + (threadId to runId) }
    scope.launchReporting(project, failure) {
      try {
        client.dispatch(command)
      } finally {
        _busy.update { busy -> busy - threadId }
      }
    }
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(QueuedRuns::class.java)

    private const val RESUME_FAILED: String = "Could not resume thread."

    private const val SAVE_FAILED: String = "Could not save the edited queued message."

    fun getInstance(project: Project): QueuedRuns = project.service()
  }
}
