package io.github.xxfast.t3code.plugin.web

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.actions.NO_THREAD
import io.github.xxfast.t3code.plugin.actions.threadRow
import io.github.xxfast.t3code.protocol.Thread

/** The server's `environmentId`, once `server.getConfig` has answered. */
private fun environmentId(project: Project): String? =
  ChatController.getInstance(project).catalog.value?.environmentId

/** One of the web UI's settings pages in a T3 web view tab, see [openWebView]. */
abstract class OpenWebViewAction(private val kind: WebViewKind) : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val route: String = webViewRoute(kind, environmentId(project)) ?: return
    openWebView(project, kind.title, route)
  }
}

class OpenServerSettingsWebViewAction : OpenWebViewAction(WebViewKind.Settings)

class OpenProviderSetupWebViewAction : OpenWebViewAction(WebViewKind.Providers)

class OpenScheduledTasksWebViewAction : OpenWebViewAction(WebViewKind.ScheduledTasks)

/**
 * "Open in T3 web view" on a thread: its route in the web UI, which is also the way to the PR
 * review guide, a right panel there with no route of its own.
 */
class OpenThreadWebViewAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val row: Thread? = e.threadRow(project, T3.get().client)
    val reason: String? = when {
      row == null -> NO_THREAD
      environmentId(project) == null -> "Waiting for the server's config"
      else -> null
    }
    e.presentation.isVisible = true
    e.presentation.isEnabled = reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val row: Thread = e.threadRow(project, T3.get().client) ?: return
    val route: String =
      webViewRoute(WebViewKind.Thread, environmentId(project), threadId = row.id) ?: return
    openWebView(project, row.title, route)
  }
}
