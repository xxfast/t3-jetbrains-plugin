package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.border
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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.ui.DocumentAdapter
import io.github.xxfast.t3code.protocol.generated.SecretRequestAnswerInput
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.awt.event.ActionListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPasswordField
import javax.swing.event.DocumentEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** `SecretRequestCard.tsx`: the input lives only in a native password field, never a draft. */
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
  var hasValue: Boolean by remember(item.id) { mutableStateOf(false) }
  var error: String? by remember(item.id) { mutableStateOf(null) }
  var answered: Boolean by remember(item.id) { mutableStateOf(false) }
  val inFlight = remember(item.id) { AtomicBoolean(false) }
  val field = remember(item.id) {
    JPasswordField().apply {
      putClientProperty("JTextField.placeholderText", item.placeholder ?: "Paste the secret")
      accessibleContext.accessibleName = item.label
      document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          val value: CharArray = password
          hasValue = value.any { !it.isWhitespace() }
          value.fill('\u0000')
        }
      })
    }
  }
  DisposableEffect(field) {
    onDispose { field.text = "" }
  }
  val enabled: Boolean = disabledReason == null && onAnswer != null && !busy && !answered

  fun send(decline: Boolean) {
    if (!enabled || !inFlight.compareAndSet(false, true)) return
    val value: CharArray = field.password
    val input: SecretRequestAnswerInput? = try {
      secretRequestAnswerInput(item, if (decline) null else String(value))
    } finally {
      value.fill('\u0000')
    }
    if (input == null) {
      inFlight.set(false)
      return
    }
    busy = true
    error = null
    scope.launch {
      try {
        onAnswer?.invoke(input)
        field.text = ""
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
  DisposableEffect(field, enabled, onAnswer) {
    val listener = ActionListener { send(decline = false) }
    field.addActionListener(listener)
    onDispose { field.removeActionListener(listener) }
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
      SwingPanel(
        factory = { field },
        update = { it.isEnabled = enabled },
        modifier = Modifier.weight(1f).height(30.dp),
      )
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
