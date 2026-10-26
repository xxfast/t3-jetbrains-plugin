package io.github.xxfast.t3code.plugin.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Subagent
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.TurnItem
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** What the timeline's subagent rows look up besides their items. */
class SubagentLookup(
  val providers: List<ServerProvider> = emptyList(), // the catalog, for logos and model names
  val threads: List<Thread> = emptyList(), // the shell's, for a child's title and workspace
  val projects: List<Project> = emptyList(),
)

/** One thread's subagent rows: its live `projection.subagents`, and where a click goes. */
class SubagentRowsContext(
  val live: List<Subagent>,
  val parentThread: Thread?,
  val lookup: SubagentLookup,
  val nowMs: Long,
  val onOpenThread: ((threadId: String) -> Unit)?,
) {
  fun provider(instanceId: String): ServerProvider? =
    lookup.providers.find { it.instanceId == instanceId }
}

/**
 * `SubagentTimelineLink`, apps/web/src/components/chat/V2LifecycleRow.tsx:398: the provider's
 * avatar with the status dot, the title over the detail line, the running time, and a chevron
 * when there's a child thread to open. Hover shows `SubagentTimelineTooltip`.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun SubagentTimelineLink(item: TurnItem.TurnItemSubagent, context: SubagentRowsContext) {
  val live: Subagent? = context.live.find { it.id == item.subagentId }
  val state: SubagentTimelineLinkState = subagentTimelineLink(item, live)
  val provider: ServerProvider? = context.provider(state.providerInstanceId)
  val threadId: String? = state.threadId
  val onOpen: (() -> Unit)? = context.onOpenThread
    ?.takeIf { threadId != null }
    ?.let { open -> { open(threadId!!) } }
  val hover: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by hover.collectIsHoveredAsState()
  val tone: Color = if (state.failed) DESTRUCTIVE else muted()
  Tooltip(
    tooltip = { SubagentTimelineTooltip(item, state, live, provider, context) },
    modifier = Modifier.fillMaxWidth(),
  ) {
    DisableSelection {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(if (hovered && onOpen != null) subtle(0.06f) else Color.Transparent)
          .hoverable(hover)
          .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
          .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SubagentAvatar(provider, state.status)
        Column(Modifier.weight(1f)) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = state.title,
              modifier = Modifier.weight(1f, fill = false),
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (state.showsInlineStatus) Text(state.statusLabel, style = small(), color = tone)
          }
          Text(
            text = state.detail ?: state.statusLabel,
            style = small(),
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        state.elapsed(context.nowMs)?.let { Text(it, style = small(), color = muted()) }
        if (threadId != null) {
          Glyph(
            name = "chevron-right",
            tint = if (hovered) JewelTheme.globalColors.text.normal else muted().copy(alpha = 0.6f),
          )
        }
      }
    }
  }
}

/**
 * `SubagentAvatar`, V2LifecycleRow.tsx:261: the provider's logo (the bot without one) in a
 * round chip, the status dot at its bottom right. Thread Details' rows draw the same logo and
 * dot colours.
 */
@Composable
private fun SubagentAvatar(
  provider: ServerProvider?,
  status: String?,
  modifier: Modifier = Modifier,
) {
  val background: Color = editorBackground()
  Box(modifier.size(AVATAR_SIZE)) {
    Box(
      modifier = Modifier
        .size(AVATAR_SIZE)
        .border(2.dp, background, CircleShape) // `ring-2 ring-background`
        .padding(2.dp)
        .clip(CircleShape)
        .background(background)
        .background(subtle(0.08f))
        .border(1.dp, JewelTheme.globalColors.borders.normal.copy(alpha = 0.7f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      if (provider != null) ProviderIcon(provider, 14.dp) else Glyph("bot", muted(), 14.dp)
    }
    if (status != null) {
      Box(
        Modifier
          .align(Alignment.BottomEnd)
          .offset(x = 1.dp, y = 1.dp)
          .size(10.dp)
          .clip(CircleShape)
          .background(background)
          .padding(2.dp)
          .clip(CircleShape)
          .background(statusDotColor(subagentStatusVisual(status).tone)),
      )
    }
  }
}

/** `dotClass` in `STATUS_VISUALS`, with Thread Details' colours. */
@Composable
private fun statusDotColor(tone: SubagentStatusTone): Color = when (tone) {
  SubagentStatusTone.Info -> accent()
  SubagentStatusTone.Idle -> muted().copy(alpha = 0.5f)
  SubagentStatusTone.Success -> SUCCESS
  SubagentStatusTone.Destructive -> DESTRUCTIVE
  SubagentStatusTone.Stopped -> muted().copy(alpha = 0.6f)
}

/**
 * `SubagentTimelineTooltip` and `SubagentTooltipContent` (SubagentTooltipContent.tsx): the title,
 * the model, the status with the running time, where the child works when that's elsewhere, and
 * the latest result or progress. Upstream's reasoning effort, speed and instance badge aren't
 * ported.
 */
@Composable
private fun SubagentTimelineTooltip(
  item: TurnItem.TurnItemSubagent,
  state: SubagentTimelineLinkState,
  live: Subagent?,
  provider: ServerProvider?,
  context: SubagentRowsContext,
) {
  val parent: Thread? = context.parentThread
  val child: Thread? = state.threadId?.let { id -> context.lookup.threads.find { it.id == id } }
  fun projectOf(thread: Thread?): Project? =
    thread?.let { context.lookup.projects.find { project -> project.id == it.projectId } }
  val metadata: SubagentMetadata = resolveSubagentMetadata(
    model = state.model,
    provider = provider,
    parentThread = parent,
    childThread = child,
    parentProject = projectOf(parent),
    childProject = projectOf(child),
  )
  val preview: String? = subagentDetailPreview(state.status, state.result, state.progress)
  val working: Boolean = state.status in setOf("running", "in_progress", "pending", "waiting")
  val failed: Boolean = state.status == "failed" || state.status == "error"
  val statusColor: Color = when {
    working -> accent()
    failed -> DESTRUCTIVE
    state.status == "completed" -> SUCCESS
    else -> muted()
  }
  Column(
    modifier = Modifier.widthIn(max = 320.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = formatSubagentDisplayTitle(child?.title ?: item.title ?: state.title),
      fontWeight = FontWeight.Medium,
    )
    TooltipLine(icon = {
      if (provider != null) {
        ProviderIcon(provider, 12.dp, alpha = 0.6f, muted = true)
      } else {
        Glyph("bot", muted(), 12.dp)
      }
    }) {
      Text(metadata.modelLabel, style = small(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TooltipLine(
        icon = {
          val glyph: String = when {
            failed -> "circle-x"
            state.status == "completed" -> "check"
            else -> "circle-dashed"
          }
          Glyph(glyph, statusColor, 12.dp)
        },
        modifier = Modifier.weight(1f, fill = false),
      ) {
        Text(
          text = state.status.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
          },
          style = small(),
          color = statusColor,
          fontWeight = FontWeight.Medium,
        )
      }
      if (live != null) {
        state.elapsed(context.nowMs)?.let { Text(it, style = small(), color = muted()) }
      }
    }
    metadata.workspace.forEach { row ->
      val glyph: String = if (row.label == "Branch") "git-branch" else "folder"
      TooltipLine(icon = { Glyph(glyph, muted(), 12.dp) }) {
        Text(row.value, style = small(), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (preview != null) {
      TooltipLine(icon = { Glyph("terminal", muted(), 12.dp) }) {
        Text(preview, style = small(), maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun TooltipLine(
  icon: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    icon()
    content()
  }
}

/** `subagent-group:${row.id}`, the group's key in the timeline's expanded entries. */
fun subagentGroupId(rowId: String): String = "subagent-group:$rowId"

/**
 * `V2SubagentGroup`, apps/web/src/components/chat/TimelineV2EventRow.tsx:212: a run of subagents
 * as one line, closed at first. Up to three avatars and "+N", "N subagents" over how they're
 * doing, the group's running time and a chevron; open, one [SubagentTimelineLink] per member in
 * a bordered box. Dimmed while closed with nothing working.
 */
@Composable
fun V2SubagentGroup(
  items: List<TurnItem.TurnItemSubagent>,
  context: SubagentRowsContext,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val members: List<SubagentGroupMember> = subagentGroupMembers(items, context.live)
  val statuses: List<String> = members.map { it.status }
  val summary: SubagentGroupSummary = subagentGroupSummary(statuses)
  val label = "${members.size} ${if (members.size == 1) "subagent" else "subagents"}"
  val timing: SubagentTiming = subagentGroupTiming(members)
  val elapsed: String? =
    deriveSubagentElapsedMs(timing.status, timing.startedAt, timing.completedAt, context.nowMs)
      ?.let { formatElapsedSeconds(it / 1_000) }
  val hover: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by hover.collectIsHoveredAsState()
  val lit: Boolean = expanded || summary.active
  Column(Modifier.fillMaxWidth()) {
    DisableSelection {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .alpha(if (lit || hovered) 1f else 0.55f)
          .hoverable(hover)
          .clickable(onClick = onToggle)
          .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val shown: List<SubagentGroupMember> = members.take(3)
        val overflow: Int = members.size - shown.size
        val slots: Int = shown.size + if (overflow > 0) 1 else 0
        // `-space-x-1.5`: each avatar tucks 6px under the one before
        Box(Modifier.size(width = AVATAR_SIZE + AVATAR_STEP * (slots - 1), height = AVATAR_SIZE)) {
          shown.forEachIndexed { index, member ->
            SubagentAvatar(
              provider = context.provider(member.item.providerInstanceId),
              status = member.status.takeIf { members.size == 1 },
              modifier = Modifier.offset(x = AVATAR_STEP * index),
            )
          }
          if (overflow > 0) {
            Box(
              modifier = Modifier
                .offset(x = AVATAR_STEP * shown.size)
                .size(AVATAR_SIZE)
                .border(2.dp, editorBackground(), CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(editorBackground())
                .background(subtle(0.08f)),
              contentAlignment = Alignment.Center,
            ) {
              Text("+$overflow", style = small(), color = muted(), fontWeight = FontWeight.Medium)
            }
          }
        }
        Column(Modifier.weight(1f)) {
          Text(
            text = label,
            color = if (lit) JewelTheme.globalColors.text.normal else muted(),
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = summarizeSubagentStatuses(statuses),
            style = small(),
            color = when {
              summary.active -> accent()
              summary.failed -> DESTRUCTIVE
              else -> muted()
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        elapsed?.let { Text(it, style = small(), color = muted()) }
        Glyph("chevron-down", muted(), modifier = Modifier.rotate(if (expanded) 180f else 0f))
      }
    }
    if (expanded) {
      Column(
        Modifier
          .padding(vertical = 4.dp)
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .border(
            1.dp,
            JewelTheme.globalColors.borders.normal.copy(alpha = 0.6f),
            RoundedCornerShape(8.dp),
          )
          .background(subtle(0.03f))
          .padding(4.dp),
      ) {
        items.forEach { item -> SubagentTimelineLink(item, context) }
      }
    }
  }
}

/** `size-6`. */
private val AVATAR_SIZE: Dp = 24.dp

/** `size-6` less `-space-x-1.5`. */
private val AVATAR_STEP: Dp = 18.dp

/** Upstream's `--destructive`, as Thread Details has it. */
private val DESTRUCTIVE: Color = Color(0xFFEF4444)

/** Upstream's `--success`, as Thread Details has it. */
private val SUCCESS: Color = Color(0xFF10B981)
