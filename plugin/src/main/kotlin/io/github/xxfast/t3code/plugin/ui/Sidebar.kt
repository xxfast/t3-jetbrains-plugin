package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.pinned
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.menuStyle
import org.jetbrains.jewel.ui.typography

/**
 * Upstream's module-scope `inboxReturns` (Sidebar.tsx:323), so shelf placement outlives a closed
 * Sidebar and every window's Sidebar shares it.
 */
private val inboxReturns: InboxReturnTracker = createInboxReturnTracker { delayMillis, task ->
  val scheduled: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
    .schedule(task, delayMillis, TimeUnit.MILLISECONDS)
  return@createInboxReturnTracker { scheduled.cancel(false) }
}

/**
 * T3's sidebar, as apps/web/src/components/Sidebar.tsx lays it out: the thread header (search,
 * then the project scope, add project and new thread icon buttons), the active threads as cards,
 * the settled ones as a paged list, and the utility footer. The brand row above and the update
 * pills in the footer are the desktop app's chrome and have no place in a tool window.
 */
@Composable
fun Sidebar(
  projects: List<Project>,
  threads: List<Thread>,
  selectedThreadId: String?,
  onOpenThread: (String) -> Unit,
  favicons: Map<String, ImageBitmap> = emptyMap(), // by project id, see ProjectIcons
  providers: Map<String, ServerProvider> = emptyMap(), // by instance id, the server's catalog
  header: @Composable () -> Unit = {}, // the connection banner, shown only when not connected
  // A row was right-clicked at x, y on screen
  onContextMenu: (threadId: String, x: Int, y: Int) -> Unit = { _, _, _ -> },
  filter: ProjectFilter = ProjectFilter.All, // which projects' threads to list
  open: Set<String> = emptySet(), // the projects this window has open, first in the picker
  onFilterChange: (ProjectFilter) -> Unit = {},
  onNewThread: (() -> Unit)? = null, // null disables the button, as upstream does with no project
  onAddProject: (() -> Unit)? = null, // null hides the button: the desktop app owns projects
  onOpenSettings: (() -> Unit)? = null, // null hides the footer
  settlement: Boolean = true, // whether the server settles threads; without it there's no shelf
  snooze: Boolean = true, // the same for snoozing
  actions: RowActions = RowActions(), // the rows' hover buttons
  shelves: Shelves = Shelves(),
  onShelvesChange: (Shelves) -> Unit = {},
  renaming: String? = null, // the row being renamed in place
  details: RowDetails = RowDetails(), // terminals, machine and checkout, for the rows' extras
  workingShelf: Boolean = true, // upstream's `sidebarWorkingShelfEnabled`
  boring: BoringSidebar? = null, // upstream's `boringSidebarEnabled`, null while it's off
  folderActions: FolderActions = FolderActions(), // the boring sidebar's folder headers
) {
  val search: TextFieldState = rememberTextFieldState()
  val query: String = search.text.toString()
  // Snoozes wake on the minute; a half-minute tick is close enough to move them back in time
  val minute: Instant by rememberNow(30_000)
  // The tracker's expiry repartitions too, upstream's `inboxReturnsVersion` in the clock key
  val returns: Int by inboxReturns.version.collectAsState()
  val requests: Map<String, PendingSubagentRequest> =
    remember(threads) { pendingSubagentRequestsByThread(threads) }
  val folding: Boolean = boring != null
  val sections: ThreadSections = remember(
    threads, projects, query, filter, settlement, snooze, minute, workingShelf, folding, returns,
  ) {
    // Folders already separate the work, so boring mode has no Working shelf
    val shelf: Boolean = workingShelf && !folding
    val observation: InboxObservation = inboxReturns.observe(if (shelf) threads else null, requests)
    val working: InboxObservation? = if (shelf) observation else null
    sections(
      threads, query, filter, settlement, snooze, minute, working, requests,
      folderGroups = sidebarFolderGroups(projects, filter, folding),
      keepEmptyFolders = keepEmptyFolders(projects, filter, query),
    )
  }
  Column(Modifier.fillMaxSize()) {
    header()
    ThreadHeader(
      search, projects, favicons, filter, open, onFilterChange, onNewThread, onAddProject,
    )
    Box(Modifier.weight(1f)) {
      when {
        sections.inbox.isNotEmpty() || sections.working.isNotEmpty() ||
          sections.snoozed.isNotEmpty() || sections.settled.isNotEmpty() ||
          !sections.folders.isNullOrEmpty() ->
          Sections(
            projects, favicons, providers, sections, selectedThreadId, onOpenThread, onContextMenu,
            actions, shelves, onShelvesChange, renaming, details, boring, folderActions, requests,
          )

        else -> Hint(emptyHint(projects, query, filter))
      }
    }
    if (onOpenSettings != null) UtilityFooter(onOpenSettings)
  }
}

/**
 * Upstream's `SidebarThreadHeader` (sidebar/SidebarThreadHeader.tsx): one row, the search field
 * taking the width, then a cluster of icon buttons. Project scope and add project only exist
 * once there are projects; new thread is always there and disabled without one.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips sit on Compose's TooltipArea
@Composable
private fun ThreadHeader(
  search: TextFieldState,
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  filter: ProjectFilter,
  open: Set<String>,
  onFilterChange: (ProjectFilter) -> Unit,
  onNewThread: (() -> Unit)?,
  onAddProject: (() -> Unit)?,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SearchField(search, Modifier.weight(1f))
    if (projects.isNotEmpty()) {
      ProjectScope(projects, favicons, filter, open, onFilterChange)
      if (onAddProject != null) {
        IconActionButton(
          key = AllIconsKeys.Actions.NewFolder,
          contentDescription = "Add project",
          onClick = onAddProject,
          tooltip = { Text("Add project") },
        )
      }
    }
    IconActionButton(
      key = AllIconsKeys.Actions.Edit,
      contentDescription = "New thread",
      onClick = { onNewThread?.invoke() },
      enabled = onNewThread != null && projects.isNotEmpty(),
      tooltip = { Text("New thread") },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips sit on Compose's TooltipArea
@Composable
private fun SearchField(state: TextFieldState, modifier: Modifier = Modifier) {
  TextField(
    state = state,
    modifier = modifier,
    placeholder = { Text("Search") },
    leadingIcon = {
      Icon(AllIconsKeys.Actions.Search, contentDescription = null, Modifier.padding(end = 6.dp))
    },
    // Upstream's "Clear thread search" button, only while there is a query
    trailingIcon = if (state.text.isEmpty()) null else {
      {
        IconActionButton(
          key = AllIconsKeys.General.CloseSmall,
          contentDescription = "Clear thread search",
          onClick = { state.clearText() },
        )
      }
    },
  )
}

/**
 * Upstream's project scope (Sidebar.tsx): an icon button showing the filter glyph, the scoped
 * project's icon, or the first two overlapped when there are several, that opens the list of
 * projects with "All projects" first. A click picks one project; Shift-click or Shift+Enter
 * toggles one in the selection and keeps the list open (`toggleProjectScopeKey`). Each selected
 * entry's icon gets a check badge in place of a highlighted row. Upstream's search field, project
 * settings button and environment badge aren't here.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun ProjectScope(
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  filter: ProjectFilter,
  open: Set<String>,
  onFilterChange: (ProjectFilter) -> Unit,
) {
  val choices: FilterChoices =
    remember(projects, filter, open) { filterChoices(projects, filter, open) }
  var expanded: Boolean by remember { mutableStateOf(false) }
  // Jewel's menu items close the menu after their click, so the Shift state is caught on the way
  // in and a toggle refuses the dismiss that follows it
  var shift: Boolean by remember { mutableStateOf(false) }
  var toggled: Boolean by remember { mutableStateOf(false) }
  Box {
    Tooltip(tooltip = { Text(choices.tooltip) }) {
      IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
        val scoped: List<Project> = choices.scoped
        when {
          scoped.size > 1 -> Box(Modifier.size(22.dp, 14.dp)) {
            // `scopedProjectGroups.slice(0, 2)` at size-3.5, overlapped by -space-x-1.5
            scoped.take(2).forEachIndexed { index, project ->
              Box(Modifier.offset(x = (index * 8).dp)) {
                ProjectIcon(project, favicons[project.id], size = 14.dp)
              }
            }
          }

          scoped.isNotEmpty() -> ProjectIcon(scoped.single(), favicons[scoped.single().id])
          else -> Icon(AllIconsKeys.General.Filter, contentDescription = FILTER_BY_PROJECT)
        }
      }
    }
    if (expanded) {
      PopupMenu(
        onDismissRequest = {
          if (toggled) {
            toggled = false
            return@PopupMenu false
          }
          expanded = false
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
          .onPreviewKeyEvent { event ->
            shift = event.isShiftPressed
            false
          }
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) {
                shift = awaitPointerEvent(PointerEventPass.Initial).keyboardModifiers
                  .isShiftPressed
              }
            }
          },
      ) {
        choices.entries.forEachIndexed { index, choice ->
          if (index == choices.divider) separator()
          // Upstream drops Base UI's `data-selected`: the badge marks selection, not the row
          selectableItem(
            selected = false,
            onClick = {
              val next: ProjectFilter = if (shift) choice.toggles else choice.picks
              toggled = shift && choice.project != null
              if (!toggled) expanded = false
              if (next != filter) onFilterChange(next)
            },
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              ProjectScopeRowIcon(choice.selected) {
                if (choice.project != null) {
                  ProjectIcon(choice.project, favicons[choice.project.id])
                } else {
                  Icon(AllIconsKeys.Nodes.Folder, contentDescription = null)
                }
              }
              Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

/**
 * Upstream's `ProjectScopeRowIcon` (Sidebar.tsx): a selected entry's icon with a check in an
 * accent dot at its bottom right corner, ringed in the popup's background.
 */
@Composable
private fun ProjectScopeRowIcon(selected: Boolean, content: @Composable () -> Unit) {
  Box {
    content()
    if (selected) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .offset(x = 5.dp, y = 5.dp)
          .size(14.dp)
          .background(JewelTheme.menuStyle.colors.background, CircleShape)
          .padding(2.dp)
          .background(accent(), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          key = AllIconsKeys.Actions.Checked,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(8.dp),
        )
      }
    }
  }
}

/**
 * Upstream's `SidebarUtilityMenu` (sidebar/SidebarChrome.tsx): a row of icon buttons at the
 * bottom. Settings is the one the plugin has; pull requests and usage are later phases.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips sit on Compose's TooltipArea
@Composable
private fun UtilityFooter(onOpenSettings: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconActionButton(
      key = AllIconsKeys.General.Settings,
      contentDescription = "Settings",
      onClick = onOpenSettings,
      tooltip = { Text("Settings") },
    )
  }
}

@Composable
private fun Sections(
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  providers: Map<String, ServerProvider>,
  sections: ThreadSections,
  selectedThreadId: String?,
  onOpenThread: (String) -> Unit,
  onContextMenu: (threadId: String, x: Int, y: Int) -> Unit,
  actions: RowActions,
  shelves: Shelves,
  onShelvesChange: (Shelves) -> Unit,
  renaming: String?,
  details: RowDetails,
  boring: BoringSidebar?,
  folderActions: FolderActions,
  requests: Map<String, PendingSubagentRequest>,
) {
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  // Only the Working and Waiting clocks need seconds; everything else is happy with minutes
  val ticking: Set<ThreadStatus> = setOf(ThreadStatus.Working, ThreadStatus.Waiting)
  val cards: List<Thread> = sections.inbox + sections.working
  val period: Long = if (cards.none { it.sidebarStatus() in ticking }) 30_000 else 1_000
  val now: Instant by rememberNow(period)
  var shown: Int by remember { mutableIntStateOf(FIRST_PAGE) }
  val more: Int = moreCount(sections.settled.size, shown)
  val scroll: ScrollState = rememberScrollState()
  // Upstream's first shelf header has mt-auto: it sits at the bottom until the list overflows,
  // then scrolls with everything else. A min-height column with a filling gap is the same thing.
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewport: Dp = maxHeight
    VerticallyScrollableContainer(scrollState = scroll, modifier = Modifier.fillMaxSize()) {
      val items: List<MotionItem> = buildList {
        // Working rows are cards too, `isCard` at Sidebar.tsx:3196
        fun card(thread: Thread, section: String) = MotionItem("$section:${thread.id}") {
          InboxCard(
            thread = thread,
            project = byId[thread.projectId],
            favicon = favicons[thread.projectId],
            providers = providers,
            now = now,
            selected = thread.id == selectedThreadId,
            onClick = { onOpenThread(thread.id) },
            onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
            actions = actions,
            renaming = thread.id == renaming,
            details = details,
          )
        }
        val folders: List<FolderView>? = sections.folders?.let { folders ->
          projectFolders(folders, boring?.projectExpandedById.orEmpty(), selectedThreadId)
        }
        if (folders == null) {
          sections.inbox.forEach { add(card(it, "inbox")) }
        } else {
          // Pinned stays above the folders; the folders draw the active rows in their place
          val pinned: List<Thread> = sections.inbox.filter { it.pinned }
          pinned.forEach { add(card(it, "inbox")) }
          folders.forEachIndexed { index, folder ->
            add(
              MotionItem("folder-header:${folder.project.id}") {
                SidebarProjectFolderHeader(
                  folder = folder,
                  favicon = favicons[folder.project.id],
                  first = index == 0 && pinned.isEmpty(),
                  actions = folderActions,
                  requests = requests,
                )
              },
            )
            folder.threads.forEach { thread ->
              add(
                MotionItem("folder:${thread.id}") {
                  FolderRow(
                    thread = thread,
                    multiline = boring?.multilineRows == true,
                    request = requests[thread.id],
                    providers = providers,
                    now = now,
                    selected = thread.id == selectedThreadId,
                    onClick = { onOpenThread(thread.id) },
                    onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
                    actions = actions,
                    renaming = thread.id == renaming,
                    details = details,
                  )
                },
              )
            }
          }
        }
        if (sections.working.isNotEmpty() || sections.snoozed.isNotEmpty() ||
          sections.settled.isNotEmpty()
        ) {
          add(MotionItem("shelf-gap", fill = true))
        }
        if (sections.working.isNotEmpty()) {
          add(
            MotionItem("working-header") {
              ShelfHeader(
                label = "Working",
                count = sections.working.size,
                open = shelves.working,
                onToggle = { onShelvesChange(shelves.copy(working = !shelves.working)) },
              )
            },
          )
          // A collapsed shelf still shows the open thread
          val working: List<Thread> = when {
            shelves.working -> sections.working
            else -> routeThreadOnly(sections.working, selectedThreadId)
          }
          working.forEach { add(card(it, "working")) }
        }
        fun slim(thread: Thread, shelf: Shelf) = MotionItem("${shelf.name}:${thread.id}") {
          SlimRow(
            thread = thread,
            shelf = shelf,
            project = byId[thread.projectId],
            favicon = favicons[thread.projectId],
            now = now,
            selected = thread.id == selectedThreadId,
            onClick = { onOpenThread(thread.id) },
            onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
            actions = actions,
            renaming = thread.id == renaming,
            details = details,
          )
        }
        if (sections.snoozed.isNotEmpty()) {
          add(
            MotionItem("snoozed-header") {
              ShelfHeader(
                label = "Snoozed",
                count = sections.snoozed.size,
                open = shelves.snoozed,
                onToggle = { onShelvesChange(shelves.copy(snoozed = !shelves.snoozed)) },
              )
            },
          )
          if (shelves.snoozed) sections.snoozed.forEach { add(slim(it, Shelf.Snoozed)) }
        }
        if (sections.settled.isEmpty()) return@buildList
        add(
          MotionItem("settled-header") {
            ShelfHeader(
              label = "Settled",
              count = sections.settled.size,
              open = shelves.settled,
              onToggle = { onShelvesChange(shelves.copy(settled = !shelves.settled)) },
            )
          },
        )
        if (!shelves.settled) return@buildList
        sections.settled.take(shown).forEach { add(slim(it, Shelf.Settled)) }
        if (more > 0) add(MotionItem("settled-more") { MoreRow(more, onClick = { shown += more }) })
      }
      // The container scrolls its content itself; a second verticalScroll here nests two
      // scrollables and the inner one gets infinite height
      MotionColumn(items, Modifier.fillMaxWidth().heightIn(min = viewport))
    }
  }
}

/**
 * Upstream's card row (`SidebarThreadRow`, variant "card", Sidebar.tsx:2258 onwards): three
 * lines in a 78px surface. Project icon and name with the status at the right, the title, then
 * the branch (worktree glyph first) with the pull request badge and the provider stack at the
 * right. Pins show as a glyph; unpinning is in the context menu. On hover the status gives way
 * to the card's actions (Sidebar.tsx:2385 to 2442), except "Woke", which stays to be dismissed.
 */
@Composable
private fun InboxCard(
  thread: Thread,
  project: Project?,
  favicon: ImageBitmap?,
  providers: Map<String, ServerProvider>,
  now: Instant,
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
  actions: RowActions,
  renaming: Boolean,
  details: RowDetails,
) {
  val status: ThreadStatus = thread.sidebarStatus()
  val woke: Boolean = thread.woke(now)
  val top: TopStatus? = thread.topStatus(woke)
  val recede: Boolean = thread.recedes(selected, woke)
  val weight: FontWeight? = if (recede) null else FontWeight.Medium
  val normal: Color = JewelTheme.globalColors.text.normal
  val info: Color = JewelTheme.globalColors.text.info
  val titleColor: Color = when {
    recede -> info
    thread.unread || woke || status == ThreadStatus.Input -> normal
    status == ThreadStatus.Failed -> normal.copy(alpha = 0.95f)
    else -> normal.copy(alpha = 0.9f)
  }
  Selectable(
    selected = selected,
    onClick = onClick,
    onContextMenu = onContextMenu,
    onDoubleClick = actions.rename?.let { rename -> { rename(thread.id) } },
    rest = subtle(0.05f),
    // A receding working card sits at 70% until hovered
    restAlpha = if (recede && status == ThreadStatus.Working) 0.7f else 1f,
    modifier = Modifier.padding(vertical = 2.dp), // the list item's `py-0.5`
  ) { hovered ->
    Column(Modifier.height(CARD_HEIGHT).padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(
        modifier = Modifier.height(20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (project != null) ProjectIcon(project, favicon)
        Text(
          text = project?.title.orEmpty(),
          style = small(),
          color = info,
          fontWeight = weight,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (thread.pinned) Glyph("pin", subtle(0.65f), 12.dp)
        val settle: ((String) -> Unit)? = actions.settle
        // `showSnoozeButton`, Sidebar.tsx:1896: not while it's asking or about to start a turn
        val snooze: ((String, Int, Int) -> Unit)? = actions.snooze?.takeIf { thread.canSnooze(now) }
        val controls: Boolean = hovered && (settle != null || snooze != null)
        if (top == TopStatus.Woke) {
          val dismiss: ((String, String) -> Unit)? = actions.dismissWoke
          val at: String? = thread.wokeAt(now)
          TopStatusLabel(thread, top, now, onClick = if (dismiss != null && at != null) {
            { dismiss(thread.id, at) }
          } else {
            null
          })
        }
        when {
          controls -> {
            if (snooze != null) {
              HoverAction("clock", null, "Snooze thread") { x, y -> snooze(thread.id, x, y) }
            }
            if (settle != null) {
              HoverAction("check", "Settle", "Settle thread") { _, _ -> settle(thread.id) }
            }
          }

          top == TopStatus.Woke -> Unit
          top != null -> TopStatusLabel(thread, top, now)
          else -> Text(compactTime(thread.inboxAt(), now), style = small(), color = info)
        }
      }
      Spacer(Modifier.height(4.dp))
      RowTitle(thread, renaming, actions, weight, titleColor, Modifier.fillMaxWidth())
      Spacer(Modifier.height(2.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val branch: String? = thread.branch
        if (branch != null) {
          if (!thread.worktreePath.isNullOrBlank()) Glyph("folder-git-2", subtle(0.4f), 12.dp)
          Text(
            text = branch,
            style = small(),
            color = subtle(0.4f),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis, // upstream's `MiddleTruncate`
          )
        } else {
          Spacer(Modifier.weight(1f))
        }
        TerminalButton(thread, details, onClick)
        thread.pullRequestBadge()?.let { PullRequestBadgeView(it) }
        ProviderStack(thread, providers)
      }
    }
  }
}

/** Upstream's `h-[4.875rem]`, padding included. */
private val CARD_HEIGHT: Dp = 78.dp

/**
 * `SidebarProjectFolderHeader` (sidebar/SidebarProjectFolderHeader.tsx): a project folder in
 * the boring sidebar. It reads like a shelf header, the favicon and name, then a status dot and
 * the hidden count only while it's closed, so a closed folder still says when a hidden thread
 * needs you, then the chevron. On hover a pen over the count starts a thread in the project.
 */
@Composable
private fun SidebarProjectFolderHeader(
  folder: FolderView,
  favicon: ImageBitmap?,
  first: Boolean,
  actions: FolderActions,
  requests: Map<String, PendingSubagentRequest>,
) {
  val project: Project = folder.project
  val dot: FolderDot? =
    if (folder.collapsed) resolveSidebarFolderDot(folder.hiddenThreads, requests) else null
  val newThread: ((String) -> Unit)? = actions.newThread
  Selectable(
    selected = false,
    onClick = { actions.toggle(project.id) },
    onContextMenu = { x, y -> actions.contextMenu(project.id, x, y) },
    modifier = Modifier.padding(top = if (first) 0.dp else 8.dp), // `mt-2 first:mt-0`
  ) { hovered ->
    Row(
      modifier = Modifier.height(32.dp).fillMaxWidth().padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ProjectIcon(project, favicon)
      Text(
        text = project.title,
        color = JewelTheme.globalColors.text.normal.copy(alpha = 0.9f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (dot != null) Box(Modifier.size(6.dp).background(folderDotColor(dot), CircleShape))
      Box(contentAlignment = Alignment.CenterEnd) {
        val hidden: Int = folder.hiddenThreads.size
        if (folder.collapsed && hidden > 0) {
          Text(
            text = hidden.toString(),
            style = small(),
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.alpha(if (hovered) 0f else 1f), // `invisible`, keeps its width
          )
        }
        if (hovered && newThread != null) {
          HoverAction("square-pen", null, "New thread in ${project.title}") { _, _ ->
            newThread(project.id)
          }
        }
      }
      // Upstream's ChevronDownIcon, turned to point right while closed
      val rotation: Float by animateFloatAsState(if (folder.collapsed) -90f else 0f, Transition)
      Icon(
        key = AllIconsKeys.General.ChevronDown,
        contentDescription = if (folder.collapsed) "Expand" else "Collapse",
        modifier = Modifier.size(12.dp).rotate(rotation),
      )
    }
  }
}

/** `FOLDER_DOT_CLASS_NAME`, SidebarProjectFolderHeader.tsx:13. */
@Composable
private fun folderDotColor(dot: FolderDot): Color = when (dot) {
  FolderDot.Attention -> JewelTheme.globalColors.text.warning
  FolderDot.Failed -> JewelTheme.globalColors.text.error
  FolderDot.Unread -> success()
  FolderDot.Working -> accent()
}

/**
 * Upstream's folder rows (`SidebarThreadRow`, variants "folder" and "folder-multiline",
 * sidebar/SidebarThreadRow.tsx:1192 onwards): a 36px row with no project icon, its title lined
 * up with the folder's name. Folder rows have no status line, so the status takes the time
 * slot; on hover the slot gives way to Settle, next to "Woke", which stays to be dismissed.
 * Two-line rows add the card's branch, pull request and agent line under the title, and put
 * the agent on the title line when that line would be empty.
 */
@Composable
private fun FolderRow(
  thread: Thread,
  multiline: Boolean,
  request: PendingSubagentRequest?, // a subagent's request flagged on this thread
  providers: Map<String, ServerProvider>,
  now: Instant,
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
  actions: RowActions,
  renaming: Boolean,
  details: RowDetails,
) {
  val status: ThreadStatus = thread.sidebarStatus(request)
  val woke: Boolean = thread.woke(now)
  val top: TopStatus? = thread.topStatus(woke, request)
  val recede: Boolean = thread.recedes(selected, woke, request)
  val normal: Color = JewelTheme.globalColors.text.normal
  val titleColor: Color = when {
    recede -> JewelTheme.globalColors.text.info
    thread.unread || woke || status == ThreadStatus.Input -> normal
    status == ThreadStatus.Failed -> normal.copy(alpha = 0.95f)
    else -> normal.copy(alpha = 0.9f)
  }
  // `metaLineEmpty`: only the provider icon would remain, so the row drops the line
  val terminals: Boolean = details.terminals[thread.id].orEmpty().isNotEmpty()
  val metaLine: Boolean =
    multiline && (thread.branch != null || thread.pullRequestBadge() != null || terminals)
  Selectable(
    selected = selected,
    onClick = onClick,
    onContextMenu = onContextMenu,
    onDoubleClick = actions.rename?.let { rename -> { rename(thread.id) } },
    // A receding working row sits at 70% until hovered
    restAlpha = if (recede && status == ThreadStatus.Working) 0.7f else 1f,
  ) { hovered ->
    Column(
      modifier = Modifier
        .then(if (multiline) Modifier.padding(vertical = 6.dp) else Modifier.height(36.dp))
        .fillMaxWidth()
        .padding(start = 36.dp, end = 10.dp), // `ps-9`, past the folder's chevron and icon
      verticalArrangement = Arrangement.Center,
    ) {
      Row(
        modifier = Modifier.height(if (multiline) 24.dp else 36.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RowTitle(
          thread = thread,
          renaming = renaming,
          actions = actions,
          weight = if (recede) null else FontWeight.Medium,
          color = titleColor,
          modifier = Modifier.weight(1f),
        )
        if (multiline && !metaLine) ProviderStack(thread, providers)
        if (thread.pinned) Glyph("pin", subtle(0.65f), 12.dp)
        if (!multiline) TerminalButton(thread, details, onClick)
        val settle: ((String) -> Unit)? = actions.settle
        if (top == TopStatus.Woke) {
          val dismiss: ((String, String) -> Unit)? = actions.dismissWoke
          val at: String? = thread.wokeAt(now)
          val onDismiss: (() -> Unit)? =
            if (dismiss != null && at != null) { { dismiss(thread.id, at) } } else null
          TopStatusLabel(thread, top, now, onClick = onDismiss, compact = true)
        }
        when {
          hovered && settle != null ->
            HoverAction("check", null, "Settle thread") { _, _ -> settle(thread.id) }

          top == TopStatus.Woke -> Unit
          top != null -> TopStatusLabel(thread, top, now, compact = true)
          else -> Text(compactTime(thread.inboxAt(), now), style = small(), color = subtle(0.55f))
        }
      }
      if (metaLine) {
        Row(
          modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val branch: String? = thread.branch
          if (branch != null) {
            if (!thread.worktreePath.isNullOrBlank()) Glyph("folder-git-2", subtle(0.4f), 12.dp)
            Text(
              text = branch,
              style = small(),
              color = subtle(0.4f),
              modifier = Modifier.weight(1f),
              maxLines = 1,
              overflow = TextOverflow.MiddleEllipsis, // upstream's `MiddleTruncate`
            )
          } else {
            Spacer(Modifier.weight(1f))
          }
          TerminalButton(thread, details, onClick)
          thread.pullRequestBadge()?.let { PullRequestBadgeView(it) }
          ProviderStack(thread, providers)
        }
      }
    }
  }
}

/**
 * The card's status slot (Sidebar.tsx:2345 to 2385): the status glyph, its label and, while
 * working or waiting, the clock, all in the status colour. With [onClick] it's a button, which
 * is how "Woke" gets dismissed.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun TopStatusLabel(
  thread: Thread,
  top: TopStatus,
  now: Instant,
  onClick: (() -> Unit)? = null,
  compact: Boolean = false, // a folder row's: a smaller glyph and no clock
) {
  val (glyph: String, color: Color) = when (top) {
    TopStatus.Working -> "circle-dashed" to accent()
    TopStatus.Waiting -> "circle-dot-dashed" to JewelTheme.globalColors.text.info
    TopStatus.Approval -> "shield-question" to JewelTheme.globalColors.text.warning
    TopStatus.Input -> "message-circle-question" to indigo()
    TopStatus.Limited -> "circle-alert" to JewelTheme.globalColors.text.warning
    TopStatus.Failed -> "circle-alert" to JewelTheme.globalColors.text.error
    TopStatus.Woke -> "alarm-clock" to JewelTheme.globalColors.text.warning
    TopStatus.Done -> "circle-check" to success()
  }
  val since: Instant? = when {
    compact -> null
    top == TopStatus.Working -> thread.workingStartedAt()
    top == TopStatus.Waiting -> thread.waitingSince()
    else -> null
  }
  val label: @Composable () -> Unit = {
    Row(
      modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph(glyph, color, if (compact) 12.dp else 16.dp)
      val name: String = thread.topStatusLabel(top)
      Text(name, style = small(), color = color, fontWeight = FontWeight.Medium)
      if (since != null) {
        Text(workingDuration(Duration.between(since, now)), style = small(), color = color)
      }
    }
  }
  if (onClick == null) {
    label()
  } else {
    Tooltip(tooltip = { Text("Dismiss Woke notification") }) { label() }
  }
}

/**
 * `SidebarProviderStack` (Sidebar.tsx:881): earlier instances small and grey, the current one
 * last, with its initials badge when the catalog has a sibling on the same driver.
 */
@Composable
private fun ProviderStack(thread: Thread, providers: Map<String, ServerProvider>) {
  val stack: List<String> = thread.providerStack()
  val current: ServerProvider = providers[stack.last()] ?: return
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (id in stack.dropLast(1)) {
      val earlier: ServerProvider = providers[id] ?: continue
      ProviderIcon(earlier, size = 12.dp, alpha = 0.35f, muted = true)
    }
    ProviderIcon(
      provider = current,
      size = 14.dp,
      alpha = 0.6f,
      badge = showInstanceBadge(current, providers.values),
    )
  }
}

/**
 * A row's hover button (Sidebar.tsx:2420): a glyph, maybe a word, muted until the pointer's on
 * it. Its own click, so pressing it never opens the thread. [onClick] gets where on screen it was
 * pressed, for a menu to open there.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun HoverAction(
  glyph: String,
  label: String?,
  tooltip: String,
  onClick: (x: Int, y: Int) -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  var pressedAt: IntOffset by remember { mutableStateOf(IntOffset.Zero) }
  val color: Color =
    if (hovered) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info
  Tooltip(tooltip = { Text(tooltip) }) {
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .pointerInput(Unit) {
          awaitEachGesture {
            val press: MouseEvent? = awaitPointerEvent(PointerEventPass.Initial).awtEventOrNull
            if (press != null) pressedAt = IntOffset(press.xOnScreen, press.yOnScreen)
          }
        }
        .hoverable(interactions)
        .clickable(
          interactionSource = interactions,
          indication = null,
          onClick = { onClick(pressedAt.x, pressedAt.y) },
        )
        .padding(horizontal = 6.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph(glyph, color, 14.dp)
      if (label != null) Text(label, style = small(), color = color)
    }
  }
}

/**
 * `SidebarSectionHeader` (sidebar/SidebarListMarkers.tsx) over `CollapsibleSectionHeader`
 * (ui/collapsible-section-header.tsx): label, rule, a muted [count] in tabular figures, chevron.
 * The count shows open or closed.
 */
@Composable
private fun ShelfHeader(label: String, count: Int, open: Boolean, onToggle: () -> Unit) {
  Row(
    modifier = Modifier
      .padding(top = 12.dp) // the thin spacer between the two sections
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .height(32.dp)
      .padding(horizontal = 18.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val color: Color = JewelTheme.globalColors.text.info
    Text(text = label, style = small(), color = color, fontWeight = FontWeight.Medium)
    // The rule stays, invisible: a header with a trailing count drops it so it doesn't read as a
    // leader, and a shelf always has one
    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
    Text(text = "$count", style = small().copy(fontFeatureSettings = "tnum"), color = color)
    // Upstream's ChevronDownIcon, `-rotate-90` while collapsed
    val rotation: Float by animateFloatAsState(if (open) 0f else -90f, Transition)
    Icon(
      key = AllIconsKeys.General.ChevronDown,
      contentDescription = if (open) "Collapse" else "Expand",
      modifier = Modifier.size(12.dp).rotate(rotation),
    )
  }
}

/** The shelves under the inbox, each a list of slim rows. */
private enum class Shelf { Snoozed, Settled }

/**
 * Upstream's slim row (`SidebarThreadRow.tsx:1192` onwards): 36px, icon, title, pin, time.
 * No pull request badge, upstream's one-line row dropped it. Shelved rows read as secondary until
 * hovered or open; the icon is at 40% and grey the same way. A settled row shows when it
 * settled and Un-settle on hover, a snoozed one how long until it wakes and Wake on hover
 * (SidebarThreadRow.tsx:1275 to 1360).
 */
@Composable
private fun SlimRow(
  thread: Thread,
  shelf: Shelf,
  project: Project?,
  favicon: ImageBitmap?,
  now: Instant,
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
  actions: RowActions,
  renaming: Boolean,
  details: RowDetails,
) {
  val rename: (() -> Unit)? = actions.rename?.let { rename -> { rename(thread.id) } }
  Selectable(
    selected = selected,
    onClick = onClick,
    onContextMenu = onContextMenu,
    onDoubleClick = rename,
  ) { hovered ->
    val lit: Boolean = selected || hovered
    val iconAlpha: Float by animateFloatAsState(if (lit) 1f else 0.4f, Transition)
    Row(
      modifier = Modifier.height(36.dp).fillMaxWidth().padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.alpha(iconAlpha)) {
        if (project != null) {
          ProjectIcon(project, favicon, muted = !lit)
        } else {
          Box(Modifier.size(16.dp))
        }
      }
      RowTitle(
        thread = thread,
        renaming = renaming,
        actions = actions,
        weight = if (thread.recedes(selected)) null else FontWeight.Medium,
        color = if (lit) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f),
      )
      if (thread.pinned) Glyph("pin", subtle(0.65f), 12.dp)
      TerminalButton(thread, details, onClick)
      val settle: ((String) -> Unit)? = actions.settle
      val wake: ((String) -> Unit)? = actions.wake
      when {
        shelf == Shelf.Snoozed && hovered && wake != null ->
          HoverAction("alarm-clock-off", null, "Wake thread now") { _, _ -> wake(thread.id) }

        // The clock tells a wake time from an age at a glance (SidebarThreadRow.tsx:1277)
        shelf == Shelf.Snoozed -> Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Upstream's `text-info-foreground`, the accent here as on the Working status
          val info: Color = accent()
          Glyph("alarm-clock", info, 12.dp)
          Text(
            text = snoozeWakeLabel(thread.snoozedUntil.orEmpty(), now),
            style = small().copy(fontFeatureSettings = "tnum"),
            color = info,
          )
        }

        hovered && settle != null ->
          HoverAction("undo-2", null, "Un-settle thread") { _, _ -> settle(thread.id) }

        else -> Text(
          text = compactTime(thread.settledAt(), now),
          style = small(),
          color = JewelTheme.globalColors.text.info,
        )
      }
    }
  }
}

/**
 * A row's title (Sidebar.tsx:1970 to 2016): faded while the server regenerates it, a text field
 * while it's being renamed.
 */
@Composable
private fun RowTitle(
  thread: Thread,
  renaming: Boolean,
  actions: RowActions,
  weight: FontWeight?,
  color: Color,
  modifier: Modifier = Modifier,
) {
  if (renaming) {
    RenameField(thread.title, { typed -> actions.renamed(thread.id, typed) }, modifier)
    return
  }
  Text(
    text = thread.title,
    modifier = modifier.alpha(if (thread.titleRegeneration != null) 0.55f else 1f),
    fontWeight = weight,
    color = color,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Upstream's inline rename input (Sidebar.tsx:1970): starts focused with the title selected.
 * Enter and leaving it commit, Escape cancels, each once.
 */
@Composable
private fun RenameField(title: String, onEnd: (typed: String?) -> Unit, modifier: Modifier) {
  val state: TextFieldState = rememberTextFieldState(title, TextRange(0, title.length))
  val focus: FocusRequester = remember { FocusRequester() }
  var focused: Boolean by remember { mutableStateOf(false) }
  var ended: Boolean by remember { mutableStateOf(false) }
  fun end(typed: String?) {
    if (ended) return
    ended = true
    onEnd(typed)
  }
  LaunchedEffect(Unit) { focus.requestFocus() }
  TextField(
    state = state,
    modifier = modifier
      .focusRequester(focus)
      .onFocusChanged { change ->
        if (focused && !change.isFocused) end(state.text.toString())
        focused = change.isFocused
      }
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.Enter -> end(state.text.toString())
          Key.Escape -> end(null)
          else -> return@onPreviewKeyEvent false
        }
        true
      }
      // What the field doesn't use stops here. The row's clickable takes Space's key-up as a
      // click, opens the thread, and its tab pulls focus out of the field mid-word.
      .onKeyEvent { true },
  )
}

/**
 * "Show N more" (Sidebar.tsx:3464): a slim-height row, chevron glyph, muted text. Plus means
 * "add", so revealing more uses a chevron.
 */
@Composable
private fun MoreRow(count: Int, onClick: () -> Unit) {
  Selectable(selected = false, onClick = onClick) { hovered ->
    Row(
      modifier = Modifier.height(36.dp).fillMaxWidth().padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val color: Color =
        if (hovered) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info
      Glyph("chevron-down", color, 16.dp)
      Text("Show $count more", color = color)
    }
  }
}

/** Upstream's `text-success`, from the theme. */
@Composable
private fun success(): Color =
  JBColor.namedColor("Label.successForeground", JBColor(0x208A3C, 0x5FAD65)).toComposeColor()

/** Upstream's `text-indigo-600 dark:text-indigo-300` on the Input status. */
@Composable
private fun indigo(): Color = if (JewelTheme.isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5)

/**
 * A rounded, full-width clickable surface: accent tint when selected, a light fill on hover. It
 * takes keyboard focus and opens on Enter or Space, as a row's `handleKeyDown` (Sidebar.tsx:1782),
 * with upstream's inset focus ring.
 */
@Composable
private fun Selectable(
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit = { _, _ -> },
  onDoubleClick: (() -> Unit)? = null,
  rest: Color = Color.Transparent,
  restAlpha: Float = 1f, // faded to fully opaque on hover
  modifier: Modifier = Modifier,
  content: @Composable (hovered: Boolean) -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  // Only the row itself, not the rename field inside it: upstream checks the event's target
  var focused: Boolean by remember { mutableStateOf(false) }
  val open: () -> Unit by rememberUpdatedState(onClick)
  val menu: (x: Int, y: Int) -> Unit by rememberUpdatedState(onContextMenu)
  val double: (() -> Unit)? by rememberUpdatedState(onDoubleClick)
  // Upstream's `isTrailingDoubleClick` (Sidebar.tsx:3204): the second click doesn't open again,
  // or the opened tab takes focus from the rename field it starts
  var trailing: Boolean by remember { mutableStateOf(false) }
  val fill: Color = when {
    selected -> accent().copy(alpha = 0.2f)
    hovered -> subtle(0.08f)
    else -> rest
  }
  val alpha: Float by animateFloatAsState(if (hovered) 1f else restAlpha, Transition)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp)
      .alpha(alpha)
      .clip(RoundedCornerShape(6.dp))
      .background(fill)
      .then(
        if (focused) Modifier.border(2.dp, accent(), RoundedCornerShape(6.dp)) else Modifier,
      )
      // Ahead of clickable, so a menu gesture never also opens the thread. The platform says
      // which event opens it: the press on macOS and Linux (Ctrl+click too), the release on
      // Windows. A secondary press is swallowed either way.
      .pointerInput(Unit) {
        awaitEachGesture {
          var menuGesture = false
          do {
            val event: PointerEvent = awaitPointerEvent(PointerEventPass.Initial)
            val mouse: MouseEvent? = event.awtEventOrNull
            val trigger: Boolean = mouse?.isPopupTrigger == true
            val secondary: Boolean =
              event.type == PointerEventType.Press && event.buttons.isSecondaryPressed
            menuGesture = menuGesture || trigger || secondary
            if (menuGesture) event.changes.forEach { change -> change.consume() }
            if (trigger && mouse != null) menu(mouse.xOnScreen, mouse.yOnScreen)
            // The second click of a double-click, upstream's rename gesture
            val released: Boolean = event.type == PointerEventType.Release && !menuGesture
            if (released) trailing = (mouse?.clickCount ?: 1) >= 2
            if (released && mouse?.clickCount == 2) double?.invoke()
          } while (event.changes.any { change -> change.pressed })
        }
      }
      .hoverable(interactions)
      .onFocusChanged { state -> focused = state.isFocused }
      .onKeyEvent { event ->
        val activates: Boolean = event.key == Key.Enter || event.key == Key.Spacebar
        if (!focused || !activates || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        open()
        true
      }
      .clickable(
        interactionSource = interactions,
        indication = null,
        onClick = { if (!trailing) onClick() },
      ),
  ) {
    content(hovered)
  }
}

/**
 * `ThreadTerminalStatusButton` (ThreadStatusIndicators.tsx:728): a pulsing terminal glyph while a
 * terminal of the thread runs a process. Pressing it opens the first one and the thread.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun TerminalButton(thread: Thread, details: RowDetails, onOpenThread: () -> Unit) {
  val running: List<String> = details.terminals[thread.id].orEmpty()
  val first: String = running.firstOrNull() ?: return
  // Upstream's `animate-status-pulse`
  val pulse: Float by rememberInfiniteTransition()
    .animateFloat(1f, 0.4f, infiniteRepeatable(tween(1_000), RepeatMode.Reverse))
  Tooltip(tooltip = { Text(terminalButtonLabel(running.size)) }) {
    Box(
      Modifier
        .clip(RoundedCornerShape(4.dp))
        .clickable {
          details.openTerminal?.invoke(thread.id, first)
          onOpenThread()
        },
    ) {
      Glyph("terminal", teal().copy(alpha = teal().alpha * pulse), 14.dp)
    }
  }
}

/** Upstream's `text-teal-600 dark:text-teal-300/90`. */
@Composable
private fun teal(): Color =
  if (JewelTheme.isDark) Color(0xFF5EEAD4).copy(alpha = 0.9f) else Color(0xFF0D9488)
