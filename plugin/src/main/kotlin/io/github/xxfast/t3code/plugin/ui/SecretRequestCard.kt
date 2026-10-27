package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.SecretRequestAnswerInput
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** `SecretRequestCard.tsx`: the secure input stays in this composition, never a draft. */
@Composable
fun SecretRequestCard(
  item: TurnItem.SecretRequest,
  visibility: String,
  disabledReason: String?,
  onAnswer: (suspend (SecretRequestAnswerInput) -> Unit)?,
) {
  val display: String? = secretRequestDisplay(item, visibility)
  if (display != null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Glyph(if (item.secretStatus == "saved") "check" else "lock", muted(), 14.dp)
      Text("${item.label} · $display", color = muted())
    }
    return
  }
  PendingSecretRequestForm(item, disabledReason, onAnswer)
}

@Composable
private fun PendingSecretRequestForm(
  item: TurnItem.SecretRequest,
  disabledReason: String?,
  onAnswer: (suspend (SecretRequestAnswerInput) -> Unit)?,
) {
  val scope: CoroutineScope = rememberCoroutineScope()
  var busy: Boolean by remember(item.id) { mutableStateOf(false) }
  var error: String? by remember(item.id) { mutableStateOf(null) }
  var answered: Boolean by remember(item.id) { mutableStateOf(false) }
  val inFlight = remember(item.id) { AtomicBoolean(false) }
  // Don't use rememberTextFieldState: its saver would retain this across restoration.
  val field: TextFieldState = remember(item.id) { TextFieldState() }
  val hasValue: Boolean = field.text.any { !it.isWhitespace() }
  DisposableEffect(field) {
    onDispose { field.clearText() }
  }
  val enabled: Boolean = disabledReason == null && onAnswer != null && !busy && !answered

  fun send(decline: Boolean) {
    if (!enabled || !inFlight.compareAndSet(false, true)) return
    val input: SecretRequestAnswerInput? =
      secretRequestAnswerInput(item, if (decline) null else field.text.toString())
    if (input == null) {
      inFlight.set(false)
      return
    }
    busy = true
    error = null
    scope.launch {
      try {
        onAnswer?.invoke(input)
        field.clearText()
        answered = true
      } catch (_: CancellationException) {
        throw CancellationException("Secret request interrupted")
      } catch (_: Exception) {
        // Never log or display a failure payload, even if the server echoes the secret.
        error = SECRET_REQUEST_FAILURE
      } finally {
        busy = false
        inFlight.set(false)
      }
    }
  }

  Column(
    modifier = Modifier.fillMaxWidth()
      .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(10.dp))
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(item.label, fontWeight = FontWeight.Medium)
    if (item.reason.isNotBlank()) Text(item.reason, color = muted())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(
        modifier = Modifier.weight(1f).height(30.dp)
          .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (field.text.isEmpty()) Text(item.placeholder ?: "Paste the secret", color = muted())
        BasicSecureTextField(
          state = field,
          enabled = enabled,
          textObfuscationMode = TextObfuscationMode.Hidden,
          textStyle = JewelTheme.defaultTextStyle,
          cursorBrush = SolidColor(JewelTheme.globalColors.text.normal),
          modifier = Modifier.fillMaxSize().padding(vertical = 5.dp)
            .onPreviewKeyEvent { event ->
              if (event.type == KeyEventType.KeyDown &&
                event.key in listOf(Key.Enter, Key.NumPadEnter)) {
                send(decline = false)
                true
              } else false
            },
        )
      }
      DefaultButton(onClick = { send(decline = false) }, enabled = enabled && hasValue) {
        Text(if (busy) "Submitting..." else "Save securely")
      }
    }
    error?.let { Text(it, color = JewelTheme.globalColors.text.error) }
    disabledReason?.let { Text(it, color = muted()) }
    if (onAnswer == null) Text("Secret requests aren't available", color = muted())
    if (answered) Text("Answer sent. Waiting for the request to update.", color = muted())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(SECRET_REQUEST_PRIVACY_NOTE, modifier = Modifier.weight(1f), color = muted())
      OutlinedButton(onClick = { send(decline = true) }, enabled = enabled) { Text("Decline") }
    }
  }
}
