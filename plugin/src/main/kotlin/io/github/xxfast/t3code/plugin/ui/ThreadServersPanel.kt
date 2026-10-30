package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.DiscoveredLocalServer
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The Servers section for one thread, as the host resolves it. */
class ThreadServersState(
  /** The thread's servers, [useThreadDiscoveredPorts]. */
  val servers: List<DiscoveredLocalServer>,
  /** Why a row can't open its server, or null when it can. */
  val openReason: String?,
  /** `canStop`: the pairing has `terminal:operate`. */
  val canStop: Boolean,
  /** `supportsStop`: the server advertises `localServerStop`. */
  val supportsStop: Boolean,
  /** The [serverKey] being stopped. One stop runs at a time. */
  val stoppingKey: String?,
)

/** What the Servers rows do, each wired by the host. */
class ThreadServersActions(
  val onOpen: (DiscoveredLocalServer) -> Unit = {},
  val onStop: (DiscoveredLocalServer) -> Unit = {},
)

/**
 * `ThreadServersPanel` (apps/web/src/components/chat/ThreadServersPanel.tsx): "Servers", the web
 * servers the thread's terminals and agent started, hidden while none run. Upstream opens a row
 * in its own browser panel and only a modified click goes to the system browser; the plugin has
 * no panel, so every click does. A rule on top when [separated].
 */
@Composable
fun ThreadServersPanel(
  state: ThreadServersState,
  actions: ThreadServersActions,
  separated: Boolean,
) {
  if (state.servers.isEmpty()) return
  ThreadDetailsSection(separated = separated, title = "Servers") {
    state.servers.forEach { server ->
      key(serverKey(server)) { ServerRow(server, state, actions) }
    }
  }
}

/**
 * One server: the globe, `localhost:{port}` over [serverDetail], and a stop button on hover or
 * focus when it's [stoppable]. The text keeps clear of the button's spot.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun ServerRow(
  server: DiscoveredLocalServer,
  state: ThreadServersState,
  actions: ThreadServersActions,
) {
  val key: String = serverKey(server)
  val canStop: Boolean = stoppable(server, state.canStop, state.supportsStop)
  val enabled: Boolean = state.openReason == null
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  var focused: Boolean by remember { mutableStateOf(false) }
  val stopShown: Boolean = canStop && (hovered || focused || state.stoppingKey == key)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .hoverable(interactions)
      .onFocusChanged { focused = it.hasFocus },
    contentAlignment = Alignment.CenterStart,
  ) {
    Tooltip(tooltip = { Text(state.openReason ?: "Open in your browser") }) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .alpha(if (enabled) 1f else 0.64f)
          .heightIn(min = 32.dp)
          .clip(ROW_SHAPE)
          .background(if (hovered && enabled) hoverFill() else Color.Transparent)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = { actions.onOpen(server) },
          )
          .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PanelIcon("globe")
        Column(
          modifier = Modifier.weight(1f).padding(end = if (canStop) 24.dp else 0.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = "localhost:${server.port}",
            color = JewelTheme.globalColors.text.normal.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = serverDetail(server),
            style = small(),
            color = muted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    if (stopShown) {
      Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
        StopServer(server, state.stoppingKey) { actions.onStop(server) }
      }
    }
  }
}

/** The hover stop button, "Stop server", a spinner while it's stopping. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun StopServer(server: DiscoveredLocalServer, stoppingKey: String?, onStop: () -> Unit) {
  Tooltip(tooltip = { Text("Stop server") }) {
    IconButton(
      onClick = onStop,
      enabled = stoppingKey == null,
      modifier = Modifier.size(24.dp),
    ) {
      if (stoppingKey == serverKey(server)) {
        CircularProgressIndicator(Modifier.size(12.dp))
      } else {
        Icon(AllIconsKeys.Actions.Suspend, contentDescription = "Stop localhost:${server.port}")
      }
    }
  }
}
