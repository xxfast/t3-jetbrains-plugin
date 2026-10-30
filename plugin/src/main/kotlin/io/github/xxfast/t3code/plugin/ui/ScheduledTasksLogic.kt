package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ScheduledTask
import io.github.xxfast.t3code.protocol.generated.ScheduledTaskSchedule
import java.time.Instant
import kotlin.math.ceil

// Ports from apps/web/src/components/settings/ScheduledTasksSettings.tsx and
// apps/web/src/components/chat/ThreadAutomationsPanel.tsx

/** `WEEKDAY_LABELS`, ScheduledTasksSettings.tsx:124. Upstream's weekdays count from Sunday. */
private val WEEKDAY_LABELS: List<String> = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * `scheduleLabel`, ScheduledTasksSettings.tsx:289: "On webhook", "Every 30 min",
 * "Weekdays at 09:00". A trigger this build doesn't know shows its type; upstream has no such case.
 */
fun scheduleLabel(schedule: ScheduledTaskSchedule): String = when (schedule) {
  is ScheduledTaskSchedule.Webhook -> "On webhook"
  is ScheduledTaskSchedule.Interval ->
    if (schedule.everyMs % 60_000 == 0L) "Every ${schedule.everyMs / 60_000} min"
    else "Every ${Math.round(schedule.everyMs / 1000.0)} sec"

  is ScheduledTaskSchedule.FixedTime -> {
    val weekdays: List<Long> = schedule.weekdays.orEmpty()
    val days: String = when {
      weekdays.isEmpty() -> "Daily"
      weekdays.size == 5 && weekdays.all { it in 1L..5L } -> "Weekdays"
      else -> weekdays.joinToString(", ") { day -> WEEKDAY_LABELS.getOrNull(day.toInt()) ?: "$day" }
    }
    "$days at ${schedule.timeOfDay}"
  }

  is ScheduledTaskSchedule.Unknown -> schedule.type
}

/**
 * `relativeLabel`, ScheduledTasksSettings.tsx:312: "in 5m", "in 3h", "in 2d" for an upcoming
 * run, `formatRelativeTimeLabel`'s "5m ago" for a past one. Upstream reads the clock once a
 * minute. A time that doesn't parse is "Not scheduled" here, where upstream would print NaN.
 */
fun relativeLabel(value: String?, now: Instant): String {
  if (value.isNullOrEmpty()) return "Not scheduled"
  val at: Instant = value.toInstantOrNull() ?: return "Not scheduled"
  val diffMs: Long = at.toEpochMilli() - now.toEpochMilli()
  if (diffMs <= 0) {
    val relative: String = relativeTime(at, now)
    return if (relative == "just now") relative else "$relative ago"
  }
  val minutes: Long = ceil(diffMs / 60_000.0).toLong()
  if (minutes < 2) return "in under a minute"
  if (minutes < 60) return "in ${minutes}m"
  val hours: Long = Math.round(minutes / 60.0)
  if (hours < 24) return "in ${hours}h"
  return "in ${Math.round(hours / 24.0)}d"
}

/**
 * The Scheduled tab row's status line, `ScheduledTaskRow`'s in ScheduledTasksSettings.tsx:723:
 * the schedule, then paused, listening for a webhook, or when it runs next.
 */
fun scheduledTaskStatusLine(task: ScheduledTask, now: Instant): String {
  val next: String = when {
    !task.enabled -> "Paused"
    task.schedule is ScheduledTaskSchedule.Webhook -> "Listening"
    task.nextRunAt != null -> "Next run ${relativeLabel(task.nextRunAt, now)}"
    else -> "Not scheduled"
  }
  return "${scheduleLabel(task.schedule)} · $next"
}

/** `taskScheduleLine`, ThreadAutomationsPanel.tsx:40: the schedule, then next or paused. */
fun taskScheduleLine(task: ScheduledTask, now: Instant): String = scheduleLabel(task.schedule) +
  when {
    task.enabled && task.nextRunAt != null -> " · next ${relativeLabel(task.nextRunAt, now)}"
    task.enabled -> ""
    else -> " · paused"
  }

/** A webhook task runs from its URL; there's no request to run it with. Both upstream rows. */
val ScheduledTask.canRunNow: Boolean get() = schedule !is ScheduledTaskSchedule.Webhook

/** `STATUS_DOT_CLASS`, ThreadAutomationsPanel.tsx:33: the last run's dot on the row's icon. */
fun scheduledTaskStatusTone(status: String): ThreadRelationshipTone = when (status) {
  "running" -> ThreadRelationshipTone.Info
  "succeeded" -> ThreadRelationshipTone.Success
  "failed" -> ThreadRelationshipTone.Destructive
  else -> ThreadRelationshipTone.Muted
}


/**
 * The tasks T3 Thread Details' Automations lists, `boundTasks` in ThreadAutomationsPanel.tsx:77:
 * those whose runs go to [threadId].
 */
fun boundTasks(tasks: List<ScheduledTask>, threadId: String): List<ScheduledTask> =
  tasks.filter { it.threadId == threadId }
