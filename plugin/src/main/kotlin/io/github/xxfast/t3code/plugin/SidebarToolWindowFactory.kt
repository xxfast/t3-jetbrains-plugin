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
import io.github.xxfast.t3code.plugin.actions.SETTLE_ACTION
import io.github.xxfast.t3code.plugin.actions.blockedBy
import io.github.xxfast.t3code.plugin.actions.performThreadAction
import io.github.xxfast.t3code.plugin.actions.showThreadMenu
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ui.ConnectionBanner
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.RowActions
import io.github.xxfast.t3code.plugin.ui.Shelves
import io.github.xxfast.t3code.plugin.ui.Sidebar
import io.github.xxfast.t3code.plugin.ui.THREAD_SETTLEMENT
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.T3Client
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** "T3 Sidebar": T3's sidebar. Opening a thread is [T3Opener.openThread]'s business. */
class SidebarToolWindowFactory : ToolWindowFactory, DumbAware {
  // Runs when the tool window registers, before it's ever opened
  override fun init(toolWindow: ToolWindow) {
    WaitingWatcher.getInstance(toolWindow.project).attach(toolWindow)
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val t3: T3 = T3.get()
    val client: T3Client = t3.client
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    val opener: T3Opener = T3Opener.getInstance(project)
    val icons: ProjectIcons = ProjectIcons.get()
    val awareness: ProjectAwareness = ProjectAwareness.getInstance(project)
    val controller: ChatController = ChatController.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      val filter: ProjectFilter by awareness.filter.collectAsState()
      val connection by client.connection.collectAsState()
      val projects by client.projects.collectAsState()
      val threads by client.threads.collectAsState()
      val selected: String? by selection.selected.collectAsState()
      val favicons by icons.favicons.collectAsState()
      LaunchedEffect(projects) { icons.ensure(projects) }
      // The provider catalog, for the cards' provider stack; the ChatView fetches the same one
      val connected: Boolean = connection is ConnectionState.Connected
      LaunchedEffect(connected) {
        if (connected) controller.loadCatalog() else controller.dropCatalog()
      }
      val catalog: ServerInfo? by controller.catalog.collectAsState()
      val providers: Map<String, ServerProvider> =
        remember(catalog) { catalog?.providers.orEmpty().associateBy { it.instanceId } }
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
      // Settled rows stay on their shelf until the config says otherwise; buttons wait for it
      val settlement: Boolean = catalog.supports(THREAD_SETTLEMENT, unknown = true)
      val operable: Boolean = blockedBy(connection) == null
      val actions: RowActions = remember(catalog, operable) {
        fun action(capability: String, id: String): ((String) -> Unit)? =
          if (operable && catalog.supports(capability)) {
            { threadId -> performThreadAction(project, threadId, id) }
          } else {
            null
          }
        RowActions(settle = action(THREAD_SETTLEMENT, SETTLE_ACTION))
      }
      val shelves: Shelves by SidebarShelves.get().shelves.collectAsState()
      Sidebar(
        projects = projects,
        threads = threads,
        selectedThreadId = selected,
        onOpenThread = opener::openThread,
        favicons = favicons,
        providers = providers,
        header = header,
        onContextMenu = { threadId, x, y -> showThreadMenu(project, threadId, x, y) },
        filter = filter,
        onFilterChange = awareness::choose,
        onNewThread = null, // #17
        onOpenSettings = { T3.openSettings(project) },
        settlement = settlement,
        actions = actions,
        shelves = shelves,
        onShelvesChange = SidebarShelves.get()::change,
      )
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}
