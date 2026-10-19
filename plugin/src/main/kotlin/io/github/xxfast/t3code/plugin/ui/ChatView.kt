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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ide.CopyPasteManager
import io.github.xxfast.t3code.plugin.review.checkpointForRun
import io.github.xxfast.t3code.plugin.review.rollbackBlockReason
import io.github.xxfast.t3code.plugin.review.rollbackTooltip
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.awt.datatransfer.StringSelection
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.typography

/** `--chat-content-max-width`, 46rem: the timeline and composer lane, centred when wider. */
private val MAX_CONTENT_WIDTH = 736.dp

@Composable
fun ChatView(
  view: ThreadView?, // null until the first snapshot arrives
  error: String?, // why the thread stopped loading, if it did
  project: Project?, // for the breadcrumb
  favicon: ImageBitmap?, // the project's, when ProjectIcons has it
  composer: ComposerState,
  draft: TextFieldState, // what's typed in the composer
  pending: PendingRequest?, // an approval or question that takes over the composer
  pendingActions: PendingActions,
  footer: ComposerFooterState?, // the thread's model and modes, null until the catalog loads
  footerActions: ComposerFooterActions,
  onClearReview: () -> Unit, // drop the review comments going with the next message
  onRollback: (Run) -> Unit, // back to the state after this run, from a prompt's revert
  onSend: (text: String, mode: ComposerDispatchMode) -> Unit,
  onQueueChange: (Boolean) -> Unit,
  onRetry: () -> Unit, // resubscribe after an error
  onOpenFile: (path: String) -> Unit, // a changed file's path
  onDiffFile: (TurnItem.FileChange) -> Unit, // "Diff" on a file change's details
  onDiffRun: (Run) -> Unit, // "Open diff" on a turn's changed files
  vcs: VcsSummary? = null, // the checkout's git line under the title, when there's one
  extras: DraftExtras = DraftExtras(), // the draft's attachments and context
  attachmentActions: AttachmentActions = AttachmentActions(),
) {
  Column(Modifier.fillMaxSize()) {
    ChatHeader(view, project, favicon, vcs)
    // A stream that fails after rendering keeps the thread and says so on top
    if (error != null && view != null) ErrorLine(error, onRetry)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        view == null && error != null -> LoadError(error, onRetry)
        view == null -> Unit // upstream draws nothing while a thread loads
        view.items.isEmpty() -> Hint("Send a message to start the conversation.")
        else -> {
          val processor: MarkdownProcessor = remember { MarkdownProcessor() }
          ProvideMarkdownStyling(markdownProcessor = processor) {
            MessagesTimeline(
              view = view,
              processor = processor,
              operateReason = composer.disabledReason,
              actions = TimelineActions(onOpenFile, onDiffFile, onDiffRun, onRollback),
            )
          }
        }
      }
    }
    if (view != null) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Composer(
          state = composer,
          draft = draft,
          pending = pending,
          pendingActions = pendingActions,
          footer = footer,
          footerActions = footerActions,
          onClearReview = onClearReview,
          onSend = onSend,
          onQueueChange = onQueueChange,
          extras = extras,
          attachmentActions = attachmentActions,
          modifier = Modifier
            .widthIn(max = MAX_CONTENT_WIDTH)
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        )
      }
    }
  }
}

private class TimelineActions(
  val onOpenFile: (String) -> Unit,
  val onDiffFile: (TurnItem.FileChange) -> Unit,
  val onDiffRun: (Run) -> Unit,
  val onRollback: (Run) -> Unit,
)

@Composable
private fun LoadError(error: String, onRetry: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "Couldn't load this thread: $error",
      color = muted(),
      textAlign = TextAlign.Center,
    )
    Link("Retry", onClick = onRetry)
  }
}

@Composable
private fun ErrorLine(error: String, onRetry: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "Stopped updating: $error",
      color = JewelTheme.globalColors.text.error,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Link("Retry", onClick = onRetry)
  }
}

/**
 * Upstream's `ChatHeader` and `WorkspaceBreadcrumb`: the project, muted, then the thread title,
 * in a flat bar with no border.
 */
@Composable
private fun ChatHeader(
  view: ThreadView?,
  project: Project?,
  favicon: ImageBitmap?,
  vcs: VcsSummary?,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (project != null) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ProjectIcon(project, favicon, 14.dp)
          Text(
            text = project.title,
            color = muted(),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
          )
        }
        Text("/", color = muted())
      }
      Text(
        text = view?.thread?.title.orEmpty(),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (vcs != null) {
      Text(
        text = vcs.text(),
        style = small(),
        color = muted(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** Upstream's `MessagesTimeline`: rows from `deriveMessagesTimelineRows`, in the content lane. */
@Composable
private fun MessagesTimeline(
  view: ThreadView,
  processor: MarkdownProcessor,
  operateReason: String?, // why nothing can change the thread, when it can't
  actions: TimelineActions,
) {
  val running: Run? = activeRun(view.runs)
  val now: Instant by rememberNow(if (running != null) 1_000 else 30_000)
  var expansion: TimelineExpansion by remember(view.thread.id) {
    mutableStateOf(TimelineExpansion())
  }
  val entries: List<TimelineEntry> = remember(view.items) { deriveTimelineEntries(view.items) }
  val rows: List<TimelineRow> = remember(entries, view.runs, view.checkpoints, expansion) {
    deriveMessagesTimelineRows(
      timelineEntries = entries,
      runs = view.runs,
      runningRun = running,
      expansion = expansion,
      checkpointFiles = { run -> checkpointForRun(view, run)?.files },
      revertTarget = { run ->
        view.runs
          .firstOrNull { it.ordinal == run.ordinal - 1 }
          ?.takeIf { checkpointForRun(view, it) != null }
      },
    )
  }
  val requests: Map<String, RuntimeRequest> =
    remember(view.runtimeRequests) { view.runtimeRequests.associateBy { it.id } }
  // Which work entries have their details open; survives rows scrolling out of the lazy column
  val open: SnapshotStateMap<String, Boolean> = remember(view.thread.id) { mutableStateMapOf() }
  val context = RowContext(
    now = now,
    processor = processor,
    requests = requests,
    open = open,
    rollbackReason = rollbackBlockReason(operateReason, view),
    actions = actions,
    onToggleRun = { runId -> expansion = expansion.copy(runIds = expansion.runIds.toggle(runId)) },
    onToggleGroup = { groupId ->
      expansion = expansion.copy(workGroupIds = expansion.workGroupIds.toggle(groupId))
    },
  )
  val state: LazyListState = rememberLazyListState()
  FollowTail(state, rows, view.items.lastOrNull())

  BoxWithConstraints(Modifier.fillMaxSize()) {
    // `.messages-timeline-scroll`: 12px a side when narrow, 48px from 640px up
    val side: Dp = if (maxWidth >= 640.dp) 48.dp else 12.dp
    VerticallyScrollableContainer(scrollState = state, modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
          val continues: Boolean = row.isWorkLog() && rows.getOrNull(index + 1).isWorkLog()
          Box(
            Modifier
              .widthIn(max = MAX_CONTENT_WIDTH)
              .fillMaxWidth()
              .padding(horizontal = side)
              .padding(bottom = row.bottomPadding(continues)),
          ) {
            TimelineRowContent(row, context)
          }
        }
      }
    }
  }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private class RowContext(
  val now: Instant,
  val processor: MarkdownProcessor,
  val requests: Map<String, RuntimeRequest>, // by id, for the state of approvals and questions
  val open: SnapshotStateMap<String, Boolean>,
  val rollbackReason: String?,
  val actions: TimelineActions,
  val onToggleRun: (String) -> Unit,
  val onToggleGroup: (String) -> Unit,
)

private fun TimelineRow?.isWorkLog(): Boolean = this is TimelineRow.Work ||
  this is TimelineRow.WorkToggle || this is TimelineRow.WorkLive || this is TimelineRow.Thinking

/** `TimelineRowContent`'s wrapper padding, MessagesTimeline.tsx:1897, and `WorkLogBlock`'s. */
private fun TimelineRow.bottomPadding(continuesWorkLog: Boolean): Dp = when (this) {
  is TimelineRow.TurnFold, is TimelineRow.Working -> 6.dp
  is TimelineRow.Message -> if (entry.user || showMeta) 16.dp else 8.dp
  is TimelineRow.AssistantMeta, is TimelineRow.Plan, is TimelineRow.ContextCompaction -> 16.dp
  is TimelineRow.Event -> 8.dp
  is TimelineRow.Work -> if (continuesWorkLog) 0.dp else if (expandedGroup) 4.dp else 8.dp
  is TimelineRow.WorkToggle, is TimelineRow.WorkLive, TimelineRow.Thinking ->
    if (continuesWorkLog) 0.dp else 8.dp
}

/** Scrolls to the end when rows change, but only if the user was already looking at the end. */
@Composable
private fun FollowTail(state: LazyListState, rows: List<TimelineRow>, last: TurnItem?) {
  val previousCount: MutableIntState = remember { mutableIntStateOf(0) }
  LaunchedEffect(rows.size, last) {
    val lastVisible: Int? = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
    val follow: Boolean = wasAtBottom(lastVisible, previousCount.intValue)
    previousCount.intValue = rows.size
    if (follow && rows.isNotEmpty()) state.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
  }
}

@Composable
private fun TimelineRowContent(row: TimelineRow, context: RowContext) {
  when (row) {
    is TimelineRow.Working -> WorkingRow(row, context.now)
    is TimelineRow.TurnFold -> TurnFoldRow(row, onToggle = { context.onToggleRun(row.runId) })
    is TimelineRow.Message ->
      if (row.entry.user) UserMessageRow(row, context) else AssistantMessageRow(row, context)
    is TimelineRow.AssistantMeta ->
      AssistantMeta(row.entry, context.now, visible = true, Modifier.padding(horizontal = 4.dp))
    is TimelineRow.Work -> Column {
      row.entries.forEach { entry ->
        val label: String =
          row.displayLabel.takeIf { row.entries.size == 1 } ?: entry.displayLabel()
        WorkEntryRow(entry, label, context)
      }
    }
    is TimelineRow.WorkToggle -> WorkLogLine(
      icon = groupIcon(row.kind),
      label = row.summary,
      failed = row.hasFailure,
      onClick = { context.onToggleGroup(row.groupId) },
    )
    is TimelineRow.WorkLive -> WorkLogLine(
      icon = workEntryIcon(row.entry),
      label = row.entry.liveLabel(row.active),
      onClick = { context.onToggleGroup(row.groupId) },
    )
    TimelineRow.Thinking -> WorkLogLine(icon = "brain", label = "Thinking")
    is TimelineRow.ContextCompaction -> ContextCompactionRow(row.label)
    is TimelineRow.Event -> EventRow(row.item, context)
    is TimelineRow.Plan -> PlanCard(row.item, context.processor)
  }
}

/** `WorkingTimelineRow`, MessagesTimeline.tsx:3540: "Working for 12s" over a hairline. */
@Composable
private fun WorkingRow(row: TimelineRow.Working, now: Instant) {
  val start: Instant? = row.startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
  Box(Modifier.fillMaxWidth().bottomHairline().padding(top = 4.dp, bottom = 8.dp)) {
    Text(
      text = start?.let { "Working for ${formatWorkingTimer(it, now)}" } ?: "Working...",
      color = muted(),
      modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
    )
  }
}

/** `TurnFoldTimelineRow`, MessagesTimeline.tsx:2544: the label and a chevron over a hairline. */
@Composable
private fun TurnFoldRow(row: TimelineRow.TurnFold, onToggle: () -> Unit) {
  val source: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by source.collectIsHoveredAsState()
  val tint: Color = if (hovered) JewelTheme.globalColors.text.normal else muted()
  Row(
    modifier = Modifier.fillMaxWidth().bottomHairline().padding(top = 4.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .hoverable(source)
        .clickable(onClick = onToggle)
        .padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(row.label, color = tint)
      Glyph(if (row.expanded) "chevron-down" else "chevron-right", tint)
    }
  }
}

/** `UserTimelineRow`, MessagesTimeline.tsx:2201: a right-aligned bubble, its meta on hover. */
@Composable
private fun UserMessageRow(row: TimelineRow.Message, context: RowContext) {
  val message: TurnItem.UserMessage = row.entry.item as TurnItem.UserMessage
  val text: String = remember(message.text) { stripContextReferences(message.text) }
  val extras: List<String> = remember(message.context, message.attachments) {
    contextLabels(message.context) + message.attachments.map { it.name }
  }
  val collapsible: Boolean = remember(text) { shouldCollapseUserMessage(text) }
  var showAll: Boolean by remember(row.id) { mutableStateOf(false) }
  val source: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by source.collectIsHoveredAsState()
  BoxWithConstraints(Modifier.fillMaxWidth().hoverable(source)) {
    val bubbleWidth: Dp = maxWidth * 0.8f
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      intentLabel(message.inputIntent)?.let { intent ->
        Row(
          modifier = Modifier.padding(end = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Glyph("redo-2", muted(), 12.dp)
          Text(intent, style = small(), color = muted())
        }
      }
      Column(
        modifier = Modifier
          .widthIn(max = bubbleWidth)
          .background(subtle(0.06f), RoundedCornerShape(18.dp))
          .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        extras.forEach { line -> Text(line, style = small(), color = muted()) }
        if (text.isNotEmpty()) {
          val clip: Modifier =
            if (collapsible && !showAll) Modifier.heightIn(max = 176.dp).clip(RectangleClip)
            else Modifier
          Box(clip) { MarkdownText(text, context.processor, fill = false) }
        }
        if (collapsible) {
          Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
            Link(
              text = if (showAll) "Show less" else "Show full message",
              onClick = { showAll = !showAll },
              textStyle = small(),
            )
          }
        }
      }
      if (message.status !in QUIET_USER) {
        StatusPill(message.status, JewelTheme.globalColors.text.error)
      }
      Row(
        modifier = Modifier.widthIn(max = bubbleWidth).alpha(if (hovered) 1f else 0f),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Timestamp(row.entry.createdAt, context.now)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          row.revertTo?.let { target ->
            IconAction(
              icon = "undo-2",
              tooltip = rollbackTooltip(target, context.rollbackReason),
              enabled = context.rollbackReason == null,
              onClick = { context.actions.onRollback(target) },
            )
          }
          if (text.isNotBlank()) CopyAction(text)
        }
      }
    }
  }
}

private val QUIET_USER: Set<String> = setOf("completed", "pending", "waiting", "running", "")

private val RectangleClip = RoundedCornerShape(0.dp)

/** `UserMessageIntentMarker`: only prompts that didn't start a turn of their own say so. */
private fun intentLabel(intent: String): String? = when (intent) {
  "steer", "promoted_queued_to_steer" -> "Steer"
  "queued_turn" -> "From queue"
  else -> null
}

/** `AssistantTimelineRow`, MessagesTimeline.tsx:2592: plain markdown, no bubble. */
@Composable
private fun AssistantMessageRow(row: TimelineRow.Message, context: RowContext) {
  val message: TurnItem.AssistantMessage = row.entry.item as TurnItem.AssistantMessage
  val text: String = message.text.ifEmpty { if (message.streaming) "" else "(empty response)" }
  val source: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by source.collectIsHoveredAsState()
  Column(Modifier.fillMaxWidth().hoverable(source).padding(horizontal = 4.dp, vertical = 2.dp)) {
    if (text.isNotEmpty()) MarkdownText(text, context.processor)
    row.changedFiles?.let { files -> ChangedFilesCard(files, context.actions) }
    if (row.showMeta) {
      AssistantMeta(row.entry, context.now, visible = hovered, Modifier.padding(top = 6.dp))
    }
  }
}

/** `AssistantMessageMeta`, MessagesTimeline.tsx:2701: status, copy, then the time. */
@Composable
private fun AssistantMeta(
  entry: TimelineEntry.Message,
  now: Instant,
  visible: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.alpha(if (visible) 1f else 0f),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (entry.item.status != "completed") StatusPill(entry.item.status, muted())
    if (entry.text.isNotBlank() && !entry.streaming) CopyAction(entry.text)
    if (!entry.streaming) Timestamp(entry.item.updatedAt, now)
  }
}

/** `ChangedFilesCard`, ChangedFilesTree.tsx: the turn's checkpoint files under its reply. */
@Composable
private fun ChangedFilesCard(changed: ChangedFiles, actions: TimelineActions) {
  val count: Int = changed.files.size
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 16.dp)
      .background(subtle(0.04f), RoundedCornerShape(10.dp))
      .padding(bottom = 6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "$count changed file${if (count == 1) "" else "s"}",
        style = small(),
        fontWeight = FontWeight.Medium,
      )
      DiffStatLabel(changed.files.sumOf { it.additions }, changed.files.sumOf { it.deletions })
      Spacer(Modifier.weight(1f))
      Row(
        modifier = Modifier.clickable { actions.onDiffRun(changed.run) },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Glyph("file-diff", muted(), 12.dp)
        Text("Open diff", style = small(), color = muted())
      }
    }
    changed.files.forEach { file ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { actions.onOpenFile(file.path) }
          .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = file.path,
          style = small(),
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.StartEllipsis,
        )
        DiffStatLabel(file.additions, file.deletions)
      }
    }
  }
}

/** `DiffStatLabel`: `+12 -3` in success and destructive. */
@Composable
private fun DiffStatLabel(additions: Long?, deletions: Long?) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    additions?.let { Text("+$it", style = small(), color = Color(0xFF10B981)) }
    deletions?.let { Text("-$it", style = small(), color = Color(0xFFEF4444)) }
  }
}

/** One work log entry: its line, and its details underneath when open. */
@Composable
private fun WorkEntryRow(entry: WorkLogEntry, label: String, context: RowContext) {
  val failedError: Boolean = entry.item is TurnItem.Error && entry.status == ToolStatus.Failed
  if (failedError) return FailedErrorRow(entry, label, context.now)
  val expandable: Boolean = entry.hasDetails()
  val expanded: Boolean = context.open[entry.id] ?: false
  Column(Modifier.fillMaxWidth()) {
    val tone: LabelTone = entry.labelTone()
    WorkLogLine(
      // MessagesTimeline.tsx:5323: warnings and destructive failures trade their icon for an alert
      icon = if (tone == LabelTone.Warning || tone == LabelTone.Error) "circle-alert"
      else workEntryIcon(entry),
      label = label,
      tone = tone,
      failed = entry.isToolLike() && entry.indicatesFailure(),
      timestamp = entry.createdAt,
      now = context.now,
      expanded = expanded.takeIf { expandable },
      onClick = if (expandable) ({ context.open[entry.id] = !expanded }) else null,
    )
    if (expanded) WorkEntryDetails(entry, context)
  }
}

private enum class LabelTone { Tool, Info, Warning, Error }

/** `headingClass`, MessagesTimeline.tsx:5410. */
private fun WorkLogEntry.labelTone(): LabelTone = when {
  sourceActivityKind == "runtime.warning" -> LabelTone.Warning
  indicatesFailure() && !isToolLike() -> LabelTone.Error
  isToolLike() -> LabelTone.Tool
  else -> LabelTone.Info
}

/** Notifications without a detail and created threads have nothing to open. */
private fun WorkLogEntry.hasDetails(): Boolean = when (val item = item) {
  is TurnItem.TurnItemNotification -> item.detail != null
  is TurnItem.ThreadCreated -> false
  is TurnItem.Reasoning -> item.text.isNotBlank()
  else -> true
}

/**
 * `WorkLogLine`, WorkLog.tsx:53: a 16px icon in a 24px slot, the muted label, the time on hover
 * and a chevron that turns when the details are open.
 */
@Composable
private fun WorkLogLine(
  icon: String,
  label: String,
  tone: LabelTone = LabelTone.Tool,
  failed: Boolean = false,
  timestamp: String? = null,
  now: Instant? = null,
  expanded: Boolean? = null, // null when there's nothing to open
  onClick: (() -> Unit)? = null,
) {
  val source: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by source.collectIsHoveredAsState()
  val labelColor: Color = when (tone) {
    LabelTone.Tool -> muted()
    LabelTone.Info -> JewelTheme.globalColors.text.normal.copy(alpha = 0.8f)
    LabelTone.Warning -> JewelTheme.globalColors.text.warning
    LabelTone.Error -> JewelTheme.globalColors.text.error
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 24.dp)
      .clip(RoundedCornerShape(8.dp))
      .hoverable(source)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
      val tint: Color =
        if (tone == LabelTone.Error || tone == LabelTone.Warning) labelColor else muted()
      Glyph(icon, tint, 16.dp)
    }
    Text(
      text = label,
      color = labelColor,
      fontWeight = if (tone == LabelTone.Warning || tone == LabelTone.Error) FontWeight.Medium
      else null,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (failed) Glyph("x", JewelTheme.globalColors.text.error.copy(alpha = 0.4f), 12.dp)
    if (hovered && timestamp != null && now != null) Timestamp(timestamp, now)
    if (expanded != null) {
      Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Glyph(
          name = "chevron-right",
          tint = muted().copy(alpha = 0.7f),
          size = 12.dp,
          modifier = Modifier.rotate(if (expanded) 90f else 0f),
        )
      }
    }
  }
}

/** A failed `error` item: red, its time always shown, the message underneath. */
@Composable
private fun FailedErrorRow(entry: WorkLogEntry, label: String, now: Instant) {
  Column(Modifier.fillMaxWidth()) {
    WorkLogLine(icon = "circle-alert", label = label, tone = LabelTone.Error)
    entry.detail?.let { message ->
      Text(
        text = message,
        color = JewelTheme.globalColors.text.normal.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp),
      )
    }
    Box(Modifier.padding(start = 28.dp)) { Timestamp(entry.createdAt, now) }
  }
}

/** `WorkLogDetails` and `V2ItemInspector`: what an open entry shows. */
@Composable
private fun WorkEntryDetails(entry: WorkLogEntry, context: RowContext) {
  val indent = Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp, bottom = 6.dp)
  val gap: (Int) -> Arrangement.Vertical = { Arrangement.spacedBy(it.dp) }
  when (val item = entry.item) {
    is TurnItem.Reasoning -> Column(indent) { MarkdownText(item.text, context.processor) }

    is TurnItem.CommandExecution -> Column(indent, verticalArrangement = gap(6)) {
      Mono(commandDisplayText(item.input), JewelTheme.globalColors.text.normal.copy(alpha = 0.85f))
      item.output?.trimEnd()?.takeIf { it.isNotEmpty() }?.let { Mono(it.takeLast(20_000), muted()) }
      if (item.outputOmitted == true) Text("Output omitted", style = small(), color = muted())
      item.exitCode?.takeIf { it != 0L }?.let { code ->
        Mono("exit $code", JewelTheme.globalColors.text.error)
      }
    }

    is TurnItem.FileChange -> Column(indent, verticalArrangement = gap(2)) {
      entry.changedFiles.forEach { path ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = path,
            style = small(),
            modifier = Modifier
              .weight(1f, fill = false)
              .clickable { context.actions.onOpenFile(path) },
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
          )
          DiffStatLabel(item.additions, item.deletions)
        }
      }
      Link("Diff", onClick = { context.actions.onDiffFile(item) }, textStyle = small())
    }

    // Answered in the composer; the row keeps the record
    is TurnItem.ApprovalRequest -> Column(indent, verticalArrangement = gap(4)) {
      Text(approvalLabel(item.requestKind), style = small(), color = muted())
      item.appName?.let { Text(it, style = small(), color = muted()) }
      item.prompt?.let { Text(it, color = muted()) }
      if (awaitingResponse(item.requestId, item.status, context.requests)) {
        Text("Waiting on you", style = small(), color = JewelTheme.globalColors.text.warning)
      } else {
        Text(
          text = decisionLabel(context.requests[item.requestId]?.decision, item.options),
          style = small(),
          color = JewelTheme.globalColors.text.normal,
        )
      }
    }

    is TurnItem.UserInputRequest -> Column(indent, verticalArrangement = gap(8)) {
      item.questions.forEach { question -> Text(question.question, color = muted()) }
      if (awaitingResponse(item.requestId, item.status, context.requests)) {
        Text("Waiting on you", style = small(), color = JewelTheme.globalColors.text.warning)
      } else {
        val answers: JsonObject? =
          context.requests[item.requestId]?.answers ?: item.questionAnswer?.answers
        answerLines(item.questions, answers).forEach { line ->
          Text(line, modifier = Modifier.padding(start = 12.dp))
        }
      }
    }

    is TurnItem.TurnItemNotification -> Column(indent) {
      item.detail?.let { Text(it, color = muted()) }
    }

    is TurnItem.Error, is TurnItem.SystemNotice, is TurnItem.FileSearch, is TurnItem.WebSearch,
    is TurnItem.Compaction -> Column(indent) {
      entry.detail?.let { Mono(it, muted()) }
    }

    // Anything this build doesn't draw: the raw JSON
    else -> Column(indent) {
      val json: String = remember(item) { prettyJson(item.json()) }
      Mono(json, muted())
    }
  }
}

/** `workEntryIconName`, MessagesTimeline.tsx:5102, as lucide names. */
private fun workEntryIcon(entry: WorkLogEntry): String {
  val item: TurnItem = entry.item
  if (item is TurnItem.TurnItemNotification) {
    if (item.outcome == "failed") return "circle-alert"
    return when (item.source.kind) {
      "subagent", "delegated_task" -> "bot"
      "command" -> "terminal"
      "monitor" -> "eye"
      else -> "zap"
    }
  }
  if (item is TurnItem.UserInputRequest || item is TurnItem.ApprovalRequest) {
    return "message-circle"
  }
  return when (entry.action()) {
    ToolAction.Read -> "eye"
    ToolAction.Edit -> "square-pen"
    ToolAction.Command -> "terminal"
    ToolAction.Search -> "globe"
    ToolAction.CodeSearch -> "search"
    ToolAction.ThreadCreate -> "zap"
    ToolAction.Update -> "hammer"
    ToolAction.Other -> when {
      item is TurnItem.DynamicTool -> "wrench"
      item.type == "subagent" -> "bot"
      entry.tone == WorkTone.Error -> "circle-alert"
      entry.tone == WorkTone.Thinking -> "brain"
      entry.tone == WorkTone.Info -> "check"
      else -> "zap"
    }
  }
}

/** `toolGroupSummaryIconName`, MessagesTimeline.tsx:3853. */
private fun groupIcon(kind: Pair<ToolGroupKind, ToolAction?>): String = when (kind.first) {
  ToolGroupKind.Action -> when (kind.second) {
    ToolAction.Read -> "eye"
    ToolAction.Edit -> "square-pen"
    ToolAction.Command -> "terminal"
    ToolAction.Search -> "globe"
    ToolAction.CodeSearch -> "search"
    ToolAction.Other -> "wrench"
    else -> "hammer"
  }
  ToolGroupKind.DynamicTool, ToolGroupKind.Mixed -> "hammer"
  ToolGroupKind.Reasoning -> "brain"
  ToolGroupKind.AgentTool -> "bot"
  ToolGroupKind.ToneTool -> "zap"
  ToolGroupKind.Other -> "wrench"
}

/** The context compaction row, MessagesTimeline.tsx:1999: a label between two hairlines. */
@Composable
private fun ContextCompactionRow(label: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Hairline(Modifier.weight(1f))
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph("minimize-2", muted(), 12.dp)
      Text(label, style = small(), color = muted())
    }
    Hairline(Modifier.weight(1f))
  }
}

/** `V2EventTimelineRow`: lifecycle events as dividers, the rest as a card. */
@Composable
private fun EventRow(item: TurnItem, context: RowContext) {
  when (item.type) {
    "run_interrupt_result" ->
      SystemDivider("x", "Run interrupted", JewelTheme.globalColors.text.error)
    "fork" -> SystemDivider("git-fork", item.title ?: "Forked from conversation", muted())
    "handoff" -> SystemDivider("arrow-right-left", item.title ?: "Handoff", muted())
    else -> EventCard(item, context)
  }
}

/** `TimelineSystemDivider`: icon and label in a pill between two hairlines. */
@Composable
private fun SystemDivider(icon: String, label: String, tint: Color) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Hairline(Modifier.weight(1f))
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph(icon, tint, 12.dp)
      Text(label, style = small(), color = tint, fontWeight = FontWeight.Medium)
    }
    Hairline(Modifier.weight(1f))
  }
}

/** The generic event card: its title, and the raw JSON on demand. */
@Composable
private fun EventCard(item: TurnItem, context: RowContext) {
  val expanded: Boolean = context.open[item.id] ?: false
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(10.dp))
      .clip(RoundedCornerShape(10.dp))
      .clickable { context.open[item.id] = !expanded }
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = item.title ?: item.type.replace("_", " ").replaceFirstChar { it.uppercase() },
      style = small(),
      fontWeight = FontWeight.Medium,
    )
    if (expanded) {
      val json: String = remember(item) { prettyJson(item.json()) }
      Mono(json, muted())
    }
  }
}

/** `ProposedPlanCard`, a rounded card around the plan's markdown. */
@Composable
private fun PlanCard(plan: TurnItem.ProposedPlan, processor: MarkdownProcessor) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(22.dp))
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(plan.title ?: "Plan", fontWeight = FontWeight.Medium)
    MarkdownText(plan.markdown, processor)
  }
}

@Composable
private fun StatusPill(status: String, tint: Color) {
  Text(
    text = status,
    style = small(),
    color = tint,
    modifier = Modifier
      .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(50))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

/** `TimelineRowTimestamp`: day-aware time, muted. */
@Composable
private fun Timestamp(at: String, now: Instant) {
  val instant: Instant = runCatching { Instant.parse(at) }.getOrNull() ?: return
  Text(formatDayAwareTimestamp(instant, now), style = small(), color = muted())
}

/** A 24px ghost icon button with a tooltip. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun IconAction(
  icon: String,
  tooltip: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Tooltip(tooltip = { Text(tooltip) }) {
    Box(
      modifier = Modifier
        .size(24.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      Glyph(icon, muted().copy(alpha = if (enabled) 1f else 0.4f), 12.dp)
    }
  }
}

/** `MessageCopyButton`: copies, then shows a check for a moment. */
@Composable
private fun CopyAction(text: String) {
  var copied: Boolean by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      kotlinx.coroutines.delay(1_500)
      copied = false
    }
  }
  IconAction(icon = if (copied) "check" else "copy", tooltip = "Copy") {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
    copied = true
  }
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) {
  Box(modifier.height(1.dp).background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.7f)))
}

/** `border-b border-border/60`. */
@Composable
private fun Modifier.bottomHairline(): Modifier {
  val color: Color = JewelTheme.globalColors.borders.normal.copy(alpha = 0.6f)
  return drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
  }
}

@Composable
private fun muted(): Color = JewelTheme.globalColors.text.info

@Composable
private fun MarkdownText(text: String, processor: MarkdownProcessor, fill: Boolean = true) {
  val blocks: List<MarkdownBlock> = remember(text) { processor.processMarkdownDocument(text) }
  val modifier: Modifier = if (fill) Modifier.fillMaxWidth() else Modifier
  Markdown(markdownBlocks = blocks, markdown = text, modifier = modifier)
}

@Composable
private fun Mono(text: String, color: Color) {
  Text(text = text, style = JewelTheme.editorTextStyle, color = color)
}
