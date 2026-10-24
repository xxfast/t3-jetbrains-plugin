package io.github.xxfast.t3code.plugin.ide

import io.github.xxfast.t3code.plugin.actions.belongsIn
import io.github.xxfast.t3code.plugin.actions.gitCwd
import io.github.xxfast.t3code.plugin.actions.openInIdeReason
import io.github.xxfast.t3code.plugin.actions.windowFor
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
    val open: Set<String> = setOf("kstore")
    val chosen: ProjectFilter = matchProjects(listOf("router"), PROJECTS, open)
    assertEquals(ProjectFilter.Only(setOf("router")), chosen)
    assertEquals(ProjectFilter.All, matchProjects(emptyList(), PROJECTS, open))
    val loading: ProjectFilter = matchProjects(listOf("x"), emptyList(), open)
    assertEquals(ProjectFilter.Only(setOf("x")), loading, "Kept until projects arrive")
  }

  @Test
  fun `without a choice the open projects are the filter, else all`() {
    val gone: ProjectFilter = matchProjects(listOf("deleted"), PROJECTS, setOf("kstore"))
    assertEquals(ProjectFilter.Only(setOf("kstore")), gone, "A choice all gone falls through")
    val open: ProjectFilter = matchProjects(null, PROJECTS, setOf("kstore"))
    assertEquals(ProjectFilter.Only(setOf("kstore")), open)
    assertEquals(ProjectFilter.All, matchProjects(null, PROJECTS, emptySet()))
  }

  @Test
  fun `content roots match T3 roots either way round, a monorepo gets all of them`() {
    fun open(vararg roots: String): Set<String> =
      openProjects(roots.map(::path), PROJECTS, NO_GIT, false)
    assertEquals(setOf("kstore"), open("/code/kstore/library"))
    assertEquals(setOf("kstore", "router"), open("/code"))
    assertEquals(setOf("kstore", "router"), open("/x", "/code/kstore", "/code/router"))
    assertEquals(emptySet(), open("/code/kstore2"))
  }

  @Test
  fun `a worktree on one side and the main checkout on the other match by git`(@TempDir dir: Path) {
    val main: Path = dir.resolve("main")
    val worktree: Path = worktree(main, dir.resolve("wt"), "wt")
    val projects: List<Project> = listOf(project("kstore", main.toString()))
    assertEquals(setOf("kstore"), openProjects(listOf(worktree), projects))
    val other: List<Project> = listOf(project("kstore", worktree.toString()))
    assertEquals(setOf("kstore"), openProjects(listOf(main), other))
    assertEquals(emptySet(), openProjects(listOf(dir.resolve("none")), projects))
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
  fun `open in IDE goes to the worktree, else the project's root`() {
    val worktree: Thread = thread("wt", worktree = "/t3/worktrees/kstore/fix")
    assertEquals(path("/t3/worktrees/kstore/fix"), gitCwd(worktree, PROJECTS))
    assertEquals(path("/code/kstore"), gitCwd(thread("plain", worktree = null), PROJECTS))
    assertNull(gitCwd(thread("plain", worktree = null), emptyList()))
  }

  @Test
  fun `a thread belongs in a window that has its checkout or a folder above it`() {
    val cwd: Path = path("/code/kstore")
    assertTrue(belongsIn(cwd, listOf(path("/code/kstore"))))
    assertTrue(belongsIn(cwd, listOf(path("/code")), ignoreCase = false), "Monorepo window")
    assertTrue(belongsIn(cwd, listOf(path("/Code/KStore")), ignoreCase = true))
    assertFalse(belongsIn(cwd, listOf(path("/Code/KStore")), ignoreCase = false))
    assertFalse(belongsIn(cwd, listOf(path("/code/kstore/core"))), "Only part of it is open")
    assertFalse(belongsIn(cwd, listOf(path("/code/router"))))
  }

  @Test
  fun `open in IDE focuses the window the checkout belongs in, else opens one`() {
    val windows: Map<String, List<Path>> = mapOf(
      "router" to listOf(path("/code/router")),
      "monorepo" to listOf(path("/code")),
    )
    val roots: (String) -> List<Path> = { windows.getValue(it) }
    assertEquals("monorepo", windowFor(path("/code/kstore"), windows.keys.toList(), roots))
    assertEquals("router", windowFor(path("/code/router"), windows.keys.toList(), roots))
    assertNull(windowFor(path("/t3/worktrees/kstore/fix"), windows.keys.toList(), roots))
  }

  @Test
  fun `open in IDE needs a local path that's on this disk`() {
    val row: Thread = thread("wt", worktree = "/t3/worktrees/kstore/fix")
    val cwd: Path = path("/t3/worktrees/kstore/fix")
    assertNull(openInIdeReason(row, cwd, true) { true })
    assertEquals("$cwd isn't on this disk", openInIdeReason(row, cwd, true) { false })
    assertEquals("Server is on another machine", openInIdeReason(row, cwd, false) { true })
    assertEquals("Thread has no workspace path", openInIdeReason(row, null, true) { true })
    assertEquals("No thread selected", openInIdeReason(null, cwd, true) { true })
  }

  @Test
  fun `open in IDE only touches the disk when the server is local`() {
    var reads = 0
    val exists: (Path) -> Boolean = { reads++; true }
    openInIdeReason(thread("wt", "/t3/wt"), path("/t3/wt"), false, exists)
    openInIdeReason(null, path("/t3/wt"), true, exists)
    assertEquals(0, reads)
    openInIdeReason(thread("wt", "/t3/wt"), path("/t3/wt"), true, exists)
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
