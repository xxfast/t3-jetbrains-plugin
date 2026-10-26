package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.ThreadStatus
import io.github.xxfast.t3code.plugin.ui.sidebarStatus
import io.github.xxfast.t3code.plugin.ui.waitingSince
import io.github.xxfast.t3code.protocol.Thread

/** Upstream's two kinds, `playNotificationSound`'s `"completion" | "input"`. */
enum class ThreadNotificationKind { Input, Completion }

/** One notification for [thread]: its [title] over the thread's title, as upstream shows it. */
data class ThreadNotification(
  val thread: Thread,
  val kind: ThreadNotificationKind,
  val status: ThreadStatus,
) {
  /** The titles inline in `EnvironmentNotifications`, ThreadNotificationCoordinator.tsx:163. */
  val title: String
    get() = when {
      kind == ThreadNotificationKind.Completion -> "Thread completed"
      status == ThreadStatus.Approval -> "Approval needed"
      status == ThreadStatus.Limited -> "Usage limit reached"
      status == ThreadStatus.Failed -> "Thread failed"
      else -> "Input needed"
    }
}

/**
 * What `EnvironmentNotifications` keeps per thread between snapshots (`NotificationState`,
 * ThreadNotificationCoordinator.tsx:96): the `runId:status` that wants attention, and when the
 * latest completed run completed, in epoch millis.
 */
data class NotificationState(val attention: String?, val completion: Long?)

/** One look's outcome: what to [notify], and the threads whose attention went away. */
data class ThreadNotificationChanges(
  val notify: List<ThreadNotification>,
  val ended: Set<String>,
)

/**
 * The diffing pass of `EnvironmentNotifications` (ThreadNotificationCoordinator.tsx:124 to 238)
 * over one thread list. A thread's first sighting is its baseline, so nothing already waiting or
 * done when the IDE connects notifies. Subagent threads aren't tracked, archived ones are tracked
 * but never notify. Upstream's `pendingSubagentRequest` isn't ported, as in [sidebarStatus].
 */
data class ThreadNotifications(val previous: Map<String, NotificationState> = emptyMap()) {
  fun next(threads: List<Thread>): Pair<ThreadNotifications, ThreadNotificationChanges> {
    val next: MutableMap<String, NotificationState> = LinkedHashMap()
    val notify: MutableList<ThreadNotification> = mutableListOf()
    for (thread in threads) {
      if (thread.lineage.relationshipToParent == "subagent") continue
      val prior: NotificationState? = previous[thread.id]
      val runStatus: String? = thread.latestRunStatus
      var status: ThreadStatus = thread.sidebarStatus()
      if (status == ThreadStatus.Ready && runStatus == "failed") status = ThreadStatus.Failed
      val attention: String? = if (status in ATTENTION) {
        "${thread.latestRunId.orEmpty()}:${status.name.lowercase()}"
      } else {
        null
      }
      val completedAt: Long? = thread.waitingSince()?.toEpochMilli()
      val completion: Long? =
        if (status == ThreadStatus.Ready && runStatus == "completed" && completedAt != null) {
          completedAt
        } else {
          prior?.completion
        }
      next[thread.id] = NotificationState(attention, completion)
      if (prior == null || thread.archivedAt != null) continue
      val kind: ThreadNotificationKind? = when {
        attention != null && attention != prior.attention -> ThreadNotificationKind.Input
        thread.latestRunStopRequested != true && completion != null &&
          (prior.completion == null || completion > prior.completion) ->
          ThreadNotificationKind.Completion
        else -> null
      }
      if (kind != null) notify += ThreadNotification(thread, kind, status)
    }
    val ended: Set<String> = previous
      .filter { (id, state) -> state.attention != null && next[id]?.attention == null }
      .keys
    return ThreadNotifications(next) to ThreadNotificationChanges(notify, ended)
  }
}

/** The statuses that raise an `input` notification. */
private val ATTENTION: Set<ThreadStatus> =
  setOf(ThreadStatus.Input, ThreadStatus.Approval, ThreadStatus.Failed, ThreadStatus.Limited)

/** `latestRun.status` as `presentThreadShell` (client-runtime state/models.ts:256) fills it. */
private val Thread.latestRunStatus: String?
  get() = when {
    latestRunId == null -> null
    status == "idle" -> "completed"
    else -> status
  }
