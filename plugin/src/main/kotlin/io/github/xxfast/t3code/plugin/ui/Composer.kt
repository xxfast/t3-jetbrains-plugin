package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * The box at the bottom of the ChatView: a text area and a round send button at the bottom
 * right, like upstream's `ChatComposer.tsx` and `ComposerPrimaryActions.tsx`. Plain text only.
 */
@Composable
fun Composer(
  state: ComposerState,
  draft: TextFieldState,
  onSend: (text: String, mode: ComposerDispatchMode) -> Unit,
  onQueueChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val focused: Boolean by interactions.collectIsFocusedAsState()
  val outline: Color = if (focused) accent() else JewelTheme.globalColors.borders.normal

  // [alternate] is Ctrl/Cmd+Enter while running: the other follow-up behaviour, steer <-> queue
  fun send(alternate: Boolean) {
    val text: String = draft.text.toString()
    if (!sendable(text, state)) return
    onSend(text.trim(), resolveComposerDispatchMode(state.running, alternate, state.queue))
  }

  // ComposerPrimaryActions.tsx, around line 276
  val label: String = when (resolveComposerDispatchMode(state.running, false, state.queue)) {
    ComposerDispatchMode.QUEUE -> "Queue message"
    ComposerDispatchMode.STEER -> "Steer message"
    else -> "Submit message"
  }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, outline, RoundedCornerShape(16.dp))
        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
    ) {
      TextArea(
        state = draft,
        modifier = Modifier
          .fillMaxWidth()
          .onPreviewKeyEvent { event ->
            val action: ComposerKey = composerKey(
              enter = event.key == Key.Enter || event.key == Key.NumPadEnter,
              keyDown = event.type == KeyEventType.KeyDown,
              shift = event.isShiftPressed,
              alt = event.isAltPressed,
              ctrl = event.isCtrlPressed,
              meta = event.isMetaPressed,
              running = state.running,
            )
            when (action) {
              ComposerKey.SEND -> send(alternate = false)

              ComposerKey.SEND_ALTERNATE -> send(alternate = true)

              // Inserted by hand: the text field's own mapping may not newline a shifted Enter
              ComposerKey.NEWLINE -> draft.edit {
                val at: TextRange = selection
                replace(at.min, at.max, "\n")
                placeCursorAfterCharAt(at.min)
              }

              ComposerKey.IGNORE -> return@onPreviewKeyEvent false
            }
            return@onPreviewKeyEvent true
          },
        enabled = state.enabled,
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 12),
        interactionSource = interactions,
        // Upstream's short placeholder (ChatComposer.tsx:6695). The long one offers @tags,
        // skills and slash commands, which this plugin doesn't have.
        placeholder = { Text("Ask anything...") },
        undecorated = true,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (state.showQueueToggle) {
          CheckboxRow(
            text = "Queue instead of steering",
            checked = state.queue,
            onCheckedChange = onQueueChange,
            textStyle = JewelTheme.typography.small,
          )
        }
        Spacer(Modifier.weight(1f))
        if (state.sending) {
          CircularProgressIndicator(Modifier.padding(4.dp).size(16.dp))
        } else {
          IconActionButton(
            key = AllIconsKeys.General.ArrowUp,
            contentDescription = label,
            onClick = { send(alternate = false) },
            enabled = sendable(draft.text.toString(), state),
          )
        }
      }
    }
    state.disabledReason?.let { reason ->
      Text(reason, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.info)
    }
    state.error?.let { error ->
      Text(error, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.error)
    }
  }
}
