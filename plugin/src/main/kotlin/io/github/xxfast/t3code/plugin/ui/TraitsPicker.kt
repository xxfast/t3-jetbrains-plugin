package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
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
 * `TraitsPicker` (`TraitsPicker.tsx:688`), the `traits` control group, as the composer owns it:
 * a text control that reads on from the model name, "Opus 5.5 High". Effort shows as its word
 * in muted text, or as [EffortGlyph] bars with the "Effort display" setting; named levels
 * (Ultracode, Max, prompt-injected ones) always show their name. A bolt marks Fast and
 * Ultrafast, and the other labels follow in small text. Its menu is `TraitsMenuContent`: a radio
 * group per select option, then On/Off per boolean, then [extraMenuContent] below a divider.
 * Nothing shows when the model has no options. A pick is a draft for the next message, as
 * upstream keeps it; "Ultrathink" edits the [prompt] instead.
 *
 * [extraMenuOwnsModeShortcut]: this trigger also answers composer.mode, while the permissions
 * chip is hidden. [extraMenuHint] ends the tooltip, naming what else the menu holds.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun TraitsPicker(
  state: ComposerFooterState,
  enabled: Boolean,
  prompt: TextFieldState,
  onModelOptions: (List<ProviderOptionSelection>?) -> Unit,
  extraMenuContent: (@Composable (close: () -> Unit) -> Unit)? = null,
  extraMenuOwnsModeShortcut: Boolean = false,
  extraMenuHint: String? = null,
) {
  val text: String = prompt.text.toString()
  val options: List<ProviderOptionSelection>? = state.selection?.optionSelections
  val selected: SelectedTraits = selectedTraits(state.capabilities, text, options)
  if (!selected.hasAnyControls) return
  val driver: String? = state.provider?.driver
  val display: TraitsTriggerDisplay =
    buildTraitsTriggerDisplay(driver, selected, state.selection, state.reported)
  // In the composer the glyph has no word, so the tooltip names the control
  val description: String =
    listOfNotNull("Effort: ${display.label}", display.speedLabel, extraMenuHint)
      .filter { it.isNotEmpty() }
      .joinToString(" · ")
  val glyph: EffortGlyphState? = selected.primarySelect
    ?.takeIf { it.id in REASONING_SELECT_DESCRIPTOR_IDS }
    ?.let { primary ->
      val current: String = traitSelectValue(selected, primary, state.selection, state.reported)
      resolveEffortGlyph(primary, current)
    }
  // Workflow levels such as Ultracode always show their name; the setting names every level.
  // Traits the bars cannot show, such as an agent mode, appear as words in the composer.
  val word: String? = if (glyph != null) {
    glyph.special ?: glyph.label.takeIf { state.effortDisplay == EffortDisplay.LABEL }
  } else {
    display.label
  }
  var open: Boolean by remember { mutableStateOf(false) }
  ComposerShortcutTarget(ComposerControlKind.EFFORT, enabled) { open = true }
  if (extraMenuOwnsModeShortcut) {
    ComposerShortcutTarget(ComposerControlKind.MODE, enabled) { open = true }
  }

  fun pick(descriptor: SelectProviderOptionDescriptor, value: String) {
    open = false
    val change: TraitSelectChange = traitSelectChange(selected, descriptor, text, value) ?: return
    if (change.prompt != text) prompt.setTextAndPlaceCursorAtEnd(change.prompt)
    if (change is TraitSelectChange.Options) onModelOptions(change.modelOptions)
  }

  Box {
    Tooltip(tooltip = { Text(description) }) {
      ComposerControl(
        onClick = { open = !open },
        // Codex's labels run long, so its trigger truncates
        modifier = if (driver == "codex") Modifier.widthIn(max = 192.dp) else Modifier,
        enabled = enabled,
        pressed = open,
        textControl = true,
        // Effort reads on from the model name at about the space inside the name
        start = if (word != null) 0.dp else 2.dp,
        end = 4.dp,
      ) {
        // The word is muted next to the model name and brightens like it on hover
        val color: Color = composerControlText(muted = word != null)
        when {
          word != null -> ComposerControlLabel(word, muted = true)
          glyph != null -> EffortGlyph(glyph.count, glyph.filled, color)
        }
        if (glyph != null) {
          if (display.speedLabel == "Fast" || display.speedLabel == "Ultrafast") {
            FastBolt(ultra = display.speedLabel == "Ultrafast", color = color)
          }
          if (display.otherLabels.isNotEmpty()) {
            Text(
              text = display.otherLabels.joinToString(" · "),
              modifier = Modifier.widthIn(max = 96.dp),
              style = small(),
              color = color,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
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
        if (extraMenuContent != null) {
          separator()
          passiveItem { extraMenuContent { open = false } }
        }
      }
    }
  }
}

/** lucide's `zap`, in its 24-unit box. */
private const val ZAP_PATH: String =
  "M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 " +
    "20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z"

/**
 * `FastBolt`: one filled bolt for Fast, 12dp. Ultrafast adds an outlined echo behind it, cut
 * away by the surface colour (a 4-unit stroke under the front bolt, `paint-order: stroke`).
 */
@Composable
private fun FastBolt(ultra: Boolean, color: Color) {
  val path: Path = remember { PathParser().parsePathString(ZAP_PATH).toPath() }
  val surface: Color = composerGlassSurface()
  Canvas(Modifier.size(width = if (ultra) 15.dp else 12.dp, height = 12.dp)) {
    val unit: Float = 12.dp.toPx() / 24f
    fun stroke(width: Float): Stroke =
      Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // In the icon's own 24-unit box from here, strokes too
    scale(unit, pivot = Offset.Zero) {
      if (ultra) {
        translate(left = 3.dp.toPx() / unit) { drawPath(path, color, style = stroke(2f)) }
        drawPath(path, surface, style = stroke(4f))
      } else {
        drawPath(path, color, style = stroke(2f))
      }
      drawPath(path, color)
    }
  }
}

/** `TraitsMenuContent`, TraitsPicker.tsx:488. */
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
