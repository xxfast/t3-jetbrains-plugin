package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultInlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.junit.jupiter.api.Test

class ThreadFindTest {
  private val processor = MarkdownProcessor(listOf(GitHubTableProcessorExtension))
  private val inlineStyling = InlinesStyling(
    TextStyle.Default, SpanStyle(), SpanStyle(), SpanStyle(), SpanStyle(), SpanStyle(),
    SpanStyle(), SpanStyle(), SpanStyle(), SpanStyle(), SpanStyle(),
  )
  private val renderInlines: (List<InlineMarkdown>) -> String = { content ->
    DefaultInlineMarkdownRenderer(emptyList()).renderAsAnnotatedString(content,
      inlineStyling, true, null).text
  }
  private val scenario: Scenario = Scenario.decode(Path.of(System.getProperty("t3code.scenarios"),
    "default", "many-items.json").readText())
  private val items: List<TurnItem> = scenario.items.values.flatten().map { it.item }
  private val assistant: TurnItem.AssistantMessage =
    items.filterIsInstance<TurnItem.AssistantMessage>().first()

  private fun documents(text: String): List<ThreadFindDocument> = threadFindDocuments(
    listOf(assistant.copy(text = text)), processor, renderInlines,
  )

  @Test
  fun `literal case-insensitive matching preserves UTF16 offsets`() {
    assertEquals(listOf(0, 7), findThreadSearchOccurrences("Orchid orchid", "ORCHID"))
    assertEquals(listOf(1), findThreadSearchOccurrences("banana", "ana"))
    assertEquals(listOf(2), findThreadSearchOccurrences("😀orchid", "orchid"))
    assertEquals(listOf(0, 2), findThreadSearchOccurrences("ς σ", "Σ"))
    assertEquals(emptyList(), findThreadSearchOccurrences("abc", ""))
    assertEquals(listOf(1), findThreadSearchOccurrences("[a.b]", "a.b"))
  }

  @Test
  fun `find searches rendered inline text without markdown syntax or link targets`() {
    val docs: List<ThreadFindDocument> = documents("An **orchid** across *inline formatting*. " +
      "[label](https://hidden.example)\n\nOther paragraph.")
    assertEquals(1, findThreadMatches(docs, "orchid across inline formatting").size)
    assertTrue(findThreadMatches(docs, "**").isEmpty())
    assertTrue(findThreadMatches(docs, "hidden.example").isEmpty())
    assertTrue(findThreadMatches(docs, "label Other").isEmpty())
  }

  @Test
  fun `code lists quotes and table cells are searchable offscreen`() {
    val docs: List<ThreadFindDocument> = documents(
      "> orchid quote\n\n- orchid list\n\n```kotlin\nval orchid = 1\n```\n\n" +
        "| Flower | Note |\n| --- | --- |\n| orchid | orchid |",
    )
    val found: List<ThreadFindMatch> = findThreadMatches(docs, "orchid")
    assertEquals(5, found.size)
    assertEquals(5, found.map { it.segment }.distinct().size)
    assertTrue(docs.single().segments.any { it.trimEnd() == "val orchid = 1" })
  }

  @Test
  fun `navigation wraps and closing clears the search`() {
    val find = ThreadFindState()
    find.updateDocuments(documents("orchid orchid orchid"))
    find.open()
    find.changeQuery("orchid")
    assertEquals("1/3", formatThreadFindCount(find.activeIndex, find.matches.size))
    find.step(-1)
    assertEquals(2, find.activeIndex)
    find.step(1)
    assertEquals(0, find.activeIndex)
    find.changeQuery("missing")
    assertNull(find.activeMatch)
    assertEquals("0/0", formatThreadFindCount(0, 0))
    find.step(1)
    find.close()
    assertFalse(find.isOpen)
    assertEquals("", find.query)
    assertTrue(find.matches.isEmpty())
  }

  @Test
  fun `streaming updates retain the selected occurrence`() {
    val find = ThreadFindState()
    find.open()
    find.updateDocuments(documents("orchid orchid"))
    find.changeQuery("orchid")
    find.step(1)
    val selected: ThreadFindMatch = assertNotNull(find.activeMatch)
    find.updateDocuments(documents("orchid orchid orchid"))
    assertEquals(selected, find.activeMatch)
    assertEquals(3, find.matches.size)
  }

  @Test
  fun `harness contains matches in folded messages and at both ends of the conversation`() {
    val docs: List<ThreadFindDocument> = threadFindDocuments(items, processor, renderInlines)
    val matches: List<ThreadFindMatch> = findThreadMatches(docs, "orchid")
    assertTrue(matches.size >= 5)
    assertEquals(assistant.id, matches.first().entryId)
    assertEquals(items.filterIsInstance<TurnItem.AssistantMessage>().last().id,
      matches.last().entryId)
    val entries: List<TimelineEntry> = deriveTimelineEntries(items)
    val runs = scenario.runs.values.flatten()
    val folded: List<TimelineRow> = deriveMessagesTimelineRows(entries, runs, null)
    assertTrue(folded.none { it.id == matches.first().entryId })
    val expanded: List<TimelineRow> = deriveMessagesTimelineRows(entries, runs, null,
      TimelineExpansion(runIds = setOfNotNull(matches.first().runId)))
    assertTrue(expanded.any { it.id == matches.first().entryId })
  }

  @Test
  fun `clipped user prompt is indexed through its final paragraph`() {
    val docs: List<ThreadFindDocument> = threadFindDocuments(items, processor, renderInlines)
    val matches: List<ThreadFindMatch> = findThreadMatches(docs, "hibiscus")
    assertEquals(1, matches.size)
    val user: TurnItem.UserMessage = items.filterIsInstance<TurnItem.UserMessage>()
      .first { it.id == matches.single().entryId }
    assertTrue(shouldCollapseUserMessage(user.text))
  }

  @Test
  fun `plan title and rendered body are both searchable`() {
    val docs: List<ThreadFindDocument> = threadFindDocuments(items, processor, renderInlines)
    val matches: List<ThreadFindMatch> = findThreadMatches(docs, "camellia")
    assertEquals(2, matches.size)
    assertTrue(matches.all { it.entryId == "find-plan" })
    assertEquals(0, matches.first().segment)
  }

  /** Jewel's table model is internal; read it reflectively here to replay its render order. */
  private fun cellContents(table: MarkdownBlock): List<List<InlineMarkdown>> {
    fun cells(row: Any): List<Any> = row.javaClass.getMethod("getCells").invoke(row) as List<Any>
    val header: Any = table.javaClass.getMethod("getHeader").invoke(table)
    val rows: List<Any> = table.javaClass.getMethod("getRows").invoke(table) as List<Any>
    @Suppress("UNCHECKED_CAST")
    return (listOf(header) + rows).flatMap(::cells)
      .map { it.javaClass.getMethod("getContent").invoke(it) as List<InlineMarkdown> }
  }

  @Test
  fun `repeated headings and table cells keep their own segments`() {
    val table = "| orchid | orchid |\n| --- | --- |\n| orchid | orchid |"
    val document: ThreadFindDocument = documents(
      "## orchid\n\n$table\n\n## orchid\n\n$table",
    ).single()
    val headings: List<MarkdownBlock.Heading> =
      document.blocks.filterIsInstance<MarkdownBlock.Heading>()
    assertEquals(2, headings.size)
    assertTrue(headings[0].inlineContent !== headings[1].inlineContent)
    val headingSegments: List<Int?> = headings.map { document.segmentFor(it.inlineContent) }
    assertEquals(2, headingSegments.filterNotNull().distinct().size)

    val tables: List<MarkdownBlock> = document.blocks.filterIsInstance<MarkdownBlock.CustomBlock>()
    assertEquals(2, tables.size)
    // The second table renders first; it still can't take the first table's cells
    val second: List<Int?> = cellContents(tables[1]).map { content ->
      document.bindTableCell(tables[1], content, "orchid")
    }
    val first: List<Int?> = cellContents(tables[0]).map { content ->
      document.bindTableCell(tables[0], content, "orchid")
    }
    assertEquals(document.tableCells.getValue(tables[0]), first)
    assertEquals(document.tableCells.getValue(tables[1]), second)
    assertTrue(first.all { it!! < headingSegments[1]!! })
    assertTrue(second.all { it!! > headingSegments[1]!! })

    // A recomposition rebinds nothing, even in another order or from the other table
    cellContents(tables[0]).reversed().forEach { content ->
      document.bindTableCell(tables[1], content, "orchid")
    }
    assertEquals(first, cellContents(tables[0]).map { document.segmentFor(it) })
    assertEquals(second, cellContents(tables[1]).map { document.segmentFor(it) })
    assertEquals(2 + 8, findThreadMatches(listOf(document), "orchid").map { it.segment }
      .distinct().size)
  }

  @Test
  fun `a table cell with other text never binds`() {
    val document: ThreadFindDocument = documents("| a | b |\n| --- | --- |\n| c | d |").single()
    val table: MarkdownBlock = document.blocks.single()
    val content: List<InlineMarkdown> = cellContents(table).first()
    assertNull(document.bindTableCell(table, content, "not a"))
    assertNull(document.segmentFor(content))
    assertEquals(document.tableCells.getValue(table).first(),
      document.bindTableCell(table, content, "a"))
  }

  @Test
  fun `selection stays on its occurrence when a message is inserted before it`() {
    val find = ThreadFindState()
    find.open()
    val later: TurnItem.AssistantMessage = assistant.copy(id = "later", text = "orchid orchid")
    find.updateDocuments(threadFindDocuments(listOf(later), processor, renderInlines))
    find.changeQuery("orchid")
    find.step(1)
    val selected: ThreadFindMatch = assertNotNull(find.activeMatch)
    val earlier: TurnItem.AssistantMessage = assistant.copy(id = "earlier", text = "orchid")
    find.updateDocuments(
      threadFindDocuments(listOf(earlier, later), processor, renderInlines, find.documents),
    )
    assertEquals(selected, find.activeMatch)
    assertEquals(2, find.activeIndex)
    assertEquals("3/3", formatThreadFindCount(find.activeIndex, find.matches.size))
  }

  @Test
  fun `highlight spans preserve text and existing annotations`() {
    val text: AnnotatedString = AnnotatedString.Builder("orchid orchid").apply {
      addStyle(SpanStyle(color = Color.Red), 0, 6)
      addStringAnnotation("link", "https://example.com", 0, 6)
    }.toAnnotatedString()
    val highlighted: AnnotatedString = highlightThreadFindText(text, "orchid", 7,
      Color.Yellow, Color.Green)
    assertEquals(text.text, highlighted.text)
    assertEquals(text.getStringAnnotations(0, text.length),
      highlighted.getStringAnnotations(0, highlighted.length))
    assertTrue(highlighted.spanStyles.any { it.item.background == Color.Green && it.start == 7 })
    assertTrue(highlighted.spanStyles.any { it.item.color == Color.Red })
  }
}
