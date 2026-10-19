package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.ConnectionState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Connection state in the tool window header: disconnected, pairing, connected, auth error,
 * with Reconnect and an inline pairing input. Callbacks are fire-and-forget.
 */
@Composable
fun ConnectionBanner(
  connection: ConnectionState,
  onReconnect: () -> Unit,
  onPair: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when (connection) {
      ConnectionState.Disconnected -> {
        Message("Disconnected")
        Link("Reconnect", onReconnect)
      }

      ConnectionState.Connecting -> Message("Connecting...")

      is ConnectionState.Connected -> {
        Text("Connected")
        Message(connection.session.auth.policy, dimmed = true)
      }

      ConnectionState.Unpaired -> {
        Text("Not paired")
        PairingInput(onPair)
        Link("Settings", onOpenSettings)
      }

      is ConnectionState.AuthError -> {
        Message(connection.message)
        PairingInput(onPair)
        Link("Reconnect", onReconnect)
      }

      is ConnectionState.Error -> {
        Message(connection.message)
        Link("Reconnect", onReconnect)
      }
    }
  }
}

@Composable
private fun RowScope.Message(text: String, dimmed: Boolean = false) {
  Text(
    text = text,
    modifier = Modifier.weight(1f, fill = false),
    color = if (dimmed) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.normal,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun RowScope.PairingInput(onPair: (String) -> Unit) {
  val input: TextFieldState = rememberTextFieldState()
  val code: String = input.text.toString().trim()
  TextField(
    state = input,
    modifier = Modifier.widthIn(min = 120.dp, max = 240.dp).weight(1f, fill = false),
    placeholder = { Text("Pairing link or code") },
  )
  DefaultButton(onClick = { onPair(code) }, enabled = code.isNotEmpty()) { Text("Pair") }
}
