package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.VcsRef
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
  /** Sets the interaction mode outright, `handleInteractionModeChange`: `/plan`, `/default`. */
  val onInteractionMode: (mode: String) -> Unit = {},
  /** The Workspace select, `onEnvModeChange`. */
  val onEnvMode: (EnvMode) -> Unit = {},
  /** A base branch picked for a new worktree, `onActiveThreadBranchOverrideChange`. */
  val onBaseBranch: (branch: String) -> Unit = {},
  val onStartFromOrigin: (Boolean) -> Unit = {},
  /** What's typed in the branch picker; the host asks `vcs.listRefs` for it. */
  val onRefsQuery: (query: String) -> Unit = {},
  /** A ref picked in local mode: check it out, or use its worktree (`selectRef`). */
  val onPickRef: (VcsRef) -> Unit = {},
  /** A branch typed that doesn't exist yet, `createRef`. */
  val onCreateRef: (name: String) -> Unit = {},
  /**
   * Re-check the provider's workspace snapshot for the thread's cwd, upstream's
   * `refreshWorkspaceSnapshotIfStale`: on a change of scope and on typing. The host decides
   * whether a `server.refreshProviders` is due.
   */
  val onRefreshWorkspaceSnapshot: () -> Unit = {},
)

/**
 * The left of the composer's footer, `ComposerControlsBar.tsx`: the model picker and the effort
 * trigger reading as one phrase, then the `mode` group (the permissions chip and Plan/Build).
 * No separators or chevrons. [resolveComposerControlsOwnership] decides where Permissions live:
 * in the effort menu always, and as a chip unless the thread is at the project's default and the
 * model has an effort menu to hold them. The values are the server's; picking one dispatches.
 * The footer never overflows here, so no block is ever hidden.
 */
@Composable
fun ComposerControls(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
  prompt: TextFieldState,
) {
  val selected: SelectedTraits = selectedTraits(
    state.capabilities,
    prompt.text.toString(),
    state.selection?.optionSelections,
  )
  val matchesDefault: Boolean = state.runtimeMode == state.defaultRuntimeMode
  val ownership: ComposerControlsOwnership = resolveComposerControlsOwnership(
    matchesDefault = matchesDefault,
    hasTraits = selected.hasAnyControls,
    planModeUiEnabled = state.showInteractionModeToggle,
    collapsed = false,
    measuredHiddenBlockCount = 0,
  )
  val hint: String? = if (matchesDefault) {
    val label: String =
      state.runtimeModes.find { it.mode == state.runtimeMode }?.label ?: state.runtimeMode
    listOfNotNull("Permissions: $label", state.modeShortcutLabel)
      .filter { it.isNotEmpty() }
      .joinToString(" · ")
  } else {
    null
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Model and effort sit a word space apart, so no gap between them
    Row(verticalAlignment = Alignment.CenterVertically) {
      ProviderModelPicker(state, enabled, actions)
      TraitsPicker(
        state = state,
        enabled = enabled,
        prompt = prompt,
        onModelOptions = actions.onModelOptions,
        // Permissions always live in the effort menu
        extraMenuContent = if (state.runtimeModes.isEmpty()) null else { close ->
          ComposerPermissionsSection(state, actions.onRuntimeMode, close)
        },
        extraMenuOwnsModeShortcut = ownership.effortOwnsModeShortcut,
        extraMenuHint = hint,
      )
    }
    if (ownership.showModeBlock) {
      ComposerFooterModeControls(
        state = state,
        enabled = enabled,
        actions = actions,
        accessDisplay = ownership.accessDisplay,
        emphasized = !matchesDefault,
      )
    }
  }
}

/**
 * `ProviderModelPicker.tsx`'s trigger: the provider's icon and the model's short name, as a text
 * [ComposerControl], with the full name as its tooltip and accessible name. Opens the IDE's
 * chooser (`showModelPicker`), centred like Search Everywhere rather than anchored here.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun ProviderModelPicker(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  Tooltip(tooltip = { Text(state.modelFullLabel) }) {
    ComposerControl(
      onClick = actions.onOpenModelPicker,
      modifier = Modifier.semantics { contentDescription = state.modelFullLabel },
      enabled = enabled && state.pickerEntries.isNotEmpty(),
      textControl = true,
      end = 4.dp,
    ) {
      val xs: Boolean = LocalComposerControlSize.current == ComposerControlSize.XS
      val icon: Dp = if (xs) 12.dp else 16.dp
      state.provider?.let { provider: ServerProvider ->
        ProviderIcon(provider, icon, badge = state.providerBadge)
      }
      ComposerControlLabel(state.modelLabel, maxWidth = 192.dp)
    }
  }
}

/** `supervisedRuntimeModeOption`: what shows when the mode isn't one the provider offers. */
private val SUPERVISED: RuntimeModeOption = RUNTIME_MODES.first { it.mode == "approval-required" }

/**
 * `ComposerFooterModeControls`, `ComposerFooterControls.tsx:29`: the permissions chip as
 * [accessDisplay] has it, then the Plan/Build toggle when the provider plans. The chip shows the
 * mode's icon and, as a [AccessDisplay.LABEL], its label; [emphasized] (the mode differs from
 * the default) draws the icon in the label's colour. It opens the same
 * [ComposerPermissionsSection] the effort menu holds, and answers composer.mode.
 */
@Composable
fun ComposerFooterModeControls(
  state: ComposerFooterState,
  enabled: Boolean,
  actions: ComposerFooterActions,
  accessDisplay: AccessDisplay = AccessDisplay.LABEL,
  emphasized: Boolean = false,
) {
  if (state.runtimeModes.isEmpty()) return
  if (accessDisplay != AccessDisplay.HIDDEN) {
    PermissionsChip(state, enabled, accessDisplay, emphasized, actions.onRuntimeMode)
  }
  if (state.showInteractionModeToggle) {
    InteractionModeToggle(state.interactionMode, enabled, actions.onToggleInteractionMode)
  }
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun PermissionsChip(
  state: ComposerFooterState,
  enabled: Boolean,
  accessDisplay: AccessDisplay,
  emphasized: Boolean,
  onRuntimeMode: (String) -> Unit,
) {
  val current: RuntimeModeOption =
    state.runtimeModes.find { it.mode == state.runtimeMode } ?: SUPERVISED
  var open: Boolean by remember { mutableStateOf(false) }
  ComposerShortcutTarget(ComposerControlKind.MODE, enabled) { open = true }
  Box {
    Tooltip(tooltip = { Text("Permissions: ${current.label}. ${current.description}") }) {
      // Mirrors the effort trigger's trailing padding so both gaps around it match
      ComposerControl(onClick = { open = !open }, enabled = enabled, start = 6.dp) {
        // Match the label, not full foreground: the chip's presence already marks the mode
        if (emphasized) {
          ComposerControlIcon(current.icon, tint = composerControlText())
        } else {
          ComposerControlIcon(current.icon)
        }
        if (accessDisplay == AccessDisplay.LABEL) ComposerControlLabel(current.label)
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
        passiveItem { ComposerPermissionsSection(state, onRuntimeMode) { open = false } }
      }
    }
  }
}

/**
 * `ComposerPermissionsSection`, `ComposerFooterControls.tsx:162`: the permission modes as one
 * horizontal toggle, with the shown mode named below. Hover or keyboard focus previews a mode's
 * name and description before a pick; a pick closes the menu ([onClose]). Lives inside a menu:
 * the permissions chip's or the effort menu.
 */
@Composable
fun ComposerPermissionsSection(
  state: ComposerFooterState,
  onRuntimeMode: (String) -> Unit,
  onClose: () -> Unit,
) {
  val previews: Map<String, MutableInteractionSource> = remember(state.runtimeModes) {
    state.runtimeModes.associate { it.mode to MutableInteractionSource() }
  }
  val focus: List<FocusRequester> =
    remember(state.runtimeModes) { state.runtimeModes.map { FocusRequester() } }
  val previewed: List<String> = state.runtimeModes.mapNotNull { option ->
    val source: MutableInteractionSource = previews.getValue(option.mode)
    val hovered: Boolean by source.collectIsHoveredAsState()
    val focused: Boolean by source.collectIsFocusedAsState()
    option.mode.takeIf { hovered || focused }
  }
  val shownMode: String = previewed.firstOrNull() ?: state.runtimeMode
  val shown: RuntimeModeOption = state.runtimeModes.find { it.mode == shownMode } ?: SUPERVISED
  Column(
    modifier = Modifier.width(288.dp).padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "Permissions",
      style = small(),
      fontWeight = FontWeight.Medium,
      color = JewelTheme.globalColors.text.info,
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(subtle(0.06f))
        .padding(2.dp)
        .selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      state.runtimeModes.forEachIndexed { index, option ->
        val source: MutableInteractionSource = previews.getValue(option.mode)
        val checked: Boolean = option.mode == state.runtimeMode
        val highlighted: Boolean = option.mode in previewed
        Box(
          modifier = Modifier
            .weight(1f)
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
              when {
                checked -> subtle(0.12f)
                highlighted -> subtle(0.06f)
                else -> Color.Transparent
              },
            )
            .focusRequester(focus[index])
            // Menus navigate with Up/Down only; the row reads horizontally, so Left/Right too
            .onPreviewKeyEvent { event ->
              if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
              val next: Int = when (event.key) {
                Key.DirectionLeft -> index - 1
                Key.DirectionRight -> index + 1
                else -> return@onPreviewKeyEvent false
              }
              val target: FocusRequester = focus.getOrNull(next) ?: return@onPreviewKeyEvent false
              target.requestFocus()
              true
            }
            .hoverable(source)
            .selectable(
              selected = checked,
              interactionSource = source,
              indication = null,
              role = Role.RadioButton,
            ) {
              onClose()
              if (option.mode != state.runtimeMode) onRuntimeMode(option.mode)
            }
            // Only an icon, so it carries the mode's name and description (`aria-describedby`)
            .semantics { contentDescription = "${option.label}. ${option.description}" },
          contentAlignment = Alignment.Center,
        ) {
          val tint: Color =
            if (checked || highlighted) JewelTheme.globalColors.text.normal
            else JewelTheme.globalColors.text.info
          Glyph(option.icon, tint, 14.dp)
        }
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = shown.label,
        color = if (shownMode == state.runtimeMode) {
          JewelTheme.globalColors.text.normal
        } else {
          JewelTheme.globalColors.text.info
        },
      )
      // Two lines reserved so the menu does not shift while you preview modes
      Text(
        text = shown.description,
        modifier = Modifier.heightIn(min = 32.dp),
        style = small(),
        color = JewelTheme.globalColors.text.info,
      )
    }
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
