package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The Working shelf, as packages/client-runtime/src/state/threadInbox.ts at the pinned tag.
// Upstream reads the presented shell (`presentThreadShell`, models.ts); the row's own fields
// stand in, with its `pendingSubagentRequest` passed alongside. Threads are keyed by id alone,
// upstream's `environmentId:id` with one environment.

/**
 * `isThreadWorking`: nothing asked of you, and a run in flight or background work holding the
 * thread at idle. A plan ready to build once its run settled is waiting on you too.
 */
fun isThreadWorking(
  thread: Thread,
  pendingSubagentRequest: PendingSubagentRequest? = null,
): Boolean {
  if (thread.hasPendingApprovals || thread.hasPendingUserInput) return false
  if (pendingSubagentRequest != null) return false
  val runtime: String? = thread.runtimeStatus()
  if (runtime !in WORKING && runtime != "idle") return false
  val run: String? = thread.latestRunStatus
  val runSettled: Boolean =
    run != null && run !in WORKING && thread.activeRunId != thread.latestRunId
  return !(thread.interactionMode == "plan" && thread.hasActionableProposedPlan && runSettled)
}

/** `toSortableTimestamp`, threadSort.ts, with upstream's `?? 0`. */
private fun sortable(iso: String?): Long = iso?.toInstantOrNull()?.toEpochMilli() ?: 0

/**
 * `sortInboxThreadsByReturn`: newest first by when each thread last came back, its creation,
 * waking from settled, its latest run's request or completion, or [observedReturnAt], when the
 * tracker saw it leave the Working shelf.
 */
fun sortInboxThreadsByReturn(
  threads: List<Thread>,
  observedReturnAt: ((Thread) -> Long?)? = null,
): List<Thread> = sortNewestFirst(threads) { thread ->
  val run: Boolean = thread.latestRunId != null
  maxOf(
    sortable(thread.createdAt),
    sortable(thread.unsettledAt),
    sortable(if (run) thread.latestRunRequestedAt else null),
    // `latestRun.completedAt` as `presentThreadShell` fills it in
    thread.waitingSince()?.toEpochMilli() ?: 0,
    observedReturnAt?.invoke(thread) ?: 0,
  )
}

/**
 * `sortWorkingThreadsBySend`: newest first by its creation or the user's latest message, the
 * latest run's request on servers without `latestUserAuthoredMessageAt`. Upstream tells an
 * absent field from a null one; the shell collapses them, so both fall back to the run.
 */
fun sortWorkingThreadsBySend(threads: List<Thread>): List<Thread> =
  sortNewestFirst(threads) { thread ->
    val requested: String? = if (thread.latestRunId != null) thread.latestRunRequestedAt else null
    maxOf(sortable(thread.createdAt), sortable(thread.latestUserAuthoredMessageAt ?: requested))
  }

/** `sortNewestFirst`: by [timestamp], newest first, then by id. */
private fun sortNewestFirst(threads: List<Thread>, timestamp: (Thread) -> Long): List<Thread> {
  val timestamps: Map<String, Long> = threads.associate { it.id to timestamp(it) }
  return threads.sortedWith(
    compareByDescending<Thread> { timestamps.getValue(it.id) }.thenBy { it.id },
  )
}

/**
 * `WORKING_EXIT_GRACE_MS`: how long a thread that stopped working keeps reading as working. An
 * orchestrator run can end moments before its continuation starts, so this long passes before
 * the thread counts as done.
 */
const val WORKING_EXIT_GRACE_MS: Long = 2_000

/** What [InboxReturnTracker.observe] answers: fixed once made, so a list built from it holds. */
class InboxObservation(
  val isWorking: (thread: Thread, pendingSubagentRequest: PendingSubagentRequest?) -> Boolean,
  val returnedAt: (thread: Thread) -> Long?,
)

/**
 * `createInboxReturnTracker`. Keep one at module scope so placement outlives a closed Sidebar.
 * Call [observe] when the shells or [version] change, or with null while the shelf is off.
 * [now] is the wall clock in milliseconds, [timer] runs a task after a delay and answers how to
 * cancel it.
 */
fun createInboxReturnTracker(
  now: () -> Long = System::currentTimeMillis,
  timer: (delayMillis: Long, task: () -> Unit) -> () -> Unit,
): InboxReturnTracker = InboxReturnTracker(now, timer)

class InboxReturnTracker internal constructor(
  private val now: () -> Long,
  private val timer: (delayMillis: Long, task: () -> Unit) -> () -> Unit,
) {
  private class State(var working: Boolean, var deadline: Long?, val placement: List<String?>)

  private val states: MutableMap<String, State> = mutableMapOf()
  private val returns: MutableMap<String, Long> = mutableMapOf()
  private var cancel: (() -> Unit)? = null
  private val snapshot: MutableStateFlow<Int> = MutableStateFlow(0)

  /** Upstream's `subscribe` and `getSnapshot`: bumps when a grace expires on its own. */
  val version: StateFlow<Int> = snapshot.asStateFlow()

  private fun schedule() {
    cancel?.invoke()
    cancel = null
    val next: Long = states.values.mapNotNull { it.deadline }.minOrNull() ?: return
    cancel = timer(maxOf(0, next - now())) { expire() }
  }

  @Synchronized
  private fun expire() {
    val at: Long = now()
    for ((key: String, state: State) in states) {
      val deadline: Long = state.deadline ?: continue
      if (deadline <= at) {
        state.working = false
        state.deadline = null
        returns[key] = at
      }
    }
    schedule()
    snapshot.update { it + 1 }
  }

  @Synchronized
  fun observe(
    threads: List<Thread>?,
    pendingSubagentRequests: Map<String, PendingSubagentRequest> = emptyMap(),
  ): InboxObservation {
    if (threads == null) {
      cancel?.invoke()
      cancel = null
      states.clear()
      returns.clear()
      return InboxObservation(isWorking = ::isThreadWorking, returnedAt = { null })
    }
    val present: MutableSet<String> = mutableSetOf()
    val at: Long = now()
    val observedAt: Instant = Instant.ofEpochMilli(at)
    for (thread in threads) {
      val key: String = thread.id
      present += key
      val placement: List<String?> = listOf(
        thread.archivedAt,
        thread.deletedAt,
        thread.pinnedAt,
        thread.settledOverride,
        thread.settledAt,
        thread.snoozedAt,
        thread.snoozedUntil,
        thread.activeOrderKey,
      )
      val previous: State? = states[key]
      val request: PendingSubagentRequest? = pendingSubagentRequests[key]
      // Upstream also counts `pendingGuestMessageCount > 0`, which isn't on the wire before
      // v0.1.5's contracts (#78); it reads as 0 until then
      val actionable: Boolean =
        thread.hasPendingApprovals ||
          thread.hasPendingUserInput ||
          request != null ||
          (thread.interactionMode == "plan" && thread.hasActionableProposedPlan) ||
          (thread.runtimeStatus() != null && thread.lastError != null) ||
          thread.latestRunStatus == "failed"
      val moved: Boolean = previous != null && previous.placement != placement
      val excluded: Boolean =
        thread.archivedAt != null ||
          thread.deletedAt != null ||
          thread.pinnedAt != null ||
          thread.settledOverride == "settled" ||
          thread.snoozed(observedAt, request)
      val actual: Boolean = isThreadWorking(thread, request)
      // Only shelf placement is held: execution state and attention requests stay authoritative
      val deadline: Long? =
        if (!actual && previous?.working == true && !actionable && !moved && !excluded) {
          previous.deadline ?: (at + WORKING_EXIT_GRACE_MS)
        } else {
          null
        }
      val working: Boolean = actual || (deadline != null && deadline > at)
      if (previous?.working == true && !working) returns[key] = at
      states[key] = State(working, if (working) deadline else null, placement)
    }
    states.keys.retainAll(present)
    returns.keys.retainAll(present)
    schedule()
    val observedWorking: Map<String, Boolean> = states.mapValues { (_, state) -> state.working }
    val observedReturns: Map<String, Long> = returns.toMap()
    return InboxObservation(
      isWorking = { thread, request ->
        observedWorking[thread.id] ?: isThreadWorking(thread, request)
      },
      returnedAt = { thread -> observedReturns[thread.id] },
    )
  }

  @Synchronized
  fun isWorking(thread: Thread, pendingSubagentRequest: PendingSubagentRequest? = null): Boolean =
    states[thread.id]?.working ?: isThreadWorking(thread, pendingSubagentRequest)

  @Synchronized
  fun returnedAt(thread: Thread): Long? = returns[thread.id]
}
