package io.github.xxfast.t3code.plugin.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.NOTIFICATION_GROUP
import io.github.xxfast.t3code.plugin.ide.launchReporting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The undo action's id in `plugin.xml`, for the notice's shortcut label. */
const val UNDO_ACTION: String = "T3Code.UndoThreadAction"

/** The Sidebar's tool window id, where the undo shortcut takes `$Undo`'s keys. */
const val SIDEBAR_TOOL_WINDOW: String = "T3 Sidebar"

/**
 * This window's [ThreadUndo], its 5 s timer and its notice. Upstream shows the notice inline at
 * the bottom of the Sidebar; here it's a balloon with the undo as its action, replaced as the
 * count changes and expired with the window.
 */
@Service(Service.Level.PROJECT)
class ThreadUndos(private val project: Project, private val scope: CoroutineScope) {
  val undo: ThreadUndo = ThreadUndo()

  private var timer: Job? = null
  private var balloon: Notification? = null

  init {
    scope.launch { undo.notice.collect { notice -> withContext(Dispatchers.EDT) { show(notice) } } }
  }

  /**
   * Sends [send] under a claim on [kind] for [threadId], then offers [revert] as an undo of
   * [action]. A failed send finishes the claim and throws, as upstream's actions do.
   */
  suspend fun undoable(
    kind: String,
    threadId: String,
    action: UndoAction,
    failureTitle: String,
    send: suspend () -> Unit,
    revert: suspend () -> Unit,
  ) {
    val claim: UndoClaim = undo.begin(kind, threadId)
    try {
      send()
    } catch (e: Throwable) {
      claim.finish()
      throw e
    }
    if (!undo.show(UndoEntry(action, claim, failureTitle, revert))) return
    synchronized(this) {
      timer?.cancel()
      timer = scope.launch {
        delay(UNDO_WINDOW_MS)
        undo.expire()
      }
    }
  }

  /** `undoLatestThreadAction`: false when there's nothing to undo. */
  fun undoLatest(): Boolean {
    val taken: List<UndoEntry> = undo.takeLatest()
    taken.forEach { entry -> scope.launchReporting(project, entry.failureTitle) { entry.undo() } }
    return taken.isNotEmpty()
  }

  private fun show(notice: UndoNotice?) {
    balloon?.expire()
    balloon = notice?.let { shown ->
      val shortcut: String = KeymapUtil.getFirstKeyboardShortcutText(UNDO_ACTION)
      NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(noticeText(shown), NotificationType.INFORMATION)
        .addAction(NotificationAction.createSimpleExpiring(undoText(shortcut)) { undoLatest() })
        .also { it.notify(project) }
    }
  }

  companion object {
    fun getInstance(project: Project): ThreadUndos = project.service()
  }
}

/**
 * `thread.undo`: undoes the latest settle, snooze, unpin or archive while its notice is up.
 * Shares `$Undo`'s shortcuts, upstream's `mod+z`, and [UndoThreadPromoter] puts it first only in
 * the Sidebar, so an editor's undo stays an editor's undo. Keymappable on its own too.
 */
class UndoThreadAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    e.presentation.isEnabledAndVisible =
      project != null && ThreadUndos.getInstance(project).undo.notice.value != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    ThreadUndos.getInstance(project).undoLatest()
  }
}

/** Ahead of `$Undo` in the Sidebar; disabled without a notice, so `$Undo` runs as before. */
class UndoThreadPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    if (context.getData(PlatformDataKeys.TOOL_WINDOW)?.id != SIDEBAR_TOOL_WINDOW) return null
    return actions.sortedByDescending { it is UndoThreadAction }
  }
}
