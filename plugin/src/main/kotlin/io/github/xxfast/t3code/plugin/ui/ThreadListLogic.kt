package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.pinned
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

sealed interface ThreadBadge {
  val label: String

  data object Running : ThreadBadge {
    override val label: String = "running"
  }

  data object WaitingOnYou : ThreadBadge {
    override val label: String = "waiting on you"
  }

  data object Idle : ThreadBadge {
    override val label: String = "idle"
  }

  data object Archived : ThreadBadge {
    override val label: String = "archived"
  }

  data class Other(override val label: String) : ThreadBadge
}

data class ThreadGroup(val project: Project, val threads: List<Thread>)

private val RUNNING: Set<String> = setOf("preparing", "queued", "starting", "running")
private val IDLE: Set<String> = setOf("idle", "completed")

fun Thread.badge(): ThreadBadge = when {
  archived -> ThreadBadge.Archived
  pendingRuntimeRequest != null || status == "waiting" -> ThreadBadge.WaitingOnYou
  status in RUNNING -> ThreadBadge.Running
  status in IDLE -> ThreadBadge.Idle
  else -> ThreadBadge.Other(status)
}

/** The persisted id if it's still a known project, otherwise `null` for "All". */
fun effectiveProjectId(projects: List<Project>, persisted: String?): String? =
  persisted?.takeIf { id -> projects.any { it.id == id } }

/** One group per project with threads, in project order. Pinned first, archived last. */
fun groupThreads(
  projects: List<Project>,
  threads: List<Thread>,
  selectedProjectId: String?,
): List<ThreadGroup> {
  val byProject: Map<String, List<Thread>> = threads.groupBy { it.projectId }
  val order: Comparator<Thread> = compareBy<Thread> { it.archived }
    .thenByDescending { it.pinned }
    .thenByDescending { it.updatedAt.toInstant() }
  return projects
    .filter { selectedProjectId == null || it.id == selectedProjectId }
    .mapNotNull { project ->
      byProject[project.id]
        ?.sortedWith(order)
        ?.let { ThreadGroup(project, it) }
    }
}

private val SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val OTHER_YEAR: DateTimeFormatter =
  DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

fun relativeTime(then: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
  val elapsed: Duration = Duration.between(then, now)
  return when {
    elapsed < Duration.ofMinutes(1) -> "just now"
    elapsed < Duration.ofHours(1) -> "${elapsed.toMinutes()}m"
    elapsed < Duration.ofDays(1) -> "${elapsed.toHours()}h"
    elapsed < Duration.ofDays(7) -> "${elapsed.toDays()}d"
    else -> {
      val date: ZonedDateTime = then.atZone(zone)
      val format: DateTimeFormatter =
        if (date.year == now.atZone(zone).year) SAME_YEAR else OTHER_YEAR
      format.format(date)
    }
  }
}

fun String.toInstant(): Instant =
  try {
    Instant.parse(this)
  } catch (_: DateTimeParseException) {
    Instant.EPOCH
  }
