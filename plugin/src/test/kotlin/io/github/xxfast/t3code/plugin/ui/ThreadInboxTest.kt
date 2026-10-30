package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.AppThreadLineage
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadInboxTest {
  @Test
  fun `running threads and background work are working, asks and endings aren't`() {
    assertTrue(isThreadWorking(running()))
    assertTrue(isThreadWorking(running().copy(status = "waiting")))
    assertFalse(isThreadWorking(finished()))
    assertFalse(isThreadWorking(BASE_THREAD), "A thread that never ran")
    val subagent = PendingBackgroundTask.PendingBackgroundTaskSubagent(taskId = "bg")
    val parked: Thread = finished().copy(pendingBackgroundTasks = listOf(subagent))
    assertTrue(isThreadWorking(parked), "Background work holds it at idle")
    assertFalse(isThreadWorking(running().copy(pendingRuntimeRequest = request("command"))))
    assertFalse(isThreadWorking(running().copy(pendingRuntimeRequest = request("user_input"))))
    assertFalse(isThreadWorking(running(), PendingSubagentRequest.Approval))
    assertFalse(isThreadWorking(finished().copy(status = "failed", lastError = "boom")))
  }

  @Test
  fun `an async question keeps the run working until it ends`() {
    val asking: Thread = running().copy(pendingRuntimeRequest = request("user_input", false))
    assertTrue(isThreadWorking(asking))
    val ended: Thread = asking.copy(status = "completed", activeRunId = null)
    assertFalse(isThreadWorking(ended))
  }

  @Test
  fun `a plan ready to build is waiting on you once its run settled`() {
    val plan: Thread = finished().copy(
      interactionMode = "plan",
      hasActionableProposedPlan = true,
      pendingBackgroundTasks = listOf(PendingBackgroundTask.Monitor(taskId = "bg")),
    )
    assertFalse(isThreadWorking(plan))
    val planning: Thread =
      running().copy(interactionMode = "plan", hasActionableProposedPlan = true)
    assertTrue(isThreadWorking(planning), "Still running")
  }

  @Test
  fun `a subagent's live approval flags its parents up the chain`() {
    val threads: List<Thread> = listOf(
      thread("root"),
      thread("parent", parent = "root"),
      child("child", parent = "parent", kind = "command"),
    )
    val approval: PendingSubagentRequest = PendingSubagentRequest.Approval
    assertEquals(
      mapOf("parent" to approval, "root" to approval),
      pendingSubagentRequestsByThread(threads),
    )
  }

  @Test
  fun `questions, provider-native children and ended runs don't flag a parent`() {
    assertTrue(
      pendingSubagentRequestsByThread(
        listOf(thread("parent"), child("child", parent = "parent", kind = "user_input")),
      ).isEmpty(),
      "The coordinator answers child questions",
    )
    val native: Thread = child("child", parent = "parent", kind = "command")
      .copy(creationSource = "provider")
    assertTrue(pendingSubagentRequestsByThread(listOf(thread("parent"), native)).isEmpty())
    val ended: Thread = child("child", parent = "parent", kind = "command")
      .copy(status = "completed", activityRunStatus = null)
    assertTrue(pendingSubagentRequestsByThread(listOf(thread("parent"), ended)).isEmpty())
    val refresh: Thread = child("child", parent = "parent", kind = "auth_refresh")
    assertTrue(pendingSubagentRequestsByThread(listOf(thread("parent"), refresh)).isEmpty())
  }

  @Test
  fun `the inbox sorts by return, the shelf by send`() {
    val old: Thread = finished("old", completedAt = at(50)).copy(createdAt = at(0))
    val new: Thread = finished("new", completedAt = at(20)).copy(createdAt = at(10))
    assertEquals(listOf("old", "new"), sortInboxThreadsByReturn(listOf(new, old)).map { it.id })
    val returned: List<Thread> = sortInboxThreadsByReturn(listOf(old, new)) { thread ->
      if (thread.id == "new") Instant.parse(at(60)).toEpochMilli() else null
    }
    assertEquals(listOf("new", "old"), returned.map { it.id })

    val sentLate: Thread = running("late").copy(latestUserAuthoredMessageAt = at(40))
    val sentEarly: Thread = running("early").copy(latestRunRequestedAt = at(30))
    val working: List<Thread> = sortWorkingThreadsBySend(listOf(sentEarly, sentLate))
    assertEquals(listOf("late", "early"), working.map { it.id })
  }

  @Test
  fun `a continuation inside the grace never leaves the shelf`() {
    val clock = FakeClock()
    val tracker: InboxReturnTracker = clock.tracker()
    assertTrue(tracker.observe(listOf(running())).isWorking(running(), null))
    clock.now = 1_000
    val done: InboxObservation = tracker.observe(listOf(finished()))
    assertTrue(done.isWorking(finished(), null), "Held for the grace")
    assertEquals(listOf("t"), shelf(done, finished()).working.map { it.id })
    clock.now = 2_500
    val again: InboxObservation = tracker.observe(listOf(running()))
    assertTrue(again.isWorking(running(), null))
    assertNull(again.returnedAt(running()))
    clock.fire()
    assertEquals(0, tracker.version.value, "Nothing was left to expire")
  }

  @Test
  fun `a finished run comes back once the grace expires, without another event`() {
    val clock = FakeClock()
    val tracker: InboxReturnTracker = clock.tracker()
    tracker.observe(listOf(running()))
    clock.now = 1_000
    tracker.observe(listOf(finished()))
    assertEquals(WORKING_EXIT_GRACE_MS, clock.delay)
    clock.now = 3_000
    clock.fire()
    assertEquals(1, tracker.version.value)
    val after: InboxObservation = tracker.observe(listOf(finished()))
    assertFalse(after.isWorking(finished(), null))
    assertEquals(3_000, after.returnedAt(finished()))
    assertEquals(listOf("t"), shelf(after, finished()).inbox.map { it.id })
  }

  @Test
  fun `a run settled inside the grace goes straight to Settled`() {
    val clock = FakeClock()
    val tracker: InboxReturnTracker = clock.tracker()
    tracker.observe(listOf(running()))
    clock.now = 1_000
    tracker.observe(listOf(finished()))
    clock.now = 1_500
    val settled: Thread = finished().copy(settledOverride = "settled", settledAt = at(1))
    val observed: InboxObservation = tracker.observe(listOf(settled))
    assertFalse(observed.isWorking(settled, null))
    val sections: ThreadSections = shelf(observed, settled)
    assertEquals(listOf("t"), sections.settled.map { it.id })
    assertTrue(sections.inbox.isEmpty() && sections.working.isEmpty())
  }

  @Test
  fun `asks, failures and moves skip the grace`() {
    for (next in listOf(
      finished().copy(pendingRuntimeRequest = request("command")),
      finished().copy(status = "failed"),
      finished().copy(activeOrderKey = "a0"),
    )) {
      val clock = FakeClock()
      val tracker: InboxReturnTracker = clock.tracker()
      tracker.observe(listOf(running()))
      clock.now = 1_000
      val observed: InboxObservation = tracker.observe(listOf(next))
      assertFalse(observed.isWorking(next, null), next.toString())
      assertEquals(1_000, observed.returnedAt(next))
    }
  }

  @Test
  fun `turning the shelf off forgets everything`() {
    val clock = FakeClock()
    val tracker: InboxReturnTracker = clock.tracker()
    tracker.observe(listOf(running()))
    tracker.observe(listOf(finished()))
    assertTrue(tracker.isWorking(finished()))
    tracker.observe(null)
    assertTrue(clock.cancelled)
    assertFalse(tracker.isWorking(finished()))
  }

  @Test
  fun `the shelf takes working rows from active, not pins, and keeps a flagged parent out`() {
    val threads: List<Thread> = listOf(
      running("busy"),
      running("pinned").copy(pinnedAt = at(0)),
      finished("done"),
      running("parent"),
      child("child", parent = "parent", kind = "command"),
    )
    val tracker: InboxReturnTracker = FakeClock().tracker()
    val requests: Map<String, PendingSubagentRequest> = pendingSubagentRequestsByThread(threads)
    val observation: InboxObservation = tracker.observe(threads, requests)
    val sections: ThreadSections = sections(threads, working = observation)
    assertEquals(listOf("busy"), sections.working.map { it.id })
    assertEquals(setOf("pinned", "done", "parent"), sections.inbox.map { it.id }.toSet())
    assertEquals("pinned", sections.inbox.first().id)
    val off: ThreadSections = sections(threads)
    assertTrue(off.working.isEmpty())
    assertEquals(4, off.inbox.size)
  }

  @Test
  fun `a collapsed shelf still shows the open thread`() {
    val rows: List<Thread> = listOf(running("a"), running("b"))
    assertEquals(listOf("b"), routeThreadOnly(rows, "b").map { it.id })
    assertTrue(routeThreadOnly(rows, "c").isEmpty())
    assertTrue(routeThreadOnly(rows, null).isEmpty())
  }

  private fun shelf(observation: InboxObservation, vararg threads: Thread): ThreadSections =
    sections(threads.toList(), working = observation)

  /** A wall clock tests move by hand, and the one timer the tracker keeps. */
  private class FakeClock {
    var now: Long = 0
    var delay: Long? = null
    var cancelled: Boolean = false
    private var task: (() -> Unit)? = null

    fun tracker(): InboxReturnTracker = createInboxReturnTracker({ now }) { delayMillis, task ->
      delay = delayMillis
      cancelled = false
      this.task = task
      return@createInboxReturnTracker {
        cancelled = true
        this.task = null
      }
    }

    fun fire() {
      val pending: () -> Unit = task ?: return
      task = null
      pending()
    }
  }
}

private fun at(seconds: Int): String = Instant.ofEpochSecond(seconds.toLong()).toString()

private fun request(kind: String, blocking: Boolean? = null): PendingRuntimeRequestSummary =
  PendingRuntimeRequestSummary("req", kind, at(0), blocking)

private fun thread(id: String = "t", parent: String? = null): Thread = BASE_THREAD.copy(
  id = id,
  lineage = AppThreadLineage(
    parentThreadId = parent,
    relationshipToParent = if (parent != null) "subagent" else null,
    rootThreadId = "root",
  ),
)

private fun running(id: String = "t"): Thread = thread(id).copy(
  status = "running",
  latestRunId = "run",
  activeRunId = "run",
  latestRunRequestedAt = at(0),
  latestRunStartedAt = at(0),
)

private fun finished(id: String = "t", completedAt: String = at(1)): Thread = running(id).copy(
  status = "completed",
  activeRunId = null,
  latestRunCompletedAt = completedAt,
)

private fun child(id: String, parent: String, kind: String): Thread = thread(id, parent).copy(
  status = "waiting",
  latestRunId = "run_$id",
  activeRunId = "run_$id",
  activityRunStatus = "waiting",
  pendingRuntimeRequest = request(kind),
)
