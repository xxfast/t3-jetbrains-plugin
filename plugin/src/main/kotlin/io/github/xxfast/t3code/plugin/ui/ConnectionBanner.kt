package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.Composable
import io.github.xxfast.t3code.protocol.ConnectionState
import org.jetbrains.jewel.ui.component.Text

/**
 * Connection state in the tool window header: disconnected, pairing, connected, auth error,
 * with Reconnect and an inline pairing input. Filled in by #3; this is the shape #4 wires.
 */
@Composable
fun ConnectionBanner(
  connection: ConnectionState,
  onReconnect: () -> Unit,
  onPair: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  Text(connection.toString())
}
