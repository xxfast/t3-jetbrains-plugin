package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.plugin.ui.ConnectionBanner
import io.github.xxfast.t3code.plugin.ui.ScenarioPicker
import io.github.xxfast.t3code.plugin.ui.T3ToolWindow
import io.github.xxfast.t3code.plugin.ui.effectiveProjectId
import io.github.xxfast.t3code.protocol.T3Client
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

class T3ToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val t3: T3 = T3.get()
    val client: T3Client = t3.client
    val fixtures: FixtureClient? = t3.fixtures
    val filter: ProjectFilter = ProjectFilter.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      val connection by client.connection.collectAsState()
      val projects by client.projects.collectAsState()
      val threads by client.threads.collectAsState()
      val persisted: String? by filter.selected.collectAsState()
      T3ToolWindow(
        connection = connection,
        projects = projects,
        threads = threads,
        selectedProjectId = effectiveProjectId(projects, persisted),
        onSelectProject = { filter.projectId = it },
        onOpenThread = { id -> LOG.info("Open thread $id") },
        header = {
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
        },
      )
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}

private val LOG: Logger = Logger.getInstance(T3ToolWindowFactory::class.java)
