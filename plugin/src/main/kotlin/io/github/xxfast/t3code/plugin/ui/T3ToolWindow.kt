package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

// Placeholder until #4 lands the real thread list.
@Composable
fun T3ToolWindow(
  connection: ConnectionState,
  projects: List<Project>,
  threads: List<Thread>,
  header: @Composable () -> Unit = {},
) {
  val projectTitles: Map<String, String> = projects.associate { it.id to it.title }
  Column(Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      header()
      Text(connection.label())
    }
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    LazyColumn(Modifier.fillMaxSize()) {
      items(threads, key = { it.id }) { thread ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
          val marks: String = buildString {
            if (thread.pinned) append("pinned ")
            if (thread.archived) append("archived ")
          }
          Text(thread.title)
          Text(
            text = "${thread.status} ${marks}in ${projectTitles[thread.projectId] ?: "?"}",
            color = JewelTheme.globalColors.text.info,
          )
        }
      }
    }
  }
}

private fun ConnectionState.label(): String = when (this) {
  ConnectionState.Disconnected -> "Disconnected"
  ConnectionState.Unpaired -> "Not paired"
  ConnectionState.Connecting -> "Connecting"
  is ConnectionState.Connected -> "Connected"
  is ConnectionState.AuthError -> "Auth error: $message"
  is ConnectionState.Error -> "Error: $message"
}
