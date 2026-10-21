package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** What the footer's controls do. The host turns these into thread commands. */
class ComposerFooterActions(
  /** Opens the model picker, the host's `BigPopupUI`. */
  val onOpenModelPicker: () -> Unit = {},
  val onModelOptions: (List<ProviderOptionSelection>?) -> Unit = {},
  val onRuntimeMode: (mode: String) -> Unit = {},
  val onToggleInteractionMode: () -> Unit = {},
)

/**
 * The left of the composer's footer, `composerControls` in `ChatComposer.tsx` (around line
 * 5151): the model picker, then the resting blocks (`traits`, then `mode`), each behind a
 * separator. The values are the server's; picking one dispatches.
 */
@Composable
fun ComposerControls(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
  prompt: TextFieldState,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ProviderModelPicker(state, enabled, actions)
    TraitsPicker(state, enabled, prompt, actions.onModelOptions)
    ComposerFooterModeControls(state, enabled, actions)
  }
}

/**
 * `ProviderModelPicker.tsx`'s trigger: the provider's icon, the model's name and a chevron, as a
 * [ComposerControl]. Opens the IDE's chooser (`showModelPicker`), centred like Search
 * Everywhere rather than anchored here.
 */
@Composable
fun ProviderModelPicker(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  ComposerControl(
    onClick = actions.onOpenModelPicker,
    enabled = enabled && state.pickerEntries.isNotEmpty(),
  ) {
    val xs: Boolean = LocalComposerControlSize.current == ComposerControlSize.XS
    val icon: Dp = if (xs) 12.dp else 16.dp
    state.provider?.let { provider: ServerProvider -> ProviderIcon(provider, icon) }
    ComposerControlLabel(state.modelLabel, maxWidth = 192.dp)
    ComposerControlChevron()
  }
}

/**
 * `ComposerFooterModeControls`, `ChatComposer.tsx:1225`: a separator, the runtime mode select
 * (its icon and label, the mode's description as the tooltip) and, when the provider plans, a
 * separator and the Plan/Build toggle.
 */
@Composable
fun ComposerFooterModeControls(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  if (state.runtimeModes.isEmpty()) return
  ComposerControlSeparator()
  RuntimeModeSelect(state, enabled, actions.onRuntimeMode)
  if (state.showInteractionModeToggle) {
    ComposerControlSeparator()
    InteractionModeToggle(state.interactionMode, enabled, actions.onToggleInteractionMode)
  }
}

/** The runtime mode `Select`; each option shows its icon, label and description. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun RuntimeModeSelect(
  state: ComposerFooterState,
  enabled: Boolean,
  onRuntimeMode: (String) -> Unit,
) {
  // `supervisedRuntimeModeOption` when the mode isn't one the provider offers
  val current: RuntimeModeOption = state.runtimeModes.find { it.mode == state.runtimeMode }
    ?: RUNTIME_MODES.first()
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    Tooltip(tooltip = { Text(current.description) }) {
      ComposerControl(onClick = { open = !open }, enabled = enabled) {
        ComposerControlIcon(current.icon)
        ComposerControlLabel(current.label)
        ComposerControlChevron()
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
        state.runtimeModes.forEach { option ->
          selectableItem(
            selected = option.mode == current.mode,
            onClick = {
              open = false
              if (option.mode != current.mode) onRuntimeMode(option.mode)
            },
          ) {
            RuntimeModeItem(option)
          }
        }
      }
    }
  }
}

@Composable
private fun RuntimeModeItem(option: RuntimeModeOption) {
  Column(
    modifier = Modifier.widthIn(min = 224.dp, max = 256.dp).padding(vertical = 2.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph(option.icon, JewelTheme.globalColors.text.info, 14.dp)
      Text(option.label, fontWeight = FontWeight.Medium)
    }
    Text(option.description, style = small(), color = JewelTheme.globalColors.text.info)
  }
}

/** Plan or Build, `ChatComposer.tsx:1247`, pressed in plan mode. The tooltip says what it does. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun InteractionModeToggle(mode: String, enabled: Boolean, onToggle: () -> Unit) {
  val plan: Boolean = mode == "plan"
  Tooltip(tooltip = { Text(interactionModeTooltip(mode)) }) {
    ComposerControl(onClick = onToggle, enabled = enabled, pressed = plan) {
      if (plan) {
        ComposerControlIcon("pencil-ruler", tint = JewelTheme.globalColors.text.normal)
      } else {
        // `opticalSize` large only in `sm`
        val sm: Boolean = LocalComposerControlSize.current == ComposerControlSize.SM
        ComposerControlIcon("bot", large = sm)
      }
      ComposerControlLabel(if (plan) "Plan" else "Build")
    }
  }
}
