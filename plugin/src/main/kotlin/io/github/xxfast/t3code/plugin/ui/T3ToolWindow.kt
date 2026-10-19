package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.ListComboBox

/** [connection] is explained by the banner in [header]; the list keeps the last known state. */
@Composable
fun T3ToolWindow(
  connection: ConnectionState,
  projects: List<Project>,
  threads: List<Thread>,
  selectedProjectId: String?,
  onSelectProject: (String?) -> Unit,
  onOpenThread: (String) -> Unit,
  header: @Composable () -> Unit = {},
) {
  Column(Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        header()
      }
      ProjectPicker(projects, selectedProjectId, onSelectProject)
    }
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    ThreadList(projects, threads, selectedProjectId, onOpenThread)
  }
}

@Composable
private fun ProjectPicker(
  projects: List<Project>,
  selectedProjectId: String?,
  onSelect: (String?) -> Unit,
) {
  val ids: List<String?> = listOf(null) + projects.map { it.id }
  val titles: List<String> = listOf("All") + projects.map { it.title }
  ListComboBox(
    items = titles,
    selectedIndex = ids.indexOf(selectedProjectId).coerceAtLeast(0),
    onSelectedItemChange = { index -> if (index in ids.indices) onSelect(ids[index]) },
    modifier = Modifier.width(160.dp),
  )
}
