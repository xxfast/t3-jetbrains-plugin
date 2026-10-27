package io.github.xxfast.t3code.plugin.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.ui.awt.RelativePoint
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.SidebarThreadDropPlan
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import java.awt.Point
import java.util.UUID
import javax.swing.JComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The one place thread actions live, registered in `plugin.xml`. */
const val THREAD_ACTIONS: String = "T3Code.ThreadActions"

fun threadActions(): ActionGroup =
  ActionManager.getInstance().getAction(THREAD_ACTIONS) as ActionGroup

/**
 * An editor tab's [content]. Puts [threadId] and its live [view], once there is one, in the
 * data context, so actions invoked inside (Find Action, a keymap) act on this tab's thread.
 */
class ThreadPanel(
  content: JComponent,
  private val threadId: String,
  private val view: () -> ThreadView?,
) : SimpleToolWindowPanel(true, true) {
  init {
    setContent(content)
  }

  override fun uiDataSnapshot(sink: DataSink) {
    super.uiDataSnapshot(sink)
    sink[T3DataKeys.THREAD_ID] = threadId
    view()?.takeIf { it.thread.id == threadId }?.let { live -> sink[T3DataKeys.THREAD_VIEW] = live }
  }
}

/** Where the Sidebar's rows report the actions they run. */
const val SIDEBAR_PLACE: String = "T3Code.Sidebar"

const val SETTLE_ACTION: String = "T3Code.ToggleSettle"
const val PIN_ACTION: String = "T3Code.TogglePin"
const val RENAME_ACTION: String = "T3Code.RenameThread"
const val SNOOZE_GROUP: String = "T3Code.Snooze"
const val WAKE_ACTION: String = "T3Code.Wake"
const val OPEN_IN_IDE_ACTION: String = "T3Code.OpenInIde"

private fun threadContext(
  project: Project,
  threadId: String,
  terminalId: String? = null,
  view: ThreadView? = null,
  fromSidebar: Boolean = true,
) = SimpleDataContext.builder()
  .add(CommonDataKeys.PROJECT, project)
  .add(T3DataKeys.THREAD_ID, threadId)
  .add(T3DataKeys.THREAD_VIEW, view?.takeIf { it.thread.id == threadId })
  .add(T3DataKeys.FROM_SIDEBAR, fromSidebar)
  .add(T3DataKeys.TERMINAL_ID, terminalId)
  .build()

/**
 * Runs the registered action [actionId] on [threadId], for a row's hover button or the
 * ChatView's title. [terminalId] names the terminal for one that opens them. False when the
 * action isn't registered, like the terminal's without the Terminal plugin.
 */
fun performThreadAction(
  project: Project,
  threadId: String,
  actionId: String,
  terminalId: String? = null,
  fromSidebar: Boolean = true,
): Boolean {
  val action: AnAction = ActionManager.getInstance().getAction(actionId) ?: return false
  val context: DataContext = threadContext(project, threadId, terminalId, fromSidebar = fromSidebar)
  ActionUtil.invokeAction(action, context, SIDEBAR_PLACE, null, null)
  return true
}

/**
 * Sends a Sidebar drop of [threadId] by [plan], one command at a time, and says which one failed
 * the way `handleThreadDragEnd` (Sidebar.tsx) toasts it. A settle goes through [SETTLE_ACTION],
 * so it gets the menu's undo and moves on from the open thread, as upstream's `settleThread`.
 */
fun dropThread(project: Project, threadId: String, plan: SidebarThreadDropPlan) {
  if (plan == SidebarThreadDropPlan.None) return
  if (plan == SidebarThreadDropPlan.Settle) {
    performThreadAction(project, threadId, SETTLE_ACTION)
    return
  }
  val client: T3Client = T3.get().client
  val row: Thread = client.threads.value.find { it.id == threadId } ?: return
  val steps: List<DropStep> = dropCommands(plan, row, client.threads.value) {
    UUID.randomUUID().toString()
  }
  ThreadActionScope.getInstance(project).scope.launch {
    for (step in steps) {
      try {
        client.dispatch(step.command)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        notify(project, e.message ?: e.javaClass.simpleName, NotificationType.ERROR, step.failure)
        return@launch
      }
    }
  }
}

/** The terminal module's action, `t3code-terminal.xml`. */
const val OPEN_TERMINAL_ACTION: String = "T3Code.OpenTerminal"


/**
 * The thread actions for [threadId] as the IDE's own popup, at [x], [y] on screen: upstream's
 * `useThreadActionMenu`, from a Sidebar row or the ChatView's title. [group] picks a registered
 * submenu instead, like a row's snooze button does. [view] is the ChatView's live thread, so its
 * actions skip the snapshot; a row has none and reads as [T3DataKeys.FROM_SIDEBAR].
 */
fun showThreadMenu(
  project: Project,
  threadId: String,
  x: Int,
  y: Int,
  group: String? = null,
  view: ThreadView? = null,
) {
  val context: DataContext =
    threadContext(project, threadId, view = view, fromSidebar = view == null)
  val actions: ActionGroup =
    group?.let { ActionManager.getInstance().getAction(it) as? ActionGroup } ?: threadActions()
  showActionPopup(actions, context, x, y)
}

/** The bulk actions, registered in `plugin.xml`. */
const val THREAD_BULK_ACTIONS: String = "T3Code.ThreadBulkActions"

/**
 * The bulk menu for the picked [threadIds], at [x], [y] on screen: upstream's
 * `handleMultiSelectContextMenu`, what a right-click on a picked row opens.
 */
fun showThreadBulkMenu(project: Project, threadIds: List<String>, x: Int, y: Int) {
  val context: DataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .add(T3DataKeys.THREAD_IDS, threadIds)
    .add(T3DataKeys.FROM_SIDEBAR, true)
    .build()
  val actions: ActionGroup =
    ActionManager.getInstance().getAction(THREAD_BULK_ACTIONS) as ActionGroup
  showActionPopup(actions, context, x, y)
}

/** The project actions, registered in `plugin.xml`. */
const val PROJECT_ACTIONS: String = "T3Code.ProjectActions"

/**
 * The menu for the T3 project [projectId] at [at]: what a right-click or Shift+F10 on a row of
 * the Sidebar's project picker opens. [onChosen] runs once an item is picked, before it runs.
 */
fun showProjectMenu(project: Project, projectId: String, at: RelativePoint, onChosen: () -> Unit) {
  val context: DataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .add(T3DataKeys.PROJECT_ID, projectId)
    .build()
  val actions: ActionGroup = ActionManager.getInstance().getAction(PROJECT_ACTIONS) as ActionGroup
  val popup: ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
    null,
    actions,
    context,
    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
    true,
  )
  popup.addListener(object : JBPopupListener {
    override fun onClosed(event: LightweightWindowEvent) {
      if (event.isOk) onChosen()
    }
  })
  popup.show(at)
}

private fun showActionPopup(actions: ActionGroup, context: DataContext, x: Int, y: Int) {
  JBPopupFactory.getInstance()
    .createActionGroupPopup(
      null,
      actions,
      context,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
      true,
    )
    .show(RelativePoint.fromScreen(Point(x, y)))
}
