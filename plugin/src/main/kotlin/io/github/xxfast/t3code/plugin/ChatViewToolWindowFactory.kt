package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.threadActions
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.ui.ChatView
import io.github.xxfast.t3code.plugin.ui.ComposerState
import io.github.xxfast.t3code.plugin.ui.Hint
import io.github.xxfast.t3code.plugin.ui.activeRun
import io.github.xxfast.t3code.plugin.ui.composerState
import io.github.xxfast.t3code.plugin.ui.mergeOptimistic
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** "T3 Chat View": whatever thread is selected in T3 Sidebar, for those who'd rather dock it. */
class ChatViewToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val client: T3Client = T3.get().client
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      when (val id: String? = selection.selected.collectAsState().value) {
        null -> Hint("Pick a thread in T3 Sidebar")
        else -> {
          val attempt: MutableIntState = remember(id) { mutableIntStateOf(0) }
          // Keyed, so a new thread or a retry starts from a fresh state instead of the last value
          val load: ThreadLoad = key(id, attempt.intValue) {
            remember { client.threadLoad(id) }.collectAsState(initial = ThreadLoad()).value
          }
          ChatContent(project, id, load, onRetry = { attempt.intValue++ })
        }
      }
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
    // Thread actions act on the selection, which is what this window shows
    toolWindow.setTitleActions(listOf(threadActions()))
    ThreadVisits.getInstance(project).follow(toolWindow)
  }
}

/** [ChatView] wired to the IDE, shared by the editor tab and the tool window. */
@Composable
fun ChatContent(project: Project, threadId: String, load: ThreadLoad, onRetry: () -> Unit) {
  val opener: T3Opener = T3Opener.getInstance(project)
  val controller: ChatController = ChatController.getInstance(project)
  val client: T3Client = T3.get().client
  val projects: List<T3Project> by client.projects.collectAsState()
  val favicons: Map<String, ImageBitmap> by ProjectIcons.get().favicons.collectAsState()
  val connection: ConnectionState by client.connection.collectAsState()
  val outboxes: Map<String, PendingMessages> by controller.outboxes.collectAsState()
  val pending: PendingMessages = outboxes[threadId] ?: PendingMessages()
  var queue: Boolean by remember(threadId) { mutableStateOf(false) }
  val draft: TextFieldState = remember(threadId) { controller.draft(threadId) }
  LaunchedEffect(projects) { ProjectIcons.get().ensure(projects) }

  val view: ThreadView? = load.view
  // Again when a dispatch answers: the view may already be past its sequence
  LaunchedEffect(view, pending.delivered) { view?.let(controller::reconcile) }
  val shown: ThreadView? = remember(view, pending.messages) {
    view?.let { current ->
      current.copy(items = mergeOptimistic(current.items, current.messages, pending.messages))
    }
  }
  val composer: ComposerState = composerState(
    connection = connection,
    running = view?.let { activeRun(it.runs) } != null,
    sending = pending.sending,
    error = pending.error,
    queue = queue,
  )
  val projectId: String? = view?.thread?.projectId
  ChatView(
    view = shown,
    error = load.error,
    project = projects.find { it.id == projectId },
    favicon = favicons[projectId],
    composer = composer,
    draft = draft,
    onSend = { text, mode -> view?.let { controller.send(it, text, mode) } },
    onQueueChange = { queue = it },
    onRetry = onRetry,
    onOpenFile = { path -> view?.let { opener.openFile(it, path) } },
    onDiffFile = { change -> view?.let { opener.showDiff(it, change) } },
    onDiffRun = { run -> view?.let { opener.showRunDiff(it, run) } },
  )
}
