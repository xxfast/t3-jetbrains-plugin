package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * `ScrollToEndButton`, chat/TimelineOverlays.tsx: a `glass` `xs` pill above the composer. Showing
 * waits 150ms, as `showScrollDebouncer` does, so it doesn't flash while a thread settles; hiding
 * is immediate.
 */
@Composable
fun ScrollToEndButton(
  visible: Boolean, // `showScrollToBottom`
  onScrollToEnd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var shown: Boolean by remember { mutableStateOf(false) }
  LaunchedEffect(visible) {
    if (visible) delay(SHOW_DELAY_MS)
    shown = visible
  }
  if (!visible || !shown) return

  val interactions = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  // `border-border/60`, the full border on hover
  val border: Color = JewelTheme.globalColors.borders.normal
  Row(
    modifier = modifier
      .padding(vertical = 6.dp) // `py-1.5`
      .height(24.dp)
      .clip(CircleShape)
      .background(composerGlassSurface())
      .border(1.dp, if (hovered) border else border.copy(alpha = 0.6f), CircleShape)
      .hoverable(interactions)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onScrollToEnd,
      )
      .padding(horizontal = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph("chevron-down", muted(), 14.dp)
    Text("Scroll to end", style = small())
  }
}

private const val SHOW_DELAY_MS: Long = 150
