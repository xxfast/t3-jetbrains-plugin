package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Upstream's `busyAction`: the panel-wide action under way. */
enum class LineageBusyAction { Merge, Detach }

/** What the Lineage can do, each wired by the host. */
class ThreadLineageActions(
  val onOpenThread: (threadId: String) -> Unit = {},
  val onMerge: () -> Unit = {},
  val onDetach: () -> Unit = {},
  val onStop: (ThreadLineageRow) -> Unit = {},
)

/**
 * "T3 Thread Details": for now only the Lineage, upstream's `ThreadRelationshipsPanel`
 * (apps/web/src/components/chat/ThreadRelationshipsControl.tsx). Upstream hides the section when
 * there's nothing related; a tool window says so instead. [enabled] is false without the operate
 * scope, which leaves opening rows but no actions. [stoppingId] is the row being stopped.
 */
@Composable
fun ThreadDetails(
  threadId: String,
  lineage: ThreadLineage?,
  busyAction: LineageBusyAction?,
  stoppingId: String?,
  enabled: Boolean,
  actions: ThreadLineageActions,
) {
  if (lineage == null) return Hint("No related threads")
  val now: Long = rememberNow(1_000).value.toEpochMilli()
  VerticallyScrollableContainer(
    scrollState = rememberScrollState(),
    modifier = Modifier.fillMaxSize(),
  ) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
      LineageHeader(lineage, busyAction, enabled, actions.onDetach)
      lineage.groups.forEach { group ->
        // Upstream keys each group by thread, so paging and folding start over per thread
        key(threadId, group.id) {
          LineageGroup(group) { row ->
            LineageRow(row, lineage, now, busyAction, stoppingId, enabled, actions)
          }
        }
      }
    }
  }
}

/** `ThreadDetailsSection`'s heading: the title, and the "…" menu while a session can detach. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun LineageHeader(
  lineage: ThreadLineage,
  busyAction: LineageBusyAction?,
  enabled: Boolean,
  onDetach: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = lineage.title,
      modifier = Modifier.weight(1f),
      style = small(),
      color = muted(),
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (!lineage.canDetach) return@Row
    var open: Boolean by remember { mutableStateOf(false) }
    Box {
      Tooltip(tooltip = { Text("More thread actions") }) {
        IconButton(
          onClick = { open = !open },
          enabled = enabled && busyAction == null,
          modifier = Modifier.size(24.dp),
        ) {
          Icon(AllIconsKeys.Actions.MoreHorizontal, contentDescription = "More thread actions")
        }
      }
      if (open) {
        PopupMenu(
          onDismissRequest = {
            open = false
            return@PopupMenu true
          },
          horizontalAlignment = Alignment.End,
        ) {
          selectableItem(
            selected = false,
            onClick = {
              open = false
              onDetach()
            },
          ) {
            Text("Disconnect agent session")
          }
        }
      }
    }
  }
}

/**
 * `ThreadLineageGroup`, ThreadRelationshipsControl.tsx:123: an optional folding header with
 * "N failed", then the first rows and "Show N more". Upstream caps the list's height inside its
 * already scrolling panel; the tool window scrolls as a whole instead.
 */
@Composable
private fun LineageGroup(group: ThreadLineageGroup, row: @Composable (ThreadLineageRow) -> Unit) {
  var expanded: Boolean by remember { mutableStateOf(group.expanded) }
  var visibleCount: Int by remember { mutableIntStateOf(THREAD_LINEAGE_INITIAL_COUNT) }
  val (visibleRows: List<ThreadLineageRow>, hiddenCount: Int) =
    resolveThreadLineageWindow(group.rows, visibleCount)
  Column(Modifier.fillMaxWidth()) {
    group.label?.let { label ->
      GroupHeader(label, group.rows.size, group.failedCount, expanded) { expanded = !expanded }
    }
    if (!expanded) return@Column
    visibleRows.forEach { visible -> key(visible.threadId) { row(visible) } }
    if (hiddenCount > 0) {
      ShowMoreRow(minOf(hiddenCount, THREAD_LINEAGE_PAGE_COUNT)) {
        visibleCount += THREAD_LINEAGE_PAGE_COUNT
      }
    }
  }
}

/**
 * `CollapsibleSectionHeader` with `variant="panel"` (ui/collapsible-section-header.tsx): label,
 * the count while closed, a rule, `SectionHeaderStatus`, the chevron.
 */
@Composable
private fun GroupHeader(
  label: String,
  count: Int,
  failedCount: Int,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(32.dp)
      .clip(RoundedCornerShape(6.dp))
      .clickable(onClick = onToggle)
      .padding(start = 8.dp, end = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = if (expanded) label else "$label ($count)",
      style = small(),
      color = muted(),
      fontWeight = FontWeight.Medium,
    )
    Divider(Orientation.Horizontal, Modifier.weight(1f))
    if (failedCount > 0) Text("$failedCount failed", style = small(), color = DESTRUCTIVE)
    // Upstream's ChevronDownIcon, rotated 180 degrees while collapsed
    val rotation: Float by animateFloatAsState(if (expanded) 0f else 180f)
    Icon(
      key = AllIconsKeys.General.ChevronDown,
      contentDescription = if (expanded) "Collapse" else "Expand",
      modifier = Modifier.size(12.dp).rotate(rotation),
    )
  }
}

/** `ThreadLineageRowList`'s "Show N more": a plus and muted text. */
@Composable
private fun ShowMoreRow(count: Int, onClick: () -> Unit) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(32.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (hovered) subtle(0.08f) else Color.Transparent)
      .hoverable(interactions)
      .clickable(interactionSource = interactions, indication = null, onClick = onClick)
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(AllIconsKeys.General.Add, contentDescription = null, Modifier.size(16.dp))
    Text(
      text = "Show $count more",
      color = if (hovered) JewelTheme.globalColors.text.normal else muted().copy(alpha = 0.7f),
      fontWeight = FontWeight.Medium,
    )
  }
}

/**
 * One related thread, ThreadRelationshipsControl.tsx from line 455: the relationship icon with
 * its status dot, the title, then the running time for an agent or the status. A merge target
 * splits off a merge back button; a running subagent trades its trailing text for a stop button
 * on hover. Upstream's rich subagent hover card isn't ported: every row has the plain hint.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun LineageRow(
  row: ThreadLineageRow,
  lineage: ThreadLineage,
  now: Long,
  busyAction: LineageBusyAction?,
  stoppingId: String?,
  enabled: Boolean,
  actions: ThreadLineageActions,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val stopShown: Boolean = row.canStop && (hovered || stoppingId == row.threadId)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(32.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (hovered && !row.missing) subtle(0.08f) else Color.Transparent)
      .hoverable(interactions),
    contentAlignment = Alignment.CenterStart,
  ) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Tooltip(tooltip = { Text(row.hint) }, modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !row.missing) { actions.onOpenThread(row.threadId) }
            .padding(horizontal = 8.dp)
            .alpha(if (row.missing) 0.5f else 1f),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RelationshipIcon(row)
          Text(
            text = row.title,
            modifier = Modifier.weight(1f),
            color = JewelTheme.globalColors.text.normal.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (row.agent == null) {
            Icon(
              key = AllIconsKeys.General.ArrowRight,
              contentDescription = null,
              modifier = Modifier.size(12.dp).alpha(if (hovered) 1f else 0f),
            )
          }
          RowTrailing(row, now, hidden = stopShown)
        }
      }
      if (row.isMergeTarget) MergeBack(row, lineage, busyAction, enabled, actions.onMerge)
    }
    if (stopShown) {
      Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
        StopSubagent(row, stoppingId, enabled) { actions.onStop(row) }
      }
    }
  }
}

/** The running time, else the status label; a merge target shows its status after the split. */
@Composable
private fun RowTrailing(row: ThreadLineageRow, now: Long, hidden: Boolean) {
  val alpha: Float = if (hidden) 0f else 1f
  when {
    row.showsElapsed -> Text(
      text = row.elapsed(now).orEmpty(),
      modifier = Modifier.alpha(alpha),
      style = small(),
      color = muted(),
    )

    !row.isMergeTarget -> Text(
      text = threadRelationshipStatusLabel(row.status),
      modifier = Modifier.alpha(alpha),
      style = small(),
      color = if (row.failed) DESTRUCTIVE else muted(),
    )
  }
}

/** The merge target's split half: a separator, merge back, and the row's status. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun MergeBack(
  row: ThreadLineageRow,
  lineage: ThreadLineage,
  busyAction: LineageBusyAction?,
  enabled: Boolean,
  onMerge: () -> Unit,
) {
  Divider(Orientation.Vertical, Modifier.height(16.dp))
  Tooltip(tooltip = { Text(lineage.mergeHint) }) {
    IconButton(
      onClick = onMerge,
      enabled = enabled && lineage.canMerge && busyAction == null,
      modifier = Modifier.padding(horizontal = 2.dp).size(24.dp),
    ) {
      if (busyAction == LineageBusyAction.Merge) {
        CircularProgressIndicator(Modifier.size(12.dp))
      } else {
        Glyph("git-merge", muted(), 12.dp)
      }
    }
  }
  Text(
    text = threadRelationshipStatusLabel(row.status),
    modifier = Modifier.padding(start = 4.dp, end = 10.dp),
    style = small(),
    color = muted(),
    fontWeight = FontWeight.Medium,
  )
}

/** The hover stop button, "Stop subagent", a spinner while it's stopping. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun StopSubagent(
  row: ThreadLineageRow,
  stoppingId: String?,
  enabled: Boolean,
  onStop: () -> Unit,
) {
  Tooltip(tooltip = { Text("Stop subagent") }) {
    IconButton(
      onClick = onStop,
      enabled = enabled && stoppingId == null,
      modifier = Modifier.size(24.dp),
    ) {
      if (stoppingId == row.threadId) {
        CircularProgressIndicator(Modifier.size(12.dp))
      } else {
        Icon(AllIconsKeys.Actions.Suspend, contentDescription = "Stop subagent ${row.title}")
      }
    }
  }
}

/**
 * `ThreadRelationshipIcon` (ThreadRelationshipIcon.tsx:45): the subagent's provider logo, else
 * the relationship's glyph, with a status dot at the bottom right. Upstream draws the logo from
 * the driver alone; this needs the provider from the catalog, and falls back to the glyph without.
 */
@Composable
private fun RelationshipIcon(row: ThreadLineageRow) {
  val provider: ServerProvider? = row.provider
  Box(Modifier.size(16.dp)) {
    if (row.driver != null && provider != null) {
      ProviderIcon(provider, 16.dp)
    } else {
      val glyph: String = when (row.icon) {
        ThreadLineageIcon.Parent -> "corner-left-up"
        ThreadLineageIcon.Subagent -> "bot"
        ThreadLineageIcon.Fork -> "git-fork"
      }
      Glyph(glyph, muted(), 16.dp)
    }
    val panel: Color = JewelTheme.globalColors.panelBackground
    Box(
      Modifier
        .align(Alignment.BottomEnd)
        .offset(x = 4.dp, y = 4.dp)
        .size(8.dp)
        .clip(CircleShape)
        .background(statusDot(threadRelationshipStatusTone(row.status)))
        .border(2.dp, panel, CircleShape),
    )
  }
}

/** `bg-info`, `bg-destructive`, `bg-success`, `bg-muted-foreground/45`. */
@Composable
private fun statusDot(tone: ThreadRelationshipTone): Color = when (tone) {
  ThreadRelationshipTone.Info -> accent()
  ThreadRelationshipTone.Destructive -> DESTRUCTIVE
  ThreadRelationshipTone.Success -> SUCCESS
  ThreadRelationshipTone.Muted -> muted().copy(alpha = 0.45f)
}

/** Upstream's `--destructive`, as the composer's stop button has it. */
private val DESTRUCTIVE: Color = Color(0xFFEF4444)

/** Upstream's `--success`, as the diff stat has it. */
private val SUCCESS: Color = Color(0xFF10B981)
