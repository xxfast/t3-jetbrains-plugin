package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.plugin.ui.ThreadStatus
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadNotificationCoordinatorTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val idle: Thread = Scenario
    .decode(scenarios.resolve("default").resolve("list.json").readText())
    .threads
    .first { it.status == "idle" && it.archivedAt == null }
  private val running: Thread = idle.copy(
    id = "a",
    title = "Bump Kotlin",
    status = "running",
    latestRunId = "run1",
    activeRunId = "run1",
  )

  @Test
  fun `a thread's first sighting is its baseline`() {
    val (_, changes: ThreadNotificationChanges) =
      ThreadNotifications().next(listOf(approval(running, "r1"), completed(running)))
    assertEquals(emptyList(), changes.notify)
  }

  @Test
  fun `a run completing notifies once`() {
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(listOf(running))
    val (done: ThreadNotifications, changes: ThreadNotificationChanges) =
      seeded.next(listOf(completed(running)))
    assertEquals(listOf(ThreadNotificationKind.Completion), changes.notify.map { it.kind })
    assertEquals("Thread completed", changes.notify.single().title)

    val (_, again: ThreadNotificationChanges) = done.next(listOf(completed(running)))
    assertEquals(emptyList(), again.notify, "Same completion, no second balloon")
  }

  @Test
  fun `a stop the user asked for doesn't notify its completion`() {
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(listOf(running))
    val stopped: Thread = completed(running).copy(latestRunStopRequested = true)
    val (_, changes: ThreadNotificationChanges) = seeded.next(listOf(stopped))
    assertEquals(emptyList(), changes.notify)
  }

  @Test
  fun `archived threads are tracked but never notify, subagents aren't tracked`() {
    val archived: Thread = running.copy(id = "b", archivedAt = "2026-10-20T10:00:00Z")
    val subagent: Thread = running
      .copy(id = "c", lineage = running.lineage.copy(relationshipToParent = "subagent"))
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(listOf(archived, subagent))
    assertEquals(setOf("b"), seeded.previous.keys)

    val (_, changes: ThreadNotificationChanges) =
      seeded.next(listOf(completed(archived), approval(subagent, "r1")))
    assertEquals(emptyList(), changes.notify)
  }

  @Test
  fun `titles follow the status`() {
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(
      listOf("a", "b", "c", "d").map { id -> running.copy(id = id) },
    )
    val (_, changes: ThreadNotificationChanges) = seeded.next(
      listOf(
        approval(running.copy(id = "a"), "r1"),
        approval(running.copy(id = "b"), "r2", kind = "user_input"),
        running.copy(id = "c", status = "failed"),
        running.copy(id = "d", status = "failed", lastErrorClass = "usage_limit"),
      ),
    )
    assertEquals(
      listOf("Approval needed", "Input needed", "Thread failed", "Usage limit reached"),
      changes.notify.map { it.title },
    )
    assertTrue(changes.notify.all { it.kind == ThreadNotificationKind.Input })
    assertEquals(
      listOf(ThreadStatus.Approval, ThreadStatus.Input, ThreadStatus.Failed, ThreadStatus.Limited),
      changes.notify.map { it.status },
    )
  }

  @Test
  fun `attention is the run and the status, so a second approval in one run is quiet`() {
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(listOf(running))
    val (first: ThreadNotifications, asked: ThreadNotificationChanges) =
      seeded.next(listOf(approval(running, "r1")))
    assertEquals(1, asked.notify.size)
    assertEquals("run1:approval", first.previous.getValue("a").attention)

    val (_, again: ThreadNotificationChanges) = first.next(listOf(approval(running, "r2")))
    assertEquals(emptyList(), again.notify)
  }

  @Test
  fun `attention going away ends it, and coming back notifies again`() {
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(listOf(running))
    val (asked: ThreadNotifications, _) = seeded.next(listOf(approval(running, "r1")))
    val (answered: ThreadNotifications, ended: ThreadNotificationChanges) =
      asked.next(listOf(running))
    assertEquals(setOf("a"), ended.ended)

    val (_, again: ThreadNotificationChanges) = answered.next(listOf(approval(running, "r2")))
    assertEquals(listOf("Approval needed"), again.notify.map { it.title })
  }

  @Test
  fun `a thread that's gone ends its attention`() {
    val (seeded: ThreadNotifications, _) = ThreadNotifications().next(listOf(running))
    val (asked: ThreadNotifications, _) = seeded.next(listOf(approval(running, "r1")))
    val (_, gone: ThreadNotificationChanges) = asked.next(emptyList())
    assertEquals(setOf("a"), gone.ended)
  }

  @Test
  fun `archived and subagent threads don't badge`() {
    val archived: Thread = approval(idle, "r1").copy(id = "a", archivedAt = "2026-10-20T10:00:00Z")
    val subagent: Thread = approval(idle, "r2")
      .let { it.copy(id = "b", lineage = it.lineage.copy(relationshipToParent = "subagent")) }
    val shown: Thread = approval(idle, "r3").copy(id = "c")
    val waitingStatus: Thread = idle.copy(id = "d", status = "waiting")
    val threads: List<Thread> = listOf(archived, subagent, shown, waitingStatus, idle)
    assertEquals(listOf("c", "d"), waitingThreads(threads).map { it.id })
  }

  @Test
  fun `waits the composer can't answer don't badge`() {
    val auth: Thread = approval(idle, "r1", kind = "auth_refresh").copy(id = "a")
    val tool: Thread = approval(idle, "r2", kind = "dynamic_tool_call").copy(id = "b")
    val question: Thread = approval(idle, "r3", kind = "user_input").copy(id = "c")
    assertEquals(listOf("c"), waitingThreads(listOf(auth, tool, question)).map { it.id })
    assertTrue(waitingThreads(emptyList()).isEmpty())
  }
}

private fun approval(thread: Thread, requestId: String, kind: String = "command"): Thread =
  thread.copy(
    status = "waiting",
    pendingRuntimeRequest = PendingRuntimeRequestSummary(
      id = requestId,
      kind = kind,
      createdAt = "2026-10-20T10:00:00Z",
    ),
  )

private fun completed(thread: Thread): Thread = thread.copy(
  status = "completed",
  activeRunId = null,
  latestRunCompletedAt = "2026-10-20T10:05:00Z",
)
