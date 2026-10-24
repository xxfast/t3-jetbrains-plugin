package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme

private const val SHIMMER_PERIOD_MS: Long = 2_200
private const val SHIMMER_STEPS: Int = 30
private val SHIMMER_BAND: Dp = 72.dp // 4.5rem

/**
 * Upstream's two sweeps (`apps/web/src/index.css`). Both cross the muted text with a band of
 * the foreground colour every 2.2s in 30 steps; they differ in the band's falloff and how far
 * past the end it travels.
 */
enum class Shimmer(val stops: List<Pair<Float, Float>>, val overshoot: Float) {
  /** `live-tool-shine`: an active tool's label, from one band before it to its end. */
  ToolShine(stops = listOf(0f to 0f, 0.5f to 1f, 1f to 0f), overshoot = 0f),

  /**
   * `live-activity-focus` (`ActivityShimmerOverlay`, TimelineRowParts.tsx): the mask's stops at
   * 0.675rem, 1.575rem and 2.25rem of 4.5rem, travelling a band past the end.
   */
  ActivityFocus(
    stops = listOf(
      0f to 0f, 0.15f to 0.12f, 0.35f to 0.55f, 0.5f to 1f, 0.65f to 0.55f, 0.85f to 0.12f,
      1f to 0f,
    ),
    overshoot = 1f,
  ),
}

/**
 * Sweeps [shimmer] across whatever this draws while [active]. `SrcAtop` paints the band onto
 * the drawn pixels only, the same as upstream's foreground copy under a mask.
 */
@Composable
fun Modifier.activityShimmer(active: Boolean, shimmer: Shimmer): Modifier {
  if (!active) return this
  val highlight: Color = JewelTheme.globalColors.text.normal
  val step: MutableIntState = remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    while (true) {
      withInfiniteAnimationFrameMillis { millis: Long ->
        // `steps(30)`: the band jumps, so only a step redraws
        step.intValue = (millis % SHIMMER_PERIOD_MS * SHIMMER_STEPS / SHIMMER_PERIOD_MS).toInt()
      }
    }
  }
  val colors: Array<Pair<Float, Color>> = remember(highlight, shimmer) {
    shimmer.stops.map { (at, alpha) -> at to highlight.copy(alpha = alpha) }.toTypedArray()
  }
  return this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
      drawContent()
      val band: Float = SHIMMER_BAND.toPx()
      val travel: Float = size.width + band + band * shimmer.overshoot
      val start: Float = -band + travel * step.intValue / SHIMMER_STEPS
      drawRect(
        brush = Brush.horizontalGradient(*colors, startX = start, endX = start + band),
        blendMode = BlendMode.SrcAtop,
      )
    }
}
