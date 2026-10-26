package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension

/*
 * The timeline minimap's model and geometry, ported from upstream's
 * `apps/web/src/components/chat/timelineMinimapItems.ts` and the minimap half of
 * `apps/web/src/components/chat/MessagesTimeline.logic.ts`. Lengths upstream gives in px are dp.
 */

/** `TimelineMinimapItem`: one tick, a user message and the reply that ended its turn. */
data class TimelineMinimapItem(
  val id: String,
  val rowIndex: Int, // into the rows the timeline lays out
  val userText: String?,
  val assistantText: String?,
)

private const val TIMELINE_MINIMAP_ITEM_SPACING: Int = 8
const val TIMELINE_MINIMAP_MIN_ITEMS: Int = 2
private val TIMELINE_MINIMAP_MAX_HEIGHT_INSET: Dp = 288.dp // `calc(100vh - 18rem)`
private const val TIMELINE_MINIMAP_PERSISTENT_GUTTER: Int = 48
private const val TIMELINE_MINIMAP_HIT_STRIP_LEFT: Int = 12
private const val TIMELINE_MINIMAP_HIT_STRIP_MAX_WIDTH: Int = 40
private val TIMELINE_MINIMAP_EXPANDED_HIT_STRIP_WIDTH: Dp = 352.dp // 22rem
private const val TIMELINE_MINIMAP_NAVIGATION_REACH: Int = 14

/** `deriveTimelineMinimapItems`: a tick per user message, with its turn's final reply. */
fun deriveTimelineMinimapItems(rows: List<TimelineRow>): List<TimelineMinimapItem> {
  val items: MutableList<TimelineMinimapItem> = mutableListOf()
  for ((index, row) in rows.withIndex()) {
    if (row !is TimelineRow.Message || !row.entry.user) continue
    items += TimelineMinimapItem(
      id = row.id,
      rowIndex = index,
      userText = row.entry.text,
      assistantText = resolveFinalAssistantTextForTurn(rows, index),
    )
  }
  return items
}

/** `resolveFinalAssistantTextForTurn`: the last reply before the next user message. */
private fun resolveFinalAssistantTextForTurn(rows: List<TimelineRow>, userRowIndex: Int): String? {
  var finalAssistantText: String? = null
  for (index in userRowIndex + 1 until rows.size) {
    val row: TimelineRow.Message = rows[index] as? TimelineRow.Message ?: continue
    if (row.entry.user) break
    finalAssistantText = row.entry.text
  }
  return finalAssistantText
}

// remark-parse with remark-gfm: the same GFM the timeline's markdown reads
private val markdownPreviewParser: Parser = Parser.builder()
  .extensions(
    listOf(
      GitHubTableProcessorExtension,
      GitHubStrikethroughProcessorExtension(),
      AutolinkProcessorExtension,
    ).mapNotNull { it.parserExtension },
  )
  .build()

private val HTML_COMMENT: Regex = Regex("<!--[\\s\\S]*?(?:-->|$)")
private val HTML_TAG: Regex =
  Regex("</?([a-z][\\w-]*)(?:[^\"'<>]|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE)
private val HTML_BLOCK_TAG: Regex = Regex(
  "^(?:address|article|aside|blockquote|br|dd|details|div|dl|dt|figcaption|figure|footer|" +
    "h[1-6]|header|hr|li|main|nav|ol|p|pre|section|summary|table|tbody|td|th|thead|tr|ul)$",
  RegexOption.IGNORE_CASE,
)

private fun Node.isInlineParent(): Boolean = this is Paragraph || this is Heading ||
  this is TableCell || this is Emphasis || this is StrongEmphasis || this is Strikethrough ||
  this is Link

/**
 * `markdownNodeText`: a markdown tree as plain text. mdast keeps a soft break inside its text
 * node, commonmark makes it a node of its own; both come out as whitespace.
 */
private fun markdownNodeText(node: Node): String = when (node) {
  is HtmlInline -> stripHtml(node.literal)
  is HtmlBlock -> stripHtml(node.literal)
  is HardLineBreak, is SoftLineBreak -> " "
  is Text -> node.literal
  is Code -> node.literal
  is FencedCodeBlock -> node.literal
  is IndentedCodeBlock -> node.literal
  else -> {
    val children: List<String> = generateSequence(node.firstChild) { it.next }
      .map(::markdownNodeText)
      .toList()
    // An image's alt is its children, as inline text
    children.joinToString(if (node.isInlineParent() || node is Image) "" else " ")
  }
}

private fun stripHtml(html: String): String = html
  .replace(HTML_COMMENT, "")
  .replace(HTML_TAG) { match -> if (HTML_BLOCK_TAG.matches(match.groupValues[1])) " " else "" }

/** `compactMinimapPreview`: whitespace runs to one space, nothing left is null. */
fun compactMinimapPreview(text: String?): String? =
  text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

/** `resolveTimelineMinimapPreview`: the item with both texts compacted, the reply as text. */
fun resolveTimelineMinimapPreview(item: TimelineMinimapItem?): TimelineMinimapItem? =
  item?.copy(
    userText = compactMinimapPreview(item.userText),
    // The card shows three lines; bound parsing work while long responses stream
    assistantText = compactMinimapPreview(
      item.assistantText?.let { text ->
        markdownNodeText(markdownPreviewParser.parse(text.take(1_500)))
      },
    ),
  )

/** `resolveTimelineMinimapHeightStyle`, with [viewportHeight] for `100vh`. */
fun resolveTimelineMinimapHeightStyle(itemCount: Int, viewportHeight: Dp): Dp {
  val naturalHeight: Dp = maxOf(1, (itemCount - 1) * TIMELINE_MINIMAP_ITEM_SPACING).dp
  return min(naturalHeight, viewportHeight - TIMELINE_MINIMAP_MAX_HEIGHT_INSET)
    .coerceAtLeast(0.dp)
}

/** `resolveTimelineMinimapTopPercent`: where tick [index] sits down the rail, 0 to 100. */
fun resolveTimelineMinimapTopPercent(index: Int, itemCount: Int): Float {
  if (itemCount <= 1) return 0f
  return index.coerceIn(0, itemCount - 1).toFloat() / (itemCount - 1) * 100f
}

/** `resolveTimelineMinimapIndexFromPointer`: the tick nearest [pointerY] on a rail. */
fun resolveTimelineMinimapIndexFromPointer(
  itemCount: Int,
  railTop: Float,
  railHeight: Float,
  pointerY: Float,
): Int? {
  if (itemCount <= 0 || railHeight <= 0f) return null
  if (itemCount == 1) return 0
  val progress: Float = ((pointerY - railTop) / railHeight).coerceIn(0f, 1f)
  return Math.round(progress * (itemCount - 1)).coerceIn(0, itemCount - 1)
}

/** The rows on screen, first and last, upstream's `{ start, end }`. */
data class TimelineMinimapVisibleRange(val start: Int?, val end: Int?)

/**
 * `resolveTimelineMinimapVisibleRange`: the rows that overlap the viewport. The lazy list's laid
 * out rows stand in for LegendList's buffered range, their offsets for its positions.
 */
fun resolveTimelineMinimapVisibleRange(
  buffered: List<LazyListItemInfo>,
  viewportStart: Int,
  viewportEnd: Int,
): TimelineMinimapVisibleRange {
  var start: Int? = null
  var end: Int? = null
  for (row in buffered) {
    if (row.offset < viewportEnd && row.offset + maxOf(1, row.size) > viewportStart) {
      start = start ?: row.index
      end = row.index
    }
  }
  return TimelineMinimapVisibleRange(start, end)
}

/** `isTimelineMinimapRowVisible`. */
fun isTimelineMinimapRowVisible(rowIndex: Int, visibleStart: Int?, visibleEnd: Int?): Boolean =
  visibleStart != null && visibleEnd != null && rowIndex in visibleStart..visibleEnd

/** `resolveTimelineMinimapCurrentIndex`: the first tick in view, else the turn being read. */
fun resolveTimelineMinimapCurrentIndex(
  visibleStart: Int?,
  visibleEnd: Int?,
  rowIndices: List<Int>,
): Int? {
  if (visibleStart == null || visibleEnd == null) return null
  var precedingIndex: Int? = null
  for ((index, rowIndex) in rowIndices.withIndex()) {
    if (rowIndex >= visibleStart) {
      // Prefer the first visible user message, otherwise the turn being read
      return if (rowIndex <= visibleEnd) index else precedingIndex
    }
    precedingIndex = index
  }
  return precedingIndex
}

/** `resolveTimelineSideGutter`: the space either side of the content lane. */
private fun resolveTimelineSideGutter(viewportWidth: Float, contentWidth: Float): Float {
  if (!viewportWidth.isFinite() || viewportWidth <= 0f || !contentWidth.isFinite()) return 0f
  return maxOf(0f, (viewportWidth - minOf(viewportWidth, contentWidth)) / 2)
}

/** `resolveTimelineMinimapHasPersistentGutter`: room to keep the rail on, not just on hover. */
fun resolveTimelineMinimapHasPersistentGutter(viewportWidth: Float, contentWidth: Float): Boolean =
  resolveTimelineSideGutter(viewportWidth, contentWidth) >= TIMELINE_MINIMAP_PERSISTENT_GUTTER

/** `resolveTimelineMinimapHitStripWidth`: how much of the gutter takes the pointer, 0 for none. */
fun resolveTimelineMinimapHitStripWidth(viewportWidth: Float, contentWidth: Float): Int {
  val sideGutter: Float = resolveTimelineSideGutter(viewportWidth, contentWidth)
  return maxOf(
    0,
    minOf(
      TIMELINE_MINIMAP_HIT_STRIP_MAX_WIDTH,
      sideGutter.toInt() - TIMELINE_MINIMAP_HIT_STRIP_LEFT,
    ),
  )
}

/** `resolveTimelineMinimapNavigationInteractive`: whether previous and next take clicks. */
fun resolveTimelineMinimapNavigationInteractive(collapsedWidth: Int): Boolean =
  collapsedWidth >= TIMELINE_MINIMAP_NAVIGATION_REACH

/** `resolveTimelineMinimapInteractiveWidth`: the strip widens under the preview while it shows. */
fun resolveTimelineMinimapInteractiveWidth(collapsedWidth: Int, expanded: Boolean): Dp =
  if (expanded) TIMELINE_MINIMAP_EXPANDED_HIT_STRIP_WIDTH else collapsedWidth.dp
