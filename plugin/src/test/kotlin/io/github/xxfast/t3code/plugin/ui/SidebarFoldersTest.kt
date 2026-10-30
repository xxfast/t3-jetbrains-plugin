package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class SidebarFoldersTest {
  private val kstore: Project = project("kstore", "KStore")
  private val router: Project = project("router", "Decompose-Router")
  private val empty: Project = project("empty", "Empty")

  @Test
  fun `folders follow group order and threads keep their order`() {
    val threads: List<Thread> = listOf(
      thread("b", project = "router"),
      thread("a", project = "kstore"),
      thread("c", project = "router"),
    )
    val folders: List<ProjectFolder> =
      groupSidebarThreadsByProject(listOf(kstore, router), threads, keepEmpty = false)
    assertEquals(listOf("kstore", "router"), folders.map { it.project.id })
    assertEquals(listOf("b", "c"), folders[1].threads.map { it.id })
  }

  @Test
  fun `a project with no threads gets a folder only when empty ones are kept`() {
    val threads: List<Thread> = listOf(thread("a", project = "kstore"))
    val groups: List<Project> = listOf(kstore, empty)
    assertEquals(
      listOf("kstore"),
      groupSidebarThreadsByProject(groups, threads, keepEmpty = false).map { it.project.id },
    )
    assertEquals(
      listOf("kstore", "empty"),
      groupSidebarThreadsByProject(groups, threads, keepEmpty = true).map { it.project.id },
    )
  }

  @Test
  fun `a thread whose project is in no group is left out`() {
    val threads: List<Thread> =
      listOf(thread("a", project = "kstore"), thread("x", project = "gone"))
    val folders: List<ProjectFolder> =
      groupSidebarThreadsByProject(listOf(kstore), threads, keepEmpty = false)
    assertEquals(listOf("a"), folders.flatMap { it.threads }.map { it.id })
  }

  @Test
  fun `boring sections keep pins above the folders and sort folder rows by send`() {
    val threads: List<Thread> = listOf(
      thread("old", project = "kstore", sentAt = "2026-10-19T10:00:00Z"),
      thread("new", project = "kstore", sentAt = "2026-10-19T11:00:00Z"),
      thread("pinned", project = "router", pinnedAt = EPOCH),
      thread("routed", project = "router"),
      thread("done", project = "router", settledOverride = "settled"),
    )
    val sections: ThreadSections =
      sections(threads, "", folderGroups = listOf(kstore, router), keepEmptyFolders = false)
    val folders: List<ProjectFolder> = sections.folders!!
    assertEquals(listOf("kstore", "router"), folders.map { it.project.id })
    assertEquals(listOf("new", "old"), folders[0].threads.map { it.id })
    assertEquals(listOf("routed"), folders[1].threads.map { it.id })
    assertEquals(listOf("pinned", "new", "old", "routed"), sections.inbox.map { it.id })
    assertEquals(listOf("done"), sections.settled.map { it.id })
  }

  @Test
  fun `without folder groups there are no folders`() {
    assertNull(sections(listOf(thread("a")), "").folders)
  }

  @Test
  fun `a folder is open until it's closed, the first stored key decides`() {
    assertTrue(resolveProjectExpanded(emptyMap(), listOf("kstore")))
    assertFalse(resolveProjectExpanded(mapOf("kstore" to false), listOf("kstore")))
    assertTrue(
      resolveProjectExpanded(mapOf("a" to true, "b" to false), listOf("missing", "a", "b")),
    )
  }

  @Test
  fun `a closed folder still shows the open thread and hides the rest`() {
    val folder = ProjectFolder(kstore, listOf(thread("a"), thread("b"), thread("c")))
    val closed: FolderView =
      projectFolders(listOf(folder), mapOf("kstore" to false), routeThreadId = "b").single()
    assertTrue(closed.collapsed)
    assertEquals(listOf("b"), closed.threads.map { it.id })
    assertEquals(listOf("a", "c"), closed.hiddenThreads.map { it.id })

    val open: FolderView = projectFolders(listOf(folder), emptyMap(), routeThreadId = "b").single()
    assertFalse(open.collapsed)
    assertEquals(listOf("a", "b", "c"), open.threads.map { it.id })
    assertEquals(emptyList(), open.hiddenThreads)
  }

  @Test
  fun `the folder dot is the most urgent hidden state`() {
    val approval: Thread = thread("approval", pending = "command")
    val input: Thread = thread("input", pending = "user_input")
    val failed: Thread = thread("failed", status = "failed", latestRunId = "r")
    val limited: Thread = failed.copy(lastErrorClass = "usage_limit")
    val unread: Thread = thread(
      "unread", latestRunId = "r", status = "completed", completedAt = TEN, visitedAt = EPOCH,
    )
    val working: Thread = thread("working", status = "running", latestRunId = "r")
    val waiting: Thread = thread("waiting", status = "idle", latestRunId = "r")

    assertEquals(FolderDot.Attention, resolveSidebarFolderDot(listOf(working, approval, failed)))
    assertEquals(FolderDot.Attention, resolveSidebarFolderDot(listOf(input)))
    assertEquals(FolderDot.Attention, resolveSidebarFolderDot(listOf(limited)))
    assertEquals(FolderDot.Failed, resolveSidebarFolderDot(listOf(unread, failed, working)))
    assertEquals(FolderDot.Unread, resolveSidebarFolderDot(listOf(working, unread)))
    assertEquals(FolderDot.Working, resolveSidebarFolderDot(listOf(working, waiting)))
    assertNull(resolveSidebarFolderDot(listOf(waiting)), "Waiting threads stay quiet")
    assertNull(resolveSidebarFolderDot(emptyList()))
  }

  @Test
  fun `a subagent's request on a hidden parent needs you, a wait on a thread stays quiet`() {
    val parent: Thread = thread("parent", status = "running", latestRunId = "r")
    val requests: Map<String, PendingSubagentRequest> =
      mapOf("parent" to PendingSubagentRequest.Approval)
    assertEquals(FolderDot.Working, resolveSidebarFolderDot(listOf(parent)))
    assertEquals(FolderDot.Attention, resolveSidebarFolderDot(listOf(parent), requests))

    val blocked: Thread = parent.copy(blockedOnThreadWait = true)
    assertEquals(ThreadStatus.Waiting, blocked.sidebarStatus())
    assertNull(resolveSidebarFolderDot(listOf(blocked)))
  }

  @Test
  fun `a scope keeps its empty folders, but not while searching`() {
    val projects: List<Project> = listOf(kstore, router)
    val scoped: ProjectFilter = ProjectFilter.Only(setOf("kstore"))
    assertTrue(keepEmptyFolders(projects, scoped, query = ""))
    assertFalse(keepEmptyFolders(projects, scoped, query = "nothing"))
    assertFalse(keepEmptyFolders(projects, ProjectFilter.All, query = ""))
    assertEquals(listOf(kstore), sidebarFolderGroups(projects, scoped, enabled = true))
    assertEquals(projects, sidebarFolderGroups(projects, ProjectFilter.All, enabled = true))
    assertNull(sidebarFolderGroups(projects, scoped, enabled = false))
  }

  @Test
  fun `forward navigation walks pins then the folders' visible rows`() {
    val threads: List<Thread> = listOf(
      thread("a", project = "kstore", sentAt = "2026-10-19T11:00:00Z"),
      thread("b", project = "kstore", sentAt = "2026-10-19T10:00:00Z"),
      thread("c", project = "router"),
      thread("d", project = "router"),
      thread("pinned", project = "router", pinnedAt = EPOCH),
    )
    val sections: ThreadSections =
      sections(threads, "", folderGroups = listOf(kstore, router), keepEmptyFolders = false)
    val closed: Map<String, Boolean> = mapOf("router" to false)
    assertEquals(
      listOf("pinned", "a", "b", "c"),
      sections.cards(closed, routeThreadId = "c").map { it.id },
    )
    assertEquals("b", nextCard(sections.cards(closed, routeThreadId = "a"), "a"))
    val flat: ThreadSections = sections(threads, "")
    assertEquals(flat.inbox, flat.cards(closed, routeThreadId = null))
  }

  @Test
  fun `only a ready thread's unseen completion dots the folder`() {
    val busy: Thread = thread(
      "busy", status = "running", latestRunId = "r", completedAt = TEN, visitedAt = EPOCH,
    )
    assertEquals(FolderDot.Working, resolveSidebarFolderDot(listOf(busy)))
  }

  @Test
  fun `a new thread starts in the open thread's project, then the draft's, then the first`() {
    val draft = DraftThread(
      threadId = "d",
      projectId = "router",
      createdAt = EPOCH,
      modelSelection = ModelSelection(
        instanceId = JsonPrimitive("codex"),
        model = JsonPrimitive("gpt-5"),
      ),
    )
    assertEquals("kstore", resolveThreadActionProjectRef(thread("a"), draft, "first"))
    assertEquals("router", resolveThreadActionProjectRef(null, draft, "first"))
    assertEquals("first", resolveThreadActionProjectRef(null, null, "first"))
    assertNull(resolveThreadActionProjectRef(null, null, null))
  }

  @Test
  fun `the boring scenario folds KStore and Decompose-Router, a closed KStore needs you`() {
    val folder: Path = Path.of(System.getProperty("t3code.scenarios")).resolve("boring")
    val files: List<Path> = Files.list(folder).use { stream -> stream.toList() }
      .filter { it.extension == "json" }
      .sortedBy { it.name }
    val scenario: Scenario = Scenario.merge(files.map { Scenario.decode(it.readText()) })
    val sections: ThreadSections = sections(
      scenario.threads, "", folderGroups = scenario.projects, keepEmptyFolders = false,
    )
    val folders: List<ProjectFolder> = sections.folders!!
    assertEquals(listOf("KStore", "Decompose-Router"), folders.map { it.project.title })
    val closed: List<FolderView> =
      projectFolders(folders, mapOf(folders[0].project.id to false), routeThreadId = null)
    assertEquals(FolderDot.Attention, resolveSidebarFolderDot(closed[0].hiddenThreads))
    assertFalse(closed[1].collapsed)
  }

  private fun thread(
    id: String,
    project: String = "kstore",
    status: String = "idle",
    latestRunId: String? = null,
    pinnedAt: String? = null,
    settledOverride: String? = null,
    sentAt: String? = null,
    pending: String? = null,
    completedAt: String? = null,
    visitedAt: String? = null,
  ): Thread = BASE_THREAD.copy(
    id = id,
    projectId = project,
    status = status,
    latestRunId = latestRunId,
    pinnedAt = pinnedAt,
    settledOverride = settledOverride,
    latestUserAuthoredMessageAt = sentAt,
    pendingRuntimeRequest = pending?.let { PendingRuntimeRequestSummary("r", it, EPOCH) },
    latestRunCompletedAt = completedAt,
    lastVisitedAt = visitedAt,
  )
}

private fun project(id: String, title: String): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "$id", "title": "$title", "workspaceRoot": "/code/$id", "scripts": [],
      "createdAt": "$EPOCH", "updatedAt": "$EPOCH"
    }
  """,
)

private const val EPOCH: String = "1970-01-01T00:00:00Z"
private const val TEN: String = "2026-10-19T10:00:00Z"
