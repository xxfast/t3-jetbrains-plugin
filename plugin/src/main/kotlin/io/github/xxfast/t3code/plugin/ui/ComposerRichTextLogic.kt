package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.text.TextRange

// The rich text composer, upstream's `createComposerExtensions(true)`
// (apps/web/src/components/ComposerPromptEditorTiptap.tsx). Upstream turns the markdown into
// Tiptap nodes and writes it back out; here the draft stays the markdown that gets sent and
// formatting is drawn over it. So the block grammar is `buildTiptapContent`'s
// (apps/web/src/composer-rich-text-doc.ts), the marks are `parseInlineMarkdown`'s
// (apps/web/src/composer-rich-text.ts), and an input rule that only exists to turn typed
// markdown into a node is a rule for when a line renders as that block instead.

/** `RichTextMark`, in `MARK_NESTING_ORDER`. */
enum class ComposerRichMark { STRIKE, BOLD, ITALIC, CODE }

/** A run of shown text, [start] to [end] in the source, with its marks. */
data class ComposerRichSpan(val start: Int, val end: Int, val marks: List<ComposerRichMark>)

/**
 * A styled range with its delimiters, `StyledRange` in collectStyledRanges: [start] to [end]
 * takes the markers in, [markers] are the delimiters' ranges.
 */
data class ComposerRichStyledRange(val start: Int, val end: Int, val markers: List<IntRange>)

/** What [parseComposerInlineMarkdown] finds in one line. */
data class ComposerInlineMarkdown(
  val spans: List<ComposerRichSpan>,
  val styled: List<ComposerRichStyledRange>,
)

private val WHITESPACE: Regex = Regex("\\s")
private val WORD: Regex = Regex("\\w")

/**
 * `parseInlineMarkdown` (apps/web/src/composer-rich-text.ts) over [text] from [from] to [to],
 * keeping where each span and delimiter sits in the source. Same stack, same open and close
 * rules: `**` bold, `*` or `_` italic, `~~` strike, a single backtick code span on one line.
 */
fun parseComposerInlineMarkdown(
  text: String,
  from: Int = 0,
  to: Int = text.length,
): ComposerInlineMarkdown {
  class Frame(val delimiter: String, val mark: ComposerRichMark, val openAt: Int) {
    val spans: MutableList<ComposerRichSpan> = mutableListOf()
  }

  val root: MutableList<ComposerRichSpan> = mutableListOf()
  val stack: ArrayDeque<Frame> = ArrayDeque()
  val pairs: MutableList<Pair<IntRange, IntRange>> = mutableListOf()
  fun current(): MutableList<ComposerRichSpan> = stack.lastOrNull()?.spans ?: root
  fun push(start: Int, end: Int, marks: List<ComposerRichMark> = emptyList()) {
    if (end > start) current() += ComposerRichSpan(start, end, marks)
  }
  fun charAt(index: Int): String =
    if (index in from until to) text[index].toString() else ""

  var index: Int = from
  while (index < to) {
    val char: Char = text[index]
    if (char == '\\') {
      push(index, minOf(index + 2, to))
      index += 2
      continue
    }
    if (char == '`') {
      var runEnd: Int = index
      while (runEnd < to && text[runEnd] == '`') runEnd++
      val run: Int = runEnd - index
      val close: Int = text.indexOf("`".repeat(run), runEnd).takeIf { it in 0 until to } ?: -1
      if (
        run == 1 &&
        close > index + 1 &&
        '\n' !in text.substring(index, close) &&
        charAt(close + 1) != "`"
      ) {
        push(index + 1, close, listOf(ComposerRichMark.CODE))
        pairs += (index..index) to (close..close)
        index = close + 1
      } else {
        push(index, runEnd)
        index = runEnd
      }
      continue
    }
    if (char != '*' && char != '_' && char != '~') {
      push(index, index + 1)
      index++
      continue
    }
    var end: Int = index
    while (end < to && text[end] == char) end++
    val before: String = charAt(index - 1)
    val after: String = charAt(end)
    val canClose: Boolean = before.isNotEmpty() && !WHITESPACE.matches(before) &&
      (char != '_' || !WORD.matches(after))
    val canOpen: Boolean = after.isNotEmpty() && !WHITESPACE.matches(after) &&
      (char != '_' || !WORD.matches(before))
    while (index < end) {
      val top: Frame? = stack.lastOrNull()
      val opensNestedBold: Boolean = top?.mark == ComposerRichMark.ITALIC &&
        end - index == 2 &&
        canOpen &&
        (char == '*' || char == '_') &&
        stack.none { it.mark == ComposerRichMark.BOLD }
      if (
        !opensNestedBold &&
        canClose &&
        top != null &&
        text.startsWith(top.delimiter, index) &&
        index + top.delimiter.length <= end
      ) {
        stack.removeLast()
        for (span in top.spans) push(span.start, span.end, listOf(top.mark) + span.marks)
        val length: Int = top.delimiter.length
        pairs += (top.openAt until top.openAt + length) to (index until index + length)
        index += length
      } else if (canOpen && (char != '~' || end - index >= 2)) {
        val length: Int = if (char == '~' || end - index >= 2) 2 else 1
        val mark: ComposerRichMark = when {
          char == '~' -> ComposerRichMark.STRIKE
          length == 2 -> ComposerRichMark.BOLD
          else -> ComposerRichMark.ITALIC
        }
        // A mark can't nest inside itself, including alternate delimiters
        if (stack.any { it.mark == mark }) {
          push(index, end)
          index = end
          continue
        }
        stack.addLast(Frame(char.toString().repeat(length), mark, index))
        index += length
      } else {
        push(index, end)
        index = end
      }
    }
  }
  while (stack.isNotEmpty()) {
    val frame: Frame = stack.removeLast()
    push(frame.openAt, frame.openAt + frame.delimiter.length)
    for (span in frame.spans) push(span.start, span.end, span.marks)
  }
  return ComposerInlineMarkdown(root.sortedBy { it.start }, styledRanges(pairs))
}

// `collectStyledRanges`: delimiter pairs that nest join one range, ones that only touch don't
private fun styledRanges(pairs: List<Pair<IntRange, IntRange>>): List<ComposerRichStyledRange> {
  val ranges: MutableList<ComposerRichStyledRange> = mutableListOf()
  for ((open, close) in pairs.sortedBy { it.first.first }) {
    val last: ComposerRichStyledRange? = ranges.lastOrNull()
    if (last != null && open.first < last.end) {
      ranges[ranges.lastIndex] = last.copy(
        end = maxOf(last.end, close.last + 1),
        markers = (last.markers + listOf(open, close)).sortedBy { it.first },
      )
    } else {
      ranges += ComposerRichStyledRange(open.first, close.last + 1, listOf(open, close))
    }
  }
  return ranges
}

/** `parseOpeningFence`: a fence line's run and info string, or null. */
data class ComposerOpeningFence(val fence: String, val language: String)

private val OPENING_FENCE: Regex = Regex("^(`{3,}|~{3,})([^\\n]*)$")
private val CLOSING_FENCE: Regex = Regex("^(`{3,}|~{3,})[ \\t]*\\r?$")

fun parseOpeningFence(line: String): ComposerOpeningFence? {
  val match: MatchResult = OPENING_FENCE.find(line) ?: return null
  val fence: String = match.groupValues[1]
  val language: String = match.groupValues[2]
  if (fence.startsWith("`") && '`' in language) return null
  return ComposerOpeningFence(fence, language)
}

/** `isClosingFence`. */
fun isClosingFence(line: String, fence: String): Boolean {
  val match: MatchResult = CLOSING_FENCE.find(line) ?: return false
  val run: String = match.groupValues[1]
  return run[0] == fence[0] && run.length >= fence.length
}

/** `ListLinePrefix`'s kind. */
enum class ComposerListKind { TASK, BULLET, ORDERED }

/**
 * `ListLinePrefix`: [marker] is `-` for a task, [space] the spaces after the marker (a task's
 * `markerSpace`), [contentSpace] those after a task's `[ ]`, [length] the whole prefix.
 */
data class ComposerListPrefix(
  val kind: ComposerListKind,
  val indent: String,
  val marker: String,
  val space: String,
  val length: Int,
  val checked: Boolean = false,
  val contentSpace: String = "",
)

private val TASK_PREFIX: Regex = Regex("^([ \\t]*)-([ \\t]+)\\[([ xX])\\]([ \\t]*)")
private val ORDERED_PREFIX: Regex = Regex("^([ \\t]*)(\\d+[.)])((?:[ \\t]+)|$)")
private val BULLET_PREFIX: Regex = Regex("^([ \\t]*)([-*+])((?:[ \\t]+)|$)")

/** `parseListPrefix`: a task first (it also looks like a bullet), then ordered, then bullet. */
fun parseListPrefix(line: String): ComposerListPrefix? {
  TASK_PREFIX.find(line)?.let { match ->
    val after: String = line.substring(match.value.length)
    if (after.isEmpty() || match.groupValues[4].isNotEmpty()) {
      return ComposerListPrefix(
        kind = ComposerListKind.TASK,
        indent = match.groupValues[1],
        marker = "-",
        space = match.groupValues[2],
        length = match.value.length,
        checked = match.groupValues[3].lowercase() == "x",
        contentSpace = match.groupValues[4],
      )
    }
  }
  ORDERED_PREFIX.find(line)?.let { match ->
    return ComposerListPrefix(
      kind = ComposerListKind.ORDERED,
      indent = match.groupValues[1],
      marker = match.groupValues[2],
      space = match.groupValues[3],
      length = match.value.length,
    )
  }
  BULLET_PREFIX.find(line)?.let { match ->
    return ComposerListPrefix(
      kind = ComposerListKind.BULLET,
      indent = match.groupValues[1],
      marker = match.groupValues[2],
      space = match.groupValues[3],
      length = match.value.length,
    )
  }
  return null
}

/** `nextOrderedMarkerText` (apps/web/src/composer-list-continuation.ts). */
fun nextOrderedMarkerText(marker: String): String {
  val numberText: String = marker.dropLast(1)
  val delimiter: String = marker.takeLast(1)
  val next: String = numberText.toLongOrNull()
    ?.let { (it + 1).toString().padStart(numberText.length, '0') }
    ?: numberText
  return "$next$delimiter"
}

/** One block of the draft as `buildTiptapContent` reads it. [start] to [end] is its source. */
sealed interface ComposerRichBlock {
  val start: Int
  val end: Int

  /** Where the inline content starts, or [end] for a block without any. */
  val contentStart: Int

  data class Paragraph(override val start: Int, override val end: Int) : ComposerRichBlock {
    override val contentStart: Int get() = start
  }

  data class Heading(
    override val start: Int,
    override val end: Int,
    override val contentStart: Int,
    val level: Int,
  ) : ComposerRichBlock

  /** One `> ` line; consecutive ones make one quote. */
  data class Quote(
    override val start: Int,
    override val end: Int,
    override val contentStart: Int,
    val prefix: String,
  ) : ComposerRichBlock

  data class Rule(override val start: Int, override val end: Int) : ComposerRichBlock {
    override val contentStart: Int get() = end
  }

  data class ListItem(
    override val start: Int,
    override val end: Int,
    val prefix: ComposerListPrefix,
  ) : ComposerRichBlock {
    override val contentStart: Int get() = start + prefix.length
  }

  /**
   * A fenced block: the fence line ends at [openEnd], the code runs [bodyStart] to [bodyEnd]
   * and the closing fence line starts at [closeStart]. [info] is the fence's info string.
   */
  data class CodeBlock(
    override val start: Int,
    override val end: Int,
    val fence: String,
    val info: String,
    val openEnd: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val closeStart: Int,
  ) : ComposerRichBlock {
    override val contentStart: Int get() = bodyStart
  }
}

private val RULE_LINE: Regex = Regex("^([-*_])(?:[ \\t]*\\1){2,}[ \\t]*\\r?$")
private val HEADING_LINE: Regex = Regex("^(#{1,6})([ \\t]+)([^\\n]*)$")
private val QUOTE_LINE: Regex = Regex("^(>[ \\t]*)([^\\n]*)$")
private val PARTIAL_TASK: Regex = Regex("^\\[[ xX]?]?$")

/** The [start] to [end] (exclusive of its `\n`) of each line in [text]. */
fun composerLineRanges(text: String): List<IntRange> {
  val lines: MutableList<IntRange> = mutableListOf()
  var start = 0
  while (true) {
    val newline: Int = text.indexOf('\n', start)
    if (newline == -1) {
      lines += start until text.length
      return lines
    }
    lines += start until newline
    start = newline + 1
  }
}

/**
 * The blocks of [text], `buildTiptapContent`'s loop: a fence, a rule, a heading, a quote line,
 * a list line, else a paragraph. Each rule only reads the start of a line, so a heading or
 * fence inside a list item or quote stays that item's text, as upstream's input rules refuse
 * anything that isn't a top-level paragraph.
 *
 * Where upstream's typing and its rebuild disagree, typing wins, since that's what's on screen:
 * - A fence only opens a block once it's closed. Upstream's fence stays a paragraph until Enter
 *   (`convertCodeFenceOnEnter`), which writes the closing fence.
 * - `***` and `___` need a space after them (`horizontalRuleInputRule`); `---` doesn't.
 * - A quote needs the space after `>` (`blockquoteInputRule`).
 * - See [listLineShows] for lists.
 */
fun composerRichTextBlocks(text: String): List<ComposerRichBlock> {
  val lines: List<IntRange> = composerLineRanges(text)
  val blocks: MutableList<ComposerRichBlock> = mutableListOf()
  var index = 0
  while (index < lines.size) {
    val range: IntRange = lines[index]
    val start: Int = range.first
    val end: Int = range.last + 1
    val line: String = text.substring(start, end)
    val opening: ComposerOpeningFence? = parseOpeningFence(line)
    if (opening != null) {
      val close: Int = (index + 1 until lines.size).firstOrNull { candidate ->
        val closeRange: IntRange = lines[candidate]
        isClosingFence(text.substring(closeRange.first, closeRange.last + 1), opening.fence)
      } ?: -1
      if (close != -1) {
        val closeRange: IntRange = lines[close]
        val bodyStart: Int = if (close > index + 1) lines[index + 1].first else end
        val bodyEnd: Int = if (close > index + 1) lines[close - 1].last + 1 else end
        blocks += ComposerRichBlock.CodeBlock(
          start = start,
          end = closeRange.last + 1,
          fence = opening.fence,
          info = opening.language,
          openEnd = end,
          bodyStart = bodyStart,
          bodyEnd = bodyEnd,
          closeStart = closeRange.first,
        )
        index = close + 1
        continue
      }
    }
    blocks += lineBlock(text, start, end, line, blocks.lastOrNull())
    index++
  }
  return blocks
}

private fun lineBlock(
  text: String,
  start: Int,
  end: Int,
  line: String,
  previous: ComposerRichBlock?,
): ComposerRichBlock {
  val rule: Boolean = RULE_LINE.matches(line) &&
    (line[0] == '-' || line.any { it == ' ' || it == '\t' })
  if (rule) return ComposerRichBlock.Rule(start, end)
  HEADING_LINE.find(line)?.let { match ->
    val prefix: Int = match.groupValues[1].length + match.groupValues[2].length
    return ComposerRichBlock.Heading(start, end, start + prefix, match.groupValues[1].length)
  }
  // `blockquoteInputRule` fires on the space after `>`, so `>foo` stays text
  QUOTE_LINE.find(line)?.takeIf { it.groupValues[1].length > 1 }?.let { match ->
    val prefix: String = match.groupValues[1]
    return ComposerRichBlock.Quote(start, end, start + prefix.length, prefix)
  }
  val list: ComposerListPrefix? = parseListPrefix(line)
  // The line just above is a list item, as after Enter split one (`splitOrLiftListItem`)
  val continues: Boolean = previous is ComposerRichBlock.ListItem && previous.end == start - 1
  if (list != null && listLineShows(list, text.substring(start + list.length, end), continues)) {
    return ComposerRichBlock.ListItem(start, end, list)
  }
  return ComposerRichBlock.Paragraph(start, end)
}

/**
 * Whether a list line renders as an item yet, the input rules of `createComposerExtensions`
 * as render rules. `* `, `+ ` and `1. ` (or `1) `) start a list on their space
 * (`listMarkerInputRule`). `- ` waits for the next character and carries it in, so `- [ ] `
 * can be typed whole; a `[` there waits for the task rule. `- [ ] ` starts a task on its space
 * (`taskInputRule`). A line under an item is an item at once, as Enter makes one.
 */
fun listLineShows(prefix: ComposerListPrefix, content: String, continues: Boolean): Boolean {
  if (continues) return true
  return when {
    prefix.kind == ComposerListKind.TASK ->
      content.isNotEmpty() || prefix.contentSpace.isNotEmpty()

    prefix.kind == ComposerListKind.BULLET && prefix.marker == "-" ->
      content.isNotEmpty() && !PARTIAL_TASK.matches(content)

    else -> prefix.space.isNotEmpty()
  }
}

/**
 * A block prefix the field draws as something else (a heading's `# `, a quote's `> `, a list
 * marker, a whole rule line): the caret never sits in [start] until [end], since upstream's
 * prefixes aren't text.
 */
data class ComposerRichPrefix(val start: Int, val end: Int)

/** [text] parsed once: its blocks, each line's marks, and the prefixes. */
class ComposerRichDocument(val text: String) {
  val blocks: List<ComposerRichBlock> = composerRichTextBlocks(text)

  val prefixes: List<ComposerRichPrefix> = blocks.mapNotNull { block ->
    when (block) {
      is ComposerRichBlock.Heading,
      is ComposerRichBlock.Quote,
      is ComposerRichBlock.ListItem,
      -> ComposerRichPrefix(block.start, block.contentStart)

      is ComposerRichBlock.Rule -> ComposerRichPrefix(block.start, block.end)
      else -> null
    }
  }

  fun blockAt(offset: Int): ComposerRichBlock? =
    blocks.find { offset >= it.start && offset <= it.end }

  fun codeBlockAt(offset: Int): ComposerRichBlock.CodeBlock? =
    blocks.filterIsInstance<ComposerRichBlock.CodeBlock>()
      .find { offset >= it.start && offset <= it.end }
}

/**
 * The marks in [document]'s inline content, line by line as `buildInline` runs, with [chips]'
 * sources masked so their markdown-looking characters stay out of it (upstream's sentinel).
 */
fun composerInlineMarkdown(
  document: ComposerRichDocument,
  chips: List<ComposerPromptChip>,
): ComposerInlineMarkdown {
  val masked: String = if (chips.isEmpty()) {
    document.text
  } else {
    val chars: CharArray = document.text.toCharArray()
    for (chip in chips) for (index in chip.start until chip.end) chars[index] = '￼'
    String(chars)
  }
  val spans: MutableList<ComposerRichSpan> = mutableListOf()
  val styled: MutableList<ComposerRichStyledRange> = mutableListOf()
  for (block in document.blocks) {
    if (block is ComposerRichBlock.CodeBlock || block is ComposerRichBlock.Rule) continue
    val parsed: ComposerInlineMarkdown =
      parseComposerInlineMarkdown(masked, block.contentStart, block.end)
    spans += parsed.spans.filter { it.marks.isNotEmpty() }
    styled += parsed.styled
  }
  return ComposerInlineMarkdown(spans, styled)
}

// Edits as upstream's commands make them, on the markdown

/** Replace [start] to [end] with [text], then the selection is [selection]. */
data class ComposerPromptEdit(
  val start: Int,
  val end: Int,
  val text: String,
  val selection: TextRange,
)

/** [old] becoming [new], as one edit over the part that changed. */
private fun editBetween(old: String, new: String, selection: TextRange): ComposerPromptEdit {
  var prefix = 0
  val max: Int = minOf(old.length, new.length)
  while (prefix < max && old[prefix] == new[prefix]) prefix++
  var suffix = 0
  while (
    suffix < max - prefix &&
    old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
  ) {
    suffix++
  }
  return ComposerPromptEdit(
    start = prefix,
    end = old.length - suffix,
    text = new.substring(prefix, new.length - suffix),
    selection = selection,
  )
}

/** The edit that applies [edit] to [text]. */
fun ComposerPromptEdit.applyTo(text: String): String =
  text.substring(0, start) + this.text + text.substring(end)

/**
 * Enter as a line break, which is Shift or Alt+Enter here as upstream: a plain Enter sends,
 * even in a list (`onComposerCommandKey` sends before it gets to the list). In a list item
 * `splitOrLiftListItem`: an empty item leaves the list, else the item splits at the caret and
 * the new one gets the next marker. On an empty quote line the quote lifts. Elsewhere null,
 * a plain `\n` (`splitBlockKeepMarks`). The code block's Enter comes first, [composerRichEnter].
 */
fun composerRichNewline(text: String, selection: TextRange): ComposerPromptEdit? {
  val base: String = text.removeRange(selection.min, selection.max)
  val document = ComposerRichDocument(base)
  val caret: Int = selection.min
  val block: ComposerRichBlock = document.blockAt(caret) ?: return null
  val next: String
  val after: Int
  when (block) {
    is ComposerRichBlock.ListItem -> {
      val at: Int = maxOf(caret, block.contentStart)
      val prefix: ComposerListPrefix = block.prefix
      if (block.contentStart == block.end) {
        val lifted: String = liftedListPrefix(document, block)
        next = base.substring(0, block.start) + lifted + base.substring(block.end)
        after = block.start + lifted.length
      } else {
        val marker: String = when (prefix.kind) {
          ComposerListKind.TASK -> "-${prefix.space}[ ] "
          ComposerListKind.BULLET -> "${prefix.marker} "
          ComposerListKind.ORDERED -> "${nextOrderedMarkerText(prefix.marker)} "
        }
        val inserted = "\n${prefix.indent}$marker"
        val split: String = base.substring(0, at) + inserted + base.substring(at)
        next = if (prefix.kind == ComposerListKind.ORDERED) {
          renumberFollowingItems(split, block.end + inserted.length, prefix)
        } else {
          split
        }
        after = at + inserted.length
      }
    }

    is ComposerRichBlock.Quote -> {
      val at: Int = maxOf(caret, block.contentStart)
      if (block.contentStart == block.end) {
        next = base.removeRange(block.start, block.contentStart)
        after = block.start
      } else {
        val inserted = "\n${block.prefix}"
        next = base.substring(0, at) + inserted + base.substring(at)
        after = at + inserted.length
      }
    }

    else -> return null
  }
  return editBetween(text, next, TextRange(after))
}

// `liftListItem` from a nested empty item takes it to its parent's level, marker aligned with
// the parent (`alignListItemWithSiblings`); a top-level one leaves the list
private fun liftedListPrefix(
  document: ComposerRichDocument,
  item: ComposerRichBlock.ListItem,
): String {
  val indent: String = item.prefix.indent
  if (indent.isEmpty()) return ""
  val parent: ComposerRichBlock.ListItem = document.blocks
    .filterIsInstance<ComposerRichBlock.ListItem>()
    .lastOrNull { it.end < item.start && it.prefix.indent.length < indent.length }
    ?: return ""
  val prefix: ComposerListPrefix = parent.prefix
  return when (prefix.kind) {
    ComposerListKind.TASK -> "${prefix.indent}-${prefix.space}[ ] "
    ComposerListKind.BULLET -> "${prefix.indent}${prefix.marker} "
    ComposerListKind.ORDERED -> "${prefix.indent}${nextOrderedMarkerText(prefix.marker)} "
  }
}

// Markdown tabs advance to the next four-column stop
private fun indentWidth(line: String): Int {
  var width = 0
  for (char in line) {
    width = when (char) {
      ' ' -> width + 1
      '\t' -> width + 4 - (width % 4)
      else -> return width
    }
  }
  return width
}

/**
 * `renumberFollowingItems` (composer-list-continuation.ts): the ordered items after the line
 * ending at [afterLineEnd], at [marker]'s depth, count on from the new one.
 */
private fun renumberFollowingItems(
  value: String,
  afterLineEnd: Int,
  marker: ComposerListPrefix,
): String {
  val baseWidth: Int = indentWidth(marker.indent)
  val delimiter: Char = marker.marker.last()
  var expected: Long = (marker.marker.dropLast(1).toLongOrNull() ?: return value) + 2
  val result = StringBuilder(value)
  var position: Int = afterLineEnd
  var delta = 0
  while (position < value.length) {
    val lineStart: Int = position + 1
    val lineEnd: Int = value.indexOf('\n', lineStart).takeIf { it != -1 } ?: value.length
    position = lineEnd
    val line: String = value.substring(lineStart, lineEnd)
    val width: Int = indentWidth(line)
    if (line.isBlank() || width < baseWidth) break
    if (width > baseWidth) continue
    val parsed: ComposerListPrefix? = parseListPrefix(line)
    if (parsed?.kind != ComposerListKind.ORDERED || parsed.marker.last() != delimiter) break
    val numberText: String = parsed.marker.dropLast(1)
    val replacement: String = if (numberText.startsWith("0")) {
      expected.toString().padStart(numberText.length, '0')
    } else {
      expected.toString()
    }
    val numberStart: Int = lineStart + parsed.indent.length + delta
    result.replace(numberStart, numberStart + numberText.length, replacement)
    delta += replacement.length - numberText.length
    expected++
  }
  return result.toString()
}

private val TRAILING_BLANK_LINES: Regex = Regex("\\n[ \\t]*\\n[ \\t]*$")
private val LEADING_WHITESPACE: Regex = Regex("^[ \\t]*")

/**
 * Enter before anything else gets it, as upstream's `handleKeyDown` runs these ahead of
 * sending: in a code block `exitCodeBlockOnClosingFence`, `exitCodeBlockOnTrailingBlankLines`,
 * `indentedNewlineInCodeBlock`, then a plain newline; at the end of a fence line in a
 * top-level paragraph `convertCodeFenceOnEnter`. Null when Enter goes on to send.
 */
fun composerRichEnter(text: String, selection: TextRange): ComposerPromptEdit? {
  val document = ComposerRichDocument(text)
  val code: ComposerRichBlock.CodeBlock? = document.codeBlockAt(selection.min)
  if (code != null && selection.max <= code.end) {
    val caret: Int = selection.min
    // The closing fence is the line the caret ends: leave the block for a line after it
    if (selection.collapsed && caret == code.end) {
      return ComposerPromptEdit(caret, caret, "\n", TextRange(caret + 1))
    }
    val inBody: Boolean = selection.min >= code.bodyStart && selection.max <= code.bodyEnd
    if (inBody && selection.collapsed && caret == code.bodyEnd) {
      val body: String = text.substring(code.bodyStart, code.bodyEnd)
      TRAILING_BLANK_LINES.find(body)?.let { trailing ->
        // The blank lines go and a line opens after the block (`exitCode`)
        val kept: Int = code.bodyEnd - trailing.value.length
        val next: String = text.substring(0, kept) + text.substring(code.bodyEnd, code.end) +
          "\n" + text.substring(code.end)
        return editBetween(text, next, TextRange(kept + (code.end - code.bodyEnd) + 1))
      }
    }
    val lineStart: Int = text.lastIndexOf('\n', selection.min - 1) + 1
    val indent: String = if (inBody) {
      LEADING_WHITESPACE.find(text.substring(lineStart, selection.min))?.value.orEmpty()
    } else {
      ""
    }
    val inserted = "\n$indent"
    return ComposerPromptEdit(
      selection.min,
      selection.max,
      inserted,
      TextRange(selection.min + inserted.length),
    )
  }
  if (!selection.collapsed) return null
  val block: ComposerRichBlock = document.blockAt(selection.start) ?: return null
  if (block !is ComposerRichBlock.Paragraph || selection.start != block.end) return null
  val opening: ComposerOpeningFence =
    parseOpeningFence(text.substring(block.start, block.end)) ?: return null
  val inserted = "\n\n${opening.fence}"
  return ComposerPromptEdit(block.end, block.end, inserted, TextRange(block.end + 1))
}

/**
 * Backspace where the field shows nothing to delete: at the start of a heading's text, whose
 * `# ` draws as nothing, the heading goes back to a paragraph (Tiptap's `undoInputRule` and
 * `clearNodes`); just after a rule, which draws as an empty line, the rule goes. Null
 * elsewhere: a list marker or quote slot is shown text, and deleting it already lifts the line.
 */
fun composerRichBackspace(text: String, selection: TextRange): ComposerPromptEdit? {
  if (!selection.collapsed) return null
  val caret: Int = selection.start
  return when (val block: ComposerRichBlock? = ComposerRichDocument(text).blockAt(caret)) {
    is ComposerRichBlock.Heading -> if (caret == block.contentStart) {
      ComposerPromptEdit(block.start, block.contentStart, "", TextRange(block.start))
    } else {
      null
    }

    is ComposerRichBlock.Rule -> if (caret == block.end) {
      ComposerPromptEdit(block.start, block.end, "", TextRange(block.start))
    } else {
      null
    }

    else -> null
  }
}

/** `CODE_BLOCK_INDENT` (apps/web/src/composer-code-block.ts). */
private const val CODE_BLOCK_INDENT: String = "  "

/**
 * Tab and Shift+Tab in a code block, `indentCodeBlock`: a caret gets two spaces, a selection
 * indents or outdents its lines. Null outside one: Tab and Shift+Tab don't nest a list item,
 * upstream drops Tiptap's list keys (`LIST_NESTING_KEYS`).
 */
fun composerRichTab(text: String, selection: TextRange, outdent: Boolean): ComposerPromptEdit? {
  val document = ComposerRichDocument(text)
  val code: ComposerRichBlock.CodeBlock = document.codeBlockAt(selection.min) ?: return null
  if (selection.min < code.bodyStart || selection.max > code.bodyEnd) return null
  if (selection.collapsed && !outdent) {
    val at: Int = selection.min
    return ComposerPromptEdit(at, at, CODE_BLOCK_INDENT, TextRange(at + CODE_BLOCK_INDENT.length))
  }
  val lineStart: Int = text.lastIndexOf('\n', maxOf(code.bodyStart, selection.min) - 1)
    .let { maxOf(it + 1, code.bodyStart) }
  val newlineAfter: Int = text.indexOf('\n', maxOf(selection.min, selection.max - 1))
  val lineEnd: Int = if (newlineAfter == -1 || newlineAfter > code.bodyEnd) {
    code.bodyEnd
  } else {
    newlineAfter
  }
  val original: List<String> = text.substring(lineStart, lineEnd).split('\n')
  val next: List<String> = original.map { line ->
    when {
      !outdent -> if (line.isEmpty()) line else "$CODE_BLOCK_INDENT$line"
      line.startsWith(CODE_BLOCK_INDENT) -> line.removePrefix(CODE_BLOCK_INDENT)
      line.startsWith("\t") || line.startsWith(" ") -> line.substring(1)
      else -> line
    }
  }
  val joined: String = next.joinToString("\n")
  val firstDelta: Int = next.first().length - original.first().length
  val totalDelta: Int = joined.length - (lineEnd - lineStart)
  val from: Int = maxOf(lineStart, selection.min + firstDelta)
  val to: Int = maxOf(from, selection.max + totalDelta)
  return ComposerPromptEdit(lineStart, lineEnd, joined, TextRange(from, to))
}

private val RULE_INPUT: Regex = Regex("^(---|\\*\\*\\*|___)\\s?$")
private val BULLET_TO_TASK: Regex = Regex("^\\[([ xX])] $")

/**
 * The input rules that still edit the markdown, after [before] became [after] with the caret
 * at [caret]:
 * - `horizontalRuleInputRule`: a top-level line becoming `---` (or `*** `, `___ `) moves the
 *   caret to the next line, adding one at the end, as the rule node takes its line.
 * - `bulletToTaskInputRule`: `[ ] ` or `[x] ` typed at the start of a `*` or `+` item makes it
 *   a task, written `- [ ] ` as upstream's task serializer does. On a `-` item the markdown is
 *   already a task's.
 * The rest (`listMarkerInputRule`, `taskInputRule`, heading, quote) are no-ops here: the
 * markdown already is the block, see [composerRichTextBlocks] and [listLineShows].
 */
fun composerRichInputRule(before: String, after: String, caret: Int): ComposerPromptEdit? {
  val document = ComposerRichDocument(after)
  val block: ComposerRichBlock = document.blockAt(caret) ?: return null
  if (caret != block.end) return null
  val line: String = after.substring(block.start, block.end)
  when (block) {
    is ComposerRichBlock.Rule -> {
      val match: MatchResult = RULE_INPUT.find(line) ?: return null
      if (!match.value.startsWith("---") && !match.value.last().isWhitespace()) return null
      val earlier: ComposerRichBlock? = ComposerRichDocument(before).blockAt(block.start)
      if (earlier !is ComposerRichBlock.Paragraph) return null
      return if (block.end < after.length) {
        ComposerPromptEdit(caret, caret, "", TextRange(block.end + 1))
      } else {
        ComposerPromptEdit(caret, caret, "\n", TextRange(caret + 1))
      }
    }

    is ComposerRichBlock.ListItem -> {
      val prefix: ComposerListPrefix = block.prefix
      if (prefix.kind != ComposerListKind.BULLET || prefix.marker == "-") return null
      val content: String = after.substring(block.contentStart, block.end)
      if (!BULLET_TO_TASK.matches(content)) return null
      val markerAt: Int = block.start + prefix.indent.length
      return ComposerPromptEdit(markerAt, markerAt + 1, "-", TextRange(caret))
    }

    else -> return null
  }
}

/** `SURROUND_CLOSE` (ComposerPromptEditorTiptap.tsx). */
val SURROUND_CLOSE: Map<String, String> = mapOf(
  "(" to ")",
  "[" to "]",
  "{" to "}",
  "'" to "'",
  "\"" to "\"",
  "“" to "”",
  "`" to "`",
  "<" to ">",
  "«" to "»",
  "*" to "*",
  "_" to "_",
)

/**
 * `handleTextInput`: [typed] over a selection wraps it in the pair, the selection kept on the
 * same text. Never around chips or styled text ([styled], the marks' ranges), where the
 * default replace keeps them whole, and never when the selection takes the whitespace that
 * keeps a mention a mention (`selectionTouchesMentionBoundary`).
 */
fun composerSurroundEdit(
  text: String,
  selection: TextRange,
  typed: String,
  chips: List<ComposerPromptChip>,
  styled: List<ComposerRichStyledRange>,
): ComposerPromptEdit? {
  val closer: String = SURROUND_CLOSE[typed] ?: return null
  if (selection.collapsed) return null
  val from: Int = selection.min
  val to: Int = selection.max
  if (chips.any { it.start < to && it.end > from }) return null
  if (styled.any { it.start < to && it.end > from }) return null
  if (selectionTouchesMentionBoundary(text, from, to)) return null
  return ComposerPromptEdit(
    start = from,
    end = to,
    text = typed + text.substring(from, to) + closer,
    selection = TextRange(from + typed.length, to + typed.length),
  )
}

/** `selectionTouchesMentionBoundary` (apps/web/src/composer-editor-mentions.ts). */
fun selectionTouchesMentionBoundary(prompt: String, start: Int, end: Int): Boolean {
  if (prompt.isEmpty() || start >= end) return false
  return collectComposerInlineTokens(prompt)
    .filter { it.type == ComposerInlineTokenType.MENTION }
    .any { mention ->
      val before: Int = mention.start - 1
      val after: Int = mention.end
      (before >= 0 && prompt[before].isWhitespace() && before in start until end) ||
        (after < prompt.length && prompt[after].isWhitespace() && after in start until end)
    }
}

/**
 * The caret kept out of a block prefix, as [snapComposerPromptSelection] keeps it out of a
 * chip: a caret that lands in one goes to the content after it, unless it came from there
 * ([previous] on the prefix's end, no edit), then it steps on to the line above. A selection's
 * ends move out to take the prefix.
 */
fun snapComposerRichSelection(
  prefixes: List<ComposerRichPrefix>,
  previous: TextRange,
  next: TextRange,
  textChanged: Boolean = false,
): TextRange {
  if (!next.collapsed) {
    fun inside(offset: Int): ComposerRichPrefix? =
      prefixes.find { it.start < offset && offset < it.end }
    val min: Int = inside(next.min)?.start ?: next.min
    val max: Int = inside(next.max)?.end ?: next.max
    return if (next.reversed) TextRange(max, min) else TextRange(min, max)
  }
  val caret: Int = next.start
  val prefix: ComposerRichPrefix =
    prefixes.find { caret >= it.start && caret < it.end } ?: return next
  if (!textChanged && previous.collapsed && previous.start == prefix.end && prefix.start > 0) {
    return TextRange(prefix.start - 1)
  }
  return TextRange(prefix.end)
}

// What the field shows: chips and markdown as one list of replacements and styles

/** How a stretch of the shown text is styled; the composable picks the actual style. */
enum class ComposerPromptStyleKind {
  BOLD,
  ITALIC,
  STRIKE,
  CODE,

  /** A mark's delimiter while the caret is in its range, `composer-rich-marker`. */
  MARKER,
  HEADING_1,
  HEADING_2,
  HEADING_3,
  HEADING_4,
  HEADING_5,
  HEADING_6,
  CODE_BLOCK,

  /** A fence line while the caret is in its block. */
  FENCE,

  /** A chip's leading space, the room for its icon. */
  CHIP_ICON,
  CHIP_DETAIL,

  /** The slot a quote's bar is drawn in. */
  QUOTE_SLOT,

  /** The slot a task's checkbox is drawn in. */
  TASK_SLOT,
}

/** A style over [start] until [end]. */
data class ComposerPromptStyle(val kind: ComposerPromptStyleKind, val start: Int, val end: Int)

/** [start] until [end] of the draft shown as [text], with [styles] in [text]'s offsets. */
data class ComposerPromptReplacement(
  val start: Int,
  val end: Int,
  val text: String,
  val styles: List<ComposerPromptStyle> = emptyList(),
)

/** A quote line's bar, beside the source line [start] to [end]. */
data class ComposerQuoteDecoration(override val start: Int, override val end: Int) :
  ComposerPromptDecoration

/** A rule across the source line [start] to [end]. */
data class ComposerRuleDecoration(override val start: Int, override val end: Int) :
  ComposerPromptDecoration

/** A task's checkbox in the slot at [start]; the `x` or space between the brackets at [box]. */
data class ComposerTaskDecoration(
  override val start: Int,
  override val end: Int,
  val checked: Boolean,
  val box: Int,
) : ComposerPromptDecoration

/** A code block's frame and its language picker. */
data class ComposerCodeBlockDecoration(val block: ComposerRichBlock.CodeBlock) :
  ComposerPromptDecoration {
  override val start: Int get() = block.start
  override val end: Int get() = block.end
}

/**
 * What the field shows for a draft: [replacements] in order and not overlapping, [styles] in
 * the draft's offsets, [decorations] for the draw layer (chips and blocks), [prefixes] for the
 * caret, and [chips] as drawn.
 */
data class ComposerPromptPresentation(
  val replacements: List<ComposerPromptReplacement>,
  val styles: List<ComposerPromptStyle>,
  val decorations: List<ComposerPromptDecoration>,
  val prefixes: List<ComposerRichPrefix>,
  val chips: List<ComposerPromptChip>,
  val styled: List<ComposerRichStyledRange>,
) {
  /** [offset] in the draft as an offset in the shown text; inside a replacement, its start. */
  fun displayOffset(offset: Int): Int {
    var delta = 0
    for (replacement in replacements) {
      if (replacement.start >= offset) break
      if (offset < replacement.end) return replacement.start + delta
      delta += replacement.text.length - (replacement.end - replacement.start)
    }
    return offset + delta
  }

  /** The shown text's length for a draft of [length]. */
  fun displayLength(length: Int): Int =
    length + replacements.sumOf { it.text.length - (it.end - it.start) }

  /** [text] as shown. The field gets the same from its output transformation. */
  fun display(text: String): String {
    val builder = StringBuilder(text)
    for (replacement in replacements.asReversed()) {
      builder.replace(replacement.start, replacement.end, replacement.text)
    }
    return builder.toString()
  }
}

// No-break spaces, so a slot never wraps away from its line
private const val NBSP: Char = ' '

private val HEADINGS: List<ComposerPromptStyleKind> = listOf(
  ComposerPromptStyleKind.HEADING_1,
  ComposerPromptStyleKind.HEADING_2,
  ComposerPromptStyleKind.HEADING_3,
  ComposerPromptStyleKind.HEADING_4,
  ComposerPromptStyleKind.HEADING_5,
  ComposerPromptStyleKind.HEADING_6,
)

private val MARK_STYLES: Map<ComposerRichMark, ComposerPromptStyleKind> = mapOf(
  ComposerRichMark.BOLD to ComposerPromptStyleKind.BOLD,
  ComposerRichMark.ITALIC to ComposerPromptStyleKind.ITALIC,
  ComposerRichMark.STRIKE to ComposerPromptStyleKind.STRIKE,
  ComposerRichMark.CODE to ComposerPromptStyleKind.CODE,
)

/**
 * The presentation of a draft with [chips] and the [selection]. [document] is null with the
 * rich text setting off: only chips then, as upstream's plain extensions have only the chip
 * nodes. With it on, chips stay out of code blocks (a fence holds raw text), marks show their
 * delimiters only while the selection is in or touches their range (`ComposerMarkersExtension`),
 * and a code block shows its fences only while the selection is in it; upstream draws a header
 * with the language picker in their place.
 */
fun composerPromptPresentation(
  document: ComposerRichDocument?,
  chips: List<ComposerPromptChip>,
  selection: TextRange,
): ComposerPromptPresentation {
  val replacements: MutableList<ComposerPromptReplacement> = mutableListOf()
  val styles: MutableList<ComposerPromptStyle> = mutableListOf()
  val decorations: MutableList<ComposerPromptDecoration> = mutableListOf()
  val codeBlocks: List<ComposerRichBlock.CodeBlock> =
    document?.blocks?.filterIsInstance<ComposerRichBlock.CodeBlock>().orEmpty()
  val shownChips: List<ComposerPromptChip> = chips.filter { chip ->
    codeBlocks.none { chip.start < it.end && chip.end > it.start }
  }
  for (chip in shownChips) {
    val display: ComposerChipDisplay = composerChipDisplay(chip)
    val chipStyles: List<ComposerPromptStyle> = listOfNotNull(
      ComposerPromptStyle(ComposerPromptStyleKind.CHIP_ICON, 0, 1),
      display.detail?.let {
        ComposerPromptStyle(ComposerPromptStyleKind.CHIP_DETAIL, it.first, it.last + 1)
      },
    )
    replacements += ComposerPromptReplacement(chip.start, chip.end, display.text, chipStyles)
    decorations += chip
  }
  if (document == null) {
    return ComposerPromptPresentation(
      replacements = replacements,
      styles = styles,
      decorations = decorations,
      prefixes = emptyList(),
      chips = shownChips,
      styled = emptyList(),
    )
  }

  fun touches(start: Int, end: Int): Boolean = if (selection.collapsed) {
    selection.start in start..end
  } else {
    selection.min < end && selection.max > start
  }

  for (block in document.blocks) {
    when (block) {
      is ComposerRichBlock.Heading -> {
        replacements += ComposerPromptReplacement(block.start, block.contentStart, "")
        styles += ComposerPromptStyle(HEADINGS[block.level - 1], block.contentStart, block.end)
      }

      is ComposerRichBlock.Quote -> {
        replacements += ComposerPromptReplacement(
          start = block.start,
          end = block.contentStart,
          text = NBSP.toString(),
          styles = listOf(ComposerPromptStyle(ComposerPromptStyleKind.QUOTE_SLOT, 0, 1)),
        )
        decorations += ComposerQuoteDecoration(block.start, block.end)
      }

      is ComposerRichBlock.Rule -> {
        replacements += ComposerPromptReplacement(block.start, block.end, "")
        decorations += ComposerRuleDecoration(block.start, block.end)
      }

      is ComposerRichBlock.ListItem -> {
        val prefix: ComposerListPrefix = block.prefix
        val markerAt: Int = block.start + prefix.indent.length
        when (prefix.kind) {
          ComposerListKind.BULLET -> replacements += ComposerPromptReplacement(
            start = markerAt,
            end = block.contentStart,
            text = "•$NBSP",
          )

          ComposerListKind.TASK -> {
            replacements += ComposerPromptReplacement(
              start = markerAt,
              end = block.contentStart,
              text = "$NBSP$NBSP",
              styles = listOf(ComposerPromptStyle(ComposerPromptStyleKind.TASK_SLOT, 0, 1)),
            )
            val box: Int = markerAt + 1 + prefix.space.length + 1
            decorations +=
              ComposerTaskDecoration(markerAt, block.contentStart, prefix.checked, box)
          }

          // The number shows as typed, `start` from it
          ComposerListKind.ORDERED -> Unit
        }
      }

      is ComposerRichBlock.CodeBlock -> {
        if (touches(block.start, block.end)) {
          styles += ComposerPromptStyle(ComposerPromptStyleKind.FENCE, block.start, block.openEnd)
          styles += ComposerPromptStyle(ComposerPromptStyleKind.FENCE, block.closeStart, block.end)
        } else {
          replacements += ComposerPromptReplacement(block.start, block.openEnd, "")
          replacements += ComposerPromptReplacement(block.closeStart, block.end, "")
        }
        if (block.bodyEnd > block.bodyStart) {
          styles += ComposerPromptStyle(
            ComposerPromptStyleKind.CODE_BLOCK,
            block.bodyStart,
            block.bodyEnd,
          )
        }
        decorations += ComposerCodeBlockDecoration(block)
      }

      is ComposerRichBlock.Paragraph -> Unit
    }
  }

  val inline: ComposerInlineMarkdown = composerInlineMarkdown(document, shownChips)
  for (span in inline.spans) {
    for (mark in span.marks) {
      styles += ComposerPromptStyle(MARK_STYLES.getValue(mark), span.start, span.end)
    }
  }
  for (range in inline.styled) {
    val active: Boolean = touches(range.start, range.end)
    for (marker in range.markers) {
      if (active) {
        styles += ComposerPromptStyle(ComposerPromptStyleKind.MARKER, marker.first, marker.last + 1)
      } else {
        replacements += ComposerPromptReplacement(marker.first, marker.last + 1, "")
      }
    }
  }

  return ComposerPromptPresentation(
    replacements = replacements.sortedWith(compareBy({ it.start }, { it.end })),
    styles = styles,
    decorations = decorations,
    prefixes = document.prefixes,
    chips = shownChips,
    styled = inline.styled,
  )
}

// The code block's language, `composer-code-languages.ts`

/** `CodeBlockLanguage`. */
data class ComposerCodeLanguage(val id: String, val label: String)

/** `CODE_BLOCK_LANGUAGES`. */
val CODE_BLOCK_LANGUAGES: List<ComposerCodeLanguage> = listOf(
  ComposerCodeLanguage("", "Plain text"),
  ComposerCodeLanguage("bash", "Bash"),
  ComposerCodeLanguage("c", "C"),
  ComposerCodeLanguage("cpp", "C++"),
  ComposerCodeLanguage("csharp", "C#"),
  ComposerCodeLanguage("css", "CSS"),
  ComposerCodeLanguage("dart", "Dart"),
  ComposerCodeLanguage("diff", "Diff"),
  ComposerCodeLanguage("dockerfile", "Dockerfile"),
  ComposerCodeLanguage("elixir", "Elixir"),
  ComposerCodeLanguage("go", "Go"),
  ComposerCodeLanguage("graphql", "GraphQL"),
  ComposerCodeLanguage("haskell", "Haskell"),
  ComposerCodeLanguage("html", "HTML"),
  ComposerCodeLanguage("java", "Java"),
  ComposerCodeLanguage("javascript", "JavaScript"),
  ComposerCodeLanguage("json", "JSON"),
  ComposerCodeLanguage("jsx", "JSX"),
  ComposerCodeLanguage("kotlin", "Kotlin"),
  ComposerCodeLanguage("lua", "Lua"),
  ComposerCodeLanguage("markdown", "Markdown"),
  ComposerCodeLanguage("php", "PHP"),
  ComposerCodeLanguage("powershell", "PowerShell"),
  ComposerCodeLanguage("python", "Python"),
  ComposerCodeLanguage("r", "R"),
  ComposerCodeLanguage("ruby", "Ruby"),
  ComposerCodeLanguage("rust", "Rust"),
  ComposerCodeLanguage("scala", "Scala"),
  ComposerCodeLanguage("scss", "SCSS"),
  ComposerCodeLanguage("sql", "SQL"),
  ComposerCodeLanguage("svelte", "Svelte"),
  ComposerCodeLanguage("swift", "Swift"),
  ComposerCodeLanguage("toml", "TOML"),
  ComposerCodeLanguage("tsx", "TSX"),
  ComposerCodeLanguage("typescript", "TypeScript"),
  ComposerCodeLanguage("vue", "Vue"),
  ComposerCodeLanguage("xml", "XML"),
  ComposerCodeLanguage("yaml", "YAML"),
  ComposerCodeLanguage("zig", "Zig"),
)

private val LANGUAGE_ALIASES: Map<String, String> = mapOf(
  "cs" to "csharp",
  "c++" to "cpp",
  "js" to "javascript",
  "md" to "markdown",
  "py" to "python",
  "rb" to "ruby",
  "rs" to "rust",
  "sh" to "bash",
  "shell" to "bash",
  "ts" to "typescript",
  "yml" to "yaml",
)

/** `languageOfInfoString`: the info string's first word. */
fun languageOfInfoString(info: String): String =
  info.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()

/**
 * `withInfoStringLanguage`: [info] with its language swapped. Plain text clears it, unless
 * something follows: a bare `title=x` would read as the language, so that keeps `text`.
 */
fun withInfoStringLanguage(info: String, language: String): String {
  val leading: String = LEADING_WHITESPACE.find(info)?.value.orEmpty()
  val rest: String = info.substring(leading.length + languageOfInfoString(info).length)
  if (language.isNotEmpty()) return "$leading$language$rest"
  return if (rest.isNotBlank()) "${leading}text$rest" else ""
}

/** `codeLanguageEntry`. */
fun codeLanguageEntry(language: String): ComposerCodeLanguage? {
  val id: String =
    if (language == "text") "" else LANGUAGE_ALIASES[language.lowercase()] ?: language
  return CODE_BLOCK_LANGUAGES.find { it.id == id.lowercase() }
}

/** `codeLanguageLabel`. */
fun codeLanguageLabel(language: String): String = codeLanguageEntry(language)?.label ?: language

private val LANGUAGE_EXTENSION_ALIASES: Map<String, String> = mapOf(
  "bash" to "sh",
  "csharp" to "cs",
  "elixir" to "ex",
  "haskell" to "hs",
  "javascript" to "js",
  "jsx" to "jsx",
  "kotlin" to "kt",
  "markdown" to "md",
  "mdx" to "mdx",
  "plaintext" to "txt",
  "powershell" to "ps1",
  "python" to "py",
  "ruby" to "rb",
  "rust" to "rs",
  "shell" to "sh",
  "shellscript" to "sh",
  "swift" to "swift",
  "typescript" to "ts",
  "tsx" to "tsx",
  "yaml" to "yml",
)

private val LANGUAGE_FILE_NAMES: Map<String, String> = mapOf("dockerfile" to "Dockerfile")

/**
 * `syntheticFileNameForLanguageId` (apps/web/src/pierre-icons.ts): a file name for the
 * language, which is how the IDE finds its file type and highlighter.
 */
fun syntheticFileNameForLanguageId(languageId: String): String {
  val normalized: String = languageId.lowercase()
  return LANGUAGE_FILE_NAMES[normalized]
    ?: "file.${LANGUAGE_EXTENSION_ALIASES[normalized] ?: normalized}"
}

/**
 * The fence line of [block] in [text] with its language set to [language], the picker's
 * `changeLanguage`.
 */
fun composerCodeLanguageEdit(
  text: String,
  block: ComposerRichBlock.CodeBlock,
  language: String,
): ComposerPromptEdit {
  val infoStart: Int = block.start + block.fence.length
  val info: String = text.substring(infoStart, block.openEnd)
  val next: String = withInfoStringLanguage(info, language)
  val caret: Int = block.bodyEnd + (next.length - info.length)
  return ComposerPromptEdit(infoStart, block.openEnd, next, TextRange(caret))
}

/** A task's checkbox clicked: the `x` or space between its brackets flips. */
fun composerTaskToggleEdit(task: ComposerTaskDecoration): ComposerPromptEdit =
  ComposerPromptEdit(task.box, task.box + 1, if (task.checked) " " else "x", TextRange(task.end))
