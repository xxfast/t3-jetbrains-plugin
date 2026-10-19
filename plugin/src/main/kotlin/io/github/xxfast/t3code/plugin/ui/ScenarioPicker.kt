package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.ListComboBox

@Composable
fun ScenarioPicker(scenarios: List<String>, selected: String?, onSelect: (String) -> Unit) {
  ListComboBox(
    items = scenarios,
    selectedIndex = scenarios.indexOf(selected).coerceAtLeast(0),
    onSelectedItemChange = { index -> scenarios.getOrNull(index)?.let(onSelect) },
    modifier = Modifier.width(180.dp),
  )
}
