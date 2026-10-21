package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.AppThread
import io.github.xxfast.t3code.protocol.generated.VcsListRefsInput
import io.github.xxfast.t3code.protocol.generated.VcsListRefsResult
import io.github.xxfast.t3code.protocol.generated.VcsRef
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupStage
import java.time.Instant

/** The refs the harness checkout has, what `vcs.listRefs` answers in fixture mode. */
val FIXTURE_REFS: List<VcsRef> = listOf(
  VcsRef(name = "main", current = true, isDefault = true, worktreePath = null),
  VcsRef(name = "develop", current = false, isDefault = false, worktreePath = null),
  VcsRef(
    name = "feature/composer-glass",
    current = false,
    isDefault = false,
    worktreePath = "/Users/xxfast/Developer/XXFAST/KMP/KStore/.t3/worktrees/composer-glass",
  ),
  VcsRef(name = "fix/sidebar-sort", current = false, isDefault = false, worktreePath = null),
  VcsRef(name = "release/0.1", current = false, isDefault = false, worktreePath = null),
  VcsRef(
    name = "origin/main",
    isRemote = true,
    remoteName = "origin",
    current = false,
    isDefault = false,
    worktreePath = null,
  ),
  VcsRef(
    name = "origin/develop",
    isRemote = true,
    remoteName = "origin",
    current = false,
    isDefault = false,
    worktreePath = null,
  ),
)

/**
 * `vcs.listRefs` over [refs]: a substring filter on the query, as the server does, with
 * [current] marked as checked out.
 */
fun fixtureListRefs(
  input: VcsListRefsInput,
  refs: List<VcsRef> = FIXTURE_REFS,
  current: String = "main",
): VcsListRefsResult {
  val query: String = input.query.orEmpty().lowercase()
  val refs: List<VcsRef> = refs
    .filter { query.isEmpty() || query in it.name.lowercase() }
    .map { it.copy(current = it.name == current) }
  return VcsListRefsResult(
    refs = refs,
    isRepo = true,
    hasPrimaryRemote = true,
    nextCursor = null,
    totalCount = refs.size.toLong(),
  )
}

/** One step of a fake setup: the snapshot to publish, after waiting [after] milliseconds. */
class FixtureSetupStep(val after: Long, val snapshot: WorktreeSetupSnapshot)

/**
 * A worktree setup as the server would stream it, stage by stage over a few seconds: fetch,
 * a checkout with a percentage, skipped submodules, a setup script with an output tail, then
 * the agent. Each step is the whole snapshot with a higher `sequence`.
 */
fun fixtureSetupSteps(
  threadId: String,
  baseRef: String,
  branch: String,
  worktreePath: String,
  startedAt: Instant,
): List<FixtureSetupStep> {
  val script = WorktreeSetupSnapshot.SetupScript(
    name = "Install",
    command = "./gradlew --offline help",
    terminalId = "setup-script",
  )
  var sequence = 0L
  var at: Instant = startedAt

  fun stage(
    id: String,
    status: String,
    started: Instant? = null,
    ended: Instant? = null,
    percent: Long? = null,
    detail: String? = null,
    tail: List<String> = emptyList(),
  ) = WorktreeSetupStage(
    id = id,
    status = status,
    startedAt = started?.toString(),
    endedAt = ended?.toString(),
    percent = percent,
    detail = detail,
    tail = tail,
  )

  fun snapshot(
    after: Long,
    phase: String,
    stages: List<WorktreeSetupStage>,
    ended: Boolean = false,
  ): FixtureSetupStep {
    at = at.plusMillis(after)
    return FixtureSetupStep(
      after = after,
      snapshot = WorktreeSetupSnapshot(
        threadId = threadId,
        phase = phase,
        startedAt = startedAt.toString(),
        endedAt = if (ended) at.toString() else null,
        branch = branch,
        baseRef = baseRef,
        worktreePath = worktreePath,
        setupScript = script,
        stages = stages,
        error = null,
        sequence = sequence++,
      ),
    )
  }

  val pending: Map<String, WorktreeSetupStage> =
    listOf("fetch", "checkout", "submodules", "setup-script", "agent")
      .associateWith { stage(it, "pending") }
  val fetchStart: Instant = startedAt
  val fetchEnd: Instant = fetchStart.plusMillis(700)
  val checkoutEnd: Instant = fetchEnd.plusMillis(1_400)
  val scriptEnd: Instant = checkoutEnd.plusMillis(2_000)
  val agentEnd: Instant = scriptEnd.plusMillis(400)
  val fetched: WorktreeSetupStage = stage("fetch", "done", fetchStart, fetchEnd, detail = baseRef)
  val checkedOut: WorktreeSetupStage =
    stage("checkout", "done", fetchEnd, checkoutEnd, detail = "1,284 files")
  val submodules: WorktreeSetupStage =
    stage("submodules", "skipped", checkoutEnd, checkoutEnd, detail = "none")
  val tail: List<String> = listOf(
    "> Task :buildSrc:compileKotlin UP-TO-DATE",
    "> Task :help",
    "Welcome to Gradle 9.1.",
    "BUILD SUCCESSFUL in 2s",
  )
  val scripted: WorktreeSetupStage =
    stage("setup-script", "done", checkoutEnd, scriptEnd, tail = tail)
  val agent: WorktreeSetupStage = stage("agent", "done", scriptEnd, agentEnd)

  fun stages(vararg done: WorktreeSetupStage): List<WorktreeSetupStage> {
    val byId: Map<String, WorktreeSetupStage> = done.associateBy { it.id }
    return pending.keys.map { id -> byId[id] ?: pending.getValue(id) }
  }

  return listOf(
    snapshot(0, "running", stages(stage("fetch", "running", fetchStart))),
    snapshot(700, "running", stages(fetched, stage("checkout", "running", fetchEnd, percent = 0))),
    snapshot(
      500,
      "running",
      stages(fetched, stage("checkout", "running", fetchEnd, percent = 42)),
    ),
    snapshot(
      500,
      "running",
      stages(fetched, stage("checkout", "running", fetchEnd, percent = 88)),
    ),
    snapshot(
      400,
      "running",
      stages(fetched, checkedOut, submodules, stage("setup-script", "running", checkoutEnd)),
    ),
    snapshot(
      700,
      "running",
      stages(
        fetched,
        checkedOut,
        submodules,
        stage("setup-script", "running", checkoutEnd, tail = tail.take(2)),
      ),
    ),
    snapshot(
      700,
      "running",
      stages(
        fetched,
        checkedOut,
        submodules,
        stage("setup-script", "running", checkoutEnd, tail = tail),
      ),
    ),
    snapshot(
      600,
      "running",
      stages(fetched, checkedOut, submodules, scripted, stage("agent", "running", scriptEnd)),
    ),
    snapshot(400, "done", stages(fetched, checkedOut, submodules, scripted, agent), ended = true),
  )
}

/** The cancelled form of [snapshot]: whatever ran is failed, the rest stays pending. */
fun cancelledSetup(snapshot: WorktreeSetupSnapshot, now: Instant): WorktreeSetupSnapshot =
  snapshot.copy(
    phase = "cancelled",
    endedAt = now.toString(),
    stages = snapshot.stages.map { stage ->
      if (stage.status == "running") stage.copy(status = "failed", endedAt = now.toString())
      else stage
    },
    sequence = snapshot.sequence + 1,
  )

/** The projection's `thread` for a shell row, the fields the two share. */
fun appThread(row: Thread): AppThread = AppThread(
  createdBy = row.createdBy,
  creationSource = row.creationSource,
  id = row.id,
  projectId = row.projectId,
  title = row.title,
  providerInstanceId = row.providerInstanceId,
  modelSelection = row.modelSelection,
  runtimeMode = row.runtimeMode,
  interactionMode = row.interactionMode,
  branch = row.branch,
  worktreePath = row.worktreePath,
  activeProviderThreadId = row.activeProviderThreadId,
  lineage = row.lineage,
  forkedFrom = null,
  createdAt = row.createdAt,
  updatedAt = row.updatedAt,
  archivedAt = row.archivedAt,
  deletedAt = row.deletedAt,
)

/** Where the fixture server puts a new worktree: the project's managed folder, by branch. */
fun fixtureWorktreePath(workspaceRoot: String, branch: String): String =
  "$workspaceRoot/.t3/worktrees/${branch.substringAfterLast('/')}"
