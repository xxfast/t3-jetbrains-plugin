package io.github.xxfast.t3code.plugin.ide

import io.github.xxfast.t3code.plugin.actions.openWorktreeReason
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectMatchTest {
  @Test
  fun `a main checkout's common dir is its git directory`(@TempDir dir: Path) {
    val main: Path = dir.resolve("main").also { it.resolve(".git").createDirectories() }
    assertEquals(main.resolve(".git").toAbsolutePath().normalize(), gitCommonDir(main))
  }

  @Test
  fun `a worktree's common dir comes from its gitdir and commondir`(@TempDir dir: Path) {
    val main: Path = dir.resolve("main")
    val worktree: Path = worktree(main, dir.resolve("wt"), "wt")
    assertEquals(gitCommonDir(main), gitCommonDir(worktree))
  }

  @Test
  fun `gitdir may be absolute, end in CRLF and lack a commondir`(@TempDir dir: Path) {
    val gitDir: Path = dir.resolve("elsewhere/.git").createDirectories()
    val checkout: Path = dir.resolve("checkout").createDirectories()
    checkout.resolve(".git").writeText("gitdir: $gitDir\r\n")
    assertEquals(gitDir.toAbsolutePath().normalize(), gitCommonDir(checkout))
  }

  @Test
  fun `no git, or a git file without a gitdir, is no common dir`(@TempDir dir: Path) {
    assertNull(gitCommonDir(dir))
    dir.resolve(".git").writeText("not a pointer\n")
    assertNull(gitCommonDir(dir))
  }

  @Test
  fun `containment is by whole segments, case folded only when asked`() {
    assertTrue(isUnder(path("/repo"), path("/repo/app"), ignoreCase = false))
    assertFalse(isUnder(path("/repo"), path("/repo2"), ignoreCase = false))
    assertFalse(isUnder(path("/Repo"), path("/repo/app"), ignoreCase = false))
    assertTrue(isUnder(path("/Repo"), path("/repo/app"), ignoreCase = true))
  }

  @Test
  fun `a persisted choice wins, an empty one is all`() {
    val roots: List<Path> = listOf(path("/code/kstore"))
    val chosen: ProjectFilter = matchProjects(listOf("router"), roots, PROJECTS, NO_GIT, false)
    assertEquals(ProjectFilter.Only(setOf("router")), chosen)
    assertEquals(ProjectFilter.All, matchProjects(emptyList(), roots, PROJECTS, NO_GIT, false))
    val loading: ProjectFilter = matchProjects(listOf("x"), roots, emptyList(), NO_GIT, false)
    assertEquals(ProjectFilter.Only(setOf("x")), loading, "Kept until projects arrive")
  }

  @Test
  fun `a persisted choice of projects that are all gone falls through`() {
    val roots: List<Path> = listOf(path("/code/kstore"))
    val gone: ProjectFilter = matchProjects(listOf("deleted"), roots, PROJECTS, NO_GIT, false)
    assertEquals(ProjectFilter.Only(setOf("kstore")), gone)
  }

  @Test
  fun `content roots match T3 roots either way round, a monorepo gets all of them`() {
    fun match(vararg roots: String): ProjectFilter =
      matchProjects(null, roots.map(::path), PROJECTS, NO_GIT, false)
    assertEquals(ProjectFilter.Only(setOf("kstore")), match("/code/kstore/library"))
    assertEquals(ProjectFilter.Only(setOf("kstore", "router")), match("/code"))
    val both: ProjectFilter = match("/x", "/code/kstore", "/code/router")
    assertEquals(ProjectFilter.Only(setOf("kstore", "router")), both)
    assertEquals(ProjectFilter.All, match("/code/kstore2"))
  }

  @Test
  fun `a worktree on one side and the main checkout on the other match by git`(@TempDir dir: Path) {
    val main: Path = dir.resolve("main")
    val worktree: Path = worktree(main, dir.resolve("wt"), "wt")
    val projects: List<Project> = listOf(project("kstore", main.toString()))
    val fromWorktree: ProjectFilter = matchProjects(null, listOf(worktree), projects)
    assertEquals(ProjectFilter.Only(setOf("kstore")), fromWorktree)
    val other: List<Project> = listOf(project("kstore", worktree.toString()))
    assertEquals(ProjectFilter.Only(setOf("kstore")), matchProjects(null, listOf(main), other))
    assertEquals(ProjectFilter.All, matchProjects(null, listOf(dir.resolve("none")), projects))
  }

  @Test
  fun `the workspace thread is the one whose worktree is a content root`() {
    val threads: List<Thread> = listOf(
      thread("plain", worktree = null),
      thread("wt", worktree = "/t3/worktrees/kstore/fix"),
      thread("archived", worktree = "/code/kstore", archivedAt = "2026-10-19T00:00:00Z"),
    )
    val roots: List<Path> = listOf(path("/T3/worktrees/kstore/fix"))
    assertEquals("wt", threadForWorkspace(roots, threads, ignoreCase = true)?.id)
    assertNull(threadForWorkspace(roots, threads, ignoreCase = false))
    assertNull(threadForWorkspace(listOf(path("/code/kstore")), threads), "Archived stays put")
  }

  @Test
  fun `open worktree needs a worktree nobody has open`() {
    val row: Thread = thread("wt", worktree = "/t3/worktrees/kstore/fix")
    assertNull(openWorktreeReason(row, true) { listOf(path("/code/kstore")) })
    val open: String? = openWorktreeReason(row, true) { listOf(path("/t3/worktrees/kstore/fix")) }
    assertEquals("Worktree is already open", open)
    assertEquals("Server is on another machine", openWorktreeReason(row, false) { emptyList() })
    assertEquals("No thread selected", openWorktreeReason(null, true) { emptyList() })
  }

  @Test
  fun `open worktree only reads the open roots when there's a worktree to look for`() {
    var reads = 0
    val roots: () -> List<Path> = { reads++; emptyList() }
    assertEquals("Thread has no worktree", openWorktreeReason(thread("plain", null), true, roots))
    openWorktreeReason(null, true, roots)
    openWorktreeReason(thread("wt", "/t3/wt"), false, roots)
    assertEquals(0, reads)
    openWorktreeReason(thread("wt", "/t3/wt"), true, roots)
    assertEquals(1, reads)
  }

  /** A main checkout at [main] with a linked worktree at [at], as `git worktree add` lays out. */
  private fun worktree(main: Path, at: Path, name: String): Path {
    val admin: Path = main.resolve(".git/worktrees/$name").createDirectories()
    admin.resolve("commondir").writeText("../..\n")
    at.createDirectories().resolve(".git").writeText("gitdir: ${admin}\n")
    return at
  }
}

private val NO_GIT: (Path) -> Path? = { null }

private fun path(path: String): Path = Path.of(path).toAbsolutePath().normalize()

private val PROJECTS: List<Project> = listOf(
  project("kstore", "/code/kstore"),
  project("router", "/code/router"),
)

private fun project(id: String, root: String): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "$id", "title": "$id", "workspaceRoot": ${JsonPrimitive(root)},
      "scripts": [], "createdAt": "2026-10-19T00:00:00Z", "updatedAt": "2026-10-19T00:00:00Z"
    }
  """,
)

private fun thread(id: String, worktree: String?, archivedAt: String? = null): Thread =
  T3Json.decodeFromString(
    Thread.serializer(),
    """
      {
        "id": "$id", "projectId": "kstore", "title": "$id", "status": "idle",
        "createdBy": "user", "creationSource": "web", "providerInstanceId": "codex",
        "modelSelection": { "model": "gpt-5" }, "runtimeMode": "full-access",
        "interactionMode": "default", "lineage": { "rootThreadId": "$id" },
        "hasActionableProposedPlan": false, "itemCount": 0, "visibleItemCount": 0,
        "createdAt": "2026-10-19T00:00:00Z", "updatedAt": "2026-10-19T00:00:00Z"
      }
    """,
  ).copy(worktreePath = worktree, archivedAt = archivedAt)
