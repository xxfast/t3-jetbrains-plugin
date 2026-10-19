package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadsLogicTest {
  @Test
  fun `active is a run in flight or a running status`() {
    assertTrue(thread(activeRunId = "r").active)
    assertTrue(thread(status = "queued").active)
    assertFalse(thread(status = "completed").active)
    assertFalse(thread(status = "waiting").active)
  }

  @Test
  fun `waiting on you`() {
    assertTrue(thread(status = "waiting").waitingOnYou)
    assertTrue(thread(status = "running", pending = true).waitingOnYou)
    assertFalse(thread(status = "running").waitingOnYou)
  }

  @Test
  fun `sections split active from settled and drop archived`() {
    val threads: List<Thread> = listOf(
      thread("idle"),
      thread("running", status = "running"),
      thread("archived", archivedAt = "2026-10-19T00:00:00Z"),
      thread("busy", activeRunId = "r"),
    )
    val sections: ThreadSections = sections(threads, "")
    assertEquals(setOf("running", "busy"), sections.active.map { it.id }.toSet())
    assertEquals(listOf("idle"), sections.settled.map { it.id })
  }

  @Test
  fun `active newest run first`() {
    val threads: List<Thread> = listOf(
      thread("old", status = "running", runStartedAt = "2026-10-19T08:00:00Z"),
      thread("new", status = "running", runStartedAt = "2026-10-19T09:00:00Z"),
    )
    assertEquals(listOf("new", "old"), sections(threads, "").active.map { it.id })
  }

  @Test
  fun `settled pinned first, then newest`() {
    val threads: List<Thread> = listOf(
      thread("old", updatedAt = "2026-10-01T00:00:00Z"),
      thread("new", updatedAt = "2026-10-18T00:00:00Z"),
      thread("pinned", updatedAt = "2026-09-01T00:00:00Z", pinnedAt = "2026-09-01T00:00:00Z"),
    )
    assertEquals(listOf("pinned", "new", "old"), sections(threads, "").settled.map { it.id })
  }

  @Test
  fun `search matches title case-insensitively`() {
    val threads: List<Thread> = listOf(
      thread("a", title = "Fix Predictive Back"),
      thread("b", title = "Release notes"),
      thread("c", title = "Back to basics", status = "running"),
    )
    val sections: ThreadSections = sections(threads, "  back ")
    assertEquals(listOf("c"), sections.active.map { it.id })
    assertEquals(listOf("a"), sections.settled.map { it.id })
  }

  @Test
  fun `show more reveals a page at a time`() {
    assertEquals(0, moreCount(total = 8, shown = 10))
    assertEquals(4, moreCount(total = 14, shown = 10))
    assertEquals(25, moreCount(total = 100, shown = 10))
    assertEquals(0, moreCount(total = 35, shown = 35))
  }

  @Test
  fun `run start falls back to updatedAt`() {
    val started: String = "2026-10-19T08:00:00Z"
    assertEquals(Instant.parse(started), thread(runStartedAt = started).runStartedAt())
    val updated: String = "2026-10-19T09:00:00Z"
    assertEquals(Instant.parse(updated), thread(updatedAt = updated).runStartedAt())
  }

  @Test
  fun `durations`() {
    assertEquals("0s", duration(Duration.ofSeconds(-5)))
    assertEquals("22s", duration(Duration.ofSeconds(22)))
    assertEquals("1m 12s", duration(Duration.ofSeconds(72)))
    assertEquals("2m", duration(Duration.ofMinutes(2)))
    assertEquals("2h 5m", duration(Duration.ofMinutes(125)))
    assertEquals("3h", duration(Duration.ofHours(3).plusSeconds(20)))
  }

  @Test
  fun `project initials`() {
    assertEquals("KS", initials("KStore"))
    assertEquals("DR", initials("Decompose-Router"))
    assertEquals("TJ", initials("t3-jetbrains-plugin"))
    assertEquals("", initials("--"))
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

  private fun thread(
    id: String = "t",
    title: String = id,
    status: String = "idle",
    activeRunId: String? = null,
    runStartedAt: String? = null,
    updatedAt: String = EPOCH,
    archivedAt: String? = null,
    pinnedAt: String? = null,
    pending: Boolean = false,
  ): Thread = BASE_THREAD.copy(
    id = id,
    title = title,
    status = status,
    activeRunId = activeRunId,
    activityRunStartedAt = runStartedAt,
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
