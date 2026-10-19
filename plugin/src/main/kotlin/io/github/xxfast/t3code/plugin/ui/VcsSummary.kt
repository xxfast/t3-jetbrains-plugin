package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusRemoteResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent

/** Where a thread's checkout stands, as `subscribeVcsStatus` last said. */
data class VcsStatus(val local: VcsStatusLocalResult, val remote: VcsStatusRemoteResult?)

/**
 * Upstream's `applyGitStatusStreamEvent`, packages/shared/src/git.ts:351 to 376: a snapshot
 * replaces both halves, an update replaces its own half and keeps the other. A remote update
 * before any local one gets upstream's placeholder local part.
 */
fun applyGitStatusStreamEvent(current: VcsStatus?, event: VcsStatusStreamEvent): VcsStatus? =
  when (event) {
    is VcsStatusStreamEvent.Snapshot -> VcsStatus(event.local, event.remote)
    is VcsStatusStreamEvent.LocalUpdated -> VcsStatus(event.local, current?.remote)
    is VcsStatusStreamEvent.RemoteUpdated -> VcsStatus(current?.local ?: PLACEHOLDER, event.remote)
    is VcsStatusStreamEvent.Unknown -> current
  }

private val PLACEHOLDER: VcsStatusLocalResult = VcsStatusLocalResult(
  isRepo = true,
  hasPrimaryRemote = false,
  isDefaultRef = false,
  refName = null,
  hasWorkingTreeChanges = false,
  workingTree = VcsStatusLocalResult.WorkingTree(
    files = emptyList(),
    insertions = 0,
    deletions = 0,
  ),
)

/**
 * The thread header's one git line. Upstream's header has no such line; this is ours, built from
 * upstream's own pieces: the branch as the branch toolbar names it, "N changed files" as
 * `ChangedFilesTree.tsx:55` counts, `+N −M` as the Sidebar row prints (`Sidebar.tsx:2456`, with a
 * real minus sign), and arrows for ahead and behind, which upstream never prints.
 *
 * Counts and totals both come from the working tree: `branchChanges` has totals but no files, and
 * mixing the two would print a file count that doesn't match its lines.
 */
data class VcsSummary(
  val branch: String, // "detached HEAD" without a ref
  val files: Int,
  val insertions: Long,
  val deletions: Long,
  val ahead: Long, // 0 without an upstream
  val behind: Long,
)

/** `null` outside a repository: nothing to say. */
fun vcsSummary(status: VcsStatus): VcsSummary? {
  val local: VcsStatusLocalResult = status.local
  if (!local.isRepo) return null
  val remote: VcsStatusRemoteResult? = status.remote?.takeIf { it.hasUpstream }
  return VcsSummary(
    branch = local.refName ?: DETACHED,
    files = local.workingTree.files.size,
    insertions = local.workingTree.insertions,
    deletions = local.workingTree.deletions,
    ahead = remote?.aheadCount ?: 0,
    behind = remote?.behindCount ?: 0,
  )
}

/** `main · 2 changed files +4 −1 · ↑2 ↓1`, every zero part left out. */
fun VcsSummary.text(): String {
  val changes: String? = files.takeIf { it > 0 }?.let { count ->
    val noun: String = if (count == 1) "changed file" else "changed files"
    val stats: String = listOfNotNull(
      insertions.takeIf { it > 0 }?.let { "+$it" },
      deletions.takeIf { it > 0 }?.let { "−$it" },
    ).joinToString(" ")
    listOf("$count $noun", stats).filter { it.isNotEmpty() }.joinToString(" ")
  }
  val sync: String? = listOfNotNull(
    ahead.takeIf { it > 0 }?.let { "↑$it" },
    behind.takeIf { it > 0 }?.let { "↓$it" },
  ).joinToString(" ").ifEmpty { null }
  return listOfNotNull(branch, changes, sync).joinToString(" · ")
}

private const val DETACHED: String = "detached HEAD"
