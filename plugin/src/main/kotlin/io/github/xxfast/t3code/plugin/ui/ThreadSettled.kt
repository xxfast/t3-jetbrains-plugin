package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// Snooze, as packages/client-runtime/src/state/threadSettled.ts at the pinned tag. Upstream reads
// the presented shell (`presentThreadShell`, models.ts:200); the row's own fields stand in, and
// `pendingSubagentRequest` isn't ported, since the plugin hides subagent rows.

/** `QUEUED_TURN_START_GRACE_MS`: a message unadopted after this long is a failed start. */
val QUEUED_TURN_START_GRACE: Duration = Duration.ofMinutes(2)

/** The presented shell's `hasPendingApprovals`, from the pending request's kind. */
private val Thread.pendingApproval: Boolean
  get() {
    val kind: String = pendingRuntimeRequest?.kind ?: return false
    return kind != "user_input" && kind != "auth_refresh"
  }

/** The presented shell's `hasPendingUserInput`. */
private val Thread.pendingUserInput: Boolean
  get() = pendingRuntimeRequest?.kind == "user_input"

/** `latestRun.status` as `presentThreadShell` derives it: an idle thread's last run completed. */
private val Thread.latestRunStatus: String?
  get() = if (latestRunId == null) null else if (status == "idle") "completed" else status

/** `hasQueuedTurnStart`: a message sent after the latest run and still waiting to start one. */
fun Thread.hasQueuedTurnStart(now: Instant): Boolean {
  val runtime: String? = runtimeStatus()
  if (runtime == "preparing" || runtime == "queued" || runtime == "starting") return true
  val messageAt: Instant = latestUserMessageAt?.toInstantOrNull() ?: return false
  // Bounded on both sides, a sender's clock ahead of this one gives a negative age
  if (Duration.between(messageAt, now).abs() > QUEUED_TURN_START_GRACE) return false
  if (latestRunId == null) return true
  return listOf(latestRunRequestedAt, latestRunStartedAt, latestRunCompletedAt).all { candidate ->
    candidate == null || candidate.toInstantOrNull()?.isBefore(messageAt) == true
  }
}

/**
 * `threadRaisedHandWhileSnoozed`: it asked for something, failed since it was snoozed, or a run
 * finished since.
 */
fun Thread.raisedHandWhileSnoozed(): Boolean {
  if (pendingApproval || pendingUserInput) return true
  val snoozed: Instant? = snoozedAt?.toInstantOrNull()
  val runtime: String? = runtimeStatus()
  if ((runtime == "error" || runtime == "failed") &&
    (snoozedAt == null || updatedAt.toInstant() > (snoozed ?: Instant.MAX))
  ) {
    return true
  }
  val completed: Instant? = waitingSince()
  return snoozed != null && latestRunStatus == "completed" && completed != null &&
    completed.isAfter(snoozed)
}

/** `canSnooze`: nothing asked of you and no turn about to start. */
fun Thread.canSnooze(now: Instant): Boolean =
  !pendingApproval && !pendingUserInput && !hasQueuedTurnStart(now)

/** `effectiveSnoozed`: a wake time still ahead, and no hand raised since. */
fun Thread.snoozed(now: Instant): Boolean {
  val wake: Instant = snoozedUntil?.toInstantOrNull() ?: return false
  if (!wake.isAfter(now)) return false
  return !raisedHandWhileSnoozed()
}

/**
 * `threadWokeAt`: when a snooze ended, by its own clock or by the thread raising its hand. Null
 * while it sleeps or when it was never snoozed.
 */
fun Thread.wokeAt(now: Instant): String? {
  val until: String = snoozedUntil ?: return null
  val wake: Instant = until.toInstantOrNull() ?: return null
  if (raisedHandWhileSnoozed()) {
    val snoozed: Instant? = snoozedAt?.toInstantOrNull()
    val completed: Instant? = waitingSince()
    if (snoozed != null && latestRunStatus == "completed" && completed != null &&
      completed.isAfter(snoozed)
    ) {
      return latestRunCompletedAt ?: updatedAt
    }
    return if (runtimeStatus() != null) updatedAt else snoozedAt
  }
  return if (!wake.isAfter(now)) until else null
}

/**
 * Whether the row shows "Woke" (Sidebar.tsx:1665): woken after the last visit, and not settled
 * since. Visiting at the wake time dismisses it.
 */
fun Thread.woke(now: Instant): Boolean {
  val woke: Instant = wokeAt(now)?.toInstantOrNull() ?: return false
  val visited: Instant? = lastVisitedAt?.toInstantOrNull()
  return (visited == null || visited.isBefore(woke)) && !settled
}

/** `SnoozePreset`: a menu entry, the wake time it reads as, and the instant it sends. */
data class SnoozePreset(
  val id: String,
  val label: String,
  val whenLabel: String,
  val snoozedUntil: Instant,
)

private const val EVENING_HOUR: Int = 18
private const val MORNING_HOUR: Int = 9

/** `toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })`. */
private fun timeLabel(at: ZonedDateTime, locale: Locale): String =
  at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

private fun weekdayLabel(at: ZonedDateTime, locale: Locale): String =
  at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

/**
 * `resolveSnoozePresets`: in an hour, in three, this evening while it's more than an hour off,
 * tomorrow morning, and next Monday morning unless that's tomorrow. Days advance on the
 * calendar, not by 24 hours, so DST doesn't skip one.
 */
fun snoozePresets(
  now: Instant,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
): List<SnoozePreset> {
  val local: ZonedDateTime = now.atZone(zone)
  fun preset(id: String, label: String, at: ZonedDateTime, whenLabel: String? = null) =
    SnoozePreset(id, label, whenLabel ?: timeLabel(at, locale), at.toInstant())
  fun atHour(day: ZonedDateTime, hour: Int): ZonedDateTime =
    day.toLocalDate().atTime(hour, 0).atZone(zone)
  val presets: MutableList<SnoozePreset> = mutableListOf(
    preset("hour", "In 1 hour", local.plusHours(1)),
    preset("three-hours", "In 3 hours", local.plusHours(3)),
  )
  val evening: ZonedDateTime = atHour(local, EVENING_HOUR)
  if (Duration.between(local, evening) > Duration.ofHours(1)) {
    presets += preset("evening", "This evening", evening)
  }
  val tomorrow: ZonedDateTime = atHour(local.plusDays(1), MORNING_HOUR)
  presets += preset("tomorrow", "Tomorrow", tomorrow)
  val untilMonday: Int = (DayOfWeek.MONDAY.value - local.dayOfWeek.value + 7) % 7
  val nextWeek: ZonedDateTime =
    atHour(local.plusDays(if (untilMonday == 0) 7L else untilMonday.toLong()), MORNING_HOUR)
  if (nextWeek != tomorrow) {
    val label = "${weekdayLabel(nextWeek, locale)} ${timeLabel(nextWeek, locale)}"
    presets += preset("next-week", "Next week", nextWeek, label)
  }
  return presets
}

/** `snoozeWakeLabel`: how long until it wakes, rounded up: `25m`, `3h`, `2d`, else `now`. */
fun snoozeWakeLabel(snoozedUntil: String, now: Instant): String {
  val wake: Instant = snoozedUntil.toInstantOrNull() ?: return "now"
  val remaining: Duration = Duration.between(now, wake)
  if (remaining <= Duration.ZERO) return "now"
  fun ceil(unit: Duration): Long = (remaining.toMillis() + unit.toMillis() - 1) / unit.toMillis()
  return when {
    remaining < Duration.ofHours(1) -> "${maxOf(1, ceil(Duration.ofMinutes(1)))}m"
    remaining < Duration.ofDays(1) -> "${ceil(Duration.ofHours(1))}h"
    else -> "${ceil(Duration.ofDays(1))}d"
  }
}

/** `CustomSnoozeInput`: a date and time, or an amount of a unit from now. */
sealed interface CustomSnooze {
  data class At(val date: String, val time: String) : CustomSnooze

  data class For(val amount: String, val unit: SnoozeUnit) : CustomSnooze
}

enum class SnoozeUnit(val label: String, val length: Duration) {
  Minutes("Minutes", Duration.ofMinutes(1)),
  Hours("Hours", Duration.ofHours(1)),
  Days("Days", Duration.ofDays(1)),
}

private val DATE: Regex = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TIME: Regex = Regex("""^\d{2}:\d{2}$""")

/**
 * `resolveCustomSnooze`: when [input] wakes, or null when it's not a time in the future. A date
 * that rolls over or a local time DST skips is rejected, not shifted.
 */
fun resolveCustomSnooze(
  input: CustomSnooze,
  now: Instant,
  zone: ZoneId = ZoneId.systemDefault(),
): Instant? {
  val wake: Instant = when (input) {
    is CustomSnooze.For -> {
      val amount: Double = input.amount.toDoubleOrNull() ?: return null
      if (!amount.isFinite() || amount <= 0) return null
      now.plusMillis((amount * input.unit.length.toMillis()).toLong())
    }

    is CustomSnooze.At -> {
      if (!DATE.matches(input.date) || !TIME.matches(input.time)) return null
      val date: LocalDate = parseOrNull { LocalDate.parse(input.date) } ?: return null
      val time: LocalTime = parseOrNull { LocalTime.parse(input.time) } ?: return null
      val local: ZonedDateTime = date.atTime(time).atZone(zone)
      if (local.toLocalDate() != date || local.toLocalTime() != time) return null
      local.toInstant()
    }
  }
  return wake.takeIf { it.isAfter(now) }
}

private fun <T> parseOrNull(parse: () -> T): T? =
  try {
    parse()
  } catch (_: DateTimeParseException) {
    null
  }
