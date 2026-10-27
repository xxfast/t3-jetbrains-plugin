package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.util.IdentityHashMap
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.WithChildBlocks
import org.jetbrains.jewel.markdown.WithInlineMarkdown
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor

/** `threadFind.ts`: stable source position, independent of which rows are on screen. */
data class ThreadFindMatch(
  val entryId: String,
  val runId: String?,
  val segment: Int,
  val start: Int,
  val end: Int,
)

/** A rendered text segment, split at block boundaries like upstream's `threadFindText.ts`. */
class ThreadFindDocument(
  val entryId: String,
  val runId: String?,
  val source: String,
  val title: String?,
  val blocks: List<MarkdownBlock>,
  val segments: List<String>,
  val inlineSegments: IdentityHashMap<Iterable<InlineMarkdown>, Int>,
  val blockSegments: IdentityHashMap<MarkdownBlock, Int>,
  val processor: MarkdownProcessor,
  /** Each table's cell segments, header then rows, keyed by the table block Jewel renders. */
  val tableCells: IdentityHashMap<MarkdownBlock, List<Int>> = IdentityHashMap(),
) {
  fun segmentFor(content: Iterable<InlineMarkdown>): Int? = inlineSegments[content]

  /**
   * Jewel's table model is internal, so a cell's inline content is only seen when [table] renders
   * it. Bind it to the first unbound cell of that table with the same text: Jewel lays a table out
   * header first, then row by row, so repeated cells bind in source order and stay bound by
   * identity across recompositions. Cells of another table can never take it.
   */
  fun bindTableCell(table: MarkdownBlock, content: Iterable<InlineMarkdown>, text: String): Int? {
    inlineSegments[content]?.let { return it }
    val cells: List<Int> = tableCells[table] ?: return null
    val bound: Set<Int> = inlineSegments.values.toSet()
    val index: Int = cells.firstOrNull { it !in bound && segments[it] == text } ?: return null
    inlineSegments[content] = index
    return index
  }
}

/** `foldThreadSearchCase`, shared/threadSearch.ts: preserve offsets when lowercasing expands. */
private fun foldThreadSearchCase(text: String): String = buildString {
  text.codePoints().forEach { codePoint ->
    val character = String(Character.toChars(codePoint))
    val lowered: String = character.lowercase().replace('ς', 'σ')
    append(if (lowered.length == character.length) lowered else character)
  }
}

/** Literal, case-insensitive and non-overlapping, as `findThreadSearchOccurrences` upstream. */
fun findThreadSearchOccurrences(text: String, query: String): List<Int> {
  if (text.isEmpty() || query.isEmpty()) return emptyList()
  val folded: String = foldThreadSearchCase(text)
  val needle: String = foldThreadSearchCase(query)
  return buildList {
    var cursor = 0
    while (cursor <= folded.length - needle.length) {
      val offset: Int = folded.indexOf(needle, cursor)
      if (offset < 0) break
      add(offset)
      cursor = offset + needle.length
    }
  }
}

/** `stepThreadFindIndex`, chat/threadFind.ts. */
fun stepThreadFindIndex(index: Int, total: Int, delta: Int): Int =
  if (total <= 0) 0 else Math.floorMod(index.coerceIn(0, total - 1) + delta, total)

/** `formatThreadFindCount`, chat/threadFind.ts. */
fun formatThreadFindCount(index: Int, total: Int): String =
  if (total <= 0) "0/0" else "${index.coerceIn(0, total - 1) + 1}/$total"

/** Index only messages and plans, matching server/orchestration-v2/ThreadFind.ts. */
fun threadFindDocuments(
  items: List<TurnItem>,
  processor: MarkdownProcessor,
  renderInlines: (List<InlineMarkdown>) -> String,
  previous: List<ThreadFindDocument> = emptyList(),
): List<ThreadFindDocument> {
  val cached: Map<String, ThreadFindDocument> = previous.associateBy { it.entryId }
  val searchable: Set<String> = deriveTimelineEntries(items)
    .filter { it is TimelineEntry.Message || it is TimelineEntry.Plan }.map { it.id }.toSet()
  return items.filter { it.id in searchable }.mapNotNull { item ->
    val source: String = when (item) {
      is TurnItem.UserMessage -> item.text
      is TurnItem.AssistantMessage -> item.text.ifEmpty {
        if (item.streaming) "" else "(empty response)"
      }
      is TurnItem.ProposedPlan -> item.markdown
      else -> return@mapNotNull null
    }
    val title: String? = (item as? TurnItem.ProposedPlan)?.let { it.title ?: "Plan" }
    val old: ThreadFindDocument? = cached[item.id]
    if (old?.source == source && old.title == title && old.runId == item.runId &&
      old.processor === processor) {
      return@mapNotNull old
    }
    val blocks: List<MarkdownBlock> = processor.processMarkdownDocument(source)
    val segments: MutableList<String> = mutableListOf()
    val inlines = IdentityHashMap<Iterable<InlineMarkdown>, Int>()
    val blockSegments = IdentityHashMap<MarkdownBlock, Int>()
    val tables = IdentityHashMap<MarkdownBlock, List<Int>>()
    // Only tables are custom blocks in the chat processor. Read their cells through CommonMark's
    // public AST, since Jewel keeps TableBlock and TableCell internal on the minimum platform.
    val tableCells: MutableList<List<String>> = mutableListOf()
    val ast: Node = Parser.builder().extensions(listOf(TablesExtension.create()))
      .build().parse(source)
    fun plainText(node: Node): String = when (node) {
      is org.commonmark.node.Text -> node.literal
      is org.commonmark.node.Code -> node.literal
      is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> " "
      else -> buildString {
        var child: Node? = node.firstChild
        while (child != null) {
          append(plainText(child))
          child = child.next
        }
      }
    }
    fun visitTable(node: Node) {
      if (node is TableBlock) {
        val cells: MutableList<String> = mutableListOf()
        fun collectCells(child: Node) {
          if (child is TableCell) cells.add(plainText(child))
          else {
            var current: Node? = child.firstChild
            while (current != null) {
              collectCells(current)
              current = current.next
            }
          }
        }
        collectCells(node)
        tableCells.add(cells)
      } else {
        var child: Node? = node.firstChild
        while (child != null) {
          visitTable(child)
          child = child.next
        }
      }
    }
    visitTable(ast)
    var tableIndex = 0
    if (title != null) segments.add(title)
    fun inlineSegment(content: List<InlineMarkdown>) {
      inlines[content] = segments.size
      segments.add(renderInlines(content))
    }
    fun visit(block: MarkdownBlock) {
      when (block) {
        is WithInlineMarkdown -> inlineSegment(block.inlineContent)
        is MarkdownBlock.CodeBlock -> {
          blockSegments[block] = segments.size
          segments.add(block.content)
        }
        is WithChildBlocks -> block.children.forEach(::visit)
        is MarkdownBlock.CustomBlock -> {
          tables[block] = tableCells.getOrNull(tableIndex++).orEmpty().map { cell ->
            segments.add(cell)
            segments.lastIndex
          }
        }
        else -> Unit
      }
    }
    blocks.forEach(::visit)
    return@mapNotNull ThreadFindDocument(
      item.id, item.runId, source, title, blocks, segments, inlines, blockSegments,
      processor, tables,
    )
  }
}

/** Local index of the full projected conversation, including folded and offscreen rows. */
fun findThreadMatches(documents: List<ThreadFindDocument>, query: String): List<ThreadFindMatch> {
  val needle: String = query.trim()
  if (needle.isEmpty()) return emptyList()
  return documents.flatMap { document ->
    document.segments.flatMapIndexed { segment, text ->
      findThreadSearchOccurrences(text, needle).map { start ->
        ThreadFindMatch(document.entryId, document.runId, segment, start, start + needle.length)
      }
    }
  }
}

/** Ephemeral per-tab find state. Query and input never enter the persisted editor state. */
class ThreadFindState {
  var isOpen: Boolean by mutableStateOf(false)
    private set
  var query: String by mutableStateOf("")
    private set
  var documents: List<ThreadFindDocument> by mutableStateOf(emptyList())
    private set
  private val found = derivedStateOf { findThreadMatches(documents, query) }
  val matches: List<ThreadFindMatch> get() = if (isOpen) found.value else emptyList()
  var activeIndex: Int by mutableIntStateOf(0)
    private set
  val activeMatch: ThreadFindMatch? get() = matches.getOrNull(activeIndex)
  var focusRequest: Int by mutableIntStateOf(0)
    private set
  var navigation: Int by mutableIntStateOf(0)
    private set

  fun open() {
    isOpen = true
    focusRequest += 1
  }

  fun close() {
    isOpen = false
    query = ""
    activeIndex = 0
  }

  fun changeQuery(value: String) {
    if (query == value.take(200)) return
    query = value.take(200)
    activeIndex = 0
    navigation += 1
  }

  fun updateDocuments(value: List<ThreadFindDocument>) {
    val selected: ThreadFindMatch? = activeMatch
    documents = value
    activeIndex = selected?.let { matches.indexOf(it).takeIf { index -> index >= 0 } }
      ?: activeIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
  }

  fun step(delta: Int) {
    activeIndex = stepThreadFindIndex(activeIndex, matches.size, delta)
    navigation += 1
  }
}
