package io.github.xxfast.t3code.plugin.terminal

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ide.NOTIFICATION_GROUP
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.has
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Thread terminals in this project's Terminal tool window, one tab per thread and terminal id.
 * Opening one again focuses its tab. Closing a tab detaches; the server's terminal lives on.
 */
@Service(Service.Level.PROJECT)
class ThreadTerminals(private val project: Project, private val scope: CoroutineScope) {
  private val tabs: MutableMap<String, Content> = ConcurrentHashMap()

  /** Opens or focuses [threadId]'s terminal. On the EDT. */
  fun open(threadId: String, terminalId: String = DEFAULT_TERMINAL_ID) {
    val manager: TerminalToolWindowManager = TerminalToolWindowManager.getInstance(project)
    val toolWindow: ToolWindow = manager.toolWindow
      ?: ToolWindowManager.getInstance(project)
        .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
      ?: return notify("No Terminal tool window in this IDE", NotificationType.ERROR)
    val key = "$threadId/$terminalId"
    val existing: Content? = tabs[key]
    if (existing != null) {
      toolWindow.activate { toolWindow.contentManager.setSelectedContent(existing, true) }
      return
    }

    val client: T3Client = T3.get().client
    val connection: ConnectionState = client.connection.value
    unavailableReason(connection)?.let { reason -> return notify(reason, NotificationType.WARNING) }
    val session: ServerSession = (connection as ConnectionState.Connected).session
    val access: TerminalAccess = accessOf(session)
    val thread: Thread = client.threads.value.find { it.id == threadId }
      ?: return notify("No thread $threadId on the server", NotificationType.WARNING)
    // No project row, no launch location: `defaultLaunchLocation` in
    // apps/web/src/components/terminal/useThreadTerminalActions.ts opens nothing either
    val workspaceRoot: String = client.projects.value
      .find { it.id == thread.projectId }
      ?.workspaceRoot
      ?: return notify("Project not loaded yet, try again", NotificationType.WARNING)
    val input: TerminalAttachInput =
      attachInput(threadId, thread.worktreePath, workspaceRoot, terminalId)

    var content: Content? = null
    val listener: T3TtyConnector.Listener = object : T3TtyConnector.Listener {
      override fun readOnly() = notify(
        "${thread.title}'s terminal is read-only: this pairing has terminal:read only",
        NotificationType.INFORMATION,
      )

      override fun failed(error: Throwable) {
        val reason: String = error.message ?: error.javaClass.simpleName
        notify("Couldn't open ${thread.title}'s terminal: $reason", NotificationType.ERROR)
        scope.launch(Dispatchers.EDT) {
          val tab: Content = content ?: return@launch
          if (!project.isDisposed && tab.manager != null) manager.closeTab(tab)
        }
      }
    }
    val connector = T3TtyConnector(
      client = client,
      input = input,
      title = thread.title,
      readOnly = access == TerminalAccess.READ,
      canObserve = session.has(TERMINAL_READ),
      parent = scope,
      listener = listener,
    )

    val parent: Disposable = Disposer.newDisposable("T3 terminal $key")
    val widget = JBTerminalWidget(project, JBTerminalSystemSettingsProvider(), parent)
    val terminal: TerminalWidget = widget.asNewWidget()
    terminal.terminalTitle.change { userDefinedTitle = thread.title }
    widget.start(connector)

    val tab: Content = manager.newTab(toolWindow, terminal)
    content = tab
    tabs[key] = tab
    Disposer.register(tab, parent)
    Disposer.register(tab) {
      tabs.remove(key, tab)
      connector.close()
    }
    // A tab whose session ended can stay open; opening again then starts a fresh one
    terminal.addTerminationCallback({ tabs.remove(key, tab) }, tab)
    connector.start()
    terminal.requestFocus()
  }

  private fun notify(message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(message, type)
      .notify(project)
  }

  companion object {
    fun getInstance(project: Project): ThreadTerminals = project.service()
  }
}
