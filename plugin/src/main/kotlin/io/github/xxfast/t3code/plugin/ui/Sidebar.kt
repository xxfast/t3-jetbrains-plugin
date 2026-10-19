package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.intellij.ui.JBColor
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.pinned
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

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
  onFilterChange: (ProjectFilter) -> Unit = {},
  onNewThread: (() -> Unit)? = null, // null disables the button, as upstream does with no project
  onAddProject: (() -> Unit)? = null, // null hides the button: the desktop app owns projects
  onOpenSettings: (() -> Unit)? = null, // null hides the footer
  settlement: Boolean = true, // whether the server settles threads; without it there's no shelf
  snooze: Boolean = true, // the same for snoozing
  actions: RowActions = RowActions(), // the rows' hover buttons
  shelves: Shelves = Shelves(),
  onShelvesChange: (Shelves) -> Unit = {},
) {
  val search: TextFieldState = rememberTextFieldState()
  val query: String = search.text.toString()
  // Snoozes wake on the minute; a half-minute tick is close enough to move them back in time
  val minute: Instant by rememberNow(30_000)
  val sections: ThreadSections = remember(threads, query, filter, settlement, snooze, minute) {
    sections(threads, query, filter, settlement, snooze, minute)
  }
  Column(Modifier.fillMaxSize()) {
    header()
    ThreadHeader(search, projects, favicons, filter, onFilterChange, onNewThread, onAddProject)
    Box(Modifier.weight(1f)) {
      when {
        sections.inbox.isNotEmpty() || sections.snoozed.isNotEmpty() ||
          sections.settled.isNotEmpty() ->
          Sections(
            projects, favicons, providers, sections, selectedThreadId, onOpenThread, onContextMenu,
            actions, shelves, onShelvesChange,
          )

        else -> Hint(emptyHint(projects.isEmpty(), query, filter))
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
      ProjectScope(projects, favicons, filter, onFilterChange)
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
 * Upstream's project scope: an icon button showing the filter glyph, or the scoped project's
 * icon, that opens the list of projects with "All projects" first. The filter itself is ours
 * (an IDE window defaults to the projects it has open), so a match of several projects is one
 * more entry, "N projects", and the glyph with their names in the tooltip.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun ProjectScope(
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  filter: ProjectFilter,
  onFilterChange: (ProjectFilter) -> Unit,
) {
  val choices: FilterChoices = remember(projects, filter) { filterChoices(projects, filter) }
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  val scoped: Project? = filter.singleProject(byId)
  val tooltip: String = when {
    scoped != null -> "Filter threads by project: ${scoped.title}"
    choices.names.isNotEmpty() -> choices.names.joinToString("\n")
    else -> "Filter threads by project"
  }
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    Tooltip(tooltip = { Text(tooltip) }) {
      IconButton(onClick = { open = !open }, modifier = Modifier.size(24.dp)) {
        if (scoped != null) {
          ProjectIcon(scoped, favicons[scoped.id])
        } else {
          Icon(AllIconsKeys.General.Filter, contentDescription = "Filter threads by project")
        }
      }
    }
    if (open) {
      PopupMenu(
        onDismissRequest = {
          open = false
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.Start,
      ) {
        choices.filters.forEachIndexed { index, choice ->
          val project: Project? = choice.singleProject(byId)
          selectableItem(
            selected = index == choices.selected,
            onClick = {
              open = false
              if (choice != filter) onFilterChange(choice)
            },
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (project != null) {
                ProjectIcon(project, favicons[project.id])
              } else {
                Icon(AllIconsKeys.Nodes.Folder, contentDescription = null)
              }
              Text(choices.labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

/** The one project a filter scopes to, if it's exactly one. */
private fun ProjectFilter.singleProject(byId: Map<String, Project>): Project? =
  (this as? ProjectFilter.Only)?.ids?.singleOrNull()?.let(byId::get)

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
) {
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  // Only the Working and Waiting clocks need seconds; everything else is happy with minutes
  val ticking: Set<ThreadStatus> = setOf(ThreadStatus.Working, ThreadStatus.Waiting)
  val period: Long = if (sections.inbox.none { it.sidebarStatus() in ticking }) 30_000 else 1_000
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
        for (thread in sections.inbox) {
          add(
            MotionItem("inbox:${thread.id}") {
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
              )
            },
          )
        }
        if (sections.snoozed.isNotEmpty() || sections.settled.isNotEmpty()) {
          add(MotionItem("shelf-gap", fill = true))
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
          )
        }
        if (sections.snoozed.isNotEmpty()) {
          add(
            MotionItem("snoozed-header") {
              ShelfHeader(
                label = shelfLabel("Snoozed", shelves.snoozed, sections.snoozed.size),
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
              label = shelfLabel("Settled", shelves.settled, sections.settled.size),
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
      Text(
        text = thread.title,
        fontWeight = weight,
        color = titleColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
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
        thread.pullRequestBadge()?.let { PullRequestBadgeView(it) }
        ProviderStack(thread, providers)
      }
    }
  }
}

/** Upstream's `h-[4.875rem]`, padding included. */
private val CARD_HEIGHT: Dp = 78.dp

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
  val since: Instant? = when (top) {
    TopStatus.Working -> thread.workingStartedAt()
    TopStatus.Waiting -> thread.waitingSince()
    else -> null
  }
  val label: @Composable () -> Unit = {
    Row(
      modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph(glyph, color, 16.dp)
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
 * `CollapsibleSectionHeader` (ui/collapsible-section-header.tsx): label, rule, chevron. A shelf
 * that's closed says how many rows it holds.
 */
@Composable
private fun ShelfHeader(label: String, open: Boolean, onToggle: () -> Unit) {
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
    Text(
      text = label,
      style = small(),
      color = JewelTheme.globalColors.text.info,
      fontWeight = FontWeight.Medium,
    )
    Divider(Orientation.Horizontal, Modifier.weight(1f))
    // Upstream's ChevronDownIcon, rotated 180 degrees while collapsed
    val rotation: Float by animateFloatAsState(if (open) 0f else 180f, Transition)
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
 * Upstream's slim row (variant "slim", Sidebar.tsx:2100 onwards): 36px, icon, title, pin,
 * badge, time. Shelved rows read as secondary until hovered or open; the icon is at 40% and
 * grey the same way. A settled row shows when it settled and Un-settle on hover, a snoozed one
 * how long until it wakes and Wake on hover (Sidebar.tsx:2151 to 2245).
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
) {
  Selectable(selected, onClick, onContextMenu) { hovered ->
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
      Text(
        text = thread.title,
        modifier = Modifier.weight(1f),
        fontWeight = if (thread.recedes(selected)) null else FontWeight.Medium,
        color = if (lit) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (thread.pinned) Glyph("pin", subtle(0.65f), 12.dp)
      thread.pullRequestBadge()?.let { PullRequestBadgeView(it) }
      val settle: ((String) -> Unit)? = actions.settle
      val wake: ((String) -> Unit)? = actions.wake
      when {
        shelf == Shelf.Snoozed && hovered && wake != null ->
          HoverAction("alarm-clock-off", null, "Wake thread now") { _, _ -> wake(thread.id) }

        shelf == Shelf.Snoozed -> Text(
          text = snoozeWakeLabel(thread.snoozedUntil.orEmpty(), now),
          style = small(),
          color = accent(),
        )

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

/** "Show N more" (Sidebar.tsx:5044): a slim-height row, plus glyph, muted text. */
@Composable
private fun MoreRow(count: Int, onClick: () -> Unit) {
  Selectable(selected = false, onClick = onClick) { hovered ->
    Row(
      modifier = Modifier.height(36.dp).fillMaxWidth().padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val color: Color =
        if (hovered) JewelTheme.globalColors.text.normal
        else JewelTheme.globalColors.text.info.copy(alpha = 0.55f)
      Icon(AllIconsKeys.General.Add, contentDescription = null, Modifier.size(16.dp))
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

/** A rounded, full-width clickable surface: accent tint when selected, a light fill on hover. */
@Composable
private fun Selectable(
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit = { _, _ -> },
  rest: Color = Color.Transparent,
  restAlpha: Float = 1f, // faded to fully opaque on hover
  modifier: Modifier = Modifier,
  content: @Composable (hovered: Boolean) -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val menu: (x: Int, y: Int) -> Unit by rememberUpdatedState(onContextMenu)
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
          } while (event.changes.any { change -> change.pressed })
        }
      }
      .hoverable(interactions)
      .clickable(interactionSource = interactions, indication = null, onClick = onClick),
  ) {
    content(hovered)
  }
}
