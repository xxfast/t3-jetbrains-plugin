package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAwareAction
import io.github.xxfast.t3code.plugin.ui.ThreadFindState

/** Find uses the IDE keymap, and only takes over inside a T3 chat editor. */
abstract class ThreadFindAction(private val delta: Int? = null) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    val find: ThreadFindState? = event.getData(T3DataKeys.THREAD_FIND)
    event.presentation.isEnabledAndVisible = find != null &&
      (delta == null || (find.isOpen && find.matches.isNotEmpty()))
  }

  override fun actionPerformed(event: AnActionEvent) {
    val find: ThreadFindState = event.getData(T3DataKeys.THREAD_FIND) ?: return
    if (delta == null) find.open() else find.step(delta)
  }
}

class FindInThreadAction : ThreadFindAction()
class FindNextInThreadAction : ThreadFindAction(1)
class FindPreviousInThreadAction : ThreadFindAction(-1)

/** Keep the editor's normal Find actions everywhere outside the chat tab. */
class ThreadFindPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    if (context.getData(T3DataKeys.THREAD_FIND) == null) return null
    return actions.sortedByDescending { it is ThreadFindAction }
  }
}
