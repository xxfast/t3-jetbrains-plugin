package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.Text

/** `--control-radius`, the corner of a composer control. */
private val CONTROL_SHAPE: Shape = RoundedCornerShape(8.dp)

/** `rounded-3xl`, the composer's own corner. */
val COMPOSER_SHAPE: Shape = RoundedCornerShape(24.dp)

/**
 * The composer's card, `ComposerSurface.tsx`'s `Shell`, `Host` and `Main`: a raised fill, a
 * hairline outline (`--chat-composer-outline`, black at 8% light, white at 5% dark) and
 * `shadow-composer` on light themes only. [highlight] is the drag-over ring.
 */
@Composable
fun ComposerSurface(
  modifier: Modifier = Modifier,
  highlight: Boolean = false,
  content: @Composable () -> Unit,
) {
  val dark: Boolean = JewelTheme.isDark
  val outline: Color =
    if (dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f)
  val ring: Color = if (highlight) accent().copy(alpha = 0.7f) else outline
  Box(
    modifier = modifier
      .then(if (dark) Modifier else Modifier.shadow(8.dp, COMPOSER_SHAPE, clip = false))
      .clip(COMPOSER_SHAPE)
      .background(composerGlassSurface())
      .then(if (highlight) Modifier.background(subtle(0.06f)) else Modifier)
      .border(1.dp, ring, COMPOSER_SHAPE),
  ) {
    content()
  }
}

/**
 * `--chat-composer-glass-surface`: `--card` on light themes, `--surface-raised` on dark. The IDE
 * has no raised colour, so dark lifts the panel a step with the text colour.
 */
@Composable
fun composerGlassSurface(): Color {
  val panel: Color = JewelTheme.globalColors.panelBackground
  if (!JewelTheme.isDark) return panel
  return JewelTheme.globalColors.text.normal.copy(alpha = 0.04f).compositeOver(panel)
}

private fun Color.compositeOver(background: Color): Color {
  val a: Float = alpha + background.alpha * (1 - alpha)
  if (a == 0f) return Color.Transparent
  fun channel(top: Float, bottom: Float): Float =
    (top * alpha + bottom * background.alpha * (1 - alpha)) / a
  return Color(
    red = channel(red, background.red),
    green = channel(green, background.green),
    blue = channel(blue, background.blue),
    alpha = a,
  )
}

/**
 * `ComposerControlSize` in `ComposerControl.tsx`: `sm` in the footer, `xs` in the resting
 * composer's strip. Controls read it from [LocalComposerControlSize].
 */
enum class ComposerControlSize { SM, XS }

/** The size every composer control under it draws at. */
val LocalComposerControlSize: ProvidableCompositionLocal<ComposerControlSize> =
  staticCompositionLocalOf { ComposerControlSize.SM }

/**
 * `ComposerControl` (`ComposerControl.tsx`): a borderless button that fills on hover or while
 * [pressed] (`aria-pressed`, `data-pressed`), 28dp tall. `sm` is medium weight with muted icons,
 * `xs` is the smaller muted variant.
 */
@Composable
fun ComposerControl(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  pressed: Boolean = false,
  size: ComposerControlSize = LocalComposerControlSize.current,
  square: Boolean = false, // an icon-only button, as wide as it's tall
  content: @Composable RowScope.() -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val fill: Color = when {
    pressed && hovered -> subtle(0.08f)
    pressed || hovered && enabled -> subtle(0.06f)
    else -> Color.Transparent
  }
  val xs: Boolean = size == ComposerControlSize.XS
  val height: Dp = if (xs) 24.dp else 28.dp
  Row(
    modifier = modifier
      .alpha(if (enabled) 1f else 0.64f)
      .height(height)
      .then(if (square) Modifier.width(height) else Modifier)
      .clip(CONTROL_SHAPE)
      .background(fill)
      .hoverable(interactions, enabled)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = if (square) 0.dp else if (xs) 7.dp else 10.dp),
    horizontalArrangement =
      Arrangement.spacedBy(if (xs) 4.dp else 6.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/** `ComposerControlIcon`: a lucide glyph at 16dp (`sm`) or 12dp (`xs`), muted. */
@Composable
fun ComposerControlIcon(
  name: String,
  size: ComposerControlSize = LocalComposerControlSize.current,
  tint: Color = JewelTheme.globalColors.text.info,
  large: Boolean = false,
) {
  val dp: Dp = when {
    size == ComposerControlSize.XS -> 12.dp
    large -> 18.dp
    else -> 16.dp
  }
  Glyph(name, tint, dp)
}

/** `ComposerControlChevron`: the select's chevron, 14dp and muted. */
@Composable
fun ComposerControlChevron(size: ComposerControlSize = LocalComposerControlSize.current) {
  val xs: Boolean = size == ComposerControlSize.XS
  val tint: Color = JewelTheme.globalColors.text.info
  Glyph("chevron-down", if (xs) tint.copy(alpha = 0.5f) else tint, if (xs) 12.dp else 14.dp)
}

/** `ComposerControlSeparator`: a vertical rule between control groups, 16dp tall. */
@Composable
fun ComposerControlSeparator(size: ComposerControlSize = LocalComposerControlSize.current) {
  Box(
    Modifier
      .padding(horizontal = 2.dp)
      .width(1.dp)
      .height(if (size == ComposerControlSize.XS) 14.dp else 16.dp)
      .background(JewelTheme.globalColors.borders.normal),
  )
}

/** The text colour of a `sm` control's label, upstream's `text-secondary-label`. */
@Composable
fun composerControlLabel(): Color = JewelTheme.globalColors.text.normal.copy(alpha = 0.85f)

/** An `xs` control's text, `text-muted-foreground/70`. */
@Composable
private fun composerControlMuted(): Color = JewelTheme.globalColors.text.info.copy(alpha = 0.7f)

/**
 * A control's label (`data-composer-control-label`): medium weight in `sm`; in `xs` regular,
 * small and `text-muted-foreground/70`. Long labels end in an ellipsis past [maxWidth].
 */
@Composable
fun ComposerControlLabel(
  text: String,
  maxWidth: Dp = Dp.Unspecified,
  size: ComposerControlSize = LocalComposerControlSize.current,
) {
  val xs: Boolean = size == ComposerControlSize.XS
  Text(
    text = text,
    modifier = Modifier.widthIn(max = maxWidth),
    style = if (xs) small() else LocalTextStyle.current,
    color = if (xs) composerControlMuted() else composerControlLabel(),
    fontWeight = if (xs) FontWeight.Normal else FontWeight.Medium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}
