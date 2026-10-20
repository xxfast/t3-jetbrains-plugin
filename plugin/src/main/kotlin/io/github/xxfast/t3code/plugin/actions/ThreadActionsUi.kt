package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import io.github.xxfast.t3code.protocol.ThreadView
import java.awt.Point
import javax.swing.JComponent

/** The one place thread actions live, registered in `plugin.xml`. */
const val THREAD_ACTIONS: String = "T3Code.ThreadActions"

/** Where the thread view's toolbar reports its actions from. */
const val THREAD_VIEW_PLACE: String = "T3Code.ThreadView"

fun threadActions(): ActionGroup =
  ActionManager.getInstance().getAction(THREAD_ACTIONS) as ActionGroup

/**
 * An editor tab's [content] with the thread actions on top. Puts [threadId] and its live [view],
 * once there is one, in the data context, so actions invoked inside act on this tab's thread.
 */
class ThreadPanel(
  content: JComponent,
  private val threadId: String,
  private val view: () -> ThreadView?,
) : SimpleToolWindowPanel(true, true) {
  init {
    setContent(content)
    val bar: ActionToolbar = ActionManager.getInstance()
      .createActionToolbar(THREAD_VIEW_PLACE, threadActions(), true)
    bar.targetComponent = this
    setToolbar(bar.component)
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
const val SNOOZE_GROUP: String = "T3Code.Snooze"
const val WAKE_ACTION: String = "T3Code.Wake"

private fun threadContext(project: Project, threadId: String, terminalId: String? = null) =
  SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .add(T3DataKeys.THREAD_ID, threadId)
    .add(T3DataKeys.FROM_SIDEBAR, true)
    .add(T3DataKeys.TERMINAL_ID, terminalId)
    .build()

/**
 * Runs the registered action [actionId] on [threadId], for a row's hover button. [terminalId]
 * names the terminal for one that opens them. False when the action isn't registered, like
 * the terminal's without the Terminal plugin.
 */
fun performThreadAction(
  project: Project,
  threadId: String,
  actionId: String,
  terminalId: String? = null,
): Boolean {
  val action: AnAction = ActionManager.getInstance().getAction(actionId) ?: return false
  val context: DataContext = threadContext(project, threadId, terminalId)
  ActionUtil.invokeAction(action, context, SIDEBAR_PLACE, null, null)
  return true
}

/** The terminal module's action, `t3code-terminal.xml`. */
const val OPEN_TERMINAL_ACTION: String = "T3Code.OpenTerminal"


/**
 * The thread actions for [threadId] as the IDE's own popup, at [x], [y] on screen. [group] picks
 * a registered submenu instead, like a row's snooze button does.
 */
fun showThreadMenu(project: Project, threadId: String, x: Int, y: Int, group: String? = null) {
  val context: DataContext = threadContext(project, threadId)
  val actions: ActionGroup =
    group?.let { ActionManager.getInstance().getAction(it) as? ActionGroup } ?: threadActions()
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
