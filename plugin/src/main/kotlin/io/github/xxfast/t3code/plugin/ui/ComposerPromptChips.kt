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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.rememberResourcePainterProvider

/**
 * The chips over one prompt's draft (ComposerPromptChipsLogic.kt). The draft keeps the markdown
 * that gets sent; this parses it once per text and hands the same chips to the field's [output]
 * (labels in place of the references), its [input] (edits that can't split a chip) and
 * [composerPromptChipBackgrounds] (the chips' backgrounds and icons).
 *
 * [skills], [records], [iconRoom] and [detailColor] are snapshot state, so the field re-runs
 * its output transformation when the provider's skills or the draft's attachments change.
 */
@Stable
class ComposerPromptChips {
  /** The provider's skills for the thread's cwd: only those draw a `$name` as a chip. */
  var skills: List<ServerProviderSkill> by mutableStateOf(emptyList())

  /** What the draft's context links point at, [composerContextRecordsFromDraft]. */
  var records: Map<String, ComposerDraftContextRecord> by mutableStateOf(emptyMap())

  /** Extra advance on a chip's leading space: the room its icon takes. */
  var iconRoom: TextUnit by mutableStateOf(TextUnit.Unspecified)

  /** The colour of an attachment chip's size. */
  var detailColor: Color by mutableStateOf(Color.Unspecified)

  // The last text's tokens, `preserveTrailingFrom`: a chip stays one when its trailing space goes
  private var confirmed: List<ComposerInlineToken> = emptyList()
  private var cached: Parsed? = null

  private class Parsed(
    val text: String,
    val skills: List<ServerProviderSkill>,
    val records: Map<String, ComposerDraftContextRecord>,
    val chips: List<ComposerPromptChip>,
  )

  /** The chips in [text], in order. */
  fun chipsFor(text: String): List<ComposerPromptChip> {
    val skills: List<ServerProviderSkill> = skills
    val records: Map<String, ComposerDraftContextRecord> = records
    cached
      ?.takeIf { it.text == text && it.skills === skills && it.records === records }
      ?.let { return it.chips }
    val tokens: List<ComposerInlineToken> = collectComposerInlineTokens(text, confirmed)
    confirmed = tokens
    val chips: List<ComposerPromptChip> = composerPromptChips(text, tokens, skills, records)
    cached = Parsed(text, skills, records, chips)
    return chips
  }

  /** Each chip's source swapped for [composerChipDisplay]: room for the icon, label, size. */
  val output: OutputTransformation = object : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
      val chips: List<ComposerPromptChip> = chipsFor(asCharSequence().toString())
      if (chips.isEmpty()) return
      val ranges: List<ComposerChipDisplayRange> = composerChipDisplayRanges(chips)
      // Back to front so the earlier ranges hold, then the styles on the final text
      for (range in ranges.asReversed()) {
        replace(range.chip.start, range.chip.end, range.display.text)
      }
      val room: TextUnit = iconRoom
      val detail: Color = detailColor
      for (range in ranges) {
        if (room.isSpecified) {
          addStyle(SpanStyle(letterSpacing = room), range.start, range.start + 1)
        }
        range.display.detail?.let { slot ->
          val from: Int = range.start + slot.first
          addStyle(SpanStyle(color = detail), from, from + slot.count())
        }
      }
    }
  }

  /**
   * Edits as upstream's atom nodes take them: [atomicComposerPromptEdit] widens a cut into a
   * chip to the whole chip (one change, so undo brings it back whole) and moves typing out of
   * one, and [snapComposerPromptSelection] keeps the caret out of a chip's source.
   */
  val input: InputTransformation = object : InputTransformation {
    @OptIn(ExperimentalFoundationApi::class) // changes
    override fun TextFieldBuffer.transformInput() {
      val before: List<ComposerPromptChip> = chipsFor(originalText.toString())
      val textChanged: Boolean = changes.changeCount > 0
      if (changes.changeCount == 1 && before.isNotEmpty()) {
        val original: TextRange = changes.getOriginalRange(0)
        val range: TextRange = changes.getRange(0)
        val inserted: String = asCharSequence().subSequence(range.min, range.max).toString()
        val atomic: TextRange = atomicComposerPromptEdit(before, original.min, original.max)
        val insertion: ComposerPromptInsertion =
          separateComposerPromptInsertion(before, atomic.min, atomic.max, inserted)
        if (atomic != TextRange(original.min, original.max) || insertion.text != inserted) {
          revertAllChanges()
          replace(atomic.min, atomic.max, insertion.text)
          selection = TextRange(atomic.min + insertion.cursor)
        }
      }
      val after: List<ComposerPromptChip> = chipsFor(asCharSequence().toString())
      val snapped: TextRange =
        snapComposerPromptSelection(after, originalSelection, selection, textChanged)
      if (snapped != selection) selection = snapped
    }
  }
}

/**
 * The line [caret] (a draft offset) is on in [layout], or null when [layout] isn't of [text]
 * as the chips show it yet. The layout is of the transformed text, so offsets go through
 * [composerPromptDisplayOffset].
 */
fun composerPromptCaretLine(
  layout: TextLayoutResult?,
  text: String,
  caret: Int,
  chips: List<ComposerPromptChip>,
): Int? {
  if (layout == null) return null
  val laidOut: Int = layout.layoutInput.text.length
  if (laidOut != composerPromptDisplayLength(text.length, chips)) return null
  val offset: Int = composerPromptDisplayOffset(chips, caret.coerceIn(0, text.length))
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

/** A painter per chip icon, for [composerPromptChipBackgrounds]. */
@Composable
fun rememberComposerPromptChipPainters(): Map<ComposerPromptChipIcon, Painter> =
  ComposerPromptChipIcon.entries.associateWith { icon ->
    val key: IconKey = icon.key
    rememberResourcePainterProvider(key, key.iconClass).getPainter().value
  }

/** How a chip looks behind its label. */
class ComposerPromptChipStyle(
  val fill: Color,
  val outline: Color,
  val iconSize: Dp = 14.dp,
  val cornerRadius: Dp = 4.dp,
)

/**
 * Draws [chips]' backgrounds and icons behind the prompt's text, from the [layout] of the
 * transformed text. The text sits [contentPadding] into the field and scrolls by [scroll], as
 * Jewel's undecorated `TextArea` lays it out. Each chip's leading space is its icon's slot.
 */
fun Modifier.composerPromptChipBackgrounds(
  chips: ComposerPromptChips,
  draft: TextFieldState,
  layout: State<(() -> TextLayoutResult?)?>,
  scroll: ScrollState,
  contentPadding: PaddingValues,
  painters: Map<ComposerPromptChipIcon, Painter>,
  style: ComposerPromptChipStyle,
): Modifier = drawBehind {
  val text: String = draft.text.toString()
  val found: List<ComposerPromptChip> = chips.chipsFor(text)
  if (found.isEmpty()) return@drawBehind
  val result: TextLayoutResult = layout.value?.invoke() ?: return@drawBehind
  // A layout from before the last edit is of another text
  if (result.layoutInput.text.length != composerPromptDisplayLength(text.length, found)) {
    return@drawBehind
  }
  val left: Float = contentPadding.calculateLeftPadding(layoutDirection).toPx()
  val top: Float = contentPadding.calculateTopPadding().toPx()
  val bottom: Float = size.height - contentPadding.calculateBottomPadding().toPx()
  val fontHeight: Float? = result.layoutInput.style.fontSize
    .takeIf { it.isSpecified }
    ?.toPx()
    ?.times(CHIP_HEIGHT_PER_FONT_SIZE)
  val radius = CornerRadius(style.cornerRadius.toPx())
  val stroke = Stroke(1.dp.toPx())
  clipRect(top = top, bottom = bottom) {
    translate(left, top - scroll.value) {
      for (range in composerChipDisplayRanges(found)) {
        val firstLine: Int = result.getLineForOffset(range.start)
        val lastLine: Int = result.getLineForOffset(range.end - 1)
        // One piece per line, should a chip ever be wider than the field
        for (line in firstLine..lastLine) {
          val from: Int = maxOf(range.start, result.getLineStart(line))
          val to: Int = minOf(range.end, result.getLineEnd(line))
          if (from >= to) continue
          val lineTop: Float = result.getLineTop(line)
          val lineBottom: Float = result.getLineBottom(line)
          val height: Float = minOf(fontHeight ?: Float.MAX_VALUE, lineBottom - lineTop)
          val x: Float = result.getBoundingBox(from).left
          val box = Rect(
            left = x,
            top = (lineTop + lineBottom - height) / 2,
            right = result.getBoundingBox(to - 1).right,
            bottom = (lineTop + lineBottom + height) / 2,
          )
          drawRoundRect(style.fill, box.topLeft, box.size, radius)
          drawRoundRect(style.outline, box.topLeft, box.size, radius, style = stroke)
        }
        val painter: Painter = painters[range.chip.icon] ?: continue
        val slot: Rect = result.getBoundingBox(range.start)
        val line: Int = result.getLineForOffset(range.start)
        val centreY: Float = (result.getLineTop(line) + result.getLineBottom(line)) / 2
        val icon: Float = style.iconSize.toPx()
        val inset: Float = CHIP_ICON_INSET.toPx()
        translate(slot.left + inset, centreY - icon / 2) {
          with(painter) { draw(Size(icon, icon)) }
        }
      }
    }
  }
}

/** A chip's height for the prompt's font size, upstream's `h-5` on 13px text. */
private const val CHIP_HEIGHT_PER_FONT_SIZE: Float = 1.5f

/** The space between a chip's left edge and its icon. */
val CHIP_ICON_INSET: Dp = 4.dp

/** The room a chip's leading space makes: the inset, the icon, and a gap before the label. */
fun composerPromptChipIconRoom(style: ComposerPromptChipStyle): Dp =
  CHIP_ICON_INSET + style.iconSize + 2.dp

