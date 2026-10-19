package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult.WorkingTree
import io.github.xxfast.t3code.protocol.generated.VcsStatusRemoteResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class VcsSummaryTest {
  @Test
  fun `events merge the way upstream's applyGitStatusStreamEvent does`() {
    val events: List<VcsStatusStreamEvent> = listOf(
      VcsStatusStreamEvent.Snapshot(local = local("main", files = 2), remote = remote(2, 1)),
      VcsStatusStreamEvent.LocalUpdated(local = local("main", files = 3)),
      VcsStatusStreamEvent.Unknown(tag = "future", raw = JsonObject(emptyMap())),
    )
    val status: VcsStatus? = events.fold(null as VcsStatus?, ::applyGitStatusStreamEvent)
    assertEquals(3, status?.local?.workingTree?.files?.size, "Local replaced")
    assertEquals(2L, status?.remote?.aheadCount, "Remote kept")
    val synced: VcsStatus? =
      applyGitStatusStreamEvent(status, VcsStatusStreamEvent.RemoteUpdated(remote = remote(0, 0)))
    assertEquals(3, synced?.local?.workingTree?.files?.size, "Local kept")
    assertEquals(0L, synced?.remote?.aheadCount)
  }

  @Test
  fun `a remote update first gets a placeholder local`() {
    val event = VcsStatusStreamEvent.RemoteUpdated(remote = remote(1, 0))
    val status: VcsStatus? = applyGitStatusStreamEvent(null, event)
    assertEquals(null, status?.local?.refName)
    assertEquals("detached HEAD · ↑1", status?.let(::vcsSummary)?.text())
  }

  @Test
  fun `the line leaves out every zero part`() {
    val full = VcsStatus(local("main", files = 2, insertions = 4, deletions = 1), remote(2, 1))
    assertEquals(
      "main · 2 changed files +4 −1 · ↑2 ↓1",
      vcsSummary(full)?.text(),
    )
    val clean = VcsStatus(local("main", files = 0), remote(0, 0))
    assertEquals("main", vcsSummary(clean)?.text())
    val one = VcsStatus(local("fix", files = 1, insertions = 0, deletions = 3), null)
    assertEquals("fix · 1 changed file −3", vcsSummary(one)?.text())
  }

  @Test
  fun `no upstream, no ahead or behind, and no repo, no line`() {
    val local: VcsStatusLocalResult = local("main", files = 0)
    val untracked = VcsStatus(local, remote(5, 5).copy(hasUpstream = false))
    assertEquals("main", vcsSummary(untracked)?.text())
    assertNull(vcsSummary(VcsStatus(local.copy(isRepo = false), null)))
  }
}

private fun local(
  ref: String?,
  files: Int,
  insertions: Long = files.toLong(),
  deletions: Long = 0,
): VcsStatusLocalResult = VcsStatusLocalResult(
  isRepo = true,
  hasPrimaryRemote = true,
  isDefaultRef = ref == "main",
  refName = ref,
  hasWorkingTreeChanges = files > 0,
  workingTree = WorkingTree(
    files = List(files) { WorkingTree.File("f$it.kt", 1, 0) },
    insertions = insertions,
    deletions = deletions,
  ),
)

private fun remote(ahead: Long, behind: Long): VcsStatusRemoteResult =
  VcsStatusRemoteResult(hasUpstream = true, aheadCount = ahead, behindCount = behind, pr = null)
