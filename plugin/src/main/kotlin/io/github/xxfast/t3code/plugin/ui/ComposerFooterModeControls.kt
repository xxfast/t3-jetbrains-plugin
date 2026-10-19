package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What the footer's controls do. The host turns these into thread commands. */
class ComposerFooterActions(
  val onModel: (instanceId: String, model: String) -> Unit = { _, _ -> },
  val onRuntimeMode: (mode: String) -> Unit = {},
  val onToggleInteractionMode: () -> Unit = {},
)

/**
 * The thread's model, runtime mode and Plan/Build, left of the send button. Upstream keeps them
 * in the composer's footer (`ProviderModelPicker` and `ComposerFooterModeControls` in
 * `ChatComposer.tsx`), so this does too. The values are the server's; picking one dispatches.
 */
@Composable
fun ComposerFooterModeControls(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ProviderModelPicker(state, enabled, actions.onModel)
    RuntimeModePicker(state, enabled, actions.onRuntimeMode)
    if (state.showInteractionModeToggle) {
      InteractionModeToggle(state.interactionMode, enabled, actions.onToggleInteractionMode)
    }
  }
}

/** The model, and a menu of every provider's models, `ProviderModelPicker.tsx`. */
@Composable
fun ProviderModelPicker(
  state: ComposerFooterState,
  enabled: Boolean,
  onModel: (instanceId: String, model: String) -> Unit,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(onClick = { open = !open }, enabled = enabled && state.models.isNotEmpty()) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = state.modelLabel,
          modifier = Modifier.widthIn(max = 160.dp),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Icon(AllIconsKeys.General.ChevronDown, contentDescription = null)
      }
    }
    if (open) {
      PopupMenu(
        onDismissRequest = {
          open = false
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.Start,
      ) {
        state.models.forEach { group ->
          passiveItem {
            Text(group.displayName, style = small(), color = JewelTheme.globalColors.text.info)
          }
          group.models.forEach { item ->
            selectableItem(
              selected = item.selected,
              onClick = {
                open = false
                if (!item.selected) onModel(item.instanceId, item.slug)
              },
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(item.name)
                if (item.isNew) {
                  Text("new", style = small(), color = JewelTheme.globalColors.text.info)
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Upstream's runtime mode select: the label, and the mode's description as the tooltip. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun RuntimeModePicker(
  state: ComposerFooterState,
  enabled: Boolean,
  onRuntimeMode: (String) -> Unit,
) {
  if (state.runtimeModes.isEmpty()) return
  val index: Int = state.runtimeModes.indexOfFirst { it.mode == state.runtimeMode }
  val description: String = state.runtimeModes.getOrNull(index)?.description.orEmpty()
  Tooltip(tooltip = { Text(description) }) {
    ListComboBox(
      items = state.runtimeModes.map { it.label },
      selectedIndex = index.coerceAtLeast(0),
      // Only a real pick dispatches, not a selection that follows the server
      onSelectedItemChange = { picked ->
        if (picked != index) state.runtimeModes.getOrNull(picked)?.let { onRuntimeMode(it.mode) }
      },
      modifier = Modifier.widthIn(min = 120.dp, max = 180.dp),
      enabled = enabled,
    )
  }
}

/** Plan or Build, `ChatComposer.tsx:1247`; the tooltip says what a click does. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun InteractionModeToggle(mode: String, enabled: Boolean, onToggle: () -> Unit) {
  Tooltip(tooltip = { Text(interactionModeTooltip(mode)) }) {
    OutlinedButton(onClick = onToggle, enabled = enabled) {
      Text(if (mode == "plan") "Plan" else "Build")
    }
  }
}
