package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.OPEN_TERMINAL_ACTION
import io.github.xxfast.t3code.plugin.actions.SETTLE_ACTION
import io.github.xxfast.t3code.plugin.actions.SNOOZE_GROUP
import io.github.xxfast.t3code.plugin.actions.ThreadUndos
import io.github.xxfast.t3code.plugin.actions.UNDO_ACTION
import io.github.xxfast.t3code.plugin.actions.UndoNotice
import io.github.xxfast.t3code.plugin.actions.WAKE_ACTION
import io.github.xxfast.t3code.plugin.actions.blockedBy
import io.github.xxfast.t3code.plugin.actions.performThreadAction
import io.github.xxfast.t3code.plugin.actions.showThreadMenu
import io.github.xxfast.t3code.plugin.actions.undoText
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ide.RunningTerminals
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.ui.BoringSidebar
import io.github.xxfast.t3code.plugin.ui.ConnectionBanner
import io.github.xxfast.t3code.plugin.ui.FolderActions
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.RowActions
import io.github.xxfast.t3code.plugin.ui.RowDetails
import io.github.xxfast.t3code.plugin.ui.Shelves
import io.github.xxfast.t3code.plugin.ui.Sidebar
import io.github.xxfast.t3code.plugin.ui.THREAD_SETTLEMENT
import io.github.xxfast.t3code.plugin.ui.THREAD_SNOOZE
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.T3Client
import java.awt.Point
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel
import io.github.xxfast.t3code.protocol.Project as T3Project

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
    val renamer: SidebarRename = SidebarRename.getInstance(project)
    val undos: ThreadUndos = ThreadUndos.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      val filter: ProjectFilter by awareness.filter.collectAsState()
      val open: Set<String> by awareness.open.collectAsState()
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
      // Shelved rows stay on their shelf until the config says otherwise; buttons wait for it
      val settlement: Boolean = catalog.supports(THREAD_SETTLEMENT, unknown = true)
      val snooze: Boolean = catalog.supports(THREAD_SNOOZE, unknown = true)
      val operable: Boolean = blockedBy(connection) == null
      val actions: RowActions = remember(catalog, operable) {
        fun can(capability: String): Boolean = operable && catalog.supports(capability)
        fun action(capability: String, id: String): ((String) -> Unit)? =
          if (can(capability)) { threadId -> performThreadAction(project, threadId, id) } else null
        RowActions(
          settle = action(THREAD_SETTLEMENT, SETTLE_ACTION),
          snooze = if (can(THREAD_SNOOZE)) {
            { threadId, x, y -> showThreadMenu(project, threadId, x, y, SNOOZE_GROUP) }
          } else {
            null
          },
          wake = action(THREAD_SNOOZE, WAKE_ACTION),
          dismissWoke = if (operable) {
            { threadId, wokeAt -> ThreadVisits.getInstance(project).acknowledge(threadId, wokeAt) }
          } else {
            null
          },
          rename = if (operable) renamer::start else null,
          renamed = renamer::end,
        )
      }
      val terminals: Map<String, List<String>> by RunningTerminals.get().byThread.collectAsState()
      val details: RowDetails = remember(terminals) {
        RowDetails(
          terminals = terminals,
          openTerminal = { threadId, terminalId ->
            performThreadAction(project, threadId, OPEN_TERMINAL_ACTION, terminalId)
          },
        )
      }
      val renaming: String? by renamer.renaming.collectAsState()
      val shelves: Shelves by SidebarShelves.get().shelves.collectAsState()
      val workingShelf: Boolean by T3Settings.get().sidebarWorkingShelf.collectAsState()
      val boringSidebar: Boolean by T3Settings.get().boringSidebar.collectAsState()
      val multiline: Boolean by T3Settings.get().boringSidebarMultiline.collectAsState()
      val expanded: Map<String, Boolean> by
        SidebarFolders.get().projectExpandedById.collectAsState()
      val boring: BoringSidebar? = if (boringSidebar) BoringSidebar(multiline, expanded) else null
      val folderActions: FolderActions = remember(operable) {
        val newThread: ((String) -> Unit)? =
          if (operable) NewThread.getInstance(project)::start else null
        FolderActions(
          toggle = SidebarFolders.get()::toggle,
          newThread = newThread,
          contextMenu = { projectId, x, y ->
            val target: T3Project? = client.projects.value.find { it.id == projectId }
            if (target != null) showFolderMenu(project, target, newThread, x, y)
          },
        )
      }
      val undo: UndoNotice? by undos.footer.collectAsState()
      // Read with each notice, so a keymap change shows up on the next one
      val undoLabel: String =
        remember(undo) { undoText(KeymapUtil.getFirstKeyboardShortcutText(UNDO_ACTION)) }
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
        open = open,
        onFilterChange = awareness::choose,
        onNewThread = if (operable) NewThread.getInstance(project)::open else null,
        onOpenSettings = { T3.openSettings(project) },
        settlement = settlement,
        snooze = snooze,
        actions = actions,
        shelves = shelves,
        onShelvesChange = SidebarShelves.get()::change,
        renaming = renaming,
        details = details,
        workingShelf = workingShelf,
        boring = boring,
        folderActions = folderActions,
        undo = undo,
        undoLabel = undoLabel,
        onUndo = { undos.undoLatest() },
      )
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}

/**
 * A folder header's context menu, upstream's `handleProjectFolderContextMenu` (Sidebar.tsx:1209):
 * New thread and Copy path. Project settings isn't there, the plugin has none to open.
 */
private fun showFolderMenu(
  project: Project,
  target: T3Project,
  newThread: ((String) -> Unit)?,
  x: Int,
  y: Int,
) {
  val group = DefaultActionGroup()
  if (newThread != null) group.add(DumbAwareAction.create("New thread") { newThread(target.id) })
  group.add(
    DumbAwareAction.create("Copy path") {
      CopyPasteManager.getInstance().setContents(StringSelection(target.workspaceRoot))
    },
  )
  JBPopupFactory.getInstance()
    .createActionGroupPopup(
      null,
      group,
      SimpleDataContext.getProjectContext(project),
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
      true,
    )
    .show(RelativePoint.fromScreen(Point(x, y)))
}
