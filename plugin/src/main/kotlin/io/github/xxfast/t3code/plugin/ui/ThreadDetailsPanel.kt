package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ProjectScript
import io.github.xxfast.t3code.protocol.generated.VcsStatusRemoteResult
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator

// T3 Thread Details' Workspace and Version Control sections, upstream's ThreadDetailsPanel.tsx
// over threadDetailsPanelStyles.ts: 32dp rows with 8dp corners, a 16dp muted icon, 13px medium
// text at 80%, a soft fill on hover. A "select" row splits a label half from a 32dp chevron
// half with a hairline between (THREAD_DETAILS_PANEL_SELECT_ROW_CLASS); a split group does the
// same for two separate actions (THREAD_DETAILS_PANEL_SPLIT_GROUP_CLASS).

/** The Open in row, `OpenInPicker` in panel mode: its label, and why it's disabled, if it is. */
class OpenInState(val label: String, val reason: String?)

/**
 * `ThreadDetailsPanelProps` as the plugin resolves them for one thread. The host builds it from
 * the shell row, the thread view, the checkout's `subscribeVcsStatus` and the IDE window.
 */
class ThreadDetailsPanelState(
  /** `environment.label`, what the run context row says when the machine is known. */
  val environmentLabel: String?,
  /** `resolveEnvironmentMachineKind`, for the row's first icon. */
  val machine: String,
  /** The path the workspace icon's tooltip names, `workspacePath` in `RunContextSelector`. */
  val workspacePath: String?,
  /** The composer's Workspace control state, null off a git repo or before the view loads. */
  val workspace: WorkspaceControlState?,
  /** The Open in row, null when the thread already belongs in this window. */
  val openIn: OpenInState?,
  val scripts: List<ProjectScript>,
  /** This window's run configurations, when the thread's checkout is open in it. */
  val runConfigurations: List<IdeRunConfiguration>,
  /** What the row runs, [resolvePrimaryRunItem]. */
  val primaryRun: RunItem?,
  /** Why T3 scripts can't run, `hasTerminalWriteAccess` failing, or null. */
  val runScriptsReason: String?,
  /** `gitCwd`: the worktree, else the project root. No Version Control section without one. */
  val gitCwd: String?,
  /** `isRepo` from the status, true until it says otherwise, as upstream reads it. */
  val isGitRepo: Boolean,
  val vcs: VcsStatus?,
  val quickAction: GitQuickAction,
  val menuItems: List<GitActionMenuItem>,
  /** Why the IDE can't run git actions for this checkout, or null when it can. */
  val gitActionsReason: String?,
  val changes: ChangesTotals,
  /** The branch's pull request, for the row under the branch (`ThreadDetailsPrRows`). */
  val pullRequest: VcsStatusRemoteResult.Pr?,
) {
  val hasPrimaryRemote: Boolean
    get() = vcs?.local?.hasPrimaryRemote ?: false
}

/** What the panel's rows do, each wired by the host. */
class ThreadDetailsPanelActions(
  val onEnvMode: (EnvMode) -> Unit = {},
  val refPicker: RefPickerActions = RefPickerActions({}, {}, {}, {}, {}),
  val onOpenInIde: () -> Unit = {},
  val onRun: (RunItem) -> Unit = {},
  /** A stacked git action by name (`commit`, `push`, `create_pr`, ...), or `pull`. */
  val onGitAction: (action: String) -> Unit = {},
  val onOpenChanges: () -> Unit = {},
  val onOpenUrl: (url: String) -> Unit = {},
)

/**
 * `ThreadDetailsSection`: `px-2 pt-2 pb-2.5`, a top rule when [separated], and the heading row
 * with its [actions] when [title] is given.
 */
@Composable
fun ThreadDetailsSection(
  separated: Boolean,
  title: String? = null,
  actions: @Composable () -> Unit = {},
  content: @Composable () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    if (separated) Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
      if (title != null) {
        Row(
          modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = small(),
            color = muted(),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          actions()
        }
      }
      content()
    }
  }
}

/** The Workspace section: the run context, Open in, and the project's scripts. No heading. */
@Composable
fun WorkspaceSection(state: ThreadDetailsPanelState, actions: ThreadDetailsPanelActions) {
  ThreadDetailsSection(separated = false) {
    RunContextRow(state, actions.onEnvMode)
    state.openIn?.let { openIn -> OpenInRow(openIn, actions.onOpenInIde) }
    ProjectScriptsRow(state, actions.onRun)
  }
}

/** The Version Control section: the branch, the git quick action with its menu, and Changes. */
@Composable
fun VersionControlSection(state: ThreadDetailsPanelState, actions: ThreadDetailsPanelActions) {
  ThreadDetailsSection(separated = true) {
    if (state.isGitRepo) {
      BranchRow(state, actions)
      state.pullRequest?.let { pr -> PullRequestRow(pr, actions.onOpenUrl) }
      GitActionsGroup(state, actions.onGitAction)
      ChangesRow(state, actions.onOpenChanges)
    } else {
      // `Initialize Git` runs `vcs.init` upstream; the plugin's protocol doesn't carry it yet
      PanelRow(
        leading = { PanelIcon("git-branch-plus") },
        label = "Not a git repository",
        enabled = false,
      )
    }
  }
}

/**
 * `RunContextSelector` in panel mode: the machine's icon, the workspace's, and the machine's
 * label. A select while the thread can still choose its workspace, a plain row once it's
 * locked. The plugin has one environment, so there's no "Run on" group in the menu.
 */
@Composable
private fun RunContextRow(state: ThreadDetailsPanelState, onEnvMode: (EnvMode) -> Unit) {
  val workspace: WorkspaceControlState? = state.workspace
  val workspaceLabel: String =
    workspace?.workspaceLabel ?: resolveLockedWorkspaceLabel(null, EnvMode.LOCAL)
  val label: String = state.environmentLabel ?: workspaceLabel
  val leading: @Composable () -> Unit = {
    Row(
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (state.environmentLabel != null) {
        PanelIcon(machineGlyph(state.machine), tooltip = state.environmentLabel)
      }
      val path: String = state.workspacePath ?: workspaceLabel
      PanelIcon(workspace?.workspaceIcon ?: "folder", tooltip = path)
    }
  }
  if (workspace == null || workspace.locked) {
    PanelRow(leading = leading, label = label, enabled = true, locked = true)
    return
  }
  PanelSelectRow(leading = leading, label = label, enabled = true) { close ->
    PopupMenu(
      onDismissRequest = {
        close()
        return@PopupMenu true
      },
      horizontalAlignment = Alignment.Start,
    ) {
      workspaceItems(workspace, onEnvMode, close)
    }
  }
}

/**
 * `OpenInPicker` in panel mode, with the IDE as the only editor: "Open in <IDE>". Upstream adds
 * a chevron to choose among installed editors; with one there's nothing to choose.
 */
@Composable
private fun OpenInRow(openIn: OpenInState, onOpenInIde: () -> Unit) {
  PanelRow(
    leading = { PanelIcon("square-arrow-out-up-right") },
    label = openIn.label,
    enabled = openIn.reason == null,
    tooltip = openIn.reason,
    onClick = onOpenInIde,
  )
}

/**
 * `ProjectScriptsControl` in panel mode: the primary as a split group whose chevron lists
 * every T3 script and, the IDE's own kind of script, this window's run configurations (where
 * upstream lists importable `package.json` scripts). "Add project script" when there's nothing
 * to run, which upstream opens an editor for and the plugin can't (scripts are edited in the
 * desktop app, `project.update`).
 */
@Composable
private fun ProjectScriptsRow(state: ThreadDetailsPanelState, onRun: (RunItem) -> Unit) {
  val primary: RunItem? = state.primaryRun
  if (primary == null) {
    PanelRow(
      leading = { PanelIcon("plus") },
      label = "Add project script",
      enabled = false,
      tooltip = "Add scripts in the T3 desktop app",
    )
    return
  }
  val canRunScripts: Boolean = state.runScriptsReason == null
  val enabled: Boolean = primary !is RunItem.Script || canRunScripts
  PanelSplitGroup(
    leading = { RunItemIcon(primary) },
    label = primary.name,
    enabled = enabled,
    tooltip = if (enabled) "Run ${primary.name}" else state.runScriptsReason,
    onClick = { onRun(primary) },
    menuTooltip = "Script actions",
  ) { close ->
    PopupMenu(
      onDismissRequest = {
        close()
        return@PopupMenu true
      },
      horizontalAlignment = Alignment.End,
    ) {
      if (state.scripts.isNotEmpty()) {
        passiveItem { MenuGroupLabel("Project scripts") }
        state.scripts.forEach { script ->
          val item = RunItem.Script(script)
          selectableItem(
            selected = item == primary,
            enabled = canRunScripts,
            onClick = {
              close()
              onRun(item)
            },
          ) {
            MenuItemRow(scriptGlyph(script.icon), "Run ${script.name}")
          }
        }
      }
      if (state.runConfigurations.isNotEmpty()) {
        if (state.scripts.isNotEmpty()) separator()
        passiveItem { MenuGroupLabel("Run configurations") }
        state.runConfigurations.forEach { configuration ->
          val item = RunItem.Configuration(configuration)
          selectableItem(
            selected = item == primary,
            onClick = {
              close()
              onRun(item)
            },
          ) {
            Row(
              modifier = Modifier.padding(vertical = 2.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RunItemIcon(item)
              Text("Run ${configuration.name}")
            }
          }
        }
      }
    }
  }
}

/** A script's `ScriptIcon` glyph, or the run configuration's own icon, 16dp either way. */
@Composable
private fun RunItemIcon(item: RunItem) {
  when (item) {
    is RunItem.Script -> PanelIcon(scriptGlyph(item.script.icon))
    is RunItem.Configuration -> {
      val bitmap: ImageBitmap? = item.configuration.icon
      if (bitmap == null) {
        PanelIcon("play")
      } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(16.dp))
      }
    }
  }
}

/**
 * `BranchToolbarBranchSelector` in panel mode: the branch, a select onto the ref picker. Locked
 * with the reason as its tooltip when the pairing can't write source control.
 */
@Composable
private fun BranchRow(state: ThreadDetailsPanelState, actions: ThreadDetailsPanelActions) {
  val workspace: WorkspaceControlState? = state.workspace
  val label: String = workspace?.branchLabel ?: state.vcs?.local?.refName ?: "Select ref"
  val canPick: Boolean =
    workspace != null && (workspace.selectingWorktreeBase || workspace.canChangeBranch)
  if (workspace == null || !canPick) {
    PanelRow(
      leading = { PanelIcon("git-branch") },
      label = label,
      enabled = true,
      locked = true,
      tooltip = if (workspace == null) null else BRANCH_LOCKED_REASON,
    )
    return
  }
  PanelSelectRow(leading = { PanelIcon("git-branch") }, label = label, enabled = true) { close ->
    RefPickerPopup(workspace, actions.refPicker, Alignment.Start, close)
  }
}

/** `ThreadDetailsPrRows`' first row: the branch's pull request, opening it in the browser. */
@Composable
private fun PullRequestRow(pr: VcsStatusRemoteResult.Pr, onOpenUrl: (String) -> Unit) {
  val glyph: String = when {
    pr.isDraft == true -> "git-pull-request-draft"
    pr.state == "open" -> "git-pull-request-arrow"
    else -> "git-pull-request-closed"
  }
  PanelRow(
    leading = { PanelIcon(glyph) },
    label = "#${pr.number} ${pr.title}",
    enabled = true,
    tooltip = pr.url,
    onClick = { onOpenUrl(pr.url) },
  )
}

/**
 * `GitActionsControl` in panel mode: the quick action (`resolveQuickAction`) as a split group
 * whose chevron lists `buildMenuItems`. Each runs the IDE's own VCS action for the step, see
 * [ideActionForGitAction]; a disabled one carries upstream's reason.
 */
@Composable
private fun GitActionsGroup(state: ThreadDetailsPanelState, onGitAction: (String) -> Unit) {
  val quick: GitQuickAction = state.quickAction
  val reason: String? = state.gitActionsReason
  val run: String? = when (quick) {
    is GitQuickAction.RunAction -> quick.action
    is GitQuickAction.RunPull -> "pull"
    // The IDE has no one-step "share on GitHub" the plugin can name; the desktop app does it
    is GitQuickAction.OpenPublish, is GitQuickAction.ShowHint -> null
  }
  val tooltip: String? = when {
    quick is GitQuickAction.ShowHint -> quick.hint
    quick is GitQuickAction.OpenPublish -> "Publish the repository from the T3 desktop app"
    reason != null -> reason
    else -> null
  }
  PanelSplitGroup(
    leading = { PanelIcon(quickActionGlyph(quick)) },
    label = quick.label,
    enabled = run != null && reason == null,
    tooltip = tooltip,
    onClick = { run?.let(onGitAction) },
    menuTooltip = "Git action options",
  ) { close ->
    PopupMenu(
      onDismissRequest = {
        close()
        return@PopupMenu true
      },
      horizontalAlignment = Alignment.End,
    ) {
      state.menuItems.forEach { item ->
        val why: String? = reason
          ?: gitActionDisabledReason(item, state.vcs, isBusy = false, state.hasPrimaryRemote)
        selectableItem(
          selected = false,
          enabled = why == null,
          onClick = {
            close()
            onGitAction(item.dialogAction)
          },
        ) {
          MenuItemRow(gitActionGlyph(item.icon), item.label, hint = why)
        }
      }
    }
  }
}

/** The Changes row: `file-diff`, "Changes", and `+N -N` in mono from [changesTotals]. */
@Composable
private fun ChangesRow(state: ThreadDetailsPanelState, onOpenChanges: () -> Unit) {
  PanelRow(
    leading = { PanelIcon("file-diff") },
    label = "Changes",
    enabled = state.gitActionsReason == null,
    tooltip = state.gitActionsReason,
    onClick = onOpenChanges,
    trailing = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "+${formatDiffCount(state.changes.insertions)}",
          style = small().copy(fontFamily = FontFamily.Monospace),
          color = DIFF_ADDITION,
        )
        Text(
          text = "-${formatDiffCount(state.changes.deletions)}",
          style = small().copy(fontFamily = FontFamily.Monospace),
          color = DIFF_DELETION,
        )
      }
    },
  )
}

/** Upstream's `--diff-addition` and `--diff-deletion`. */
private val DIFF_ADDITION: Color = Color(0xFF22C55E)
private val DIFF_DELETION: Color = Color(0xFFEF4444)

/** `THREAD_DETAILS_PANEL_ICON_CLASS`: 16dp, muted, with an optional tooltip. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun PanelIcon(glyph: String, tooltip: String? = null) {
  if (tooltip == null) return Glyph(glyph, muted(), 16.dp)
  Tooltip(tooltip = { Text(tooltip) }) { Glyph(glyph, muted(), 16.dp) }
}

/** A menu row: a 16dp glyph, the label, and a muted [hint] under it when the row is disabled. */
@Composable
private fun MenuItemRow(glyph: String, label: String, hint: String? = null) {
  Row(
    modifier = Modifier.padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph(glyph, muted(), 16.dp)
    Column {
      Text(label)
      if (hint != null) Text(hint, style = small(), color = muted())
    }
  }
}

/** `THREAD_DETAILS_PANEL_HOVER_SURFACE_CLASS`: black at 3.5% on light, white at 8% on dark. */
@Composable
private fun hoverFill(): Color =
  if (JewelTheme.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.035f)

/** `text-foreground/80`, the rows' text. */
@Composable
private fun rowText(): Color = JewelTheme.globalColors.text.normal.copy(alpha = 0.8f)

private val ROW_SHAPE: Shape = RoundedCornerShape(8.dp)
private val ROW_START_SHAPE: Shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
private val ROW_END_SHAPE: Shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)

/**
 * `THREAD_DETAILS_PANEL_ROW_CLASS`, or `THREAD_DETAILS_PANEL_LOCKED_ROW_CLASS` when [locked]: one
 * full-width 32dp row. Disabled rows dim to 64% and keep their [tooltip], as upstream's do.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun PanelRow(
  leading: @Composable () -> Unit,
  label: String,
  enabled: Boolean,
  locked: Boolean = false,
  tooltip: String? = null,
  onClick: () -> Unit = {},
  trailing: (@Composable () -> Unit)? = null,
) {
  val content: @Composable () -> Unit = {
    RowSurface(
      enabled = enabled && !locked,
      onClick = onClick,
      shape = ROW_SHAPE,
      modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.64f),
    ) {
      leading()
      RowLabel(label, Modifier.weight(1f))
      trailing?.invoke()
    }
  }
  if (tooltip == null) content() else Tooltip(tooltip = { Text(tooltip) }, content = content)
}

/**
 * `THREAD_DETAILS_PANEL_SELECT_ROW_CLASS`: the label half and the chevron half light up on their
 * own but open the same [popup], which gets a `close`.
 */
@Composable
private fun PanelSelectRow(
  leading: @Composable () -> Unit,
  label: String,
  enabled: Boolean,
  popup: @Composable (close: () -> Unit) -> Unit,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
      RowSurface(
        enabled = enabled,
        onClick = { open = !open },
        shape = ROW_START_SHAPE,
        modifier = Modifier.weight(1f),
      ) {
        leading()
        RowLabel(label, Modifier.weight(1f))
      }
      ChevronHalf(enabled = enabled, onClick = { open = !open })
    }
    if (open) popup { open = false }
  }
}

/**
 * `THREAD_DETAILS_PANEL_SPLIT_GROUP_CLASS`: a primary action and, past a hairline, a chevron
 * onto its [popup]. The two are independent: a disabled primary still lets the menu open.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun PanelSplitGroup(
  leading: @Composable () -> Unit,
  label: String,
  enabled: Boolean,
  tooltip: String?,
  onClick: () -> Unit,
  menuTooltip: String,
  popup: @Composable (close: () -> Unit) -> Unit,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
      val primary: @Composable () -> Unit = {
        RowSurface(
          enabled = enabled,
          onClick = onClick,
          shape = ROW_START_SHAPE,
          modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.64f),
        ) {
          leading()
          RowLabel(label, Modifier.weight(1f))
        }
      }
      Box(Modifier.weight(1f)) {
        if (tooltip == null) primary() else Tooltip(tooltip = { Text(tooltip) }, content = primary)
      }
      Tooltip(tooltip = { Text(menuTooltip) }) {
        ChevronHalf(enabled = true, onClick = { open = !open })
      }
    }
    if (open) popup { open = false }
  }
}

/** The 32dp chevron half, with the hairline on its left (`select-icon`'s `before:` rule). */
@Composable
private fun ChevronHalf(enabled: Boolean, onClick: () -> Unit) {
  Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
    Divider(Orientation.Vertical, Modifier.height(16.dp))
    RowSurface(
      enabled = enabled,
      onClick = onClick,
      shape = ROW_END_SHAPE,
      modifier = Modifier.width(32.dp),
      padding = 0.dp,
    ) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Glyph("chevron-down", muted(), 16.dp)
      }
    }
  }
}

/** The hover surface every row part shares: 32dp tall, [shape], [padding] on the sides. */
@Composable
private fun RowSurface(
  enabled: Boolean,
  onClick: () -> Unit,
  shape: Shape,
  modifier: Modifier = Modifier,
  padding: Dp = 10.dp,
  content: @Composable RowScope.() -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  Row(
    modifier = modifier
      .height(32.dp)
      .clip(shape)
      .background(if (hovered && enabled) hoverFill() else Color.Transparent)
      .hoverable(interactions, enabled)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      )
      .padding(horizontal = padding),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/** `THREAD_DETAILS_PANEL_LABEL_CLASS` text: 13px medium at 80%, one line. */
@Composable
private fun RowLabel(label: String, modifier: Modifier = Modifier) {
  Text(
    text = label,
    modifier = modifier,
    color = rowText(),
    fontWeight = FontWeight.Medium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}
