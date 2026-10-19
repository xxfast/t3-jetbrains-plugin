package io.github.xxfast.t3code.plugin.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.actions.T3DataKeys

/**
 * Opens the thread's server-side terminal as a tab in the Terminal tool window. Lives in the
 * optional terminal module, so the action doesn't exist when the terminal plugin doesn't.
 */
class OpenTerminalAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val reason: String? = when (threadIdOf(event)) {
      null -> "No thread selected"
      else -> unavailableReason(T3.get().client.connection.value)
    }
    event.presentation.isEnabled = reason == null
    event.presentation.description = reason ?: DESCRIPTION
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project: Project = event.project ?: return
    val threadId: String = threadIdOf(event) ?: return
    ThreadTerminals.getInstance(project).open(threadId)
  }

  private fun threadIdOf(event: AnActionEvent): String? {
    val project: Project = event.project ?: return null
    return event.getData(T3DataKeys.THREAD_ID)
      ?: ThreadSelection.getInstance(project).selected.value
  }

  private companion object {
    const val DESCRIPTION: String = "Open the thread's terminal in the Terminal tool window"
  }
}
