package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ui.Hint
import io.github.xxfast.t3code.plugin.ui.ThreadScreen
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** "T3 Chat": whatever thread is selected in T3 Threads, for those who'd rather dock it. */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val client: T3Client = T3.get().client
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      when (val id: String? = selection.selected.collectAsState().value) {
        null -> Hint("Pick a thread in T3 Threads")
        else -> {
          val view: ThreadView? by remember(id) { client.thread(id) }.collectAsState(initial = null)
          ChatContent(project, view)
        }
      }
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}

/** [ThreadScreen] wired to the IDE, shared by the editor tab and the tool window. */
@Composable
fun ChatContent(project: Project, view: ThreadView?) {
  val opener: T3Opener = T3Opener.getInstance(project)
  val projects: List<T3Project> by T3.get().client.projects.collectAsState()
  val favicons by ProjectIcons.get().favicons.collectAsState()
  LaunchedEffect(projects) { ProjectIcons.get().ensure(projects) }
  val projectId: String? = view?.thread?.projectId
  ThreadScreen(
    view = view,
    project = projects.find { it.id == projectId },
    favicon = favicons[projectId],
    onOpenFile = { path -> view?.let { opener.openFile(it, path) } },
    onDiffFile = { change -> view?.let { opener.showDiff(it, change) } },
    onDiffRun = { run -> view?.let { opener.showRunDiff(it, run) } },
  )
}
