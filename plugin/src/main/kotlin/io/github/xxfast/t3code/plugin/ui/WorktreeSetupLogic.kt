package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupStage
import java.time.Duration
import java.time.Instant

/** `WORKTREE_SETUP_STAGE_ORDER`, `packages/contracts/src/worktreeSetup.ts:84`. */
val WORKTREE_SETUP_STAGE_ORDER: List<String> =
  listOf("fetch", "checkout", "submodules", "setup-script", "agent")

/** `worktreeSetupStageLabel`, `worktreeSetup.ts:92`. An unknown stage shows its id. */
fun worktreeSetupStageLabel(id: String): String = when (id) {
  "fetch" -> "Fetch base branch"
  "checkout" -> "Check out files"
  "submodules" -> "Init submodules"
  "setup-script" -> "Run setup script"
  "agent" -> "Start agent"
  else -> id
}

/** `worktreeSetupAgentStarted`, `packages/client-runtime/src/worktreeSetup.ts:46`. */
fun worktreeSetupAgentStarted(snapshot: WorktreeSetupSnapshot): Boolean =
  snapshot.stages.any { it.id == "agent" && it.status == "done" }

/**
 * `resolveVisibleWorktreeSetup`, `worktreeSetup.ts:24`: which snapshot the timeline shows. A
 * running setup always; a finished one until the turn starts, unless a stage failed; nothing
 * once a follow-up went out. [recorded] is the activity upstream keeps on the thread; the
 * plugin has none, so it's null.
 */
fun resolveVisibleWorktreeSetup(
  live: WorktreeSetupSnapshot?,
  recorded: WorktreeSetupSnapshot?,
  turnStarted: Boolean,
  followUpSent: Boolean,
): WorktreeSetupSnapshot? {
  val snapshot: WorktreeSetupSnapshot =
    (if (live != null && (recorded == null || live.sequence >= recorded.sequence)) live
    else recorded) ?: return null
  if (snapshot.phase == "running") return snapshot
  if (followUpSent) return null
  if (snapshot.phase != "done") return snapshot
  if (!turnStarted) return snapshot
  return snapshot.takeIf { it.stages.any { stage -> stage.status == "failed" } }
}

/** What [resolveWorktreeSetupProgress] works out. */
data class WorktreeSetupProgress(
  val snapshot: WorktreeSetupSnapshot?,
  /** `isPreparingWorktree`: a send is waiting on the worktree. */
  val preparing: Boolean,
)

/**
 * `resolveWorktreeSetupProgress`, `ChatView.logic.ts:269`: the newest of the live and the held
 * snapshot for [threadId], and whether the worktree is still being prepared. [localPreparing]
 * is a launch the plugin sent and the server hasn't answered; [runStatus] is the latest run's.
 */
fun resolveWorktreeSetupProgress(
  threadId: String,
  localPreparing: Boolean,
  runStatus: String?,
  latest: WorktreeSetupSnapshot?,
  held: WorktreeSetupSnapshot?,
): WorktreeSetupProgress {
  val latestFor: WorktreeSetupSnapshot? = latest?.takeIf { it.threadId == threadId }
  val heldFor: WorktreeSetupSnapshot? = held?.takeIf { it.threadId == threadId }
  val snapshot: WorktreeSetupSnapshot? =
    if (latestFor != null && (heldFor == null || latestFor.sequence >= heldFor.sequence)) {
      latestFor
    } else {
      heldFor
    }
  return WorktreeSetupProgress(
    snapshot = snapshot,
    preparing = localPreparing || runStatus == "preparing" ||
      (snapshot?.phase == "running" && !worktreeSetupAgentStarted(snapshot)),
  )
}

/**
 * `heldSnapshot`'s rule in `useWorktreeSetup.ts:95`: a newer snapshot replaces the held one, a
 * late older one for the same thread doesn't, and the server's null keeps what's held.
 */
fun holdWorktreeSetup(
  held: WorktreeSetupSnapshot?,
  latest: WorktreeSetupSnapshot?,
): WorktreeSetupSnapshot? = when {
  latest == null -> held
  held?.threadId == latest.threadId && held.sequence > latest.sequence -> held
  else -> latest
}

/**
 * `blocksSend`, `useWorktreeSetup.ts:134`: a send waits for the agent handoff, not for the
 * setup script, and before the first snapshot the launch in flight stands in for it.
 */
fun worktreeSetupBlocksSend(
  visible: WorktreeSetupSnapshot?,
  preparing: Boolean,
): Boolean = if (visible != null) {
  visible.phase == "running" && !worktreeSetupAgentStarted(visible)
} else {
  preparing
}

/** `headerLabel`, `WorktreeSetupCard.tsx:103`. */
fun worktreeSetupHeaderLabel(snapshot: WorktreeSetupSnapshot): String = when (snapshot.phase) {
  "running" -> "Setting up worktree…"
  "done" -> if (snapshot.stages.any { it.status == "failed" }) {
    "Worktree ready, setup script failed"
  } else {
    "Worktree ready"
  }
  "failed" -> "Worktree setup failed"
  "cancelled" -> "Worktree setup cancelled"
  else -> snapshot.phase
}

/** `CollapsedSummaryRow`'s status, `WorktreeSetupCard.tsx:329`. */
fun worktreeSetupSummaryStatus(snapshot: WorktreeSetupSnapshot): String = when {
  snapshot.phase == "failed" || snapshot.phase == "cancelled" -> "failed"
  snapshot.stages.any { it.id == "setup-script" && it.status == "failed" } -> "failed"
  else -> "done"
}

/**
 * `StageRow`'s label, `WorktreeSetupCard.tsx:177`: the setup script's own name on its stage.
 */
fun worktreeSetupStageLabel(stage: WorktreeSetupStage, scriptName: String?): String =
  if (stage.id == "setup-script" && scriptName != null) scriptName
  else worktreeSetupStageLabel(stage.id)

/** `StageRow`'s trailing text, `WorktreeSetupCard.tsx:180`. */
fun worktreeSetupStageTrailing(stage: WorktreeSetupStage): String? = when {
  stage.status == "pending" -> null
  stage.status == "skipped" -> stage.detail ?: "skipped"
  stage.id == "checkout" && stage.status == "running" && stage.percent != null ->
    "${stage.percent}%"
  else -> stage.detail
}

/** `stageElapsedMs`, `WorktreeSetupCard.tsx:35`. */
fun worktreeSetupStageElapsedMs(stage: WorktreeSetupStage, now: Instant): Long? {
  val start: Instant = stage.startedAt?.let(::instantOrNull) ?: return null
  val end: Instant = stage.endedAt?.let(::instantOrNull) ?: now
  return Duration.between(start, end).toMillis().coerceAtLeast(0)
}

/** The card's total, `WorktreeSetupCard.tsx:391`. */
fun worktreeSetupElapsedMs(snapshot: WorktreeSetupSnapshot, now: Instant): Long? {
  val start: Instant = instantOrNull(snapshot.startedAt) ?: return null
  val end: Instant = snapshot.endedAt?.let(::instantOrNull) ?: now
  return Duration.between(start, end).toMillis().coerceAtLeast(0)
}

/** `OUTPUT_TAIL_LINES`, `WorktreeSetupCard.tsx:270`: the last lines of the setup script. */
const val WORKTREE_SETUP_TAIL_LINES: Int = 4

/** `showTail`, `WorktreeSetupCard.tsx:399`: the script's output while it runs or once it failed. */
fun worktreeSetupShowsTail(snapshot: WorktreeSetupSnapshot): Boolean {
  val script: WorktreeSetupStage = snapshot.stages.find { it.id == "setup-script" } ?: return false
  return script.status == "failed" || (script.status == "running" && script.tail.isNotEmpty())
}

private fun instantOrNull(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()
