package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager

/** The action place T3 Thread Details' rows invoke IDE actions from. */
const val THREAD_DETAILS_PLACE: String = "T3ThreadDetails"

/**
 * Runs the IDE action [actionId] against [project], from T3 Thread Details. Upstream's git
 * actions run on the server; here the IDE's own commit, push and pull request dialogs do the
 * work, which is where this user does them anyway. False when the IDE has no such action, as
 * without the Git or GitHub plugin. On the EDT.
 */
fun runIdeAction(project: Project, actionId: String): Boolean {
  val action: AnAction = ActionManager.getInstance().getAction(actionId) ?: return false
  val context: DataContext = SimpleDataContext.getProjectContext(project)
  ActionUtil.invokeAction(action, context, THREAD_DETAILS_PLACE, null, null)
  return true
}

/**
 * Shows the IDE's local changes, the Commit tool window (or Version Control where commits are
 * modal), for the Changes row. Upstream opens its diff surface on the branch scope instead; the
 * IDE already has that view. False without either tool window. On the EDT.
 */
fun showIdeChanges(project: Project): Boolean {
  val manager: ToolWindowManager = ToolWindowManager.getInstance(project)
  val window: ToolWindow = manager.getToolWindow(ToolWindowId.COMMIT)
    ?: manager.getToolWindow(ToolWindowId.VCS)
    ?: return false
  window.activate(null)
  return true
}
