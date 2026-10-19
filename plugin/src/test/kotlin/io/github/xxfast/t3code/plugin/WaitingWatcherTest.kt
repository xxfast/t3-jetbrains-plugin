package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WaitingWatcherTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val idle: Thread = Scenario
    .decode(scenarios.resolve("default").resolve("list.json").readText())
    .threads
    .first { it.status == "idle" && it.archivedAt == null }

  @Test
  fun `the first list is the baseline, then only new waits start`() {
    val first: Thread = waiting(idle, "a", "r1")
    val (seeded: WaitingTracker, none: WaitingChanges) = WaitingTracker().look(listOf(first))
    assertEquals(emptyList(), none.started, "Already waiting when the IDE connects")
    assertEquals(setOf("a:r1"), seeded.keys)

    val second: Thread = waiting(idle, "b", "r2")
    val (_, changes: WaitingChanges) = seeded.look(listOf(first, second))
    assertEquals(listOf("b"), changes.started.map { it.id })
    assertEquals(emptySet(), changes.stopped)
  }

  @Test
  fun `an empty list doesn't seed`() {
    val (tracker: WaitingTracker, _) = WaitingTracker().look(emptyList())
    val (_, changes: WaitingChanges) = tracker.look(listOf(waiting(idle, "a", "r1")))
    assertEquals(emptyList(), changes.started, "Still the baseline")
  }

  @Test
  fun `a resolved wait stops, and a new request on the same thread starts again`() {
    val (seeded: WaitingTracker, _) = WaitingTracker().look(listOf(waiting(idle, "a", "r1")))

    val (resolved: WaitingTracker, stopped: WaitingChanges) =
      seeded.look(listOf(idle.copy(id = "a")))
    assertEquals(setOf("a:r1"), stopped.stopped)

    val (_, again: WaitingChanges) = resolved.look(listOf(waiting(idle, "a", "r2")))
    assertEquals(listOf("a"), again.started.map { it.id })
  }

  @Test
  fun `archived and subagent threads don't count or notify`() {
    val archived: Thread = waiting(idle, "a", "r1").copy(archivedAt = "2026-10-20T10:00:00Z")
    val subagent: Thread = waiting(idle, "b", "r2")
      .let { it.copy(lineage = it.lineage.copy(relationshipToParent = "subagent")) }
    val shown: Thread = waiting(idle, "c", "r3")
    val waitingStatus: Thread = idle.copy(id = "d", status = "waiting")
    val threads: List<Thread> = listOf(archived, subagent, shown, waitingStatus, idle)

    assertEquals(2, waitingThreads(threads).size)
    val (seeded: WaitingTracker, _) = WaitingTracker().look(listOf(idle))
    val (_, changes: WaitingChanges) = seeded.look(threads)
    assertEquals(listOf("c", "d"), changes.started.map { it.id })
  }

  @Test
  fun `waits the composer can't answer don't count or notify`() {
    val auth: Thread = waiting(idle, "a", "r1", kind = "auth_refresh")
    val tool: Thread = waiting(idle, "b", "r2", kind = "dynamic_tool_call")
    val question: Thread = waiting(idle, "c", "r3", kind = "user_input")
    assertEquals(listOf("c"), waitingThreads(listOf(auth, tool, question)).map { it.id })
    assertNull(waitingKey(auth))
    assertNull(waitingKey(tool))
  }

  @Test
  fun `keys and labels`() {
    assertNull(waitingKey(idle))
    assertEquals("a:r1", waitingKey(waiting(idle, "a", "r1")))
    assertEquals("d:waiting", waitingKey(idle.copy(id = "d", status = "waiting")))
    assertEquals("question", waitingLabel("user_input"))
    assertEquals("command approval", waitingLabel("command"))
    assertEquals("approval", waitingLabel(null))
    assertTrue(waitingThreads(emptyList()).isEmpty())
  }
}

private fun waiting(
  thread: Thread,
  id: String,
  requestId: String,
  kind: String = "command",
): Thread = thread.copy(
  id = id,
  status = "waiting",
  pendingRuntimeRequest = PendingRuntimeRequestSummary(
    id = requestId,
    kind = kind,
    createdAt = "2026-10-20T10:00:00Z",
  ),
)

/** One look at a thread list, the way [WaitingWatcher] takes it. */
private fun WaitingTracker.look(threads: List<Thread>): Pair<WaitingTracker, WaitingChanges> =
  next(waitingThreads(threads), loaded = threads.isNotEmpty())
