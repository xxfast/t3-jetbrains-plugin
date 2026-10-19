package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ThreadListLogicTest {
  private val kstore: Project = project("kstore")
  private val router: Project = project("router")

  @Test
  fun `groups follow project order and skip projects without threads`() {
    val empty: Project = project("empty")
    val threads: List<Thread> = listOf(thread("a", "router"), thread("b", "kstore"))
    val groups: List<ThreadGroup> = groupThreads(listOf(kstore, empty, router), threads, null)
    assertEquals(listOf("kstore", "router"), groups.map { it.project.id })
  }

  @Test
  fun `pinned first, then newest, archived last`() {
    val threads: List<Thread> = listOf(
      thread("old", updatedAt = "2026-10-01T00:00:00Z"),
      thread("archived", updatedAt = "2026-10-19T00:00:00Z", archivedAt = "2026-10-19T00:00:00Z"),
      thread("new", updatedAt = "2026-10-18T00:00:00Z"),
      thread("pinned", updatedAt = "2026-09-01T00:00:00Z", pinnedAt = "2026-09-01T00:00:00Z"),
    )
    val group: ThreadGroup = groupThreads(listOf(kstore), threads, null).single()
    assertEquals(listOf("pinned", "new", "old", "archived"), group.threads.map { it.id })
  }

  @Test
  fun `filter keeps one project`() {
    val threads: List<Thread> = listOf(thread("a", "router"), thread("b", "kstore"))
    val groups: List<ThreadGroup> = groupThreads(listOf(kstore, router), threads, "router")
    assertEquals(listOf("a"), groups.flatMap { group -> group.threads.map { it.id } })
  }

  @Test
  fun `missing project id falls back to all`() {
    assertNull(effectiveProjectId(listOf(kstore), "gone"))
    assertNull(effectiveProjectId(emptyList(), "kstore"))
    assertNull(effectiveProjectId(listOf(kstore), null))
    assertEquals("kstore", effectiveProjectId(listOf(kstore, router), "kstore"))
  }

  @Test
  fun `statuses map to badges`() {
    assertEquals(ThreadBadge.Running, thread(status = "queued").badge())
    assertEquals(ThreadBadge.WaitingOnYou, thread(status = "waiting").badge())
    assertEquals(ThreadBadge.WaitingOnYou, thread(status = "running", pending = true).badge())
    assertEquals(ThreadBadge.Idle, thread(status = "completed").badge())
    assertEquals(ThreadBadge.Archived, thread(archivedAt = "2026-10-01T00:00:00Z").badge())
  }

  @Test
  fun `unknown status keeps the raw string`() {
    assertEquals(ThreadBadge.Other("paused"), thread(status = "paused").badge())
    assertEquals("failed", thread(status = "failed").badge().label)
  }

  @Test
  fun `relative time`() {
    val now: Instant = Instant.parse("2026-10-19T12:00:00Z")
    fun ago(at: String): String = relativeTime(Instant.parse(at), now, ZoneOffset.UTC)
    assertEquals("just now", ago("2026-10-19T11:59:30Z"))
    assertEquals("just now", ago("2026-10-19T12:05:00Z"))
    assertEquals("5m", ago("2026-10-19T11:55:00Z"))
    assertEquals("3h", ago("2026-10-19T09:00:00Z"))
    assertEquals("2d", ago("2026-10-17T11:00:00Z"))
    assertEquals("Oct 3", ago("2026-10-03T08:00:00Z"))
    assertEquals("Dec 24, 2025", ago("2025-12-24T08:00:00Z"))
  }

  private fun project(id: String): Project = Project(
    id = id,
    title = id,
    workspaceRoot = "/tmp/$id",
    defaultModelSelection = null,
    scripts = emptyList(),
    createdAt = EPOCH,
    updatedAt = EPOCH,
  )

  private fun thread(
    id: String = "t",
    projectId: String = "kstore",
    status: String = "idle",
    updatedAt: String = EPOCH,
    archivedAt: String? = null,
    pinnedAt: String? = null,
    pending: Boolean = false,
  ): Thread = BASE_THREAD.copy(
    id = id,
    projectId = projectId,
    title = id,
    status = status,
    archivedAt = archivedAt,
    pinnedAt = pinnedAt,
    pendingRuntimeRequest =
      if (pending) PendingRuntimeRequestSummary("r", "command", EPOCH) else null,
    updatedAt = updatedAt,
  )
}

private const val EPOCH: String = "1970-01-01T00:00:00Z"

/** A thread row with only what upstream requires. Tests copy it with the fields they care about. */
private val BASE_THREAD: Thread = T3Json.decodeFromString(
  Thread.serializer(),
  """
    {
      "id": "t", "projectId": "kstore", "title": "t", "status": "idle",
      "createdBy": "user", "creationSource": "web", "providerInstanceId": "codex",
      "modelSelection": { "model": "gpt-5" }, "runtimeMode": "full-access",
      "interactionMode": "default", "lineage": { "rootThreadId": "t" },
      "hasActionableProposedPlan": false, "itemCount": 0, "visibleItemCount": 0,
      "createdAt": "$EPOCH", "updatedAt": "$EPOCH"
    }
  """,
)
