package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.DEFAULT_SNOOZE_PRESETS
import io.github.xxfast.t3code.protocol.SnoozePresetDay
import io.github.xxfast.t3code.protocol.SnoozePresetSetting
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
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
// the presented shell (`presentThreadShell`, models.ts:200); the row's own fields stand in, with
// its `pendingSubagentRequest` passed alongside (`pendingSubagentRequestsByThread`).

/** `QUEUED_TURN_START_GRACE_MS`: a message unadopted after this long is a failed start. */
val QUEUED_TURN_START_GRACE: Duration = Duration.ofMinutes(2)

/** The presented shell's `hasPendingApprovals`, from the pending request's kind. */
internal val Thread.hasPendingApprovals: Boolean
  get() {
    val kind: String = pendingRuntimeRequest?.kind ?: return false
    return kind != "user_input" && kind != "auth_refresh"
  }

/** Runtime statuses `presentThreadShell` reads as working; a waiting run only drains effects. */
private val RUNTIME_WORKING: Set<String> = setOf("preparing", "starting", "running")

/**
 * The presented shell's `hasPendingUserInput`: a question, unless it's an async one the run
 * keeps working past.
 */
internal val Thread.hasPendingUserInput: Boolean
  get() {
    val request: PendingRuntimeRequestSummary = pendingRuntimeRequest ?: return false
    if (request.kind != "user_input") return false
    return request.blocking != false || runtimeStatus() !in RUNTIME_WORKING
  }

/** `latestRun.status` as `presentThreadShell` derives it: an idle thread's last run completed. */
internal val Thread.latestRunStatus: String?
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
fun Thread.raisedHandWhileSnoozed(
  pendingSubagentRequest: PendingSubagentRequest? = null,
): Boolean {
  if (hasPendingApprovals || hasPendingUserInput) return true
  if (pendingSubagentRequest != null) return true
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
  !hasPendingApprovals && !hasPendingUserInput && !hasQueuedTurnStart(now)

/** `effectiveSnoozed`: a wake time still ahead, and no hand raised since. */
fun Thread.snoozed(
  now: Instant,
  pendingSubagentRequest: PendingSubagentRequest? = null,
): Boolean {
  val wake: Instant = snoozedUntil?.toInstantOrNull() ?: return false
  if (!wake.isAfter(now)) return false
  return !raisedHandWhileSnoozed(pendingSubagentRequest)
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

/** `snoozeTimeOfDayLabel`: `toLocaleTimeString` with a numeric hour and 2-digit minute. */
private fun timeLabel(at: ZonedDateTime, locale: Locale): String =
  at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

private fun weekdayLabel(at: ZonedDateTime, locale: Locale): String =
  at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

/** `WEEKDAY_INDEX`, as java.time numbers the week. */
private val SnoozePresetDay.dayOfWeek: DayOfWeek?
  get() = when (this) {
    SnoozePresetDay.Today, SnoozePresetDay.Tomorrow -> null
    SnoozePresetDay.Monday -> DayOfWeek.MONDAY
    SnoozePresetDay.Tuesday -> DayOfWeek.TUESDAY
    SnoozePresetDay.Wednesday -> DayOfWeek.WEDNESDAY
    SnoozePresetDay.Thursday -> DayOfWeek.THURSDAY
    SnoozePresetDay.Friday -> DayOfWeek.FRIDAY
    SnoozePresetDay.Saturday -> DayOfWeek.SATURDAY
    SnoozePresetDay.Sunday -> DayOfWeek.SUNDAY
  }

private fun pluralize(count: Int, unit: String): String =
  "$count $unit${if (count == 1) "" else "s"}"

/** `formatSnoozeDuration`: "3 hours", "90 minutes", "2 days", the largest unit that divides. */
private fun formatSnoozeDuration(minutes: Int): String = when {
  minutes % (24 * 60) == 0 -> pluralize(minutes / (24 * 60), "day")
  minutes % 60 == 0 -> pluralize(minutes / 60, "hour")
  else -> pluralize(minutes, "minute")
}

/** `snoozePresetDaysAhead`: a weekday is its next occurrence after today, so never 0. */
private fun snoozePresetDaysAhead(setting: SnoozePresetSetting.At, now: ZonedDateTime): Int {
  val weekday: DayOfWeek = setting.day.dayOfWeek
    ?: return if (setting.day == SnoozePresetDay.Today) 0 else 1
  return ((weekday.value - now.dayOfWeek.value + 7) % 7).takeIf { it != 0 } ?: 7
}

/** `snoozePresetLabel`: by the actual day gap, so a Monday option on a Sunday reads "Tomorrow". */
private fun snoozePresetLabel(setting: SnoozePresetSetting, daysAhead: Int): String {
  val at: SnoozePresetSetting.At = when (setting) {
    is SnoozePresetSetting.After -> return "In ${formatSnoozeDuration(setting.minutes)}"
    is SnoozePresetSetting.At -> setting
  }
  if (daysAhead == 0) {
    val hour: Int = at.time.substring(0, 2).toInt()
    return if (hour < 12) "This morning" else if (hour < 17) "This afternoon" else "This evening"
  }
  if (daysAhead == 1) return "Tomorrow"
  if (at.day == SnoozePresetDay.Monday) return "Next week"
  val day: String = at.day.wire.replaceFirstChar { it.uppercaseChar() }
  return if (daysAhead == 7) "Next $day" else day
}

/** `snoozePresetId`: `after:180`, `at:tomorrow:09:00`. */
fun snoozePresetId(setting: SnoozePresetSetting): String = when (setting) {
  is SnoozePresetSetting.After -> "after:${setting.minutes}"
  is SnoozePresetSetting.At -> "at:${setting.day.wire}:${setting.time}"
}

/**
 * `resolveSnoozePreset`: when [setting] wakes from [now], or null for an option today whose time
 * has passed. Days advance on the calendar, not by 24 hours, so DST doesn't skip one.
 */
fun resolveSnoozePreset(
  setting: SnoozePresetSetting,
  now: Instant,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
  formatTime: (ZonedDateTime) -> String = { at -> timeLabel(at, locale) },
): SnoozePreset? {
  val local: ZonedDateTime = now.atZone(zone)
  var daysAhead = 0
  val wake: ZonedDateTime = when (setting) {
    is SnoozePresetSetting.After ->
      now.plus(Duration.ofMinutes(setting.minutes.toLong())).atZone(zone)

    is SnoozePresetSetting.At -> {
      daysAhead = snoozePresetDaysAhead(setting, local)
      val time: LocalTime = LocalTime.of(
        setting.time.substring(0, 2).toInt(),
        setting.time.substring(3).toInt(),
      )
      val at: ZonedDateTime =
        local.toLocalDate().plusDays(daysAhead.toLong()).atTime(time).atZone(zone)
      // A today option whose time has passed is left out until tomorrow
      if (!at.toInstant().isAfter(now)) return null
      at
    }
  }
  val label: String = snoozePresetLabel(setting, daysAhead)
  // Durations and "Next week" don't name the wake day, so a wake on another day shows it
  val labelNamesDay: Boolean = setting is SnoozePresetSetting.At && label != "Next week"
  val time: String = formatTime(wake)
  return SnoozePreset(
    id = snoozePresetId(setting),
    label = label,
    whenLabel = if (labelNamesDay || wake.toLocalDate() == local.toLocalDate()) {
      time
    } else {
      "${weekdayLabel(wake, locale)} $time"
    },
    snoozedUntil = wake.toInstant(),
  )
}

/** By minute, so a duration (which keeps the clock's seconds) and a set time in that minute tie. */
private val SnoozePreset.wakeMinute: Long
  get() = Math.floorDiv(snoozedUntil.toEpochMilli(), 60_000L)

/**
 * `resolveSnoozePresets`: the snooze menu from the server's [presets], soonest first, one row per
 * wake minute. The sort is stable, so of two that tie, the first in settings stays.
 */
fun resolveSnoozePresets(
  now: Instant,
  presets: List<SnoozePresetSetting> = DEFAULT_SNOOZE_PRESETS,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
  formatTime: (ZonedDateTime) -> String = { at -> timeLabel(at, locale) },
): List<SnoozePreset> = presets
  .mapNotNull { setting -> resolveSnoozePreset(setting, now, zone, locale, formatTime) }
  .sortedBy { preset -> preset.wakeMinute }
  .distinctBy { preset -> preset.wakeMinute }

/**
 * `pickSnoozePreset`: a menu choice as picked. A duration re-resolves from its setting (not the
 * deduped menu) so it starts at selection. A set time keeps the shown wake, which a day change
 * would otherwise shift. Null when that wake has passed.
 */
fun pickSnoozePreset(
  shown: List<SnoozePreset>,
  id: String,
  now: Instant,
  presets: List<SnoozePresetSetting> = DEFAULT_SNOOZE_PRESETS,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
  formatTime: (ZonedDateTime) -> String = { at -> timeLabel(at, locale) },
): SnoozePreset? {
  val original: SnoozePreset? = shown.find { preset -> preset.id == id }
  val ahead: SnoozePreset? = original?.takeIf { it.snoozedUntil.isAfter(now) }
  val setting: SnoozePresetSetting? = presets.find { candidate ->
    candidate is SnoozePresetSetting.After && snoozePresetId(candidate) == id
  }
  return setting?.let { resolveSnoozePreset(it, now, zone, locale, formatTime) } ?: ahead
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
