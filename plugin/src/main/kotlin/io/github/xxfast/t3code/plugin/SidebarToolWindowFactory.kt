package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.OPEN_TERMINAL_ACTION
import io.github.xxfast.t3code.plugin.actions.PIN_ACTION
import io.github.xxfast.t3code.plugin.actions.SETTLE_ACTION
import io.github.xxfast.t3code.plugin.actions.SNOOZE_GROUP
import io.github.xxfast.t3code.plugin.actions.WAKE_ACTION
import io.github.xxfast.t3code.plugin.actions.blockedBy
import io.github.xxfast.t3code.plugin.actions.dropThread
import io.github.xxfast.t3code.plugin.actions.performThreadAction
import io.github.xxfast.t3code.plugin.actions.showThreadBulkMenu
import io.github.xxfast.t3code.plugin.actions.showThreadMenu
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ide.RunningTerminals
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.ui.ConnectionBanner
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.RowActions
import io.github.xxfast.t3code.plugin.ui.RowDetails
import io.github.xxfast.t3code.plugin.ui.Shelves
import io.github.xxfast.t3code.plugin.ui.Sidebar
import io.github.xxfast.t3code.plugin.ui.THREAD_ACTIVE_REORDER
import io.github.xxfast.t3code.plugin.ui.THREAD_PINNING
import io.github.xxfast.t3code.plugin.ui.THREAD_PIN_REORDER
import io.github.xxfast.t3code.plugin.ui.THREAD_SETTLEMENT
import io.github.xxfast.t3code.plugin.ui.THREAD_JUMP_COUNT
import io.github.xxfast.t3code.plugin.ui.THREAD_SNOOZE
import io.github.xxfast.t3code.plugin.ui.ThreadSearch
import io.github.xxfast.t3code.plugin.ui.ThreadSelectionState
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.plugin.ui.threadSearch
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.generated.SearchThreadsInput
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.T3Client
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    val renamer: SidebarRename = SidebarRename.getInstance(project)
    val picks: SidebarMultiSelection = SidebarMultiSelection.getInstance(project)
    val navigation: SidebarNavigation = SidebarNavigation.getInstance(project)
    navigation.watch(toolWindow)
    // Out here so the Escape action below can clear it too
    val search = TextFieldState()
    val searchFocused = AtomicBoolean(false)
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
          unpin = action(THREAD_PINNING, PIN_ACTION),
          // Upstream drags only where the server stores pin orders, `partitionSidebarThreads`
          drop = if (can(THREAD_PINNING) && can(THREAD_PIN_REORDER)) {
            { threadId, plan -> dropThread(project, threadId, plan) }
          } else {
            null
          },
          reorderActive = catalog.supports(THREAD_ACTIVE_REORDER),
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
      val picked: ThreadSelectionState by picks.selection.collectAsState()
      // `useThreadSearch`: only a connected server is asked, upstream's `environmentIds`
      val messages: ThreadSearch by remember {
        val queries: Flow<String> =
          combine(snapshotFlow { search.text.toString() }, client.connection) { query, state ->
            if (state is ConnectionState.Connected) query else ""
          }
        threadSearch(queries) { query -> client.searchThreads(SearchThreadsInput(query)).matches }
      }.collectAsState(ThreadSearch())
      // Read as the hints come up, so a key just bound in the keymap shows on the next hold
      val hints: Boolean by navigation.hints.collectAsState()
      val jumpLabels: List<String?> = remember(hints) {
        if (!hints) return@remember emptyList()
        (1..THREAD_JUMP_COUNT).map { n ->
          KeymapUtil.getFirstKeyboardShortcutText("$JUMP_TO_THREAD_ACTION$n").ifEmpty { null }
        }
      }
      Sidebar(
        projects = projects,
        threads = threads,
        selectedThreadId = selected,
        onOpenThread = opener::openThread,
        favicons = favicons,
        providers = providers,
        header = header,
        // A picked row opens the bulk menu, `handleThreadContextMenu`
        onContextMenu = { threadId, x, y ->
          val now: ThreadSelectionState = picks.selection.value
          if (now.hasSelection && threadId in now.selectedThreadKeys) {
            showThreadBulkMenu(project, now.selectedThreadKeys.toList(), x, y)
          } else {
            showThreadMenu(project, threadId, x, y)
          }
        },
        filter = filter,
        open = open,
        // A selection made under one scope can hold rows the next one hides, upstream's
        // `setProjectScopeKey`
        onFilterChange = { choice ->
          picks.clear()
          awareness.choose(choice)
        },
        onNewThread = if (operable) NewThread.getInstance(project)::open else null,
        onOpenSettings = { T3.openSettings(project) },
        settlement = settlement,
        snooze = snooze,
        actions = actions,
        shelves = shelves,
        onShelvesChange = SidebarShelves.get()::change,
        renaming = renaming,
        details = details,
        selection = picked,
        onSelectionChange = picks::change,
        search = search,
        onSearchFocus = searchFocused::set,
        messages = messages,
        onOrderedThreadIds = navigation::order,
        jumpLabels = jumpLabels,
      )
    }
    // Escape drops the multi-selection, as upstream's `_chat.tsx` keydown does. An action on the
    // panel rather than a Compose key handler, as the IDE's keymap sees Escape first. Off while
    // renaming, so the rename field's Escape still cancels it. In the search field with a query
    // it clears the search instead, as `handleThreadSearchKeyDown` stops it going further.
    object : DumbAwareAction() {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      private fun clearsSearch(): Boolean = searchFocused.get() && search.text.isNotBlank()

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = clearsSearch() ||
          picks.selection.value.hasSelection && renamer.renaming.value == null
      }

      override fun actionPerformed(e: AnActionEvent) {
        if (clearsSearch()) search.clearText() else picks.clear()
      }
    }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, panel, toolWindow.disposable)
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}
