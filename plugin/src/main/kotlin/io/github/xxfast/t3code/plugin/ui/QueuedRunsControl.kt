package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import java.time.Instant
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What the queue shows for one thread: its queue, which run is being edited and which is busy. */
class QueuedRunsState(
  val workflow: ThreadQueueWorkflowState,
  /** Upstream's `editingRunId`: the queued run the composer is editing. */
  val editingRunId: String? = null,
  /** Upstream's `busyRunId`: a command is on its way. */
  val busy: Boolean = false,
  /** The pairing may operate the thread. */
  val enabled: Boolean = true,
  /** Thread titles by id, for a folded agent message's sender, `useQuietThreadShell`. */
  val threadTitles: Map<String, String> = emptyMap(),
  /** Upstream's `steerShortcutLabel`: the keymap's shortcut for the first row's Send now. */
  val steerShortcutLabel: String? = null,
)

/** What the queue's buttons do; the host dispatches. */
class QueuedRunsActions(
  val onEdit: (QueuedThreadRun) -> Unit = {},
  val onCancelEdit: () -> Unit = {},
  val onSendNow: (queuedRunId: String) -> Unit = {},
  val onRemove: (runId: String) -> Unit = {},
  val onResume: () -> Unit = {},
  /** An agent sender's "Open thread", upstream's `onOpenThread`. */
  val onOpenThread: ((threadId: String) -> Unit)? = null,
)

/**
 * The queued messages at the end of the timeline, upstream's `QueuedRunsControl.tsx`: the last
 * part of the timeline footer, one dashed bubble per queued message with Send now, edit and
 * remove under it. While a row is being edited it only offers Cancel. A queue longer than
 * [QUEUE_VISIBLE_ROWS] folds behind "Show all N queued". A held queue (an interrupt that held
 * it) shows Resume on top, where upstream turns the composer's send button into "Resume thread".
 * Nothing when the queue is empty.
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
  var showAll: Boolean by remember { mutableStateOf(false) }
  val now: Instant by rememberNow(60_000L)
  // `editLatest`'s `setShowAll(true)`: the row being edited is never behind the fold
  LaunchedEffect(state.editingRunId) {
    val editing: String = state.editingRunId ?: return@LaunchedEffect
    if (visibleQueuedRuns(queued, showAll = false).none { it.run.id == editing }) showAll = true
  }
  // `flex flex-col gap-2 pb-4`
  Column(
    modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
    horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (workflow.isHeld) {
      Tooltip(tooltip = { Text("Resume thread") }) {
        Link(
          text = "Resume",
          onClick = actions.onResume,
          enabled = state.enabled && !state.busy,
          textStyle = small(),
          modifier = Modifier.padding(end = 4.dp),
        )
      }
    }
    // The `ol`'s `gap-3`
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      visibleQueuedRuns(queued, showAll).forEachIndexed { index, item ->
        QueuedRow(item = item, first = index == 0, state = state, actions = actions, now = now)
      }
    }
    if (queued.size > QUEUE_VISIBLE_ROWS) {
      Row(
        modifier = Modifier.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Link(
          text = queuedFoldLabel(queued.size, showAll),
          onClick = { showAll = !showAll },
          textStyle = small(),
        )
        Glyph(
          name = "chevron-down",
          tint = muted(),
          size = 14.dp,
          modifier = Modifier.rotate(if (showAll) 180f else 0f),
        )
      }
    }
  }
}

/** One `li`: the message, then its controls under it, both on the right. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun QueuedRow(
  item: QueuedThreadRun,
  first: Boolean,
  state: QueuedRunsState,
  actions: QueuedRunsActions,
  now: Instant,
) {
  val workflow: ThreadQueueWorkflowState = state.workflow
  val idle: Boolean = state.enabled && !state.busy
  val editing: Boolean = item.run.id == state.editingRunId
  val thumbnails: List<ChatAttachment> = item.attachments.filter { it.type == "image" }
  val preview: String = queuedRunPreviewText(item.text, hasThumbnails = thumbnails.isNotEmpty())
  QueuedMessage(
    message = item.message,
    preview = preview,
    thumbnails = thumbnails,
    editing = editing,
    now = now,
    threadTitles = state.threadTitles,
    onOpenThread = actions.onOpenThread,
  ) {
    if (canSendQueuedRun(workflow) && !editing) {
      val shortcut: String? = state.steerShortcutLabel.takeIf { first }
      Tooltip(tooltip = { Text(queuedRunSendTooltip(workflow, shortcut)) }) {
        IconButton(
          onClick = { actions.onSendNow(item.run.id) },
          enabled = idle,
          modifier = Modifier.heightIn(min = SMALL_BUTTON),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            val tint: Color = if (idle) muted() else muted().copy(alpha = 0.5f)
            Glyph("arrow-up", tint, 12.dp)
            Text("Send now", style = small(), color = tint)
          }
        }
      }
    }
    if (editing) {
      Text(
        text = "Editing in the composer",
        style = small(),
        modifier = Modifier.padding(horizontal = 4.dp),
      )
      Link(
        text = "Cancel",
        onClick = actions.onCancelEdit,
        textStyle = small(),
        modifier = Modifier.padding(horizontal = 6.dp),
      )
      return@QueuedMessage
    }
    IconActionButton(
      key = AllIconsKeys.Actions.Edit,
      contentDescription = "Edit queued message",
      onClick = { actions.onEdit(item) },
      enabled = idle && isQueuedRunEditable(item),
      tooltip = { Text("Edit in the composer") },
      modifier = Modifier.size(SMALL_BUTTON),
    )
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

/**
 * `QueuedMessage`, QueuedRunsControl.tsx:393: the message in a dashed, unfilled bubble, a turn
 * that hasn't been sent yet, at most 80% of the lane, and [controls] in a row under it. It wears
 * its sent form's attribution: "Sent by T3 Code" over it, an automation's or agent's message
 * folded behind a header, and on hover the agent sender's thread and when it was queued.
 */
@Composable
private fun QueuedMessage(
  message: ConversationMessage?,
  preview: String,
  thumbnails: List<ChatAttachment>,
  editing: Boolean,
  now: Instant,
  threadTitles: Map<String, String>,
  onOpenThread: ((threadId: String) -> Unit)?,
  controls: @Composable () -> Unit,
) {
  val presentation: UserMessagePresentation? =
    remember(message) { message?.let(::resolveUserMessagePresentation) }
  val senderThreadId: String? = message?.senderThreadId
  val senderTitle: String? = senderThreadId?.let(threadTitles::get)
  val fold: MessageFold? = remember(presentation, preview, senderTitle) {
    queuedMessageFold(presentation, preview, senderTitle)
  }
  var expanded: Boolean by remember(message?.id) { mutableStateOf(false) }
  // The `li`'s `group/queued`: its hover shows the meta
  val hover: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by hover.collectIsHoveredAsState()
  // `border-primary/60 text-foreground` while editing, else `border-muted-foreground/40`
  val border: Color = if (editing) accent().copy(alpha = 0.6f) else muted().copy(alpha = 0.4f)
  val text: Color = if (editing) JewelTheme.globalColors.text.normal else muted()
  BoxWithConstraints(Modifier.fillMaxWidth().hoverable(hover)) {
    val bubbleWidth: Dp = maxWidth * 0.8f
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (presentation?.attribution == "t3code") {
        // `me-1 text-2xs text-muted-foreground/70`
        Text(
          text = SENT_BY_T3_CODE,
          style = small(),
          color = muted().copy(alpha = 0.7f),
          modifier = Modifier.padding(end = 4.dp),
        )
      }
      // `rounded-2xl border border-dashed`, `px-3 py-2` unless folded, `w-full` once opened
      Column(
        modifier = Modifier
          .widthIn(max = bubbleWidth)
          .then(if (fold != null && expanded) Modifier.fillMaxWidth() else Modifier)
          .dashedBorder(border, BUBBLE_RADIUS)
          .then(
            if (fold == null) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier,
          ),
      ) {
        if (fold != null) {
          FoldedMessageHeader(fold, expanded, onToggle = { expanded = !expanded })
        }
        if (fold == null || expanded) {
          // `flex flex-col gap-2`, folded `max-h-96 overflow-auto px-3 pb-2`
          Column(
            modifier = if (fold != null) {
              Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .heightIn(max = FOLDED_BODY_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
            } else {
              Modifier
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (thumbnails.isNotEmpty()) QueuedThumbnails(thumbnails)
            // In full, like a sent turn: a queued message is read before it is sent
            if (preview.isNotEmpty()) Text(preview, color = text)
          }
        }
      }
      // `flex items-center gap-0.5 pe-1 text-xs text-muted-foreground`
      Row(
        modifier = Modifier.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        if (message != null) {
          // `me-1 flex gap-2 px-1 opacity-0 group-hover/queued:opacity-100`
          Row(
            modifier = Modifier
              .padding(start = 4.dp, end = 8.dp)
              .alpha(if (hovered) 1f else 0f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (presentation?.attribution == "agent" && senderThreadId != null &&
              onOpenThread != null
            ) {
              Link("Open thread", onClick = { onOpenThread(senderThreadId) }, textStyle = small())
            }
            QueuedTimestamp(message.createdAt, now)
          }
        }
        controls()
      }
    }
  }
}

/** `TimelineRowTimestamp` with `alwaysVisible`: when the message was queued. */
@Composable
private fun QueuedTimestamp(at: String, now: Instant) {
  val instant: Instant = runCatching { Instant.parse(at) }.getOrNull() ?: return
  Text(formatDayAwareTimestamp(instant, now), style = small(), color = muted())
}

/** A 1dp `border-dashed` along a rounded box, under its content. */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
  val width: Float = 1.dp.toPx()
  drawRoundRect(
    color = color,
    topLeft = Offset(width / 2, width / 2),
    size = Size(size.width - width, size.height - width),
    cornerRadius = CornerRadius(radius.toPx()),
    style = Stroke(
      width = width,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
    ),
  )
}

/** A message's images, 32dp each, upstream's `size-8 rounded-md` thumbnails. */
@Composable
private fun QueuedThumbnails(images: List<ChatAttachment>) {
  val state: MessageImageState = LocalMessageImages.current
  LaunchedEffect(images.map { it.id }) { state.onShown(images) }
  val shape = RoundedCornerShape(6.dp)
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    images.forEach { image ->
      val bitmap: ImageBitmap? = state.previews[image.id]
      Box(
        modifier = Modifier
          .size(32.dp)
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

/** A folded body's `max-h-96`, 24rem. */
private val FOLDED_BODY_MAX_HEIGHT: Dp = 384.dp

/** `rounded-2xl`, 1rem. */
private val BUBBLE_RADIUS: Dp = 16.dp

/** `size="icon-xs"`. */
private val SMALL_BUTTON: Dp = 20.dp
