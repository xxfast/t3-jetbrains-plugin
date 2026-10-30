package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.plugin.actions.UndoNotice
import io.github.xxfast.t3code.plugin.actions.noticeSummary
import kotlin.math.max
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** Upstream's `transition-opacity duration-200 ease-out`. */
private const val UNDO_FADE_MS: Int = 200

/**
 * Upstream's `SidebarThreadUndoNotice` (sidebar/SidebarThreadUndoNotice.tsx): fills the free
 * space in the footer's utility row, so it never moves or covers the list. The short summary,
 * then [label] as an inline button. It fades in and out and keeps the last text while it fades.
 */
@Composable
fun SidebarThreadUndoNotice(
  notice: UndoNotice?,
  label: String, // `threadUndoLabel`, "⌘Z to undo"
  onUndo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Keeps the last text on screen while it fades out, upstream's `shownSummary`
  var shown: UndoNotice? by remember { mutableStateOf(notice) }
  if (notice != null && notice != shown) shown = notice
  // Starts hidden, so a notice that's there on first composition fades in too (`starting:`)
  val visible: MutableTransitionState<Boolean> = remember { MutableTransitionState(false) }
  visible.targetState = notice != null
  val text: UndoNotice = notice ?: shown ?: return
  AnimatedVisibility(
    visibleState = visible,
    modifier = modifier,
    enter = fadeIn(tween(UNDO_FADE_MS, easing = EaseOut)),
    exit = fadeOut(tween(UNDO_FADE_MS, easing = EaseOut)),
  ) {
    // `aria-hidden` and `inert` while it fades out
    val hidden: Modifier = if (notice == null) Modifier.clearAndSetSemantics {} else Modifier
    ReversedWrap(Modifier.fillMaxWidth().height(24.dp).clipToBounds().then(hidden)) {
      UndoButton(label, onClick = onUndo.takeIf { notice != null })
      // Screen readers hear the full sentence from ThreadUndos, as upstream's announcement
      Text(
        text = "${noticeSummary(text)},",
        style = small(),
        color = JewelTheme.globalColors.text.info,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

/**
 * Upstream's `flex-row-reverse flex-wrap gap-x-1 overflow-hidden` on one line: the button sits
 * at the end, and a summary that doesn't fit beside it drops out of sight rather than being cut
 * off mid-word. Takes the button, then the summary.
 */
@Composable
private fun ReversedWrap(modifier: Modifier, content: @Composable () -> Unit) {
  Layout(content = content, modifier = modifier) { measurables, constraints ->
    val gap: Int = 4.dp.roundToPx()
    val button: Placeable = measurables[0].measure(Constraints())
    val summary: Placeable = measurables[1].measure(Constraints())
    val fits: Boolean = button.width + gap + summary.width <= constraints.maxWidth
    val width: Int = if (constraints.hasBoundedWidth) {
      constraints.maxWidth
    } else {
      button.width + gap + summary.width
    }
    val height: Int = constraints.constrainHeight(max(button.height, summary.height))
    layout(width, height) {
      button.place(width - button.width, (height - button.height) / 2)
      if (fits) {
        summary.place(width - button.width - gap - summary.width, (height - summary.height) / 2)
      }
    }
  }
}

/**
 * Upstream's `InlineButton`: foreground text, medium weight, underlined on hover, with
 * `focus-visible:outline-2 focus-visible:outline-ring`.
 */
@Composable
private fun UndoButton(label: String, onClick: (() -> Unit)?) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val focused: Boolean by interactions.collectIsFocusedAsState()
  Text(
    text = label,
    style = small(),
    fontWeight = FontWeight.Medium,
    textDecoration = TextDecoration.Underline.takeIf { hovered && onClick != null },
    maxLines = 1,
    softWrap = false,
    modifier = if (onClick == null) {
      Modifier
    } else {
      Modifier
        .then(if (focused) Modifier.border(2.dp, accent(), RoundedCornerShape(2.dp)) else Modifier)
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interactionSource = interactions, indication = null, onClick = onClick)
    },
  )
}
