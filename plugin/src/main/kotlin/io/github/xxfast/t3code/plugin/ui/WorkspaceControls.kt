package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.VcsRef
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator

/**
 * Upstream's `BranchToolbar` (`apps/web/src/components/BranchToolbar.tsx`) as far as a server
 * thread needs it: the Workspace select (`RunContextSelector`, Current checkout or New
 * worktree) on the left and, while a new worktree's base is being picked, the base branch
 * picker (`WorktreeBaseBranchPicker`) on the right. It is upstream's `ComposerSurface.
 * ContextStrip`, the `xs` row above the prompt, so the footer keeps its room. The checkout's
 * own branch is on the ChatView header, which is why the branch control only shows when it's a
 * choice. Nothing when the thread isn't on a git repo (`showGitControls`).
 */
@Composable
fun WorkspaceStrip(
  state: WorkspaceControlState,
  enabled: Boolean,
  actions: ComposerFooterActions,
  modifier: Modifier = Modifier,
) {
  CompositionLocalProvider(LocalComposerControlSize provides ComposerControlSize.XS) {
    Row(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      WorkspaceSelect(state, enabled, actions.onEnvMode)
      Spacer(Modifier.weight(1f))
      if (state.selectingWorktreeBase) {
        BaseBranchPicker(state, enabled, actions)
      }
    }
  }
}

/** The Workspace menu, `RunContextSelector`'s `MenuRadioGroup` (`BranchToolbar.tsx:361`). */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun WorkspaceSelect(
  state: WorkspaceControlState,
  enabled: Boolean,
  onEnvMode: (EnvMode) -> Unit,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    Tooltip(tooltip = { Text(if (state.locked) "Workspace" else "Choose a workspace") }) {
      ComposerControl(onClick = { open = !open }, enabled = enabled && !state.locked) {
        ComposerControlIcon(state.workspaceIcon)
        ComposerControlLabel(state.workspaceLabel, maxWidth = 160.dp)
        if (!state.locked) ComposerControlChevron()
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
        passiveItem { MenuGroupLabel("Workspace") }
        selectableItem(
          selected = state.envMode == EnvMode.LOCAL,
          onClick = {
            open = false
            onEnvMode(EnvMode.LOCAL)
          },
        ) {
          MenuRow(
            icon = if (state.activeWorktreePath != null) "folder-git" else "folder",
            label = resolveCurrentWorkspaceLabel(state.activeWorktreePath),
          )
        }
        selectableItem(
          selected = state.envMode == EnvMode.WORKTREE,
          onClick = {
            open = false
            onEnvMode(EnvMode.WORKTREE)
          },
        ) {
          MenuRow(icon = "folder-git-2", label = resolveEnvModeLabel(EnvMode.WORKTREE))
        }
      }
    }
  }
}

/**
 * `WorktreeBaseBranchPicker.tsx`: "From <ref>", opening a searchable list of the checkout's
 * refs with the "Start from origin" switch under it. The query goes to the host, which asks
 * `vcs.listRefs`; the rows are whatever it answered.
 */
@Composable
private fun BaseBranchPicker(
  state: WorkspaceControlState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    ComposerControl(onClick = { open = !open }, enabled = enabled) {
      ComposerControlIcon("git-branch")
      ComposerControlLabel(state.branchLabel, maxWidth = 192.dp)
      ComposerControlChevron()
    }
    if (open) {
      val query: TextFieldState = remember { TextFieldState(state.refsQuery) }
      LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collect { actions.onRefsQuery(it) }
      }
      PopupMenu(
        onDismissRequest = {
          open = false
          actions.onRefsQuery("")
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.Start,
      ) {
        passiveItem {
          TextField(
            state = query,
            modifier = Modifier.width(280.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Search refs") },
          )
        }
        state.refs.forEach { ref ->
          selectableItem(
            selected = ref.name == state.baseBranch,
            onClick = {
              open = false
              actions.onRefsQuery("")
              actions.onBaseBranch(ref.name)
            },
          ) {
            RefRow(ref)
          }
        }
        state.refsStatus?.let { status ->
          passiveItem {
            Text(
              text = status,
              style = small(),
              color = JewelTheme.globalColors.text.info,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
          }
        }
        separator()
        passiveItem {
          // `originControl`, BranchPicker.tsx: a new worktree can start from the remote branch
          CheckboxRow(
            text = "Start from origin",
            checked = state.startFromOrigin,
            onCheckedChange = actions.onStartFromOrigin,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

/** `BranchPickerRefItem`: the ref's name, with "default" and the remote's name as badges. */
@Composable
private fun RefRow(ref: VcsRef) {
  Row(
    modifier = Modifier.widthIn(min = 240.dp, max = 280.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph("git-branch", JewelTheme.globalColors.text.info, 12.dp)
    Text(
      text = ref.name,
      modifier = Modifier.weight(1f, fill = false),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (ref.isDefault) Badge("default")
    if (ref.isRemote == true) Badge(ref.remoteName ?: "remote")
    if (ref.worktreePath != null) Badge("worktree")
  }
}

@Composable
private fun Badge(text: String) {
  Text(text, style = small(), color = JewelTheme.globalColors.text.info)
}

@Composable
private fun MenuRow(icon: String, label: String) {
  Row(
    modifier = Modifier.widthIn(min = 180.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph(icon, JewelTheme.globalColors.text.info, 12.dp)
    Text(label)
  }
}

/** `MenuGroupLabel`: the group's heading, small and muted. */
@Composable
private fun MenuGroupLabel(text: String) {
  Text(
    text = text,
    style = small(),
    color = JewelTheme.globalColors.text.info,
    fontWeight = FontWeight.Medium,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
  )
}
