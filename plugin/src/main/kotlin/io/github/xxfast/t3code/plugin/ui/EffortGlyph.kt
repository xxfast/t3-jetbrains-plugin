package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.SelectProviderOptionDescriptor
import kotlin.math.max
import kotlin.math.roundToInt

// The composer's effort bars, ported from upstream's
// `apps/web/src/components/chat/EffortGlyph.tsx`.

/** Upstream's `effortDisplay` client setting: the level's name, or rising bars. */
enum class EffortDisplay(val value: String) {
  LABEL("label"),
  BARS("bars"),
  ;

  companion object {
    /** `EffortDisplay`'s decoding default, "label", for anything else. */
    fun of(value: String?): EffortDisplay = entries.find { it.value == value } ?: LABEL
  }
}

private const val GLYPH_HEIGHT_SM: Int = 14
private const val GLYPH_HEIGHT_XS: Int = 12

// Bars stop this far above the box's bottom, which puts their flat base on the text baseline.
private const val BASELINE_INSET: Int = 2

// Ultracode changes the workflow, not only intensity, so it keeps its name like Ultrathink.
// Max is named too, so the full scale stops short of it.
private val NAMED_LEVELS: Set<String> = setOf("ultracode", "max")
private val OFF_LEVELS: Set<String> = setOf("off", "none")

/** What [resolveEffortGlyph] answers: [special] names a level the bars don't draw. */
data class EffortGlyphState(
  val count: Int,
  val filled: Int,
  val special: String?,
  val label: String,
)

/**
 * `resolveEffortGlyph`: maps a reasoning select to bars, one per ordinary level, filled up to
 * the current one, with an off level as none filled. Max, prompt-injected and workflow levels are
 * named instead of drawn.
 */
fun resolveEffortGlyph(
  descriptor: SelectProviderOptionDescriptor,
  current: String?,
): EffortGlyphState? {
  val special: Set<String> = descriptor.promptInjectedValues.orEmpty().toSet() + NAMED_LEVELS
  val levels = descriptor.options.filter { it.id !in special }
  if (levels.size < 2 || current == null) return null
  val startsAtOff: Boolean = levels.first().id in OFF_LEVELS
  val count: Int = if (startsAtOff) levels.size - 1 else levels.size
  if (current in special) {
    val label: String = descriptor.options.find { it.id == current }?.label ?: current
    return EffortGlyphState(count = count, filled = count, special = label, label = label)
  }
  val index: Int = levels.indexOfFirst { it.id == current }
  if (index < 0) return null
  return EffortGlyphState(
    count = count,
    filled = if (startsAtOff) index else index + 1,
    special = null,
    label = levels[index].label,
  )
}

/** `effortBarHeights`: bar heights that rise by one whole-pixel step, from about half height. */
fun effortBarHeights(count: Int, maxBar: Int): List<Int> {
  // Rounding each bar separately mixes 1px and 2px steps, so round the step once.
  val step: Int = max(1, (maxBar / 2.0 / max(count - 1, 1)).roundToInt())
  return List(count) { index -> maxBar - (count - 1 - index) * step }
}

/**
 * `EffortGlyph`: rising bars on whole pixels, 2 wide and 4 apart, in [color] (the text's). The
 * box is centred like the icons beside it; the bars sit on the text baseline inside it. Unfilled
 * bars are at 40%.
 */
@Composable
fun EffortGlyph(
  count: Int,
  filled: Int,
  color: Color,
  size: ComposerControlSize = LocalComposerControlSize.current,
) {
  val height: Int = if (size == ComposerControlSize.XS) GLYPH_HEIGHT_XS else GLYPH_HEIGHT_SM
  val step = 4
  val width: Int = step * count - 2
  val maxBar: Int = height - BASELINE_INSET
  val heights: List<Int> = effortBarHeights(count, maxBar)
  Canvas(Modifier.size(width.dp, height.dp)) {
    val unit: Float = 1.dp.toPx()
    heights.forEachIndexed { index, barHeight ->
      drawRect(
        color = color.copy(alpha = color.alpha * if (index < filled) 1f else 0.4f),
        topLeft = Offset(x = index * step * unit, y = (maxBar - barHeight) * unit),
        size = Size(width = 2 * unit, height = barHeight * unit),
      )
    }
  }
}
