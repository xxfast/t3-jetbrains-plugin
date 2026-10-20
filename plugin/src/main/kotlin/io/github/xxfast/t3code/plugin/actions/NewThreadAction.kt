package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.NewThread
import io.github.xxfast.t3code.plugin.T3

/**
 * Upstream's `chat.new` (mod+n): with more than one project it opens the "New thread in..."
 * picker. The IDE owns its mod+n for the New and Generate popups, so this joins those groups
 * instead of taking the key. Enabled when a command can go out, as the Sidebar's button.
 */
class NewThreadAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val reason: String? = blockedBy(T3.get().client.connection.value)
    e.presentation.isEnabledAndVisible = e.project != null
    e.presentation.isEnabled = e.project != null && reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    NewThread.getInstance(project).open()
  }
}
