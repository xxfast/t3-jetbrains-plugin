package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ProjectScript
import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusRemoteResult
import java.text.NumberFormat

// The rules behind T3 Thread Details' Workspace and Version Control sections, upstream's
// ThreadDetailsPanel.tsx and what it composes: BranchToolbar, OpenInPicker, ProjectScriptsControl
// and GitActionsControl. Pure, so they're tested without an IDE.

/**
 * `ChangeRequestTerminology`: what the host calls a change request. GitHub's definition says
 * pull request, GitLab's merge request (`packages/client-runtime/src/sourceControlClients.ts`);
 * a repository that hasn't reported its host yet reads as GitHub.
 */
data class ChangeRequestTerminology(val singular: String, val shortLabel: String)

/** `resolveChangeRequestTerminology`, GitActionsControl.logic.ts:65. */
fun resolveChangeRequestTerminology(status: VcsStatus?): ChangeRequestTerminology =
  if (status?.local?.sourceControlProvider?.kind == "gitlab") MERGE_REQUEST else PULL_REQUEST

private val PULL_REQUEST = ChangeRequestTerminology("pull request", "PR")
private val MERGE_REQUEST = ChangeRequestTerminology("merge request", "MR")

/**
 * Upstream's `VcsStatusResult` is one flat record; the plugin keeps the two stream halves. A
 * missing remote half reads as upstream's placeholder: no upstream, nothing ahead or behind.
 */
private val NO_REMOTE = VcsStatusRemoteResult(
  hasUpstream = false,
  aheadCount = 0,
  behindCount = 0,
  pr = null,
)

/** The remote half, or the placeholder. */
val VcsStatus.remoteOrPlaceholder: VcsStatusRemoteResult
  get() = remote ?: NO_REMOTE

/** `GitQuickAction`, GitActionsControl.logic.ts: the Version Control section's first row. */
sealed interface GitQuickAction {
  val label: String
  val disabled: Boolean

  /**
   * `run_action`: a stacked git action, [action] one of `commit`, `push`, `commit_push`,
   * `commit_push_pr` and `create_pr`.
   */
  data class RunAction(override val label: String, val action: String) : GitQuickAction {
    override val disabled: Boolean get() = false
  }

  /** `run_pull`. */
  data object RunPull : GitQuickAction {
    override val label: String get() = "Pull"
    override val disabled: Boolean get() = false
  }

  /** `open_publish`: the repository has no remote to push to yet. */
  data object OpenPublish : GitQuickAction {
    override val label: String get() = "Publish repository"
    override val disabled: Boolean get() = false
  }

  /** `show_hint`: nothing to do, [hint] says why. */
  data class ShowHint(override val label: String, val hint: String) : GitQuickAction {
    override val disabled: Boolean get() = true
  }
}

/** `resolveQuickAction`, GitActionsControl.logic.ts:187, line for line. */
fun resolveQuickAction(
  status: VcsStatus?,
  isBusy: Boolean,
  isDefaultRef: Boolean = false,
  hasPrimaryRemote: Boolean = true,
): GitQuickAction {
  if (isBusy) return GitQuickAction.ShowHint("Commit", "Git action in progress.")
  if (status == null) return GitQuickAction.ShowHint("Commit", "Git status is unavailable.")
  val local: VcsStatusLocalResult = status.local
  val remote: VcsStatusRemoteResult = status.remoteOrPlaceholder
  val hasBranch: Boolean = local.refName != null
  val hasChanges: Boolean = local.hasWorkingTreeChanges
  val hasOpenPr: Boolean = remote.pr?.state == "open"
  val isAhead: Boolean = remote.aheadCount > 0
  val hasDefaultBranchDelta: Boolean = (remote.aheadOfDefaultCount ?: remote.aheadCount) > 0
  val isBehind: Boolean = remote.behindCount > 0
  val isDiverged: Boolean = isAhead && isBehind
  val terminology: ChangeRequestTerminology = resolveChangeRequestTerminology(status)

  if (!hasBranch) {
    return GitQuickAction.ShowHint(
      "Commit",
      "Create and checkout a ref before pushing or opening a ${terminology.singular}.",
    )
  }
  if (hasChanges) {
    if (!remote.hasUpstream && !hasPrimaryRemote) {
      return GitQuickAction.RunAction("Commit", "commit")
    }
    if (hasOpenPr || isDefaultRef) return GitQuickAction.RunAction("Commit & push", "commit_push")
    return GitQuickAction.RunAction("Commit, push & ${terminology.shortLabel}", "commit_push_pr")
  }
  if (!remote.hasUpstream) {
    if (!hasPrimaryRemote) return GitQuickAction.OpenPublish
    if (!isAhead) {
      if (hasOpenPr) return GitQuickAction.ShowHint("Commit", UP_TO_DATE)
      return GitQuickAction.ShowHint("Push", "No local commits to push.")
    }
    if (hasOpenPr || isDefaultRef) {
      return GitQuickAction.RunAction("Push", if (isDefaultRef) "commit_push" else "push")
    }
    return GitQuickAction.RunAction("Push & create ${terminology.shortLabel}", "create_pr")
  }
  if (isDiverged) {
    return GitQuickAction.ShowHint(
      "Sync ref",
      "Branch has diverged from upstream. Rebase/merge first.",
    )
  }
  if (isBehind) return GitQuickAction.RunPull
  if (isAhead) {
    if (hasOpenPr || isDefaultRef) {
      return GitQuickAction.RunAction("Push", if (isDefaultRef) "commit_push" else "push")
    }
    return GitQuickAction.RunAction("Push & create ${terminology.shortLabel}", "create_pr")
  }
  if (hasOpenPr && remote.hasUpstream) return GitQuickAction.ShowHint("Commit", UP_TO_DATE)
  if (hasDefaultBranchDelta && !isDefaultRef) {
    return GitQuickAction.RunAction("Create ${terminology.shortLabel}", "create_pr")
  }
  return GitQuickAction.ShowHint("Commit", UP_TO_DATE)
}

private const val UP_TO_DATE: String = "Branch is up to date. No action needed."

/** `GitActionMenuItem`: a row of the Version Control section's "…" menu. */
data class GitActionMenuItem(
  val id: String,
  val label: String,
  val disabled: Boolean,
  /** `commit`, `push` or `pr`, what `GitActionItemIcon` draws. */
  val icon: String,
  /** The stacked action the row opens a dialog for: `commit`, `push`, `create_pr`. */
  val dialogAction: String,
)

/** `buildMenuItems`, GitActionsControl.logic.ts:118. */
fun buildMenuItems(
  status: VcsStatus?,
  isBusy: Boolean,
  hasPrimaryRemote: Boolean = true,
): List<GitActionMenuItem> {
  if (status == null) return emptyList()
  val local: VcsStatusLocalResult = status.local
  val remote: VcsStatusRemoteResult = status.remoteOrPlaceholder
  val terminology: ChangeRequestTerminology = resolveChangeRequestTerminology(status)
  val hasBranch: Boolean = local.refName != null
  val hasChanges: Boolean = local.hasWorkingTreeChanges
  val hasOpenPr: Boolean = remote.pr?.state == "open"
  val isBehind: Boolean = remote.behindCount > 0
  val hasDefaultBranchDelta: Boolean = (remote.aheadOfDefaultCount ?: remote.aheadCount) > 0
  val canPushWithoutUpstream: Boolean = hasPrimaryRemote && !remote.hasUpstream
  val canCommit: Boolean = !isBusy && hasChanges
  val canPush: Boolean = !isBusy && hasBranch && !isBehind && remote.aheadCount > 0 &&
    (remote.hasUpstream || canPushWithoutUpstream)
  val canCreatePr: Boolean = !isBusy && hasBranch && !hasOpenPr && hasDefaultBranchDelta &&
    !isBehind && (remote.hasUpstream || canPushWithoutUpstream)

  val commit = GitActionMenuItem("commit", "Commit", !canCommit, "commit", "commit")
  if (!hasPrimaryRemote) return listOf(commit)
  val push = GitActionMenuItem("push", "Push", !canPush, "push", "push")
  if (hasOpenPr) return listOf(commit, push)
  return listOf(
    commit,
    push,
    GitActionMenuItem("pr", "Create ${terminology.shortLabel}", !canCreatePr, "pr", "create_pr"),
  )
}

/**
 * `getGitActionDisabledReason`, GitActionsControl.tsx:225: why a menu item is disabled, for its
 * tooltip. Null for an enabled one.
 */
fun gitActionDisabledReason(
  item: GitActionMenuItem,
  status: VcsStatus?,
  isBusy: Boolean,
  hasPrimaryRemote: Boolean,
): String? {
  if (!item.disabled) return null
  if (isBusy) return "Git action in progress."
  if (status == null) return "Git status is unavailable."
  val local: VcsStatusLocalResult = status.local
  val remote: VcsStatusRemoteResult = status.remoteOrPlaceholder
  val hasBranch: Boolean = local.refName != null
  val hasChanges: Boolean = local.hasWorkingTreeChanges
  val isAhead: Boolean = remote.aheadCount > 0
  val isBehind: Boolean = remote.behindCount > 0
  val terminology: ChangeRequestTerminology = resolveChangeRequestTerminology(status)
  if (item.id == "commit") {
    if (!hasChanges) return "Worktree is clean. Make changes before committing."
    return "Commit is currently unavailable."
  }
  if (item.id == "push") {
    if (!hasBranch) return "Detached HEAD: check out a branch before pushing."
    if (hasChanges) return "Commit or stash local changes before pushing."
    if (isBehind) return "Branch is behind upstream. Pull/rebase before pushing."
    if (!remote.hasUpstream && !hasPrimaryRemote) return "Add an \"origin\" remote before pushing."
    if (!isAhead) return "No local commits to push."
    return "Push is currently unavailable."
  }
  if (!hasBranch) {
    return "Detached HEAD: check out a branch before creating a ${terminology.singular}."
  }
  if (!remote.hasUpstream && !hasPrimaryRemote) {
    return "Add an \"origin\" remote before creating a ${terminology.singular}."
  }
  if ((remote.aheadOfDefaultCount ?: remote.aheadCount) <= 0) {
    return "No local commits to include in a ${terminology.singular}."
  }
  if (isBehind) {
    return "Branch is behind upstream. Pull/rebase before creating a ${terminology.singular}."
  }
  return "Create ${terminology.singular} is currently unavailable."
}

/** `GitQuickActionIcon`, GitActionsControl.tsx:302, as a lucide glyph name. */
fun quickActionGlyph(action: GitQuickAction): String = when (action) {
  is GitQuickAction.OpenPublish -> "cloud-upload"
  is GitQuickAction.RunPull -> "cloud-download"
  is GitQuickAction.RunAction -> when (action.action) {
    "commit" -> "git-commit-horizontal"
    "push", "commit_push" -> "cloud-upload"
    else -> "git-pull-request"
  }
  is GitQuickAction.ShowHint -> when (action.label) {
    "Commit" -> "git-commit-horizontal"
    "Push" -> "cloud-upload"
    else -> "info"
  }
}

/** `GitActionItemIcon`, GitActionsControl.tsx:292. */
fun gitActionGlyph(icon: String): String = when (icon) {
  "commit" -> "git-commit-horizontal"
  "push" -> "cloud-upload"
  else -> "git-pull-request"
}

/**
 * The IDE action that does the first step of a stacked git [action]. Upstream runs the stack on
 * the server (`git.runStackedAction`); the plugin hands it to the IDE's own VCS UI instead, since
 * that's where the user commits, pushes and opens pull requests already. A commit stack opens
 * the Commit tool window, which has "Commit and Push..." of its own. Null for one the IDE has
 * no action for.
 */
fun ideActionForGitAction(action: String): String? = when (action) {
  "commit", "commit_push", "commit_push_pr" -> "CheckinProject"
  "push" -> "Vcs.Push"
  "create_pr" -> "Github.Create.Pull.Request"
  "pull" -> "Vcs.UpdateProject"
  else -> null
}

/**
 * The totals the Changes row prints, `changesTotals` (GitActionsControl.tsx:1093): the branch's
 * changes against its base when the server reports them, else the working tree's.
 */
data class ChangesTotals(val insertions: Long, val deletions: Long)

fun changesTotals(status: VcsStatus?): ChangesTotals {
  val local: VcsStatusLocalResult = status?.local ?: return ChangesTotals(0, 0)
  val branch: VcsStatusLocalResult.BranchChanges? = local.branchChanges
  return if (branch != null) {
    ChangesTotals(branch.insertions, branch.deletions)
  } else {
    ChangesTotals(local.workingTree.insertions, local.workingTree.deletions)
  }
}

/** `formatDiffCount`, packages/client-runtime/src/diffCount.ts: `Intl.NumberFormat()`, grouped. */
fun formatDiffCount(count: Long): String = NumberFormat.getIntegerInstance().format(count)

/** `primaryProjectScript`, apps/web/src/projectScripts.ts:103: the first plain script, else the
 * first that isn't a settle hook. */
fun primaryProjectScript(scripts: List<ProjectScript>): ProjectScript? =
  scripts.find { !it.runOnWorktreeCreate && it.runOnSettle != true }
    ?: scripts.find { it.runOnSettle != true }

/**
 * `ProjectScriptsControl`'s pick: the last script run in this project when it's still listed,
 * else [primaryProjectScript].
 */
fun resolvePrimaryScript(scripts: List<ProjectScript>, preferredScriptId: String?): ProjectScript? =
  preferredScriptId?.let { id -> scripts.find { it.id == id } } ?: primaryProjectScript(scripts)

/** `projectScriptTerminalInput`, packages/shared/src/projectScripts.ts:88: a leading space keeps
 * it out of the shell's history, the return runs it. */
fun projectScriptTerminalInput(command: String): String = " $command\r"

/** `ScriptIcon`, apps/web/src/components/projectScriptEditor.tsx:72, as a lucide glyph name. */
fun scriptGlyph(icon: String): String = when (icon) {
  "test" -> "flask-conical"
  "lint" -> "list-checks"
  "configure" -> "wrench"
  "build" -> "hammer"
  "debug" -> "bug"
  else -> "play"
}

/**
 * `EnvironmentMachineIcon`'s `ICON_BY_KIND` (apps/web/src/components/EnvironmentMachineIcon.tsx)
 * as a lucide glyph name. Upstream draws Linux, Mac mini and Mac Studio with icons of its own;
 * those read as a server, a desktop and a desktop here.
 */
fun machineGlyph(kind: String): String = when (kind) {
  "cloud" -> "cloud"
  "desktop", "mac-mini", "mac-studio" -> "monitor"
  "laptop" -> "laptop"
  else -> "server"
}

/**
 * `nextTerminalId`, packages/shared/src/terminalLabels.ts:33: the first `term-N` not in
 * [existingTerminalIds], with [uniqueSuffix] (a UUID) appended when the caller can't see every
 * terminal the server has, as the plugin can't.
 */
fun nextTerminalId(existingTerminalIds: Collection<String>, uniqueSuffix: String? = null): String {
  val used: Set<String> = existingTerminalIds.mapNotNull(::terminalNumber).toSet()
  var next = 1
  while (next.toString() in used) next += 1
  return "term-$next" + (uniqueSuffix?.let { "-$it" } ?: "")
}

/** `terminalNumber`: the N of `term-N`, `terminal-N` or `term-N-<uuid>`, else null. */
private fun terminalNumber(terminalId: String): String? =
  TERMINAL_ID.find(terminalId)?.groupValues?.get(1)

private val TERMINAL_ID = Regex(
  "^term(?:inal)?-(\\d+)(?:-[\\da-f]{8}(?:-[\\da-f]{4}){3}-[\\da-f]{12})?$",
  RegexOption.IGNORE_CASE,
)
