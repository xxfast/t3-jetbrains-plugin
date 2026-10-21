package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupStage
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorktreeSetupLogicTest {
  private val now: Instant = Instant.parse("2026-10-21T10:00:10Z")

  @Test
  fun `the agent has the worktree once its stage is done`() {
    assertFalse(worktreeSetupAgentStarted(snapshot("running", agent = "running")))
    assertTrue(worktreeSetupAgentStarted(snapshot("running", agent = "done")))
  }

  @Test
  fun `a running setup always shows, a finished one until the turn starts`() {
    val running: WorktreeSetupSnapshot = snapshot("running")
    assertSame(running, resolveVisibleWorktreeSetup(running, null, true, followUpSent = true))
    val done: WorktreeSetupSnapshot = snapshot("done", agent = "done")
    assertSame(done, resolveVisibleWorktreeSetup(done, null, turnStarted = false, followUpSent = false))
    assertNull(resolveVisibleWorktreeSetup(done, null, turnStarted = true, followUpSent = false))
    assertNull(resolveVisibleWorktreeSetup(done, null, turnStarted = false, followUpSent = true))
    val failed: WorktreeSetupSnapshot = snapshot("failed")
    assertSame(failed, resolveVisibleWorktreeSetup(failed, null, turnStarted = true, followUpSent = false))
  }

  @Test
  fun `a finished setup with a failed script stays after the turn starts`() {
    val scriptFailed: WorktreeSetupSnapshot = snapshot("done", script = "failed", agent = "done")
    assertSame(scriptFailed, resolveVisibleWorktreeSetup(scriptFailed, null, true, false))
    assertEquals("Worktree ready, setup script failed", worktreeSetupHeaderLabel(scriptFailed))
    assertEquals("failed", worktreeSetupSummaryStatus(scriptFailed))
  }

  @Test
  fun `the newer of live and held wins, and another thread's is ignored`() {
    val older: WorktreeSetupSnapshot = snapshot("running", sequence = 1)
    val newer: WorktreeSetupSnapshot = snapshot("running", sequence = 2)
    assertSame(newer, resolveWorktreeSetupProgress("t", false, null, older, newer).snapshot)
    assertSame(newer, resolveWorktreeSetupProgress("t", false, null, newer, older).snapshot)
    assertNull(resolveWorktreeSetupProgress("other", false, null, newer, older).snapshot)
  }

  @Test
  fun `preparing while launching, while the run prepares, or until the agent starts`() {
    assertTrue(resolveWorktreeSetupProgress("t", true, null, null, null).preparing)
    assertTrue(resolveWorktreeSetupProgress("t", false, "preparing", null, null).preparing)
    assertTrue(resolveWorktreeSetupProgress("t", false, null, snapshot("running"), null).preparing)
    assertFalse(
      resolveWorktreeSetupProgress("t", false, null, snapshot("running", agent = "done"), null)
        .preparing,
    )
    assertFalse(resolveWorktreeSetupProgress("t", false, "running", null, null).preparing)
  }

  @Test
  fun `the held snapshot survives the server's null and a late older one`() {
    val first: WorktreeSetupSnapshot = snapshot("running", sequence = 3)
    assertSame(first, holdWorktreeSetup(null, first))
    assertSame(first, holdWorktreeSetup(first, null))
    assertSame(first, holdWorktreeSetup(first, snapshot("running", sequence = 2)))
    val later: WorktreeSetupSnapshot = snapshot("done", sequence = 4)
    assertSame(later, holdWorktreeSetup(first, later))
  }

  @Test
  fun `a send waits for the agent, not for the script`() {
    assertTrue(worktreeSetupBlocksSend(snapshot("running", script = "running"), preparing = false))
    assertFalse(worktreeSetupBlocksSend(snapshot("running", script = "running", agent = "done"), true))
    assertTrue(worktreeSetupBlocksSend(null, preparing = true))
    assertFalse(worktreeSetupBlocksSend(null, preparing = false))
  }

  @Test
  fun `stage rows show the percent while checking out and the script's own name`() {
    val checkout = stage("checkout", "running", percent = 42)
    assertEquals("42%", worktreeSetupStageTrailing(checkout))
    assertEquals("skipped", worktreeSetupStageTrailing(stage("submodules", "skipped")))
    assertEquals("none", worktreeSetupStageTrailing(stage("submodules", "skipped", detail = "none")))
    assertNull(worktreeSetupStageTrailing(stage("agent", "pending")))
    assertEquals("Install", worktreeSetupStageLabel(stage("setup-script", "running"), "Install"))
    assertEquals("Run setup script", worktreeSetupStageLabel(stage("setup-script", "running"), null))
    assertEquals("Check out files", worktreeSetupStageLabel("checkout"))
  }

  @Test
  fun `elapsed time runs to now while a stage has no end`() {
    val open = stage("fetch", "running", started = "2026-10-21T10:00:00Z")
    assertEquals(10_000, worktreeSetupStageElapsedMs(open, now))
    val closed = stage("fetch", "done", started = "2026-10-21T10:00:00Z", ended = "2026-10-21T10:00:02Z")
    assertEquals(2_000, worktreeSetupStageElapsedMs(closed, now))
    assertNull(worktreeSetupStageElapsedMs(stage("fetch", "pending"), now))
    assertEquals(10_000, worktreeSetupElapsedMs(snapshot("running"), now))
  }

  @Test
  fun `the tail shows while the script runs with output, or once it failed`() {
    assertFalse(worktreeSetupShowsTail(snapshot("running", script = "running")))
    assertTrue(worktreeSetupShowsTail(snapshot("running", script = "running", tail = listOf("x"))))
    assertTrue(worktreeSetupShowsTail(snapshot("done", script = "failed")))
    assertFalse(worktreeSetupShowsTail(snapshot("done", script = "done", tail = listOf("x"))))
  }

  private fun snapshot(
    phase: String,
    script: String = "pending",
    agent: String = "pending",
    tail: List<String> = emptyList(),
    sequence: Long = 0,
  ): WorktreeSetupSnapshot = WorktreeSetupSnapshot(
    threadId = "t",
    phase = phase,
    startedAt = "2026-10-21T10:00:00Z",
    endedAt = null,
    branch = "t3/abc",
    baseRef = "main",
    worktreePath = "/wt",
    setupScript = null,
    stages = listOf(
      stage("fetch", "done"),
      stage("checkout", "done"),
      stage("submodules", "skipped"),
      stage("setup-script", script, tail = tail),
      stage("agent", agent),
    ),
    error = null,
    sequence = sequence,
  )

  private fun stage(
    id: String,
    status: String,
    started: String? = null,
    ended: String? = null,
    percent: Long? = null,
    detail: String? = null,
    tail: List<String> = emptyList(),
  ): WorktreeSetupStage = WorktreeSetupStage(
    id = id,
    status = status,
    startedAt = started,
    endedAt = ended,
    percent = percent,
    detail = detail,
    tail = tail,
  )
}
