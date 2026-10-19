package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.typography

@Composable
fun ThreadScreen(
  view: ThreadView?, // null until the first snapshot arrives
  onBack: () -> Unit,
  onOpenFile: (path: String) -> Unit, // "Open" on a file_change card
  onDiffFile: (TurnItem.FileChange) -> Unit, // "Diff" on a file_change card
  onDiffRun: (Run) -> Unit, // "Diff" on a run header
  header: @Composable () -> Unit = {}, // the tool window's existing header slot
) {
  Column(Modifier.fillMaxSize()) {
    header()
    TopBar(view, onBack)
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    if (view == null) {
      Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text("Loading…", color = JewelTheme.globalColors.text.info, textAlign = TextAlign.Center)
      }
    } else {
      val processor: MarkdownProcessor = remember { MarkdownProcessor() }
      ProvideMarkdownStyling(markdownProcessor = processor) {
        Items(view, processor, onOpenFile, onDiffFile, onDiffRun)
      }
    }
  }
}

@Composable
private fun TopBar(view: ThreadView?, onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Link("Back", onClick = onBack)
    Text(
      text = view?.thread?.title.orEmpty(),
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (view != null) StatusBadge(view.thread.badge())
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
  val now: Instant by rememberNow()
  val rows: List<ThreadRow> = remember(view.runs, view.items) { threadRows(view.runs, view.items) }
  // Disclosure state survives items scrolling out of the lazy column
  val expanded: SnapshotStateMap<String, Boolean> = remember(view.thread.id) { mutableStateMapOf() }
  val state: LazyListState = rememberLazyListState()
  FollowTail(state, rows, view.items.lastOrNull())

  VerticallyScrollableContainer(scrollState = state, modifier = Modifier.fillMaxSize()) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
      items(rows, key = { it.key }) { row ->
        when (row) {
          is ThreadRow.RunHeader -> RunHeader(row.run, onDiff = { onDiffRun(row.run) })
          is ThreadRow.Item -> {
            val isExpanded: Boolean = expanded[row.key] ?: false
            ItemCard(
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
private fun RunHeader(run: Run, onDiff: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "Run ${run.ordinal}",
      color = JewelTheme.globalColors.text.info,
      style = JewelTheme.typography.small,
    )
    Text(
      text = run.status,
      color = JewelTheme.globalColors.text.info,
      style = JewelTheme.typography.small,
      modifier = Modifier.weight(1f),
    )
    Link("Diff", onClick = onDiff, textStyle = JewelTheme.typography.small)
  }
}

@Composable
private fun ItemCard(
  item: TurnItem,
  now: Instant,
  processor: MarkdownProcessor,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenFile: (String) -> Unit,
  onDiffFile: (TurnItem.FileChange) -> Unit,
) {
  when (item) {
    is TurnItem.UserMessage -> Card(item, now, label = "You") {
      Text(item.text)
    }

    is TurnItem.AssistantMessage -> Card(item, now, label = "Assistant") {
      MarkdownText(item.text, processor)
    }

    is TurnItem.Reasoning -> Card(
      item = item,
      now = now,
      label = "Reasoning",
      action = { Link(if (expanded) "Hide" else "Show", onClick = onToggle, textStyle = small()) },
    ) {
      if (expanded) Text(item.text, color = JewelTheme.globalColors.text.info)
    }

    is TurnItem.CommandExecution -> Card(item, now, label = item.title ?: "Command") {
      Mono("$ ${item.input}")
      val output: String? = item.output?.trimEnd()?.takeIf { it.isNotEmpty() }
      if (output != null) {
        Mono(output, maxLines = if (expanded) Int.MAX_VALUE else 12)
        if (output.lines().size > 12) {
          Link(if (expanded) "Less" else "More", onClick = onToggle, textStyle = small())
        }
      }
      if (item.outputOmitted == true) Caption("Output omitted")
      item.exitCode?.let { code ->
        val tint: Color =
          if (code == 0L) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.error
        Text("exit $code", color = tint, style = small())
      }
    }

    is TurnItem.FileChange -> Card(
      item = item,
      now = now,
      label = "File change",
      action = {
        Link("Open", onClick = { onOpenFile(item.openPath()) }, textStyle = small())
        Link("Diff", onClick = { onDiffFile(item) }, textStyle = small())
      },
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = item.fileName,
          modifier = Modifier.weight(1f, fill = false),
          maxLines = 1,
          overflow = TextOverflow.StartEllipsis,
        )
        Text(diffStat(item.additions, item.deletions), color = JewelTheme.globalColors.text.info)
      }
      item.changes.orEmpty().forEach { change ->
        Caption("${change.operation} ${change.path}")
      }
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

    else -> GenericCard(item, now, expanded, onToggle)
  }
}

/** The fallback for anything this build doesn't know: type, time, and the raw JSON on demand. */
@Composable
private fun GenericCard(
  item: TurnItem,
  now: Instant,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  Card(
    item = item,
    now = now,
    label = item.title ?: item.type,
    action = { Link(if (expanded) "Hide JSON" else "JSON", onClick = onToggle, textStyle = small()) },
  ) {
    if (item.title != null) Caption(item.type)
    if (expanded) {
      val json: String = remember(item) { prettyJson(item.json()) }
      Mono(json)
    }
  }
}

@Composable
private fun Card(
  item: TurnItem,
  now: Instant,
  label: String,
  action: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
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
      action()
      val at: Instant? = item.time()
      if (at != null) {
        Text(relativeTime(at, now), style = small(), color = JewelTheme.globalColors.text.info)
      }
    }
    content()
  }
}

private val QUIET: Set<String> = setOf("completed", "")

@Composable
private fun MarkdownText(text: String, processor: MarkdownProcessor) {
  val blocks: List<MarkdownBlock> = remember(text) { processor.processMarkdownDocument(text) }
  Markdown(markdownBlocks = blocks, markdown = text, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Mono(text: String, maxLines: Int = Int.MAX_VALUE) {
  Text(
    text = text,
    style = JewelTheme.editorTextStyle,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun Caption(text: String) {
  Text(text, style = small(), color = JewelTheme.globalColors.text.info)
}

@Composable
private fun small(): TextStyle = JewelTheme.typography.small

@Composable
private fun rememberNow(): State<Instant> = produceState(Instant.now()) {
  while (true) {
    delay(30_000)
    value = Instant.now()
  }
}
