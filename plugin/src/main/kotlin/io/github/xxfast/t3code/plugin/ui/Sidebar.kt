package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.pinned
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
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
  selection: ThreadSelectionState = EMPTY_SELECTION, // the rows picked for a bulk action
  onSelectionChange: (ThreadSelectionState) -> Unit = {},
) {
  val search: TextFieldState = rememberTextFieldState()
  val query: String = search.text.toString()
  // Snoozes wake on the minute; a half-minute tick is close enough to move them back in time
  val minute: Instant by rememberNow(30_000)
  val sections: ThreadSections = remember(threads, query, filter, settlement, snooze, minute) {
    sections(threads, query, filter, settlement, snooze, minute)
  }
  // Upstream's `orderedThreadKeys`: every row in the order they're listed, collapsed shelves too.
  // A Shift range runs over it.
  val ordered: List<String> = remember(sections) {
    (sections.inbox + sections.snoozed + sections.settled).map { it.id }
  }
  val current: ThreadSelectionState by rememberUpdatedState(selection)
  // Rows that leave the list leave the selection, as a bulk delete's `removeFromSelection` does
  LaunchedEffect(ordered, selection) {
    val gone: Set<String> = selection.selectedThreadKeys - ordered.toSet()
    if (gone.isNotEmpty()) onSelectionChange(selection.removeFromSelection(gone))
  }
  // `handleThreadClick`, then `navigateToThread`: a plain click drops the selection and anchors
  // the next Shift range on the row it opens
  val onClick: (String, SidebarRowClick) -> Unit = { threadId, click ->
    when (click) {
      SidebarRowClick.Open -> {
        onSelectionChange(current.clearSelection().setAnchor(threadId))
        onOpenThread(threadId)
      }

      SidebarRowClick.Toggle -> onSelectionChange(current.toggleThread(threadId))
      SidebarRowClick.Range -> onSelectionChange(current.rangeSelectTo(threadId, ordered))
    }
  }
  Column(Modifier.fillMaxSize()) {
    header()
    ThreadHeader(
      search, projects, favicons, filter, open, onFilterChange, onNewThread, onAddProject,
    )
    Box(Modifier.weight(1f)) {
      when {
        sections.inbox.isNotEmpty() || sections.snoozed.isNotEmpty() ||
          sections.settled.isNotEmpty() ->
          Sections(
            projects, favicons, providers, threads, sections, selectedThreadId, onClick,
            onContextMenu, actions, shelves, onShelvesChange, renaming, details, settlement,
            selection.selectedThreadKeys,
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
  open: Set<String>,
  onFilterChange: (ProjectFilter) -> Unit,
) {
  val choices: FilterChoices =
    remember(projects, filter, open) { filterChoices(projects, filter, open) }
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  val scoped: Project? = filter.singleProject(byId)
  val tooltip: String = when {
    scoped != null -> "Filter threads by project: ${scoped.title}"
    choices.names.isNotEmpty() -> choices.names.joinToString("\n")
    else -> "Filter threads by project"
  }
  var expanded: Boolean by remember { mutableStateOf(false) }
  Box {
    Tooltip(tooltip = { Text(tooltip) }) {
      IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
        if (scoped != null) {
          ProjectIcon(scoped, favicons[scoped.id])
        } else {
          Icon(AllIconsKeys.General.Filter, contentDescription = "Filter threads by project")
        }
      }
    }
    if (expanded) {
      PopupMenu(
        onDismissRequest = {
          expanded = false
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.Start,
      ) {
        choices.filters.forEachIndexed { index, choice ->
          if (index == choices.divider) separator()
          val project: Project? = choice.singleProject(byId)
          selectableItem(
            selected = index == choices.selected,
            onClick = {
              expanded = false
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
  threads: List<Thread>, // every row the server has, for the order keys hidden rows hold
  sections: ThreadSections,
  selectedThreadId: String?,
  onClick: (threadId: String, click: SidebarRowClick) -> Unit,
  onContextMenu: (threadId: String, x: Int, y: Int) -> Unit,
  actions: RowActions,
  shelves: Shelves,
  onShelvesChange: (Shelves) -> Unit,
  renaming: String?,
  details: RowDetails,
  settlement: Boolean,
  picked: Set<String>, // the multi-selection
) {
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  val rowsById: Map<String, Thread> = remember(threads) { threads.associateBy { it.id } }
  // Only the Working and Waiting clocks need seconds; everything else is happy with minutes
  val ticking: Set<ThreadStatus> = setOf(ThreadStatus.Working, ThreadStatus.Waiting)
  val period: Long = if (sections.inbox.none { it.sidebarStatus() in ticking }) 30_000 else 1_000
  val now: Instant by rememberNow(period)
  var shown: Int by remember { mutableIntStateOf(FIRST_PAGE) }
  val more: Int = moreCount(sections.settled.size, shown)
  val scroll: ScrollState = rememberScrollState()
  val snoozedShown: List<Thread> = if (shelves.snoozed) sections.snoozed else emptyList()
  val settledShown: List<Thread> =
    if (shelves.settled) sections.settled.take(shown) else emptyList()
  val list: List<SidebarListItem> = remember(sections, shelves, shown) {
    sidebarListItems(sections, snoozedShown, settledShown)
  }
  val (pinned: List<Thread>, active: List<Thread>) = sections.inbox.partition { it.pinned }
  val drag: SidebarDrag = remember { SidebarDrag() }
  val dragging: Boolean = drag.key != null
  // A pick doesn't open anything, so the list keeps focus for Escape to clear it
  val focus: FocusRequester = remember { FocusRequester() }
  val click: (String, SidebarRowClick) -> Unit = { threadId, kind ->
    if (kind != SidebarRowClick.Open) focus.requestFocus()
    onClick(threadId, kind)
  }

  // What `handleThreadDragOver` and the collision detection work out as the pointer moves: the
  // nearest item, the drop that makes, and whether it does anything. A drop that doesn't is no
  // target at all, as upstream filters those collisions out.
  fun aim() {
    val key: String = drag.key ?: return
    val row: Thread? = rowsById[key]
    val ids: Set<String> = list.map(::sidebarListItemId).toSet()
    val over: String? = drag.bounds
      .filterKeys { it in ids }
      .minByOrNull { (_, span) -> abs(span.top + span.height / 2 - drag.pointer) }
      ?.key
    val target: SidebarDropTarget? = over?.let { resolveSidebarDropTarget(list, key, it) }
    val plan: SidebarThreadDropPlan = if (row == null || target == null) {
      SidebarThreadDropPlan.None
    } else {
      planSidebarThreadDrop(
        activeKey = key,
        activeSection = drag.from,
        target = target,
        pinnedOrder = pinned.map { it.id },
        pinnedKeysById = threads.associate { it.id to it.pinOrderKey },
        activeOrder = active.map { it.id },
        activeKeysById = threads.associate { it.id to it.activeOrderKey },
        activePinned = row.pinned,
        activeSettled = row.settled,
        supportsSettlement = settlement,
        activeReorderableKeys = if (actions.reorderActive) null else emptySet(),
      )
    }
    val valid: Boolean = plan != SidebarThreadDropPlan.None
    drag.plan = plan
    drag.over = if (valid) over else null
    drag.target = if (valid) target?.section else null
  }

  // Upstream's `sortableDisabled`: no drag without the server's pin orders, or while renaming
  fun rowDrag(thread: Thread, section: SidebarSection): RowDrag? {
    val drop: (String, SidebarThreadDropPlan) -> Unit = actions.drop ?: return null
    if (thread.id == renaming) return null
    return RowDrag(
      start = { y ->
        val top: Float = drag.bounds[thread.id]?.top ?: y
        drag.key = thread.id
        drag.from = section
        drag.grab = y - top
        drag.slot = top
        drag.pointer = y
        aim()
      },
      move = { y ->
        drag.pointer = y
        aim()
      },
      end = { dropped ->
        val plan: SidebarThreadDropPlan = drag.plan
        drag.reset()
        if (dropped) drop(thread.id, plan)
      },
    )
  }

  val line: Color = JewelTheme.globalColors.borders.focused

  // Every list item sits in one: it reports where it is, draws the insertion line when a drop
  // lands by it, and lifts the dragged row off the list to follow the pointer
  @Composable
  fun Slot(id: String, content: @Composable () -> Unit) {
    val lifted: Boolean = drag.key == id
    Box(
      Modifier
        .onGloballyPositioned { coordinates ->
          val top: Float = coordinates.positionInRoot().y
          drag.bounds[id] = Span(top, coordinates.size.height.toFloat())
          if (id == drag.key) drag.slot = top
        }
        .drawWithContent {
          drawContent()
          val key: String = drag.key ?: return@drawWithContent
          if (drag.over != id) return@drawWithContent
          val below: Boolean = dropsBelow(list, key, id) ?: return@drawWithContent
          val inset: Float = 8.dp.toPx() // the rows' own inset
          val thickness: Float = 2.dp.toPx()
          val y: Float = if (below) size.height else 0f
          drawRect(
            color = line,
            topLeft = Offset(inset, y - thickness / 2),
            size = Size(size.width - inset * 2, thickness),
          )
        }
        .then(
          if (lifted) {
            Modifier.graphicsLayer {
              translationY = drag.pointer - drag.grab - drag.slot
              alpha = 0.85f
            }
          } else {
            Modifier
          },
        ),
    ) {
      content()
    }
  }

  SideEffect { drag.bounds.keys.retainAll(list.map(::sidebarListItemId).toSet()) }
  val verb: SidebarDropVerb? =
    if (dragging) resolveSidebarDropVerb(drag.from, drag.target) else null
  fun motionKey(row: SidebarListItem.Row): String = when (row.section) {
    SidebarSection.Pinned, SidebarSection.Active -> "inbox:${row.key}"
    SidebarSection.Snoozed -> "${Shelf.Snoozed.name}:${row.key}"
    SidebarSection.Settled -> "${Shelf.Settled.name}:${row.key}"
  }

  // Upstream's first shelf header has mt-auto: it sits at the bottom until the list overflows,
  // then scrolls with everything else. A min-height column with a filling gap is the same thing.
  BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).focusable()) {
    val viewport: Dp = maxHeight
    VerticallyScrollableContainer(scrollState = scroll, modifier = Modifier.fillMaxSize()) {
      val items: List<MotionItem> = buildList {
        var gap = false
        fun shelfGap() {
          if (gap) return
          gap = true
          add(MotionItem("shelf-gap", fill = true))
        }
        for (item in list) {
          when (item) {
            is SidebarListItem.Row -> {
              val thread: Thread = rowsById[item.key] ?: continue
              val lifted: Boolean = drag.key == thread.id
              val dropVerb: SidebarDropVerb? = if (lifted) verb else null
              val rowDrag: RowDrag? = rowDrag(thread, item.section)
              add(
                MotionItem(motionKey(item)) {
                  Slot(thread.id) {
                    when (item.section) {
                      SidebarSection.Pinned, SidebarSection.Active -> InboxCard(
                        thread = thread,
                        project = byId[thread.projectId],
                        favicon = favicons[thread.projectId],
                        providers = providers,
                        now = now,
                        selected = thread.id == selectedThreadId,
                        picked = thread.id in picked,
                        onClick = { kind -> click(thread.id, kind) },
                        onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
                        actions = actions,
                        renaming = thread.id == renaming,
                        details = details,
                        rowDrag = rowDrag,
                        lifted = lifted,
                        dropVerb = dropVerb,
                      )

                      SidebarSection.Snoozed, SidebarSection.Settled -> SlimRow(
                        thread = thread,
                        shelf = if (item.section == SidebarSection.Snoozed) {
                          Shelf.Snoozed
                        } else {
                          Shelf.Settled
                        },
                        project = byId[thread.projectId],
                        favicon = favicons[thread.projectId],
                        now = now,
                        selected = thread.id == selectedThreadId,
                        picked = thread.id in picked,
                        onClick = { kind -> click(thread.id, kind) },
                        onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
                        actions = actions,
                        renaming = thread.id == renaming,
                        details = details,
                        rowDrag = rowDrag,
                        lifted = lifted,
                        dropVerb = dropVerb,
                      )
                    }
                  }
                },
              )
            }

            is SidebarListItem.Marker -> {
              val id: String = sidebarMarkerId(item.marker)
              when (item.marker) {
                SidebarListMarker.PinnedHeader, SidebarListMarker.PinnedDivider -> {
                  val pins: Boolean = item.marker == SidebarListMarker.PinnedHeader
                  val section: SidebarSection =
                    if (pins) SidebarSection.Pinned else SidebarSection.Active
                  add(
                    MotionItem(item.marker.id) {
                      Slot(id) {
                        DragBoundary(
                          label = if (pins) "Pinned" else "Active",
                          visible = dragging,
                          dropTarget = drag.target == section,
                        )
                      }
                    },
                  )
                }

                SidebarListMarker.ActivePlaceholder, SidebarListMarker.SettledPlaceholder -> {
                  val settledShelf: Boolean = item.marker == SidebarListMarker.SettledPlaceholder
                  val section: SidebarSection =
                    if (settledShelf) SidebarSection.Settled else SidebarSection.Active
                  val rows: List<Thread> = if (settledShelf) settledShown else active
                  // Upstream's `showHint`: an empty section, or one whose only row is leaving
                  val leaving: Boolean = drag.from == section && rows.size == 1 &&
                    drag.target != null && drag.target != section
                  val hint: Boolean = dragging && (!settledShelf || settlement) &&
                    (rows.isEmpty() || leaving)
                  add(
                    MotionItem(item.marker.id) {
                      Slot(id) {
                        SectionPlaceholder(
                          label = if (settledShelf) "Settled" else "Active",
                          visible = hint,
                          dropTarget = drag.target == section,
                        )
                      }
                    },
                  )
                }

                SidebarListMarker.SnoozedHeader -> {
                  shelfGap()
                  add(
                    MotionItem(item.marker.id) {
                      Slot(id) {
                        ShelfHeader(
                          label = shelfLabel("Snoozed", shelves.snoozed, sections.snoozed.size),
                          open = shelves.snoozed,
                          onToggle = { onShelvesChange(shelves.copy(snoozed = !shelves.snoozed)) },
                        )
                      }
                    },
                  )
                }

                // Shown while dragging even with no settled rows: it's where a drag settles
                SidebarListMarker.SettledHeader -> {
                  if (sections.settled.isEmpty() && !(dragging && settlement)) continue
                  shelfGap()
                  add(
                    MotionItem(item.marker.id) {
                      Slot(id) {
                        ShelfHeader(
                          label = shelfLabel("Settled", shelves.settled, sections.settled.size),
                          open = shelves.settled,
                          onToggle = { onShelvesChange(shelves.copy(settled = !shelves.settled)) },
                          dragging = dragging,
                          dropTarget = drag.target == SidebarSection.Settled,
                        )
                      }
                    },
                  )
                }
              }
            }
          }
        }
        if (shelves.settled && more > 0) {
          add(MotionItem("settled-more") { MoreRow(more, onClick = { shown += more }) })
        }
      }
      val lifted: String? = list
        .firstOrNull { it is SidebarListItem.Row && it.key == drag.key }
        ?.let { motionKey(it as SidebarListItem.Row) }
      // The container scrolls its content itself; a second verticalScroll here nests two
      // scrollables and the inner one gets infinite height
      MotionColumn(items, Modifier.fillMaxWidth().heightIn(min = viewport), lifted)
    }
  }
}

/**
 * A row being dragged, upstream's `dragState`, and where every list item sits so the pointer
 * can find the nearest one. Positions are in the root, where the rows report the pointer.
 */
private class SidebarDrag {
  var key: String? by mutableStateOf(null) // the dragged thread
  var from: SidebarSection by mutableStateOf(SidebarSection.Active)
  var pointer: Float by mutableFloatStateOf(0f)
  var grab: Float = 0f // how far below the row's top it was pressed
  var slot: Float by mutableFloatStateOf(0f) // the dragged row's own top in the list
  var over: String? by mutableStateOf(null) // the item a drop lands by
  var target: SidebarSection? by mutableStateOf(null) // the section a drop lands in
  var plan: SidebarThreadDropPlan = SidebarThreadDropPlan.None
  val bounds: MutableMap<String, Span> = mutableMapOf() // by list item id

  fun reset() {
    key = null
    over = null
    target = null
    plan = SidebarThreadDropPlan.None
  }
}

/** What a row reports while it's dragged: the pointer's y in the root. */
private class RowDrag(
  val start: (y: Float) -> Unit,
  val move: (y: Float) -> Unit,
  val end: (dropped: Boolean) -> Unit,
)

/** Where a row was laid out, to turn the pointer into root coordinates. */
private class Anchor {
  var coordinates: LayoutCoordinates? = null
}

/** `SIDEBAR_DRAG_LABEL_HEIGHT`, Sidebar.tsx. */
private val DRAG_LABEL_HEIGHT: Dp = 24.dp

/**
 * `SidebarDragBoundary` (sidebar/SidebarListMarkers.tsx): "Pinned" above the pinned rows and
 * "Active" under them, only while a row is dragged, in the accent over the section it'd land in.
 */
@Composable
private fun DragBoundary(label: String, visible: Boolean, dropTarget: Boolean) {
  val height: Dp by animateDpAsState(if (visible) DRAG_LABEL_HEIGHT else 0.dp, tween(150))
  Box(Modifier.fillMaxWidth().height(height)) {
    if (!visible) return@Box
    val color: Color =
      if (dropTarget) accent() else JewelTheme.globalColors.text.normal.copy(alpha = 0.8f)
    Row(
      modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp).height(16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = small(), color = color, fontWeight = FontWeight.Medium)
      Box(
        Modifier
          .weight(1f)
          .height(1.dp)
          .background(if (dropTarget) accent().copy(alpha = 0.5f) else subtle(0.25f)),
      )
    }
  }
}

/**
 * `SidebarSectionPlaceholder` (sidebar/SidebarListMarkers.tsx): a dashed, slim-row high slot
 * where an empty section takes a drop.
 */
@Composable
private fun SectionPlaceholder(label: String, visible: Boolean, dropTarget: Boolean) {
  if (!visible) return
  val border: Color = if (dropTarget) accent().copy(alpha = 0.4f) else subtle(0.25f)
  val fill: Color = if (dropTarget) accent().copy(alpha = 0.05f) else Color.Transparent
  val text: Color =
    if (dropTarget) accent() else JewelTheme.globalColors.text.normal.copy(alpha = 0.8f)
  Box(
    modifier = Modifier
      .padding(horizontal = 8.dp)
      .fillMaxWidth()
      .height(36.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(fill)
      .drawBehind {
        val stroke = Stroke(
          width = 1.dp.toPx(),
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
        drawRoundRect(color = border, style = stroke, cornerRadius = CornerRadius(6.dp.toPx()))
      },
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = small(), color = text)
  }
}

/** A dragged row's `dropVerbBadge` (sidebar/SidebarThreadRow.tsx): what dropping it here does. */
@Composable
private fun DropVerbBadge(verb: SidebarDropVerb) {
  val color: Color = accent()
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(color.copy(alpha = 0.1f))
      .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
      .height(20.dp)
      .padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph(verb.glyph, color, 12.dp)
    Text(verb.label, style = small(), color = color, fontWeight = FontWeight.Medium)
  }
}

/**
 * The pin on a pinned row, SidebarThreadRow.tsx's `pinIndicator`: with [unpin] a button that
 * turns to pin-off on hover and unpins, else just the glyph.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun PinGlyph(threadId: String, unpin: ((String) -> Unit)?) {
  if (unpin == null) {
    Glyph("pin", subtle(0.65f), 12.dp)
    return
  }
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  Tooltip(tooltip = { Text("Unpin thread") }) {
    Box(
      Modifier
        .clip(RoundedCornerShape(4.dp))
        .hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null) { unpin(threadId) },
    ) {
      val color: Color = if (hovered) JewelTheme.globalColors.text.normal else subtle(0.65f)
      Glyph(if (hovered) "pin-off" else "pin", color, 12.dp)
    }
  }
}

/**
 * Upstream's card row (`SidebarThreadRow`, variant "card", Sidebar.tsx:2258 onwards): three
 * lines in a 78px surface. Project icon and name with the status at the right, the title, then
 * the branch (worktree glyph first) with the pull request badge and the provider stack at the
 * right. A pin shows as a glyph that unpins. On hover the status gives way to the card's
 * actions (Sidebar.tsx:2385 to 2442), except "Woke", which stays to be dismissed. [lifted] while
 * it's dragged, when [dropVerb] says what dropping it does instead.
 */
@Composable
private fun InboxCard(
  thread: Thread,
  project: Project?,
  favicon: ImageBitmap?,
  providers: Map<String, ServerProvider>,
  now: Instant,
  selected: Boolean,
  picked: Boolean,
  onClick: (SidebarRowClick) -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
  actions: RowActions,
  renaming: Boolean,
  details: RowDetails,
  rowDrag: RowDrag? = null,
  lifted: Boolean = false,
  dropVerb: SidebarDropVerb? = null,
) {
  val status: ThreadStatus = thread.sidebarStatus()
  val woke: Boolean = thread.woke(now)
  val top: TopStatus? = thread.topStatus(woke)
  val recede: Boolean = thread.recedes(selected || picked, woke)
  val open: () -> Unit = { onClick(SidebarRowClick.Open) }
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
    onClick = open,
    onContextMenu = onContextMenu,
    onDoubleClick = actions.rename?.let { rename -> { rename(thread.id) } },
    picked = picked,
    onPick = onClick,
    rest = subtle(0.05f),
    // A receding working card sits at 70% until hovered
    restAlpha = if (recede && status == ThreadStatus.Working) 0.7f else 1f,
    modifier = Modifier.padding(vertical = 2.dp), // the list item's `py-0.5`
    rowDrag = rowDrag,
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
        val unpin: ((String) -> Unit)? = actions.unpin?.takeIf { !lifted }
        if (thread.pinned && dropVerb == null) PinGlyph(thread.id, unpin)
        val settle: ((String) -> Unit)? = actions.settle
        // `showSnoozeButton`, Sidebar.tsx:1896: not while it's asking or about to start a turn
        val snooze: ((String, Int, Int) -> Unit)? = actions.snooze?.takeIf { thread.canSnooze(now) }
        val controls: Boolean = hovered && !lifted && (settle != null || snooze != null)
        if (dropVerb != null) {
          DropVerbBadge(dropVerb)
        } else if (top == TopStatus.Woke) {
          val dismiss: ((String, String) -> Unit)? = actions.dismissWoke
          val at: String? = thread.wokeAt(now)
          TopStatusLabel(thread, top, now, onClick = if (dismiss != null && at != null) {
            { dismiss(thread.id, at) }
          } else {
            null
          })
        }
        when {
          dropVerb != null -> Unit
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
        TerminalButton(thread, details, open)
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
private fun ShelfHeader(
  label: String,
  open: Boolean,
  onToggle: () -> Unit,
  dragging: Boolean = false, // a row is being dragged: the label stands out, as upstream's tone
  dropTarget: Boolean = false, // and it'd land on this shelf: the label takes the accent
) {
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
      color = when {
        dropTarget -> accent()
        dragging -> JewelTheme.globalColors.text.normal
        else -> JewelTheme.globalColors.text.info
      },
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
 * how long until it wakes and Wake on hover (Sidebar.tsx:2151 to 2245). Dragged, it says what
 * dropping it does instead.
 */
@Composable
private fun SlimRow(
  thread: Thread,
  shelf: Shelf,
  project: Project?,
  favicon: ImageBitmap?,
  now: Instant,
  selected: Boolean,
  picked: Boolean,
  onClick: (SidebarRowClick) -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
  actions: RowActions,
  renaming: Boolean,
  details: RowDetails,
  rowDrag: RowDrag? = null,
  lifted: Boolean = false,
  dropVerb: SidebarDropVerb? = null,
) {
  val rename: (() -> Unit)? = actions.rename?.let { rename -> { rename(thread.id) } }
  val open: () -> Unit = { onClick(SidebarRowClick.Open) }
  Selectable(
    selected = selected,
    onClick = open,
    onContextMenu = onContextMenu,
    onDoubleClick = rename,
    picked = picked,
    onPick = onClick,
    rowDrag = rowDrag,
  ) { pointing ->
    val hovered: Boolean = pointing && !lifted
    val lit: Boolean = selected || picked || hovered
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
        weight = if (thread.recedes(selected || picked)) null else FontWeight.Medium,
        color = if (lit) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f),
      )
      val unpin: ((String) -> Unit)? = actions.unpin?.takeIf { !lifted }
      if (thread.pinned && dropVerb == null) PinGlyph(thread.id, unpin)
      TerminalButton(thread, details, open)
      thread.pullRequestBadge()?.let { PullRequestBadgeView(it) }
      val settle: ((String) -> Unit)? = actions.settle
      val wake: ((String) -> Unit)? = actions.wake
      when {
        dropVerb != null -> DropVerbBadge(dropVerb)

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

/**
 * A rounded, full-width clickable surface: accent tint when selected, the IDE's inactive list
 * selection when [picked] for a bulk action, a light fill on hover. It takes keyboard focus and
 * opens on Enter or Space, as a row's `handleKeyDown` (Sidebar.tsx:1782), with upstream's inset
 * focus ring. With [onPick] a Cmd or Ctrl click, or a Shift click, picks instead of opening, as
 * `handleThreadClick`. With [rowDrag] a primary press that moves drags it, as upstream's
 * sortable rows start on movement rather than a long press; a press that doesn't move stays a
 * click.
 */
@Composable
private fun Selectable(
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit = { _, _ -> },
  onDoubleClick: (() -> Unit)? = null,
  picked: Boolean = false,
  onPick: ((SidebarRowClick) -> Unit)? = null,
  rest: Color = Color.Transparent,
  restAlpha: Float = 1f, // faded to fully opaque on hover
  modifier: Modifier = Modifier,
  rowDrag: RowDrag? = null,
  content: @Composable (hovered: Boolean) -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  // Read when the gesture runs, so a recomposition mid-drag doesn't restart it
  val dragging: RowDrag? by rememberUpdatedState(rowDrag)
  val anchor: Anchor = remember { Anchor() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  // Only the row itself, not the rename field inside it: upstream checks the event's target
  var focused: Boolean by remember { mutableStateOf(false) }
  val open: () -> Unit by rememberUpdatedState(onClick)
  val menu: (x: Int, y: Int) -> Unit by rememberUpdatedState(onContextMenu)
  val double: (() -> Unit)? by rememberUpdatedState(onDoubleClick)
  val pick: ((SidebarRowClick) -> Unit)? by rememberUpdatedState(onPick)
  // What the press's modifiers make of the click, read when it lands
  var click: SidebarRowClick by remember { mutableStateOf(SidebarRowClick.Open) }
  // Upstream's `isTrailingDoubleClick` (Sidebar.tsx:3204): the second click doesn't open again,
  // or the opened tab takes focus from the rename field it starts
  var trailing: Boolean by remember { mutableStateOf(false) }
  // Upstream's `bg-sidebar-row-selected`, under the open row's `bg-sidebar-row-active`
  val fill: Color = when {
    selected -> accent().copy(alpha = 0.2f)
    picked -> JBUI.CurrentTheme.List.Selection.background(false).toComposeColor()
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
      .onGloballyPositioned { coordinates -> anchor.coordinates = coordinates }
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
            if (event.type == PointerEventType.Press) {
              val keys: PointerKeyboardModifiers = event.keyboardModifiers
              click = sidebarRowClick(
                isMac = SystemInfo.isMac,
                meta = keys.isMetaPressed,
                ctrl = keys.isCtrlPressed,
                shift = keys.isShiftPressed,
              )
            }
            // The second click of a double-click, upstream's rename gesture
            val released: Boolean = event.type == PointerEventType.Release && !menuGesture
            if (released) trailing = (mouse?.clickCount ?: 1) >= 2
            val plain: Boolean = click == SidebarRowClick.Open
            if (released && plain && mouse?.clickCount == 2) double?.invoke()
          } while (event.changes.any { change -> change.pressed })
        }
      }
      // Past the touch slop the moves are consumed, which cancels the click
      .pointerInput(Unit) {
        awaitEachGesture {
          val down: PointerInputChange = awaitFirstDown(requireUnconsumed = false)
          if (dragging == null || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
          fun rootY(change: PointerInputChange): Float? =
            anchor.coordinates?.takeIf { it.isAttached }?.localToRoot(change.position)?.y
          val pressed: Float = rootY(down) ?: return@awaitEachGesture
          val moved: PointerInputChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
            change.consume()
          } ?: return@awaitEachGesture
          dragging?.start?.invoke(pressed) ?: return@awaitEachGesture
          rootY(moved)?.let { y -> dragging?.move?.invoke(y) }
          var dropped = false
          try {
            dropped = drag(moved.id) { change ->
              change.consume()
              rootY(change)?.let { y -> dragging?.move?.invoke(y) }
            }
          } finally {
            // Ends on a cancelled gesture too, or the row would stay lifted
            dragging?.end?.invoke(dropped)
          }
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
        onClick = {
          val picks: ((SidebarRowClick) -> Unit)? = pick
          when {
            // Upstream picks ahead of its trailing double-click check
            click != SidebarRowClick.Open && picks != null -> picks(click)
            !trailing -> onClick()
          }
        },
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
