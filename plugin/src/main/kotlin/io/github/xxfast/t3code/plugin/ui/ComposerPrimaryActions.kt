package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Canvas
import org.jetbrains.jewel.foundation.theme.JewelTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** Upstream's `--destructive`, the stop button's fill. */
private val DESTRUCTIVE: Color = Color(0xFFEF4444)

/**
 * The composer's round button, `ComposerPrimaryActions.tsx` from line 263: Stop while a run is
 * going and there's nothing to send, else send. Sending shows a spinner. Running, the icon says
 * whether a click queues or steers; Ctrl/Cmd-click does the other ([onSend]'s `alternate`).
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun ComposerPrimaryActions(
  state: ComposerState,
  hasSendableContent: Boolean,
  onSend: (alternate: Boolean) -> Unit,
  onInterrupt: () -> Unit,
  compactBeforeSendTokens: Long? = null,
  keepFullHistory: Boolean = false,
  onToggleKeepFullHistory: () -> Unit = {},
) {
  if (state.running && state.canInterrupt && !hasSendableContent && !state.editingQueuedMessage) {
    StopGenerationButton(enabled = state.enabled, onInterrupt = onInterrupt)
    return
  }
  val mode: ComposerDispatchMode = resolveComposerDispatchMode(state.running, false, state.queue)
  // `compactTokens` is null while editing a queued message, ComposerPrimaryActions.tsx:272
  val compactTokens: String? = compactBeforeSendTokens
    ?.takeUnless { state.editingQueuedMessage }
    ?.let(::formatContextWindowTokens)
  val compactsBeforeSend: Boolean = compactTokens != null && !keepFullHistory
  val tooltip: String = if (compactsBeforeSend && !state.sending && state.disabledReason == null) {
    "Summarize $compactTokens tokens of history, then send"
  } else {
    submitTooltip(state, mode)
  }
  if (compactTokens != null) {
    CompactBeforeSendToggle(compactTokens, keepFullHistory, state.enabled, onToggleKeepFullHistory)
  }
  Tooltip(tooltip = { Text(tooltip) }) {
    SendButton(
      enabled = state.enabled && !state.sending && hasSendableContent,
      sending = state.sending,
      onSend = onSend,
    ) {
      when {
        state.editingQueuedMessage -> Glyph("check", Color.White, 16.dp)
        mode == ComposerDispatchMode.QUEUE -> Glyph("list-plus", Color.White, 16.dp)
        mode == ComposerDispatchMode.STEER -> Glyph("corner-up-right", Color.White, 16.dp)
        else -> SendArrow()
      }
    }
  }
}

/**
 * `submitTooltip`, `ComposerPrimaryActions.tsx` around line 298: while running, which click does
 * what; else the label. `alternateShortcutLabel` is the plugin's Ctrl/Cmd+Enter.
 */
fun submitTooltip(state: ComposerState, mode: ComposerDispatchMode): String {
  val editing: Boolean = state.editingQueuedMessage
  if (state.sending) return if (editing) "Updating queued message" else "Submitting message"
  if (state.disabledReason != null) return state.disabledReason
  if (editing) return "Update queued message"
  return when (mode) {
    ComposerDispatchMode.QUEUE, ComposerDispatchMode.STEER -> {
      val behaviour: String = if (state.queue) "queue" else "steer"
      val alternate: String = if (state.queue) "steer" else "queue"
      "Click to $behaviour, Ctrl/⌘-click or Ctrl/⌘+Enter to $alternate"
    }

    else -> "Submit message"
  }
}

@OptIn(ExperimentalFoundationApi::class) // onClick with keyboard modifiers
@Composable
private fun SendButton(
  enabled: Boolean,
  sending: Boolean,
  onSend: (alternate: Boolean) -> Unit,
  icon: @Composable () -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val action: Color = accent()
  // `hover:bg-message-action-hover`, the primary mixed 10% toward the page
  val fill: Color = if (hovered && enabled) action.copy(alpha = 0.9f) else action
  Box(
    modifier = Modifier
      .alpha(if (enabled || sending) 1f else 0.64f)
      .size(32.dp)
      .then(if (enabled) Modifier.shadow(1.dp, CircleShape) else Modifier)
      .clip(CircleShape)
      .background(fill)
      .hoverable(interactions, enabled)
      .onClick(enabled = enabled, keyboardModifiers = { isCtrlPressed || isMetaPressed }) {
        onSend(true)
      }
      .onClick(enabled = enabled, keyboardModifiers = { !isCtrlPressed && !isMetaPressed }) {
        onSend(false)
      },
    contentAlignment = Alignment.Center,
  ) {
    if (sending) CircularProgressIndicator(Modifier.size(16.dp)) else icon()
  }
}

/**
 * The "Compact 301k" pill left of send, `ComposerPrimaryActions.tsx` around line 365: on, the
 * next send compacts that much history first, in the warning tone; off ("Full") it keeps it all.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip, onClick
@Composable
private fun CompactBeforeSendToggle(
  tokens: String,
  keepFullHistory: Boolean,
  enabled: Boolean,
  onToggle: () -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val warning: Color = if (JewelTheme.isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
  val tone: Color = when {
    !keepFullHistory -> warning
    hovered -> JewelTheme.globalColors.text.normal
    else -> JewelTheme.globalColors.text.info
  }
  val outline: Color =
    if (keepFullHistory) JewelTheme.globalColors.borders.normal else warning.copy(alpha = 0.4f)
  val tooltip: String = if (keepFullHistory) {
    "Next send keeps all $tokens tokens. Click to compact first"
  } else {
    "Next send compacts $tokens tokens first. Click to keep full history"
  }
  val fill: Color =
    if (!keepFullHistory && hovered) warning.copy(alpha = 0.08f) else Color.Transparent
  Tooltip(tooltip = { Text(tooltip) }) {
    Row(
      modifier = Modifier
        .alpha(if (enabled) 1f else 0.64f)
        .height(32.dp)
        .clip(CircleShape)
        .background(fill)
        .border(1.dp, outline, CircleShape)
        .hoverable(interactions, enabled)
        .onClick(enabled = enabled, onClick = onToggle)
        .padding(horizontal = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph("minimize-2", tone, 14.dp)
      Text(if (keepFullHistory) "Full" else "Compact", style = small(), color = tone)
      Text(tokens, style = small(), color = tone)
    }
  }
}

/** Upstream's own 14px arrow: a stem and a chevron, 1.8 wide, round caps. */
@Composable
private fun SendArrow() {
  Canvas(Modifier.size(14.dp)) {
    val unit: Float = size.width / 14f
    val path = Path().apply {
      moveTo(7f * unit, 11.5f * unit)
      lineTo(7f * unit, 2.5f * unit)
      moveTo(3f * unit, 6.5f * unit)
      lineTo(7f * unit, 2.5f * unit)
      lineTo(11f * unit, 6.5f * unit)
    }
    drawPath(
      path = path,
      color = Color.White,
      style = Stroke(width = 1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }
}

/** `renderStopGenerationButton`: a red disc with a rounded square, tooltip "Interrupt". */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip, onClick
@Composable
private fun StopGenerationButton(enabled: Boolean, onInterrupt: () -> Unit) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  Tooltip(tooltip = { Text("Interrupt") }) {
    Box(
      modifier = Modifier
        .alpha(if (enabled) 1f else 0.64f)
        .size(32.dp)
        .clip(CircleShape)
        .background(if (hovered) DESTRUCTIVE else DESTRUCTIVE.copy(alpha = 0.9f))
        .hoverable(interactions, enabled)
        .onClick(enabled = enabled, onClick = onInterrupt),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(Modifier.size(12.dp)) {
        val unit: Float = size.width / 12f
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(2f * unit, 2f * unit),
          size = Size(8f * unit, 8f * unit),
          cornerRadius = CornerRadius(1.5f * unit),
        )
      }
    }
  }
}
