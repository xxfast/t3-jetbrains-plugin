package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.VcsRef
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator

/** How far the strip tucks under the box, upstream's `-mt-4` on `ContextStrip`. */
val CONTEXT_STRIP_OVERLAP: Dp = 16.dp

/**
 * Upstream's `BranchToolbar` on `ComposerSurface.ContextStrip`: the drawer under the composer
 * box, 22dp in from each side, a bordered tab with 16dp bottom corners, its `xs` controls in
 * one row. Left, `RunContextSelector`: Current checkout or New worktree. Right, the branch
 * (`BranchToolbarBranchSelector`): the checkout's branch, or in worktree mode the base to
 * start from with its ref picker (`WorktreeBaseBranchPicker`). Shown in the draft hero state
 * only, see [shouldShowComposerContextStrip]. In local mode a pick switches the checkout's
 * branch (`vcs.switchRef`), which needs the `source-control:write` scope; without it the
 * control is locked, see [RefPicker].
 */
@Composable
fun ComposerContextStrip(
  state: WorkspaceControlState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  val dark: Boolean = JewelTheme.isDark
  val outline: Color =
    if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.08f)
  val shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
  CompositionLocalProvider(LocalComposerControlSize provides ComposerControlSize.XS) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = CONTEXT_STRIP_INSET)
        .clip(shape)
        .background(composerGlassSurface())
        .border(1.dp, outline, shape)
        .padding(start = 8.dp, end = 8.dp, top = CONTEXT_STRIP_OVERLAP + 4.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      WorkspaceSelect(state, enabled, actions.onEnvMode)
      Spacer(Modifier.weight(1f))
      RefPicker(state, enabled, actions)
    }
  }
}

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val CONTEXT_STRIP_INSET: Dp = 22.dp

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
    Tooltip(tooltip = { Text("Choose a workspace") }) {
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
 * `BranchToolbarBranchSelector.tsx`: "From <ref>" while a new worktree's base is being picked
 * (`WorktreeBaseBranchPicker`, with the "Start from origin" switch), else the checkout's
 * branch, a pick checking that ref out (`selectRef`) or using the worktree it already has. A
 * typed name no ref carries offers "Create branch", `createRef`. Opening lists the refs
 * `vcs.listRefs` answers for what's typed. Locked, with the reason as its tooltip, when the
 * pairing can't write source control (`canChangeThreadBranch`); the worktree base needs no
 * such scope.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun RefPicker(
  state: WorkspaceControlState,
  enabled: Boolean,
  actions: ComposerFooterActions,
) {
  val base: Boolean = state.selectingWorktreeBase
  val canPick: Boolean = base || state.canChangeBranch
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    Tooltip(tooltip = { Text(if (canPick) "Branch" else BRANCH_LOCKED_REASON) }) {
      ComposerControl(onClick = { open = !open }, enabled = enabled && canPick) {
        ComposerControlIcon("git-branch")
        ComposerControlLabel(state.branchLabel, maxWidth = 192.dp)
        if (canPick) ComposerControlChevron()
      }
    }
    if (open) {
      val query: TextFieldState = remember { TextFieldState(state.refsQuery) }
      LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collect { actions.onRefsQuery(it) }
      }
      fun close() {
        open = false
        actions.onRefsQuery("")
      }
      PopupMenu(
        onDismissRequest = {
          close()
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.End,
      ) {
        passiveItem {
          TextField(
            state = query,
            modifier = Modifier.width(280.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text(if (base) "Search refs" else "Search or create a branch") },
          )
        }
        state.refs.forEach { ref ->
          selectableItem(
            selected = ref.name == state.baseBranch,
            onClick = {
              close()
              if (base) actions.onBaseBranch(ref.name) else actions.onPickRef(ref)
            },
          ) {
            RefRow(ref)
          }
        }
        // `createBranchItemValue`: only in local mode, as a worktree starts from what exists
        val create: String? = if (base) null else createBranchName(state.refsQuery, state.refs)
        if (create != null) {
          selectableItem(
            selected = false,
            onClick = {
              close()
              actions.onCreateRef(create)
            },
          ) {
            Row(
              modifier = Modifier.widthIn(min = 240.dp, max = 280.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Glyph("git-branch", JewelTheme.globalColors.text.info, 12.dp)
              Text("Create branch \"$create\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
        if (base) {
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
