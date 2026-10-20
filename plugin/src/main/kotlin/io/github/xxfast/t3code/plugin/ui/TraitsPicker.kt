package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.BooleanProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.SelectProviderOptionDescriptor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * `TraitsPicker` (`TraitsPicker.tsx:647`), the `traits` resting block: a separator, then a
 * [ComposerControl] with a brain, the options' labels ("High", "Medium · Fast") and a chevron.
 * Its menu is `TraitsMenuContent`: a radio group per select option, then On/Off per boolean.
 * Nothing shows when the model has no options. A pick is a draft for the next message, as
 * upstream keeps it; "Ultrathink" edits the [prompt] instead.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun TraitsPicker(
  state: ComposerFooterState,
  enabled: Boolean,
  prompt: TextFieldState,
  onModelOptions: (List<ProviderOptionSelection>?) -> Unit,
) {
  val text: String = prompt.text.toString()
  val options: List<ProviderOptionSelection>? = state.selection?.optionSelections
  val selected: SelectedTraits = selectedTraits(state.capabilities, text, options)
  if (!selected.hasAnyControls) return
  val label: String =
    buildTraitsTriggerLabel(state.provider?.driver, selected, state.selection, state.reported)
  var open: Boolean by remember { mutableStateOf(false) }

  fun pick(descriptor: SelectProviderOptionDescriptor, value: String) {
    open = false
    val change: TraitSelectChange = traitSelectChange(selected, descriptor, text, value) ?: return
    if (change.prompt != text) prompt.setTextAndPlaceCursorAtEnd(change.prompt)
    if (change is TraitSelectChange.Options) onModelOptions(change.modelOptions)
  }

  ComposerControlSeparator()
  Box {
    Tooltip(tooltip = { Text(label) }) {
      ComposerControl(onClick = { open = !open }, enabled = enabled) {
        ComposerControlIcon("brain")
        Text(
          text = label,
          // Codex's labels run long, so its trigger truncates
          modifier = Modifier.widthIn(max = 192.dp),
          color = composerControlLabel(),
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
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
        traitsMenuContent(state, selected, ::pick) { descriptor, value ->
          open = false
          onModelOptions(traitBooleanChange(selected, descriptor, value))
        }
      }
    }
  }
}

/** `TraitsMenuContent`, TraitsPicker.tsx:382. */
private fun MenuScope.traitsMenuContent(
  state: ComposerFooterState,
  selected: SelectedTraits,
  onSelect: (SelectProviderOptionDescriptor, String) -> Unit,
  onBoolean: (BooleanProviderOptionDescriptor, Boolean) -> Unit,
) {
  selected.selects.forEachIndexed { index, descriptor ->
    if (index > 0) separator()
    val value: String = traitSelectValue(selected, descriptor, state.selection, state.reported)
    val locked: Boolean =
      selected.ultrathinkInBodyText && descriptor.id == selected.primarySelect?.id
    passiveItem { MenuHeading(descriptor.label) }
    if (locked) {
      passiveItem {
        MenuNote(
          "Your prompt contains \"ultrathink\" in the text. Remove it to change this option.",
        )
      }
    }
    descriptor.options.forEach { option ->
      selectableItem(
        selected = option.id == value,
        enabled = !locked,
        onClick = { onSelect(descriptor, option.id) },
      ) {
        Column(Modifier.widthIn(max = 224.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(option.label)
            if (option.isDefault == true) DefaultBadge()
          }
          option.description?.let { description ->
            Text(description, style = small(), color = JewelTheme.globalColors.text.info)
          }
        }
      }
    }
  }
  selected.booleans.forEachIndexed { index, descriptor ->
    if (index > 0 || selected.selects.isNotEmpty()) separator()
    passiveItem { MenuHeading(descriptor.label) }
    listOf(true, false).forEach { on ->
      selectableItem(
        selected = (descriptor.currentValue == true) == on,
        onClick = { onBoolean(descriptor, on) },
      ) {
        Text(if (on) "On" else "Off")
      }
    }
  }
}

@Composable
private fun MenuHeading(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(top = 2.dp),
    style = small(),
    fontWeight = FontWeight.Medium,
    color = JewelTheme.globalColors.text.info,
  )
}

@Composable
private fun MenuNote(text: String) {
  Text(
    text = text,
    modifier = Modifier.widthIn(max = 224.dp),
    style = small(),
    color = JewelTheme.globalColors.text.info,
  )
}

/** `DefaultBadge`: an outlined "Default" next to the provider's default option. */
@Composable
private fun DefaultBadge() {
  Text(
    text = "Default",
    style = small(),
    color = JewelTheme.globalColors.text.info,
    overflow = TextOverflow.Clip,
  )
}
