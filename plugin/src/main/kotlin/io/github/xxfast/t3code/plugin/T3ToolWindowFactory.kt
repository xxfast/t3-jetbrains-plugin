package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.xxfast.t3code.plugin.ui.T3ToolWindow
import io.github.xxfast.t3code.plugin.ui.ThreadScreen
import io.github.xxfast.t3code.plugin.ui.effectiveProjectId
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

class T3ToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val t3: T3 = T3.get()
    val client: T3Client = t3.client
    val fixtures: FixtureClient? = t3.fixtures
    val filter: ProjectFilter = ProjectFilter.getInstance(project)
    val opener: T3Opener = T3Opener.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      val connection by client.connection.collectAsState()
      val projects by client.projects.collectAsState()
      val threads by client.threads.collectAsState()
      val persisted: String? by filter.selected.collectAsState()
      var openThreadId: String? by remember { mutableStateOf(null) }
      val header: @Composable () -> Unit = {
        if (fixtures != null) {
          val scenarios by fixtures.scenarios.collectAsState()
          val selected by fixtures.selected.collectAsState()
          ScenarioPicker(scenarios, selected, fixtures::select)
        }
        ConnectionBanner(
          connection = connection,
          onReconnect = t3::reconnect,
          onPair = t3::pair,
          onOpenSettings = { T3.openSettings(project) },
        )
      }
      when (val id: String? = openThreadId) {
        null -> T3ToolWindow(
          connection = connection,
          projects = projects,
          threads = threads,
          selectedProjectId = effectiveProjectId(projects, persisted),
          onSelectProject = { filter.projectId = it },
          onOpenThread = { openThreadId = it },
          header = header,
        )

        else -> {
          val view: ThreadView? by remember(id) { client.thread(id) }.collectAsState(initial = null)
          val current: ThreadView? = view
          ThreadScreen(
            view = view,
            onBack = { openThreadId = null },
            onOpenFile = { path -> current?.let { opener.openFile(it, path) } },
            onDiffFile = { change -> current?.let { opener.showDiff(it, change) } },
            onDiffRun = { run -> current?.let { opener.showRunDiff(it, run) } },
            header = header,
          )
        }
      }
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}
