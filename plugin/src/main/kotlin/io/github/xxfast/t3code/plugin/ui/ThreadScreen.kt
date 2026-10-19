package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/** The conversation column is capped like T3's, centred when the host is wider. */
private val MAX_CONTENT_WIDTH = 760.dp

@Composable
fun ThreadScreen(
  view: ThreadView?, // null until the first snapshot arrives
  project: Project?, // for the breadcrumb
  favicon: ImageBitmap?, // the project's, when ProjectIcons has it
  onOpenFile: (path: String) -> Unit, // "Open" on a file_change row
  onDiffFile: (TurnItem.FileChange) -> Unit, // "Diff" on a file_change row
  onDiffRun: (Run) -> Unit, // "Diff" on a run's line
) {
  Column(Modifier.fillMaxSize()) {
    Breadcrumb(view, project, favicon)
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    if (view == null || view.items.isEmpty()) {
      Hint(if (view == null) "Loading..." else "Nothing in this thread yet")
    } else {
      val processor: MarkdownProcessor = remember { MarkdownProcessor() }
      ProvideMarkdownStyling(markdownProcessor = processor) {
        Items(view, processor, onOpenFile, onDiffFile, onDiffRun)
      }
    }
  }
}

@Composable
private fun Breadcrumb(view: ThreadView?, project: Project?, favicon: ImageBitmap?) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (project != null) {
      // Upstream's ChatHeader: the favicon at 14px, then the name, then the separator
      ProjectIcon(project, favicon, 14.dp)
      Text(project.title, color = JewelTheme.globalColors.text.info, maxLines = 1)
      Text("/", color = JewelTheme.globalColors.text.info)
    }
    Text(
      text = view?.thread?.title.orEmpty(),
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun Items(
  view: ThreadView,
  processor: MarkdownProcessor,
  onOpenFile: (String) -> Unit,
  onDiffFile: (TurnItem.FileChange) -> Unit,
  onDiffRun: (Run) -> Unit,
) {
  val period: Long = if (view.runs.any { it.running }) 1_000 else 30_000
  val now: Instant by rememberNow(period)
  val rows: List<ThreadRow> = remember(view.runs, view.items) { threadRows(view.runs, view.items) }
  // Disclosure state survives items scrolling out of the lazy column
  val expanded: SnapshotStateMap<String, Boolean> = remember(view.thread.id) { mutableStateMapOf() }
  val state: LazyListState = rememberLazyListState()
  FollowTail(state, rows, view.items.lastOrNull())

  VerticallyScrollableContainer(scrollState = state, modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = state,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      items(rows, key = { it.key }) { row ->
        Box(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().padding(horizontal = 16.dp)) {
          when (row) {
            is ThreadRow.RunLine -> RunLine(row.run, now, onDiff = { onDiffRun(row.run) })
            is ThreadRow.Item -> {
              val isExpanded: Boolean = expanded[row.key] ?: false
              ItemRow(
                item = row.item,
                now = now,
                processor = processor,
                expanded = isExpanded,
                onToggle = { expanded[row.key] = !isExpanded },
                onOpenFile = onOpenFile,
                onDiffFile = onDiffFile,
              )
            }
          }
        }
      }
    }
  }
}

/** Scrolls to the end when rows change, but only if the user was already looking at the end. */
@Composable
private fun FollowTail(state: LazyListState, rows: List<ThreadRow>, last: TurnItem?) {
  val previousCount: MutableIntState = remember { mutableIntStateOf(0) }
  LaunchedEffect(rows.size, last) {
    val lastVisible: Int? = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
    val follow: Boolean = wasAtBottom(lastVisible, previousCount.intValue)
    previousCount.intValue = rows.size
    if (follow && rows.isNotEmpty()) state.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
  }
}

@Composable
private fun RunLine(run: Run, now: Instant, onDiff: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(runLabel(run, now), color = JewelTheme.globalColors.text.info, style = small())
    Divider(Orientation.Horizontal, Modifier.weight(1f))
    Link("Diff", onClick = onDiff, textStyle = small())
  }
}

@Composable
private fun ItemRow(
  item: TurnItem,
  now: Instant,
  processor: MarkdownProcessor,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenFile: (String) -> Unit,
  onDiffFile: (TurnItem.FileChange) -> Unit,
) {
  when (item) {
    is TurnItem.UserMessage -> UserBubble(item.text)

    is TurnItem.AssistantMessage -> Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
      MarkdownText(item.text, processor)
    }

    is TurnItem.Reasoning -> ToolRow(
      icon = AllIconsKeys.Actions.IntentionBulbGrey,
      summary = firstLine(item.text).ifEmpty { "Reasoning" },
      item = item,
      expanded = expanded,
      onToggle = onToggle,
    ) {
      Text(item.text, color = JewelTheme.globalColors.text.info)
    }

    is TurnItem.CommandExecution -> ToolRow(
      icon = AllIconsKeys.Nodes.Console,
      summary = item.title ?: firstLine(item.input),
      item = item,
      expanded = expanded,
      onToggle = onToggle,
    ) {
      Mono("$ ${item.input}")
      item.output?.trimEnd()?.takeIf { it.isNotEmpty() }?.let { Mono(it) }
      if (item.outputOmitted == true) Caption("Output omitted")
      item.exitCode?.let { code ->
        val tint: Color =
          if (code == 0L) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.error
        Text("exit $code", color = tint, style = small())
      }
    }

    is TurnItem.FileChange -> ToolRow(
      icon = AllIconsKeys.Actions.Edit,
      summary = item.fileName,
      item = item,
      expanded = expanded,
      onToggle = onToggle,
      trailing = {
        Caption(diffStat(item.additions, item.deletions))
        Link("Open", onClick = { onOpenFile(item.openPath()) }, textStyle = small())
        Link("Diff", onClick = { onDiffFile(item) }, textStyle = small())
      },
    ) {
      Text(item.fileName, maxLines = 1, overflow = TextOverflow.StartEllipsis)
      item.changes.orEmpty().forEach { change -> Caption("${change.operation} ${change.path}") }
    }

    is TurnItem.ApprovalRequest -> Card(item, now, label = "Approval: ${item.requestKind}") {
      item.prompt?.let { Text(it) }
      val options: List<String> = item.options.orEmpty().map { it.label }
      if (options.isNotEmpty()) Caption(options.joinToString(" · "))
      Caption("Respond in T3 Code")
    }

    is TurnItem.Error -> Card(item, now, label = "Error") {
      Text(item.failure.message, color = JewelTheme.globalColors.text.error)
      Caption(listOfNotNull(item.failure.`class`, item.failure.code).joinToString(" "))
    }

    is TurnItem.SystemNotice -> Card(item, now, label = "Notice") {
      Text(item.message, color = JewelTheme.globalColors.text.info)
    }

    // The fallback for anything this build doesn't know: type, and the raw JSON on demand
    else -> ToolRow(
      icon = AllIconsKeys.Actions.Lightning,
      summary = item.title ?: item.type,
      item = item,
      expanded = expanded,
      onToggle = onToggle,
    ) {
      if (item.title != null) Caption(item.type)
      val json: String = remember(item) { prettyJson(item.json()) }
      Mono(json)
    }
  }
}

@Composable
private fun UserBubble(text: String) {
  BoxWithConstraints(
    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Text(
      text = text,
      modifier = Modifier
        .widthIn(max = maxWidth * 0.7f)
        .background(subtle(0.07f), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
    )
  }
}

/** Tool activity as one dim line; the chevron opens the details underneath. */
@Composable
private fun ToolRow(
  icon: IconKey,
  summary: String,
  item: TurnItem,
  expanded: Boolean,
  onToggle: () -> Unit,
  trailing: @Composable RowScope.() -> Unit = {},
  details: @Composable ColumnScope.() -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .clickable(onClick = onToggle)
        .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null)
      Text(
        text = summary,
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (item.status !in QUIET) {
        Text(item.status, style = small(), color = JewelTheme.globalColors.text.warning)
      }
      trailing()
      val chevron: IconKey =
        if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight
      Icon(chevron, contentDescription = if (expanded) "Collapse" else "Expand")
    }
    if (expanded) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 24.dp, top = 2.dp, bottom = 6.dp)
          .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = details,
      )
    }
  }
}

@Composable
private fun Card(
  item: TurnItem,
  now: Instant,
  label: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = small(),
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (item.status !in QUIET) {
        Text(item.status, style = small(), color = JewelTheme.globalColors.text.warning)
      }
      val at: Instant? = item.time()
      if (at != null) {
        Text(relativeTime(at, now), style = small(), color = JewelTheme.globalColors.text.info)
      }
    }
    content()
  }
}

private val QUIET: Set<String> = setOf("completed", "")

private fun firstLine(text: String): String = text.trim().lineSequence().firstOrNull().orEmpty()

@Composable
private fun MarkdownText(text: String, processor: MarkdownProcessor) {
  val blocks: List<MarkdownBlock> = remember(text) { processor.processMarkdownDocument(text) }
  Markdown(markdownBlocks = blocks, markdown = text, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Mono(text: String) {
  Text(text = text, style = JewelTheme.editorTextStyle)
}

@Composable
private fun Caption(text: String) {
  Text(text, style = small(), color = JewelTheme.globalColors.text.info)
}

@Composable
private fun small(): TextStyle = JewelTheme.typography.small
