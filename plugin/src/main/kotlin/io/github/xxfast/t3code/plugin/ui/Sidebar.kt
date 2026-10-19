package io.github.xxfast.t3code.plugin.ui

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
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import com.intellij.ui.JBColor
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
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
  header: @Composable () -> Unit = {}, // the connection banner, shown only when not connected
  // A row was right-clicked at x, y on screen
  onContextMenu: (threadId: String, x: Int, y: Int) -> Unit = { _, _, _ -> },
  filter: ProjectFilter = ProjectFilter.All, // which projects' threads to list
  onFilterChange: (ProjectFilter) -> Unit = {},
  onNewThread: (() -> Unit)? = null, // null disables the button, as upstream does with no project
  onAddProject: (() -> Unit)? = null, // null hides the button: the desktop app owns projects
  onOpenSettings: (() -> Unit)? = null, // null hides the footer
) {
  val search: TextFieldState = rememberTextFieldState()
  val query: String = search.text.toString()
  val sections: ThreadSections =
    remember(threads, query, filter) { sections(threads, query, filter) }
  Column(Modifier.fillMaxSize()) {
    header()
    ThreadHeader(search, projects, favicons, filter, onFilterChange, onNewThread, onAddProject)
    Box(Modifier.weight(1f)) {
      when {
        sections.inbox.isNotEmpty() || sections.settled.isNotEmpty() ->
          Sections(projects, favicons, sections, selectedThreadId, onOpenThread, onContextMenu)

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
  sections: ThreadSections,
  selectedThreadId: String?,
  onOpenThread: (String) -> Unit,
  onContextMenu: (threadId: String, x: Int, y: Int) -> Unit,
) {
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  // Only the "Working" clock needs seconds; everything else is happy with minutes
  val period: Long = if (sections.inbox.none { it.active }) 30_000 else 1_000
  val now: Instant by rememberNow(period)
  var settledOpen: Boolean by remember { mutableStateOf(true) }
  var shown: Int by remember { mutableIntStateOf(FIRST_PAGE) }
  val more: Int = moreCount(sections.settled.size, shown)
  val scroll: ScrollState = rememberScrollState()
  // Upstream's settled header has mt-auto: it sits at the bottom until the list overflows, then
  // scrolls with everything else. A min-height column with a weighted spacer is the same thing.
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewport: Dp = maxHeight
    VerticallyScrollableContainer(scrollState = scroll, modifier = Modifier.fillMaxSize()) {
      // The container scrolls its content itself; a second verticalScroll here nests two
      // scrollables and the inner one gets infinite height
      Column(Modifier.fillMaxWidth().heightIn(min = viewport)) {
        for (thread in sections.inbox) {
          key("inbox:${thread.id}") {
            InboxCard(
              thread = thread,
              project = byId[thread.projectId],
              favicon = favicons[thread.projectId],
              now = now,
              selected = thread.id == selectedThreadId,
              onClick = { onOpenThread(thread.id) },
              onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
            )
          }
        }
        if (sections.settled.isEmpty()) return@Column
        Spacer(Modifier.weight(1f))
        SettledHeader(open = settledOpen, onToggle = { settledOpen = !settledOpen })
        if (!settledOpen) return@Column
        for (thread in sections.settled.take(shown)) {
          key("settled:${thread.id}") {
            SettledRow(
              thread = thread,
              project = byId[thread.projectId],
              favicon = favicons[thread.projectId],
              now = now,
              selected = thread.id == selectedThreadId,
              onClick = { onOpenThread(thread.id) },
              onContextMenu = { x, y -> onContextMenu(thread.id, x, y) },
            )
          }
        }
        if (more > 0) MoreRow(more, onClick = { shown += more })
      }
    }
  }
}

@Composable
private fun InboxCard(
  thread: Thread,
  project: Project?,
  favicon: ImageBitmap?,
  now: Instant,
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
) {
  val spaced: Modifier = Modifier.padding(vertical = 3.dp)
  val status: CardStatus? = thread.cardStatus()
  Selectable(
    selected = selected,
    onClick = onClick,
    onContextMenu = onContextMenu,
    rest = subtle(0.05f),
    modifier = spaced,
  ) { _ ->
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (project != null) ProjectIcon(project, favicon)
        Text(
          text = project?.title.orEmpty(),
          style = JewelTheme.typography.small,
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        when {
          thread.waitingOnYou -> Text(
            text = "Waiting on you",
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.warning,
          )

          thread.active -> {
            CircularProgressIndicator(Modifier.size(12.dp))
            Text(
              text = "Working ${duration(Duration.between(thread.runStartedAt(), now))}",
              style = JewelTheme.typography.small,
              color = accent(),
              maxLines = 1,
            )
          }

          status != null -> CardStatusLabel(status)

          else -> Text(
            text = relativeTime(thread.inboxAt(), now),
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.info,
          )
        }
      }
      Text(
        text = thread.title,
        fontWeight = titleWeight(thread),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      thread.branch?.let { branch ->
        Text(
          text = branch,
          style = JewelTheme.typography.small,
          color = JewelTheme.globalColors.text.info,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SettledHeader(open: Boolean, onToggle: () -> Unit) {
  Row(
    modifier = Modifier
      .padding(top = 12.dp) // the thin spacer between the two sections
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .padding(horizontal = 18.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Settled", style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.info)
    Divider(Orientation.Horizontal, Modifier.weight(1f))
    // Upstream's ChevronDownIcon, rotated 180 degrees while collapsed
    val chevron = if (open) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronUp
    Icon(chevron, contentDescription = if (open) "Collapse" else "Expand")
  }
}

@Composable
private fun SettledRow(
  thread: Thread,
  project: Project?,
  favicon: ImageBitmap?,
  now: Instant,
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit,
) {
  Selectable(selected, onClick, onContextMenu) { hovered ->
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Upstream's slim row: the icon is at 40% and greyscale unless the row is open or hovered
      val muted: Boolean = !(selected || hovered)
      Box(Modifier.alpha(if (muted) 0.4f else 1f)) {
        if (project != null) {
          ProjectIcon(project, favicon, muted = muted)
        } else {
          Box(Modifier.size(16.dp))
        }
      }
      Text(
        text = thread.title,
        modifier = Modifier.weight(1f),
        fontWeight = titleWeight(thread),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      thread.pullRequestBadge()?.let { PullRequestBadgeView(it) }
      Text(
        text = relativeTime(thread.settledAt(), now),
        style = JewelTheme.typography.small,
        color = JewelTheme.globalColors.text.info,
      )
    }
  }
}

@Composable
private fun MoreRow(count: Int, onClick: () -> Unit) {
  Selectable(selected = false, onClick = onClick) { _ ->
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(AllIconsKeys.General.Add, contentDescription = null)
      Text("Show $count more", color = JewelTheme.globalColors.text.info)
    }
  }
}

/**
 * A [CardStatus] where the time would be, as apps/web/src/components/Sidebar.tsx:1705 to 1724
 * draws it: an icon and a label in the status colour. Limited and Failed share upstream's
 * `CircleAlertIcon`, Done is its `CircleCheckIcon`.
 */
@Composable
private fun CardStatusLabel(status: CardStatus) {
  val (icon: IconKey, label: String, color: Color) = when (status) {
    CardStatus.Limited -> Triple(
      AllIconsKeys.General.Warning, "Limited", JewelTheme.globalColors.text.warning,
    )

    CardStatus.Failed -> Triple(
      AllIconsKeys.General.Error, "Failed", JewelTheme.globalColors.text.error,
    )

    CardStatus.Done -> Triple(AllIconsKeys.Status.Success, "Done", success())
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, Modifier.size(12.dp))
    Text(text = label, style = JewelTheme.typography.small, color = color)
  }
}

/** Upstream's `text-success`, from the theme. */
@Composable
private fun success(): Color =
  JBColor.namedColor("Label.successForeground", JBColor(0x208A3C, 0x5FAD65)).toComposeColor()

/**
 * Sidebar.tsx gives a title `font-medium` unless the row recedes, and an idle row recedes until
 * it's unread. Ours only lifts the unread ones.
 */
private fun titleWeight(thread: Thread): FontWeight? =
  if (thread.unread) FontWeight.Medium else null

/** A rounded, full-width clickable surface: accent tint when selected, a light fill on hover. */
@Composable
private fun Selectable(
  selected: Boolean,
  onClick: () -> Unit,
  onContextMenu: (x: Int, y: Int) -> Unit = { _, _ -> },
  rest: Color = Color.Transparent,
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
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp)
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
