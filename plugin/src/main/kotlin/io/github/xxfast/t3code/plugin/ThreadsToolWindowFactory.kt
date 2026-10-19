package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ui.ConnectionBanner
import io.github.xxfast.t3code.plugin.ui.ScenarioPicker
import io.github.xxfast.t3code.plugin.ui.ThreadsPanel
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.T3Client
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** "T3 Threads": T3's sidebar. Opening a thread is [T3Opener.openThread]'s business. */
class ThreadsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val t3: T3 = T3.get()
    val client: T3Client = t3.client
    val fixtures: FixtureClient? = t3.fixtures
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    val opener: T3Opener = T3Opener.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      val connection by client.connection.collectAsState()
      val projects by client.projects.collectAsState()
      val threads by client.threads.collectAsState()
      val selected: String? by selection.selected.collectAsState()
      val header: @Composable () -> Unit = {
        // T3's sidebar has no connection line; ours only appears when something needs doing
        if (connection !is ConnectionState.Connected) {
          ConnectionBanner(
            connection = connection,
            onReconnect = t3::reconnect,
            onPair = t3::pair,
            onOpenSettings = { T3.openSettings(project) },
          )
        }
      }
      val footer: @Composable () -> Unit = {
        if (fixtures != null) {
          val scenarios by fixtures.scenarios.collectAsState()
          val scenario by fixtures.selected.collectAsState()
          ScenarioPicker(scenarios, scenario, fixtures::select)
        }
      }
      ThreadsPanel(
        projects = projects,
        threads = threads,
        selectedThreadId = selected,
        onOpenThread = opener::openThread,
        header = header,
        footer = footer,
      )
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}
