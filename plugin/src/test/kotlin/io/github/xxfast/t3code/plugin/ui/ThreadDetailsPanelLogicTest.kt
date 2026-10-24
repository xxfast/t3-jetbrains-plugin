package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ProjectScript
import io.github.xxfast.t3code.protocol.generated.SourceControlProviderInfo
import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult.WorkingTree
import io.github.xxfast.t3code.protocol.generated.VcsStatusRemoteResult
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadDetailsPanelLogicTest {
  @Test
  fun `quick action without status is a disabled Commit`() {
    val action: GitQuickAction = resolveQuickAction(null, isBusy = false)
    assertEquals(GitQuickAction.ShowHint("Commit", "Git status is unavailable."), action)
  }

  @Test
  fun `changes on a feature branch commit, push and open a PR`() {
    val status = VcsStatus(local(changes = true), remote(hasUpstream = true))
    assertEquals(
      GitQuickAction.RunAction("Commit, push & PR", "commit_push_pr"),
      resolveQuickAction(status, isBusy = false),
    )
  }

  @Test
  fun `changes on the default branch commit and push`() {
    val status = VcsStatus(local(changes = true), remote(hasUpstream = true))
    assertEquals(
      GitQuickAction.RunAction("Commit & push", "commit_push"),
      resolveQuickAction(status, isBusy = false, isDefaultRef = true),
    )
  }

  @Test
  fun `GitLab says merge request`() {
    val status = VcsStatus(
      local(changes = true).copy(
        sourceControlProvider = SourceControlProviderInfo("gitlab", "GitLab", "https://gitlab.com"),
      ),
      remote(hasUpstream = true),
    )
    assertEquals("Commit, push & MR", resolveQuickAction(status, isBusy = false).label)
  }

  @Test
  fun `a clean branch behind upstream pulls, diverged waits`() {
    val behind = VcsStatus(local(), remote(hasUpstream = true, behind = 2))
    assertEquals(GitQuickAction.RunPull, resolveQuickAction(behind, isBusy = false))
    val diverged = VcsStatus(local(), remote(hasUpstream = true, ahead = 1, behind = 2))
    assertEquals("Sync ref", resolveQuickAction(diverged, isBusy = false).label)
    assertTrue(resolveQuickAction(diverged, isBusy = false).disabled)
  }

  @Test
  fun `a clean branch with no remote at all publishes`() {
    val status = VcsStatus(local(), remote(hasUpstream = false))
    assertEquals(
      GitQuickAction.OpenPublish,
      resolveQuickAction(status, isBusy = false, hasPrimaryRemote = false),
    )
  }

  @Test
  fun `no remote half reads as no upstream`() {
    val status = VcsStatus(local(), remote = null)
    // Nothing ahead, nothing to push: upstream's "No local commits to push."
    assertEquals(
      GitQuickAction.ShowHint("Push", "No local commits to push."),
      resolveQuickAction(status, isBusy = false),
    )
  }

  @Test
  fun `menu lists commit, push and create PR with their own gates`() {
    val status = VcsStatus(local(changes = true), remote(hasUpstream = true, ahead = 1))
    val items: List<GitActionMenuItem> = buildMenuItems(status, isBusy = false)
    assertEquals(listOf("Commit", "Push", "Create PR"), items.map { it.label })
    // Push doesn't wait for a clean tree upstream either, only for commits ahead
    assertEquals(listOf(false, false, false), items.map { it.disabled })
    assertNull(gitActionDisabledReason(items[0], status, isBusy = false, hasPrimaryRemote = true))
    val behind = VcsStatus(local(changes = true), remote(hasUpstream = true, behind = 1))
    val gated: List<GitActionMenuItem> = buildMenuItems(behind, isBusy = false)
    assertEquals(listOf(false, true, true), gated.map { it.disabled })
    assertEquals(
      "Commit or stash local changes before pushing.",
      gitActionDisabledReason(gated[1], behind, isBusy = false, hasPrimaryRemote = true),
    )
  }

  @Test
  fun `menu without a primary remote is commit alone, with an open PR no create`() {
    val clean = VcsStatus(local(), remote(hasUpstream = false))
    val alone: List<GitActionMenuItem> = buildMenuItems(clean, false, hasPrimaryRemote = false)
    assertEquals(listOf("Commit"), alone.map { it.label })
    val withPr = VcsStatus(local(), remote(hasUpstream = true, pr = "open"))
    assertEquals(listOf("Commit", "Push"), buildMenuItems(withPr, false).map { it.label })
  }

  @Test
  fun `changes totals prefer the branch's changes over the working tree's`() {
    val tree = local(changes = true, insertions = 4, deletions = 1)
    assertEquals(ChangesTotals(4, 1), changesTotals(VcsStatus(tree, null)))
    val branch = tree.copy(branchChanges = VcsStatusLocalResult.BranchChanges("main", 120, 7))
    assertEquals(ChangesTotals(120, 7), changesTotals(VcsStatus(branch, null)))
    assertEquals(ChangesTotals(0, 0), changesTotals(null))
  }

  @Test
  fun `diff counts group thousands`() {
    assertEquals("0", formatDiffCount(0))
    assertEquals("1,234", formatDiffCount(1_234).replace(' ', ',').replace('.', ','))
  }

  @Test
  fun `primary script skips worktree and settle hooks, preferred wins when listed`() {
    val setup = script("setup", runOnWorktreeCreate = true)
    val settle = script("settle", runOnSettle = true)
    val test = script("test")
    assertEquals(test, primaryProjectScript(listOf(setup, settle, test)))
    assertEquals(setup, primaryProjectScript(listOf(setup, settle)))
    assertNull(primaryProjectScript(listOf(settle)))
    assertEquals(setup, resolvePrimaryScript(listOf(setup, test), "setup"))
    assertEquals(test, resolvePrimaryScript(listOf(setup, test), "gone"))
  }

  @Test
  fun `script input keeps the command out of history and runs it`() {
    assertEquals(" ./gradlew build\r", projectScriptTerminalInput("./gradlew build"))
  }

  @Test
  fun `glyphs follow upstream's icon tables`() {
    assertEquals("flask-conical", scriptGlyph("test"))
    assertEquals("play", scriptGlyph("run"))
    assertEquals("laptop", machineGlyph("laptop"))
    assertEquals("server", machineGlyph("linux"))
    val commit = GitQuickAction.RunAction("Commit", "commit")
    val pr = GitQuickAction.RunAction("Create PR", "create_pr")
    assertEquals("git-commit-horizontal", quickActionGlyph(commit))
    assertEquals("git-pull-request", quickActionGlyph(pr))
    assertEquals("info", quickActionGlyph(GitQuickAction.ShowHint("Sync ref", "x")))
  }

  @Test
  fun `next terminal id takes the first free number and keeps the suffix`() {
    assertEquals("term-1", nextTerminalId(emptyList()))
    assertEquals("term-2", nextTerminalId(listOf("term-1", "term-3")))
    val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
    assertEquals("term-3-$uuid", nextTerminalId(listOf("term-1", "term-2-$uuid", "custom"), uuid))
  }

  private fun local(
    changes: Boolean = false,
    insertions: Long = if (changes) 1 else 0,
    deletions: Long = 0,
    ref: String? = "feature",
  ): VcsStatusLocalResult = VcsStatusLocalResult(
    isRepo = true,
    hasPrimaryRemote = true,
    isDefaultRef = false,
    refName = ref,
    hasWorkingTreeChanges = changes,
    workingTree = WorkingTree(files = emptyList(), insertions = insertions, deletions = deletions),
  )

  private fun remote(
    hasUpstream: Boolean,
    ahead: Long = 0,
    behind: Long = 0,
    pr: String? = null,
  ): VcsStatusRemoteResult = VcsStatusRemoteResult(
    hasUpstream = hasUpstream,
    aheadCount = ahead,
    behindCount = behind,
    pr = pr?.let { state ->
      VcsStatusRemoteResult.Pr(1, "PR", "https://example.test/pr/1", "main", "feature", state)
    },
  )

  private fun script(
    id: String,
    runOnWorktreeCreate: Boolean = false,
    runOnSettle: Boolean? = null,
  ): ProjectScript = ProjectScript(
    id = id,
    name = id,
    command = id,
    icon = "run",
    runOnWorktreeCreate = runOnWorktreeCreate,
    runOnSettle = runOnSettle,
  )
}
