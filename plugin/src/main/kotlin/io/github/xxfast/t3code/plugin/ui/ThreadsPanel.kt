package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.time.Duration
import java.time.Instant
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/** T3's sidebar: search, active threads as cards, then the settled ones as a paged list. */
@Composable
fun ThreadsPanel(
  projects: List<Project>,
  threads: List<Thread>,
  selectedThreadId: String?,
  onOpenThread: (String) -> Unit,
  favicons: Map<String, ImageBitmap> = emptyMap(), // by project id, see ProjectIcons
  header: @Composable () -> Unit = {}, // the connection banner, shown only when not connected
) {
  val search: TextFieldState = rememberTextFieldState()
  val query: String = search.text.toString()
  val sections: ThreadSections = remember(threads, query) { sections(threads, query) }
  Column(Modifier.fillMaxSize()) {
    header()
    SearchField(search)
    Box(Modifier.weight(1f)) {
      when {
        sections.inbox.isNotEmpty() || sections.settled.isNotEmpty() ->
          Sections(projects, favicons, sections, selectedThreadId, onOpenThread)

        projects.isEmpty() -> Hint("No projects yet. Open the T3 Code desktop app to add one.")
        query.isNotBlank() -> Hint("No threads match")
        else -> Hint("No threads")
      }
    }
  }
}

@Composable
private fun SearchField(state: TextFieldState) {
  TextField(
    state = state,
    modifier = Modifier.fillMaxWidth().padding(8.dp),
    placeholder = { Text("Search") },
    leadingIcon = {
      Icon(AllIconsKeys.Actions.Search, contentDescription = null, Modifier.padding(end = 6.dp))
    },
  )
}

@Composable
private fun Sections(
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  sections: ThreadSections,
  selectedThreadId: String?,
  onOpenThread: (String) -> Unit,
) {
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  // Only the "Working" clock needs seconds; everything else is happy with minutes
  val period: Long = if (sections.inbox.none { it.active }) 30_000 else 1_000
  val now: Instant by rememberNow(period)
  var settledOpen: Boolean by remember { mutableStateOf(true) }
  var shown: Int by remember { mutableIntStateOf(FIRST_PAGE) }
  val more: Int = moreCount(sections.settled.size, shown)
  val state: LazyListState = rememberLazyListState()
  VerticallyScrollableContainer(scrollState = state, modifier = Modifier.fillMaxSize()) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
      items(sections.inbox, key = { "inbox:${it.id}" }) { thread ->
        InboxCard(
          thread = thread,
          project = byId[thread.projectId],
          favicon = favicons[thread.projectId],
          now = now,
          selected = thread.id == selectedThreadId,
          onClick = { onOpenThread(thread.id) },
        )
      }
      if (sections.settled.isEmpty()) return@LazyColumn
      item(key = "settled") {
        SettledHeader(open = settledOpen, onToggle = { settledOpen = !settledOpen })
      }
      if (!settledOpen) return@LazyColumn
      items(sections.settled.take(shown), key = { "settled:${it.id}" }) { thread ->
        SettledRow(
          thread = thread,
          project = byId[thread.projectId],
          favicon = favicons[thread.projectId],
          now = now,
          selected = thread.id == selectedThreadId,
          onClick = { onOpenThread(thread.id) },
        )
      }
      if (more > 0) item(key = "more") { MoreRow(more, onClick = { shown += more }) }
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
) {
  val spaced: Modifier = Modifier.padding(vertical = 3.dp)
  Selectable(selected, onClick, rest = subtle(0.05f), modifier = spaced) {
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

          else -> Text(
            text = relativeTime(thread.inboxAt(), now),
            style = JewelTheme.typography.small,
            color = JewelTheme.globalColors.text.info,
          )
        }
      }
      Text(thread.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val chevron = if (open) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight
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
) {
  Selectable(selected, onClick) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Dimmed like upstream's settled rows, which sit at 40% until hovered
      Box(Modifier.alpha(if (selected) 1f else 0.4f)) {
        if (project != null) ProjectIcon(project, favicon) else Box(Modifier.size(16.dp))
      }
      Text(
        text = thread.title,
        modifier = Modifier.weight(1f),
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
  Selectable(selected = false, onClick = onClick) {
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

/** A rounded, full-width clickable surface: accent tint when selected, a light fill on hover. */
@Composable
private fun Selectable(
  selected: Boolean,
  onClick: () -> Unit,
  rest: Color = Color.Transparent,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
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
      .hoverable(interactions)
      .clickable(interactionSource = interactions, indication = null, onClick = onClick),
  ) {
    content()
  }
}
