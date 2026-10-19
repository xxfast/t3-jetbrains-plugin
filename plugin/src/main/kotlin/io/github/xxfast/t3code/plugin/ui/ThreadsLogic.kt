package io.github.xxfast.t3code.plugin.ui

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

/** Thread and run statuses that mean work is in flight. */
val RUNNING: Set<String> = setOf("preparing", "queued", "starting", "running")

/** Settled rows shown before the first "Show N more", and how many each press reveals. */
const val FIRST_PAGE: Int = 10
const val NEXT_PAGE: Int = 25

/** The two sections of the threads panel, archived threads left out. */
data class ThreadSections(val active: List<Thread>, val settled: List<Thread>)

val Thread.active: Boolean get() = activeRunId != null || status in RUNNING

val Thread.waitingOnYou: Boolean get() = pendingRuntimeRequest != null || status == "waiting"

/** Splits [threads] matching [query] by title. Active newest run first; settled pinned first. */
fun sections(threads: List<Thread>, query: String): ThreadSections {
  val needle: String = query.trim()
  val (active: List<Thread>, settled: List<Thread>) = threads
    .filter { !it.archived && it.title.contains(needle, ignoreCase = true) }
    .partition { it.active }
  val order: Comparator<Thread> = compareByDescending<Thread> { it.pinned }
    .thenByDescending { it.updatedAt.toInstant() }
  return ThreadSections(
    active = active.sortedByDescending { it.runStartedAt() },
    settled = settled.sortedWith(order),
  )
}

/** How many rows "Show N more" reveals, `0` when all [total] are already [shown]. */
fun moreCount(total: Int, shown: Int): Int = (total - shown).coerceIn(0, NEXT_PAGE)

/**
 * Where the "Working" clock starts. The list row has no `Run`, so this is the shell's own copy of
 * the run start, falling back to `updatedAt` when the server didn't send one.
 */
fun Thread.runStartedAt(): Instant =
  (activityRunStartedAt ?: latestRunStartedAt ?: latestRunRequestedAt ?: updatedAt).toInstant()

/** `22s`, `1m 12s`, `2h 5m`. Zero trailing parts are dropped, negative durations are `0s`. */
fun duration(elapsed: Duration): String {
  val seconds: Long = elapsed.seconds.coerceAtLeast(0)
  fun parts(big: Long, bigUnit: String, small: Long, smallUnit: String): String =
    if (small == 0L) "$big$bigUnit" else "$big$bigUnit $small$smallUnit"
  return when {
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> parts(seconds / 60, "m", seconds % 60, "s")
    else -> parts(seconds / 3_600, "h", seconds % 3_600 / 60, "m")
  }
}

/** Two letters for a project badge: the first letters of the first two words, else two letters. */
fun initials(title: String): String {
  val words: List<String> = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
  val letters: String = when {
    words.size >= 2 -> "${words[0].first()}${words[1].first()}"
    else -> words.firstOrNull()?.take(2).orEmpty()
  }
  return letters.uppercase()
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
