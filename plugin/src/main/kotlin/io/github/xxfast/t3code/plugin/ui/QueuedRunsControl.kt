package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What the strip shows for one thread: its queue, which run is being edited and which is busy. */
class QueuedRunsState(
  val workflow: ThreadQueueWorkflowState,
  /** Upstream's `editingRunId`: the queued run the composer is editing. */
  val editingRunId: String? = null,
  /** Upstream's `busyRunId`: a command is on its way. */
  val busy: Boolean = false,
  /** The pairing may operate the thread. */
  val enabled: Boolean = true,
)

/** What the strip's buttons do; the host dispatches. */
class QueuedRunsActions(
  val onEdit: (QueuedThreadRun) -> Unit = {},
  val onCancelEdit: () -> Unit = {},
  val onSteer: (queuedRunId: String, targetRunId: String) -> Unit = { _, _ -> },
  val onRemove: (runId: String) -> Unit = {},
  val onMove: (QueuedRunMove) -> Unit = {},
  val onResume: () -> Unit = {},
)

/**
 * The queued messages above the composer, upstream's `QueuedRunsControl.tsx`: a drawer over the
 * box (`ComposerBanner.Attachment`) with a "Queued" header that folds the list, and one row per
 * queued message with edit, Steer and remove. While a row is being edited it only offers Cancel.
 * Upstream drags a row by its grip to reorder; here the grip is a pair of move buttons, its
 * ArrowUp and ArrowDown. A held queue (an interrupt that held it) shows Resume in the header,
 * where upstream turns the composer's send button into "Resume thread". Nothing when the queue is
 * empty.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun QueuedRunsControl(
  state: QueuedRunsState,
  actions: QueuedRunsActions,
  modifier: Modifier = Modifier,
) {
  val workflow: ThreadQueueWorkflowState = state.workflow
  val queued: List<QueuedThreadRun> = workflow.queuedRuns
  if (queued.isEmpty()) return
  var expanded: Boolean by remember { mutableStateOf(true) }
  val dark: Boolean = JewelTheme.isDark
  val outline: Color =
    if (dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f)
  val shape: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = DRAWER_INSET)
      .clip(shape)
      .background(composerGlassSurface())
      .border(1.dp, outline, shape)
      .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = DRAWER_OVERLAP + 4.dp),
  ) {
    QueuedHeader(
      count = queued.size,
      expanded = expanded,
      held = workflow.isHeld,
      resumeEnabled = state.enabled && !state.busy,
      onToggle = { expanded = !expanded },
      onResume = actions.onResume,
    )
    if (!expanded) return@Column
    Column(
      modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
      queued.forEachIndexed { index, item ->
        QueuedRow(
          item = item,
          index = index,
          state = state,
          actions = actions,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun QueuedHeader(
  count: Int,
  expanded: Boolean,
  held: Boolean,
  resumeEnabled: Boolean,
  onToggle: () -> Unit,
  onResume: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = ROW_MIN_HEIGHT)
      .clip(RoundedCornerShape(6.dp))
      .clickable(onClick = onToggle),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
      Glyph("list-ordered", muted(), 12.dp)
    }
    Text("Queued", style = small(), color = muted(), modifier = Modifier.weight(1f))
    if (held) {
      Tooltip(tooltip = { Text("Resume thread") }) {
        Link("Resume", onClick = onResume, enabled = resumeEnabled, textStyle = small())
      }
    }
    Text(
      text = "$count",
      style = small().copy(fontWeight = FontWeight.Medium),
      color = muted(),
      modifier = Modifier.width(ICON_COLUMN),
    )
    val toggle: String = if (expanded) "Collapse queued messages" else "Expand queued messages"
    Tooltip(tooltip = { Text(toggle) }) {
      Glyph(
        name = "chevron-down",
        tint = muted(),
        size = 14.dp,
        modifier = Modifier.rotate(if (expanded) 0f else 180f),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun QueuedRow(
  item: QueuedThreadRun,
  index: Int,
  state: QueuedRunsState,
  actions: QueuedRunsActions,
) {
  val workflow: ThreadQueueWorkflowState = state.workflow
  val idle: Boolean = state.enabled && !state.busy
  val editing: Boolean = item.run.id == state.editingRunId
  val thumbnails: List<ChatAttachment> = item.attachments.filter { it.type == "image" }
  val preview: String = queuedRunPreviewText(item.text, hasThumbnails = thumbnails.isNotEmpty())
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = ROW_MIN_HEIGHT)
      .clip(RoundedCornerShape(4.dp))
      .background(if (editing) accent().copy(alpha = 0.16f) else Color.Transparent),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // The grip, only when the provider reorders queued messages
    if (workflow.canReorder) {
      val up: QueuedRunMove? = queuedRunMoveUp(workflow.queuedRuns, index)
      val down: QueuedRunMove? = queuedRunMoveDown(workflow.queuedRuns, index)
      Row {
        IconActionButton(
          key = AllIconsKeys.Actions.MoveUp,
          contentDescription = "Move queued message up",
          onClick = { up?.let(actions.onMove) },
          enabled = idle && up != null,
          tooltip = { Text("Move up") },
          modifier = Modifier.size(SMALL_BUTTON),
        )
        IconActionButton(
          key = AllIconsKeys.Actions.MoveDown,
          contentDescription = "Move queued message down",
          onClick = { down?.let(actions.onMove) },
          enabled = idle && down != null,
          tooltip = { Text("Move down") },
          modifier = Modifier.size(SMALL_BUTTON),
        )
      }
    } else {
      Box(Modifier.width(ICON_COLUMN))
    }
    if (thumbnails.isNotEmpty()) QueuedThumbnails(thumbnails)
    Tooltip(tooltip = { Text(preview) }, modifier = Modifier.weight(1f)) {
      Text(
        text = preview,
        style = small(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (editing) {
      Link(
        text = "Cancel",
        onClick = actions.onCancelEdit,
        textStyle = small(),
        modifier = Modifier.padding(horizontal = 6.dp),
      )
      return@Row
    }
    IconActionButton(
      key = AllIconsKeys.Actions.Edit,
      contentDescription = "Edit queued message",
      onClick = { actions.onEdit(item) },
      enabled = idle,
      tooltip = { Text("Edit in the composer") },
      modifier = Modifier.size(SMALL_BUTTON),
    )
    val activeRunId: String? = workflow.activeRun?.id
    val canSteer: Boolean = canSteerQueuedRun(workflow, idle)
    Tooltip(tooltip = { Text(queuedRunSteerTooltip(workflow.activeRun)) }) {
      IconButton(
        onClick = { if (activeRunId != null) actions.onSteer(item.run.id, activeRunId) },
        enabled = canSteer,
        modifier = Modifier.heightIn(min = SMALL_BUTTON),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          val tint: Color = if (canSteer) JewelTheme.globalColors.text.normal else muted()
          Glyph("corner-up-right", tint, 12.dp)
          Text("Steer", style = small(), color = tint)
        }
      }
    }
    IconActionButton(
      key = AllIconsKeys.Actions.Close,
      contentDescription = "Remove queued message",
      onClick = { actions.onRemove(item.run.id) },
      enabled = idle,
      tooltip = { Text("Remove from queue") },
      modifier = Modifier.size(SMALL_BUTTON),
    )
  }
}

/** A row's images, 16dp each, upstream's `size-4` thumbnails. */
@Composable
private fun QueuedThumbnails(images: List<ChatAttachment>) {
  val state: MessageImageState = LocalMessageImages.current
  LaunchedEffect(images.map { it.id }) { state.onShown(images) }
  val shape = RoundedCornerShape(3.dp)
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    images.forEach { image ->
      val bitmap: ImageBitmap? = state.previews[image.id]
      Box(
        modifier = Modifier
          .size(16.dp)
          .clip(shape)
          .background(JewelTheme.globalColors.panelBackground)
          .border(1.dp, JewelTheme.globalColors.borders.normal, shape),
      ) {
        if (bitmap != null) {
          Image(
            bitmap = bitmap,
            contentDescription = image.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val DRAWER_INSET: Dp = 22.dp

/** `--chat-composer-attachment-overlap`, `calc(1rem+1px)`: the part behind the box. */
private val DRAWER_OVERLAP: Dp = 17.dp

/** `--composer-banner-icon-column`, `--spacing(6)`. */
private val ICON_COLUMN: Dp = 24.dp

/** A row's `min-h-5`. */
private val ROW_MIN_HEIGHT: Dp = 20.dp

/** `size="icon-xs"`. */
private val SMALL_BUTTON: Dp = 20.dp

/** The list's `max-h-32`, 8rem. */
private val LIST_MAX_HEIGHT: Dp = 128.dp
