package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.rememberResourcePainterProvider

/** A highlighted token in a code block's code, [start] to [end] in that code. */
data class ComposerCodeHighlight(val start: Int, val end: Int, val style: SpanStyle)

/** Highlights a code block's code for a fence language, ComposerCodeBlocks.kt has the IDE's. */
fun interface ComposerCodeHighlighter {
  fun highlight(language: String, code: String): List<ComposerCodeHighlight>
}

/**
 * Everything drawn over one prompt's draft: #60's chips (ComposerPromptChipsLogic.kt) and, with
 * [richText] on, the formatted markdown (ComposerRichTextLogic.kt). The draft keeps the
 * markdown that gets sent; this parses it once per text and selection into a
 * [ComposerPromptPresentation] and hands it to the field's [output] (what shows in place of
 * the source, and its styles), its [input] (edits as upstream's editor makes them) and
 * [composerPromptDecorationBackgrounds] (chips, quote bars, rules, checkboxes, code blocks).
 *
 * The properties are snapshot state, so the field re-runs its output transformation when the
 * provider's skills, the draft's attachments, the setting or the theme change.
 */
@Stable
class ComposerPromptDecorations {
  /** The provider's skills for the thread's cwd: only those draw a `$name` as a chip. */
  var skills: List<ServerProviderSkill> by mutableStateOf(emptyList())

  /** What the draft's context links point at, [composerContextRecordsFromDraft]. */
  var records: Map<String, ComposerDraftContextRecord> by mutableStateOf(emptyMap())

  /** Extra advance on a chip's leading space: the room its icon and gap take. */
  var iconRoom: TextUnit by mutableStateOf(TextUnit.Unspecified)

  /** Extra advance on a chip's closing space, taking it back: upstream's chip has no padding. */
  var closingRoom: TextUnit by mutableStateOf(TextUnit.Unspecified)

  /** The theme's chip inks, which the chips' labels draw in. */
  var chipInks: ContextChipInks by mutableStateOf(ContextChipInks())

  /** Upstream's `composerRichTextEnabled`. Off, only chips. */
  var richText: Boolean by mutableStateOf(true)

  /** The style for each kind of formatting; null draws none (plain tests). */
  var textStyles: Map<ComposerPromptStyleKind, SpanStyle> by mutableStateOf(emptyMap())

  /** Highlights code blocks, null for none. */
  var highlighter: ComposerCodeHighlighter? by mutableStateOf(null)

  // The last text's tokens, `preserveTrailingFrom`: a chip stays one when its trailing space goes
  private var confirmed: List<ComposerInlineToken> = emptyList()
  private var cachedChips: ParsedChips? = null
  private var cachedDocument: ComposerRichDocument? = null
  private var cachedPresentation: Presented? = null

  private class ParsedChips(
    val text: String,
    val skills: List<ServerProviderSkill>,
    val records: Map<String, ComposerDraftContextRecord>,
    val richText: Boolean,
    val chips: List<ComposerPromptChip>,
  )

  private class Presented(
    val text: String,
    val selection: TextRange,
    val chips: List<ComposerPromptChip>,
    val richText: Boolean,
    val presentation: ComposerPromptPresentation,
  )

  /** [text]'s blocks and marks, or null with [richText] off. */
  fun documentFor(text: String): ComposerRichDocument? {
    if (!richText) return null
    cachedDocument?.takeIf { it.text == text }?.let { return it }
    return ComposerRichDocument(text).also { cachedDocument = it }
  }

  /** The chips in [text], in order. None in a code block, which holds raw text. */
  fun chipsFor(text: String): List<ComposerPromptChip> {
    val skills: List<ServerProviderSkill> = skills
    val records: Map<String, ComposerDraftContextRecord> = records
    val richText: Boolean = richText
    cachedChips
      ?.takeIf {
        it.text == text && it.skills === skills && it.records === records &&
          it.richText == richText
      }
      ?.let { return it.chips }
    val tokens: List<ComposerInlineToken> = collectComposerInlineTokens(text, confirmed)
    confirmed = tokens
    val codeBlocks: List<ComposerRichBlock.CodeBlock> = documentFor(text)?.blocks
      ?.filterIsInstance<ComposerRichBlock.CodeBlock>()
      .orEmpty()
    val chips: List<ComposerPromptChip> = composerPromptChips(text, tokens, skills, records)
      .filter { chip -> codeBlocks.none { chip.start < it.end && chip.end > it.start } }
    cachedChips = ParsedChips(text, skills, records, richText, chips)
    return chips
  }

  /** What the field shows for [text] with [selection]. */
  fun presentationFor(text: String, selection: TextRange): ComposerPromptPresentation {
    val chips: List<ComposerPromptChip> = chipsFor(text)
    val richText: Boolean = richText
    cachedPresentation
      ?.takeIf {
        it.text == text && it.selection == selection && it.chips === chips &&
          it.richText == richText
      }
      ?.let { return it.presentation }
    val presentation: ComposerPromptPresentation =
      composerPromptPresentation(documentFor(text), chips, selection)
    cachedPresentation = Presented(text, selection, chips, richText, presentation)
    return presentation
  }

  private fun spanFor(kind: ComposerPromptStyleKind): SpanStyle? = when (kind) {
    ComposerPromptStyleKind.CHIP_ICON -> iconRoom.takeIf { it.isSpecified }
      ?.let { SpanStyle(letterSpacing = it) }

    ComposerPromptStyleKind.CHIP_CLOSING -> closingRoom.takeIf { it.isSpecified }
      ?.let { SpanStyle(letterSpacing = it) }

    // `text-3xs`, the size after the name
    ComposerPromptStyleKind.CHIP_DETAIL -> SpanStyle(fontSize = COMPOSER_CHIP_DETAIL_SCALE.em)

    else -> textStyles[kind]
  }

  /**
   * Each replacement's source swapped for what shows (chip labels, list bullets, hidden
   * markers and prefixes), then the styles on the final text, then the code's highlighting.
   */
  val output: OutputTransformation = object : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
      val text: String = asCharSequence().toString()
      val presentation: ComposerPromptPresentation = presentationFor(text, selection)
      // Back to front so the earlier ranges hold
      for (replacement in presentation.replacements.asReversed()) {
        replace(replacement.start, replacement.end, replacement.text)
      }
      for (replacement in presentation.replacements) {
        val at: Int = presentation.displayOffset(replacement.start)
        for (style in replacement.styles) {
          val span: SpanStyle = spanFor(style.kind) ?: continue
          addStyle(span, at + style.start, at + style.end)
        }
      }
      for (style in presentation.styles) {
        val span: SpanStyle = spanFor(style.kind) ?: continue
        val from: Int = presentation.displayOffset(style.start)
        val to: Int = presentation.displayOffset(style.end)
        if (to > from) addStyle(span, from, to)
      }
      // `ContextChip`'s `font-medium`, in the kind's ink
      for (chip in presentation.chips) {
        val ink: Color = chipInks.ink(chip.accent, chip.state)
        if (ink == Color.Unspecified) continue
        val at: Int = presentation.displayOffset(chip.start)
        val span = SpanStyle(color = ink, fontWeight = FontWeight.Medium)
        addStyle(span, at, at + composerChipDisplay(chip).text.length)
      }
      val highlighter: ComposerCodeHighlighter = highlighter ?: return
      for (decoration in presentation.decorations) {
        if (decoration !is ComposerCodeBlockDecoration) continue
        val block: ComposerRichBlock.CodeBlock = decoration.block
        if (block.bodyEnd <= block.bodyStart) continue
        val code: String = text.substring(block.bodyStart, block.bodyEnd)
        val at: Int = presentation.displayOffset(block.bodyStart)
        for (token in highlighter.highlight(languageOfInfoString(block.info), code)) {
          addStyle(token.style, at + token.start, at + token.end)
        }
      }
    }
  }

  /**
   * Edits as upstream's editor takes them. Typing a pair character over a selection wraps it
   * ([composerSurroundEdit]). [atomicComposerPromptEdit] widens a cut into a chip to the whole
   * chip (one change, so undo brings it back whole) and moves typing out of one. The input
   * rules that still edit the markdown run ([composerRichInputRule]). Then
   * [snapComposerPromptSelection] and [snapComposerRichSelection] keep the caret out of chip
   * sources and block prefixes.
   */
  val input: InputTransformation = object : InputTransformation {
    @OptIn(ExperimentalFoundationApi::class) // changes
    override fun TextFieldBuffer.transformInput() {
      val original: String = originalText.toString()
      val before: List<ComposerPromptChip> = chipsFor(original)
      val textChanged: Boolean = changes.changeCount > 0
      if (changes.changeCount == 1) {
        val originalRange: TextRange = changes.getOriginalRange(0)
        val range: TextRange = changes.getRange(0)
        val inserted: String = asCharSequence().subSequence(range.min, range.max).toString()
        val surround: ComposerPromptEdit? = originalSelection
          .takeIf { !it.collapsed && originalRange == TextRange(it.min, it.max) }
          ?.let { picked ->
            val styled: List<ComposerRichStyledRange> = documentFor(original)
              ?.let { composerInlineMarkdown(it, before).styled }
              .orEmpty()
            composerSurroundEdit(original, picked, inserted, before, styled)
          }
        if (surround != null) {
          revertAllChanges()
          apply(surround)
        } else {
          if (before.isNotEmpty()) {
            val atomic: TextRange =
              atomicComposerPromptEdit(before, originalRange.min, originalRange.max)
            val insertion: ComposerPromptInsertion =
              separateComposerPromptInsertion(before, atomic.min, atomic.max, inserted)
            val same: Boolean = atomic == TextRange(originalRange.min, originalRange.max)
            if (!same || insertion.text != inserted) {
              revertAllChanges()
              replace(atomic.min, atomic.max, insertion.text)
              selection = TextRange(atomic.min + insertion.cursor)
            }
          }
          if (richText && inserted.isNotEmpty() && selection.collapsed) {
            composerRichInputRule(original, asCharSequence().toString(), selection.start)
              ?.let { apply(it) }
          }
        }
      }
      val text: String = asCharSequence().toString()
      var snapped: TextRange =
        snapComposerPromptSelection(chipsFor(text), originalSelection, selection, textChanged)
      documentFor(text)?.let { document ->
        snapped =
          snapComposerRichSelection(document.prefixes, originalSelection, snapped, textChanged)
      }
      if (snapped != selection) selection = snapped
    }
  }
}

/** [edit] made on this buffer. */
fun TextFieldBuffer.apply(edit: ComposerPromptEdit) {
  replace(edit.start, edit.end, edit.text)
  selection = edit.selection
}

/** [edit] made on [state], outside the input transformation. */
fun TextFieldState.apply(edit: ComposerPromptEdit) {
  edit { apply(edit) }
}

/**
 * The line [caret] (a draft offset) is on in [layout], or null when [layout] isn't of [text]
 * as [presentation] shows it yet. The layout is of the transformed text.
 */
fun composerPromptCaretLine(
  layout: TextLayoutResult?,
  text: String,
  caret: Int,
  presentation: ComposerPromptPresentation,
): Int? {
  if (layout == null) return null
  val laidOut: Int = layout.layoutInput.text.length
  if (laidOut != presentation.displayLength(text.length)) return null
  val offset: Int = presentation.displayOffset(caret.coerceIn(0, text.length))
  return layout.getLineForOffset(offset)
}

/** The IDE icon each [ComposerPromptChipIcon] draws, the `@` and `#` menus' where they share. */
private val ComposerPromptChipIcon.key: IconKey
  get() = when (this) {
    // Upstream's `SkillChipIcon` (`SKILL_CHIP_ICON_SVG`, lucide's `box`): the IDE's box glyph
    ComposerPromptChipIcon.SKILL -> AllIconsKeys.Nodes.Artifact
    ComposerPromptChipIcon.FILE -> AllIconsKeys.FileTypes.Any_type
    ComposerPromptChipIcon.FOLDER -> AllIconsKeys.Nodes.Folder
    ComposerPromptChipIcon.IMAGE -> AllIconsKeys.FileTypes.Image
    ComposerPromptChipIcon.THREAD -> AllIconsKeys.Toolwindows.ToolWindowMessages
    ComposerPromptChipIcon.PULL_REQUEST -> AllIconsKeys.Vcs.Branch
    ComposerPromptChipIcon.PULL_REQUEST_MERGED -> AllIconsKeys.Vcs.Merge
    // Upstream's `MessageCircleIcon`
    ComposerPromptChipIcon.REVIEW_COMMENT -> AllIconsKeys.General.Balloon
    // Upstream's `circle-dashed` for an unresolved chip
    ComposerPromptChipIcon.UNAVAILABLE -> AllIconsKeys.FileTypes.Unknown
  }

/** A painter per chip icon, for [composerPromptDecorationBackgrounds]. */
@Composable
fun rememberComposerPromptChipPainters(): Map<ComposerPromptChipIcon, Painter> =
  ComposerPromptChipIcon.entries.associateWith { icon ->
    val key: IconKey = icon.key
    rememberResourcePainterProvider(key, key.iconClass).getPainter().value
  }

/**
 * How the blocks look: the quote's bar (`border-left: 2px`), the rule, the task checkbox
 * (`0.95rem`), the code block's frame.
 */
class ComposerPromptBlockStyle(
  val quoteBar: Color,
  val rule: Color,
  val checkboxOutline: Color,
  val checkboxFill: Color,
  val checkmark: Color,
  val codeBackground: Color,
  val codeOutline: Color,
  val quoteBarWidth: Dp = 2.dp,
  val checkboxSize: Dp = 13.dp,
  val codeCornerRadius: Dp = 6.dp,
)

/**
 * Draws [decorations]' pieces behind the prompt's text, from the [layout] of the transformed
 * text: chips' icons, then the blocks. The text sits [contentPadding] into the field and
 * scrolls by [scroll], as Jewel's undecorated `TextArea` lays it out.
 */
fun Modifier.composerPromptDecorationBackgrounds(
  decorations: ComposerPromptDecorations,
  draft: TextFieldState,
  layout: State<(() -> TextLayoutResult?)?>,
  scroll: ScrollState,
  contentPadding: PaddingValues,
  painters: Map<ComposerPromptChipIcon, Painter>,
  blockStyle: ComposerPromptBlockStyle,
): Modifier = drawBehind {
  val text: String = draft.text.toString()
  val presentation: ComposerPromptPresentation =
    decorations.presentationFor(text, draft.selection)
  if (presentation.decorations.isEmpty()) return@drawBehind
  val result: TextLayoutResult = layout.value?.invoke() ?: return@drawBehind
  // A layout from before the last edit is of another text
  if (result.layoutInput.text.length != presentation.displayLength(text.length)) {
    return@drawBehind
  }
  val left: Float = contentPadding.calculateLeftPadding(layoutDirection).toPx()
  val top: Float = contentPadding.calculateTopPadding().toPx()
  val bottom: Float = size.height - contentPadding.calculateBottomPadding().toPx()
  val right: Float = contentPadding.calculateRightPadding(layoutDirection).toPx()
  val width: Float = size.width - left - right
  clipRect(top = top, bottom = bottom) {
    translate(left, top - scroll.value) {
      for (decoration in presentation.decorations) {
        when (decoration) {
          is ComposerCodeBlockDecoration ->
            drawCodeBlock(decoration, presentation, result, width, blockStyle)

          else -> Unit
        }
      }
      drawChipIcons(presentation, result, painters, decorations.chipInks)
      for (decoration in presentation.decorations) {
        when (decoration) {
          is ComposerQuoteDecoration -> drawQuoteBar(decoration, presentation, result, blockStyle)
          is ComposerRuleDecoration -> drawRule(decoration, presentation, result, width, blockStyle)
          is ComposerTaskDecoration -> drawCheckbox(decoration, presentation, result, blockStyle)
          else -> Unit
        }
      }
    }
  }
}

// `ContextChip` has no frame: only the icon, `size-[1em]` and centred on the line, in the kind's
// ink. A file's, a folder's and an image's are the IDE's own icons and keep their colours,
// but for a document's.
private fun DrawScope.drawChipIcons(
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  painters: Map<ComposerPromptChipIcon, Painter>,
  inks: ContextChipInks,
) {
  val fontSize: TextUnit = result.layoutInput.style.fontSize
  val icon: Float =
    if (fontSize.isSpecified) fontSize.toPx() * COMPOSER_CHIP_ICON_EM else 14.dp.toPx()
  for (chip in presentation.chips) {
    val painter: Painter = painters[chip.icon] ?: continue
    val start: Int = presentation.displayOffset(chip.start)
    val slot: Rect = result.getBoundingBox(start)
    val line: Int = result.getLineForOffset(start)
    val centreY: Float = (result.getLineTop(line) + result.getLineBottom(line)) / 2
    val ink: Color = inks.ink(chip.accent, chip.state)
    // A document's icon is stroked in the ink too, `--document-icon-stroke`
    val tinted: Boolean = chip.icon !in UNTINTED_CHIP_ICONS || chip.accent.isDocument
    val tint: ColorFilter? = ColorFilter.tint(ink).takeIf { ink != Color.Unspecified && tinted }
    translate(slot.left, centreY - icon / 2) {
      with(painter) { draw(Size(icon, icon), colorFilter = tint) }
    }
  }
}

private val UNTINTED_CHIP_ICONS: Set<ComposerPromptChipIcon> = setOf(
  ComposerPromptChipIcon.FILE,
  ComposerPromptChipIcon.FOLDER,
  ComposerPromptChipIcon.IMAGE,
)

// The source line [start] to [end]'s visual lines
private fun visualLines(
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  start: Int,
  end: Int,
): IntRange {
  val from: Int = presentation.displayOffset(start)
  val to: Int = presentation.displayOffset(end)
  return result.getLineForOffset(from)..result.getLineForOffset(to)
}

private fun DrawScope.drawQuoteBar(
  quote: ComposerQuoteDecoration,
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  style: ComposerPromptBlockStyle,
) {
  val lines: IntRange = visualLines(presentation, result, quote.start, quote.end)
  val x: Float = result.getBoundingBox(presentation.displayOffset(quote.start)).left
  drawRect(
    color = style.quoteBar,
    topLeft = Offset(x, result.getLineTop(lines.first)),
    size = Size(style.quoteBarWidth.toPx(), result.getLineBottom(lines.last) -
      result.getLineTop(lines.first)),
  )
}

private fun DrawScope.drawRule(
  rule: ComposerRuleDecoration,
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  width: Float,
  style: ComposerPromptBlockStyle,
) {
  val line: Int = result.getLineForOffset(presentation.displayOffset(rule.start))
  val y: Float = (result.getLineTop(line) + result.getLineBottom(line)) / 2
  drawLine(style.rule, Offset(0f, y), Offset(width, y), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawCheckbox(
  task: ComposerTaskDecoration,
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  style: ComposerPromptBlockStyle,
) {
  val box: Rect = composerTaskCheckboxBounds(task, presentation, result, style.checkboxSize.toPx())
  val radius = CornerRadius(3.dp.toPx())
  if (task.checked) {
    drawRoundRect(style.checkboxFill, box.topLeft, box.size, radius)
    val path = Path().apply {
      moveTo(box.left + box.width * 0.25f, box.top + box.height * 0.52f)
      lineTo(box.left + box.width * 0.43f, box.top + box.height * 0.7f)
      lineTo(box.left + box.width * 0.76f, box.top + box.height * 0.32f)
    }
    drawPath(path, style.checkmark, style = Stroke(1.6.dp.toPx()))
  } else {
    drawRoundRect(style.checkboxOutline, box.topLeft, box.size, radius, style = Stroke(1.dp.toPx()))
  }
}

/** Where [task]'s checkbox sits in [result], in the text's own coordinates. */
fun composerTaskCheckboxBounds(
  task: ComposerTaskDecoration,
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  size: Float,
): Rect {
  val slot: Int = presentation.displayOffset(task.start)
  val line: Int = result.getLineForOffset(slot)
  val centreY: Float = (result.getLineTop(line) + result.getLineBottom(line)) / 2
  val x: Float = result.getBoundingBox(slot).left
  return Rect(Offset(x, centreY - size / 2), Size(size, size))
}

private fun DrawScope.drawCodeBlock(
  code: ComposerCodeBlockDecoration,
  presentation: ComposerPromptPresentation,
  result: TextLayoutResult,
  width: Float,
  style: ComposerPromptBlockStyle,
) {
  val lines: IntRange = visualLines(presentation, result, code.start, code.end)
  val top: Float = result.getLineTop(lines.first)
  // A little past the text either side, so code doesn't touch the frame
  val inset: Float = 4.dp.toPx()
  val box = Rect(-inset, top, width + inset, result.getLineBottom(lines.last))
  val radius = CornerRadius(style.codeCornerRadius.toPx())
  drawRoundRect(style.codeBackground, box.topLeft, box.size, radius)
  drawRoundRect(style.codeOutline, box.topLeft, box.size, radius, style = Stroke(1.dp.toPx()))
}

/** A chip's icon and the gap after it, in the prompt's font size: `size-[1em]`, `gap-[0.2em]`. */
const val COMPOSER_CHIP_ICON_EM: Float = 1f
const val COMPOSER_CHIP_GAP_EM: Float = 0.2f

/** An attachment chip's size against its name, `text-3xs`. */
const val COMPOSER_CHIP_DETAIL_SCALE: Float = 0.7f
