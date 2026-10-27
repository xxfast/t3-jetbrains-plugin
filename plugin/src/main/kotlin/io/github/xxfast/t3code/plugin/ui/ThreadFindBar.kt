package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** `ThreadFindBar.tsx`, with native text field, match counter, navigation, and Escape. */
@Composable
fun ThreadFindBar(find: ThreadFindState) {
  if (!find.isOpen) return
  val field: TextFieldState = rememberTextFieldState(find.query)
  val focus = remember { FocusRequester() }
  LaunchedEffect(find.focusRequest) {
    focus.requestFocus()
    field.edit { selection = TextRange(0, length) }
  }
  LaunchedEffect(field) {
    snapshotFlow { field.text.toString() }.collect { value ->
      if (value.length > 200) field.edit { replace(200, length, "") }
      find.changeQuery(value)
    }
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.Escape -> { find.close(); true }
          Key.Enter, Key.NumPadEnter -> { find.step(if (event.isShiftPressed) -1 else 1); true }
          else -> false
        }
      },
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextField(
      state = field,
      modifier = Modifier.weight(1f).focusRequester(focus),
      placeholder = { Text("Find in thread") },
    )
    Text(
      formatThreadFindCount(find.activeIndex, find.matches.size),
      color = if (find.query.isNotBlank() && find.matches.isEmpty())
        JewelTheme.globalColors.text.error else muted(),
    )
    OutlinedButton(onClick = { find.step(-1) }, enabled = find.matches.isNotEmpty()) {
      Text("Previous")
    }
    OutlinedButton(onClick = { find.step(1) }, enabled = find.matches.isNotEmpty()) {
      Text("Next")
    }
    OutlinedButton(onClick = find::close) { Text("Close") }
  }
}
