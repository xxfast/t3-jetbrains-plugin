package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.DEFAULT_SNOOZE_PRESETS
import io.github.xxfast.t3code.protocol.SnoozePresetDay
import io.github.xxfast.t3code.protocol.SnoozePresetSetting
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// Cases follow upstream's packages/client-runtime/src/state/threadSnoozed.test.ts
class ThreadSettledTest {
  @Test
  fun `snoozed until the wake time passes`() {
    assertTrue(snoozing().snoozed(NOW))
    assertFalse(snoozing(until = PAST_WAKE).snoozed(NOW), "Timer wake, no event")
    assertFalse(BASE_THREAD.snoozed(NOW))
    assertFalse(snoozing(until = "not-a-date").snoozed(NOW))
  }

  @Test
  fun `a raised hand wakes it early`() {
    assertFalse(snoozing().raisedHandWhileSnoozed())
    assertFalse(snoozing(pending = "approval").snoozed(NOW))
    assertFalse(snoozing(pending = "user_input").snoozed(NOW))
    assertFalse(snoozing(status = "failed", updatedAt = "2026-04-10T11:00:00Z").snoozed(NOW))
    assertTrue(
      snoozing(status = "failed", updatedAt = "2026-04-10T08:00:00Z").snoozed(NOW),
      "The failure predates the snooze, the user saw it",
    )
    assertTrue(snoozing(status = "running").snoozed(NOW), "Snooze never pauses the agent")
    assertFalse(snoozing(completedAt = "2026-04-10T10:30:00Z").snoozed(NOW))
    assertTrue(snoozing(completedAt = "2026-04-10T08:00:00Z").snoozed(NOW))
  }

  @Test
  fun `can't snooze while asking or about to start a turn`() {
    assertTrue(BASE_THREAD.canSnooze(NOW))
    assertFalse(thread(pending = "approval").canSnooze(NOW))
    assertFalse(thread(pending = "user_input").canSnooze(NOW))
    val sent: Thread = BASE_THREAD.copy(latestUserMessageAt = "2026-04-10T11:59:00Z")
    assertFalse(sent.canSnooze(NOW), "A message with no run yet is a turn about to start")
    val stale: Thread = BASE_THREAD.copy(latestUserMessageAt = "2026-04-10T11:00:00Z")
    assertTrue(stale.canSnooze(NOW), "Past the grace window it's a failed start, not pending")
    assertFalse(thread(status = "starting").canSnooze(NOW))
  }

  @Test
  fun `woke at the wake time, or when the hand went up`() {
    assertNull(snoozing().wokeAt(NOW), "Still asleep")
    assertEquals(PAST_WAKE, snoozing(until = PAST_WAKE).wokeAt(NOW))
    val completed = "2026-04-10T10:30:00Z"
    assertEquals(completed, snoozing(completedAt = completed).wokeAt(NOW))
    assertNull(BASE_THREAD.wokeAt(NOW))
  }

  @Test
  fun `woke shows until a visit at or after it, and never on a settled thread`() {
    assertTrue(snoozing(until = PAST_WAKE).woke(NOW))
    assertFalse(snoozing(until = PAST_WAKE).copy(lastVisitedAt = PAST_WAKE).woke(NOW))
    assertTrue(snoozing(until = PAST_WAKE).copy(lastVisitedAt = SNOOZED_AT).woke(NOW))
    assertFalse(snoozing(until = PAST_WAKE).copy(settledOverride = "settled").woke(NOW))
    assertFalse(snoozing().woke(NOW))
  }

  @Test
  fun `lists custom presets soonest first and names the day when the label does not`() {
    // Wednesday 20:00
    val presets: List<SnoozePresetSetting> = listOf(
      at(SnoozePresetDay.Monday, "18:00"),
      at(SnoozePresetDay.Tomorrow, "21:00"),
      after(360),
      after(180),
    )
    assertEquals(
      listOf("In 3 hours 23:00", "In 6 hours Thu 2:00", "Tomorrow 21:00", "Next week Mon 18:00"),
      menu(local(2026, 4, 8, 20), presets),
    )
  }

  @Test
  fun `drops a today preset once its time passes and moves a weekday on that day to next week`() {
    // Monday 18:30
    val presets: List<SnoozePreset> = resolveSnoozePresets(
      local(2026, 4, 13, 18, 30),
      listOf(at(SnoozePresetDay.Today, "18:00"), at(SnoozePresetDay.Monday, "09:00")),
      UTC,
      Locale.US,
    )
    assertEquals(listOf("at:monday:09:00"), presets.map { it.id })
    assertEquals(local(2026, 4, 20, 9), presets.single().snoozedUntil)
  }

  @Test
  fun `names the real day gap, a Monday option on Sunday is Tomorrow, a week out is Next`() {
    val theo: List<SnoozePresetSetting> = listOf(
      at(SnoozePresetDay.Tomorrow, "21:00"),
      at(SnoozePresetDay.Monday, "18:00"),
      at(SnoozePresetDay.Friday, "17:00"),
    )
    // Sunday 14:00
    assertEquals(
      listOf("Tomorrow 18:00", "Tomorrow 21:00", "Friday 17:00"),
      menu(local(2026, 8, 30, 14), theo),
    )
    // Friday 10:00: today's 17:00 is a week away
    assertEquals(
      listOf("Tomorrow 21:00", "Next week Mon 18:00", "Next Friday 17:00"),
      menu(local(2026, 9, 4, 10), theo),
    )
  }

  @Test
  fun `shows one row when two presets wake at the same time`() {
    // Sunday, so Tomorrow 9:00 and Next week 9:00 are the same Monday
    assertEquals(
      listOf("In 1 hour 8:01", "In 3 hours 10:01", "This evening 18:00", "Tomorrow 9:00"),
      menu(local(2026, 8, 30, 7, 1)),
    )
  }

  @Test
  fun `treats a duration and a set time in the same minute as one row`() {
    val now: Instant = local(2026, 4, 8, 15).plusSeconds(25)
    assertEquals(
      listOf("In 3 hours 18:00"),
      menu(now, listOf(after(180), at(SnoozePresetDay.Today, "18:00"))),
    )
    assertEquals(
      listOf("This evening 18:00"),
      menu(now, listOf(at(SnoozePresetDay.Today, "18:00"), after(180))),
      "Of two that tie, the first in settings stays",
    )
  }

  @Test
  fun `orders by wake time and a passed today option drops out`() {
    // SnoozePresetsSettings.logic.test.ts's `sortSnoozeRows`: 16:00, so 20 hours wakes tomorrow
    // and today 09:00 has passed
    val now: Instant = local(2026, 10, 28, 16)
    val passed: SnoozePresetSetting = at(SnoozePresetDay.Today, "09:00")
    val presets: List<SnoozePresetSetting> =
      listOf(after(20 * 60), passed, at(SnoozePresetDay.Today, "18:00"), after(60))
    assertNull(resolveSnoozePreset(passed, now, UTC, Locale.US))
    assertEquals(
      listOf("after:60", "at:today:18:00", "after:1200"),
      resolveSnoozePresets(now, presets, UTC, Locale.US).map { it.id },
    )
    assertEquals(
      listOf("In 1 hour 17:00", "This evening 18:00", "In 20 hours Thu 12:00"),
      menu(now, presets),
    )
  }

  @Test
  fun `labels a today option by the time of day and a duration by its largest unit`() {
    val presets: List<SnoozePresetSetting> = listOf(
      at(SnoozePresetDay.Today, "11:59"),
      at(SnoozePresetDay.Today, "12:00"),
      at(SnoozePresetDay.Today, "17:00"),
      after(90),
      after(2 * 24 * 60),
    )
    assertEquals(
      listOf(
        "In 90 minutes 9:30",
        "This morning 11:59",
        "This afternoon 12:00",
        "This evening 17:00",
        "In 2 days Fri 8:00",
      ),
      menu(local(2026, 4, 8, 8), presets),
    )
  }

  @Test
  fun `the defaults are upstream's`() {
    // Tuesday 10:00
    assertEquals(
      listOf(
        "after:60",
        "after:180",
        "at:today:18:00",
        "at:tomorrow:09:00",
        "at:monday:09:00",
      ),
      resolveSnoozePresets(local(2026, 10, 20, 10), zone = UTC, locale = Locale.US).map { it.id },
    )
  }

  @Test
  fun `starts a duration when it is picked, even when the menu merged it with a set time`() {
    val presets: List<SnoozePresetSetting> = listOf(at(SnoozePresetDay.Today, "18:30"), after(180))
    // At 15:00 the two differ; at 15:30 "In 3 hours" lands on 18:30 and the menu shows one row
    val shown: List<SnoozePreset> =
      resolveSnoozePresets(local(2026, 4, 8, 15), presets, UTC, Locale.US)
    val picked: SnoozePreset? =
      pickSnoozePreset(shown, "after:180", local(2026, 4, 8, 15, 30), presets, UTC, Locale.US)
    assertEquals(local(2026, 4, 8, 18, 30), picked?.snoozedUntil)
  }

  @Test
  fun `keeps a set time's shown wake when the day changes while the menu is open`() {
    // Sunday 23:59: "Tomorrow" means Monday 9:00, even if the pick lands after midnight
    val shown: List<SnoozePreset> =
      resolveSnoozePresets(local(2026, 8, 30, 23, 59), zone = UTC, locale = Locale.US)
    val picked: SnoozePreset? = pickSnoozePreset(
      shown,
      "at:tomorrow:09:00",
      local(2026, 8, 31, 0, 1),
      zone = UTC,
      locale = Locale.US,
    )
    assertEquals(local(2026, 8, 31, 9), picked?.snoozedUntil)
  }

  @Test
  fun `a shown set time that has passed can't be picked`() {
    val shown: List<SnoozePreset> = resolveSnoozePresets(
      local(2026, 4, 8, 17, 59),
      listOf(at(SnoozePresetDay.Today, "18:00")),
      UTC,
      Locale.US,
    )
    assertNull(pickSnoozePreset(shown, "at:today:18:00", local(2026, 4, 8, 18, 0), zone = UTC))
  }

  @Test
  fun `wake label rounds up`() {
    assertEquals("1m", snoozeWakeLabel("2026-04-10T12:00:30Z", NOW))
    assertEquals("2h", snoozeWakeLabel("2026-04-10T13:30:00Z", NOW))
    assertEquals("2d", snoozeWakeLabel("2026-04-11T13:00:00Z", NOW))
    assertEquals("now", snoozeWakeLabel(PAST_WAKE, NOW))
    assertEquals("now", snoozeWakeLabel("nope", NOW))
  }

  @Test
  fun `custom snooze takes a future time or a positive duration`() {
    val hours = CustomSnooze.For("2", SnoozeUnit.Hours)
    assertEquals(Instant.parse("2026-04-10T14:00:00Z"), resolveCustomSnooze(hours, NOW, UTC))
    assertEquals(
      Instant.parse("2026-04-10T12:45:00Z"),
      resolveCustomSnooze(CustomSnooze.For("0.75", SnoozeUnit.Hours), NOW, UTC),
    )
    assertNull(resolveCustomSnooze(CustomSnooze.For("0", SnoozeUnit.Days), NOW, UTC))
    assertNull(resolveCustomSnooze(CustomSnooze.For("soon", SnoozeUnit.Days), NOW, UTC))
    val at = CustomSnooze.At("2026-04-11", "09:30")
    assertEquals(Instant.parse("2026-04-11T09:30:00Z"), resolveCustomSnooze(at, NOW, UTC))
    assertNull(resolveCustomSnooze(CustomSnooze.At("2026-04-09", "09:30"), NOW, UTC), "Past")
    assertNull(resolveCustomSnooze(CustomSnooze.At("2026-02-30", "09:30"), NOW, UTC))
    assertNull(resolveCustomSnooze(CustomSnooze.At("2026-4-11", "9:30"), NOW, UTC))
    val newYork: ZoneId = ZoneId.of("America/New_York")
    val skipped = CustomSnooze.At("2027-03-14", "02:30")
    assertNull(resolveCustomSnooze(skipped, NOW, newYork), "DST skips that local time")
  }

  /** The menu as upstream's test reads it, `label whenLabel`, with an `H:mm` clock. */
  private fun menu(
    now: Instant,
    presets: List<SnoozePresetSetting> = DEFAULT_SNOOZE_PRESETS,
  ): List<String> = resolveSnoozePresets(now, presets, UTC, Locale.US, formatTime = ::clock)
    .map { preset -> "${preset.label} ${preset.whenLabel}" }

  private fun thread(status: String = "idle", pending: String? = null): Thread = BASE_THREAD.copy(
    status = status,
    latestRunId = if (status == "idle") null else "run",
    pendingRuntimeRequest = pending?.let { PendingRuntimeRequestSummary("r", it, SNOOZED_AT) },
  )

  /** A thread snoozed at [SNOOZED_AT] until [until], with its latest run in [status]. */
  private fun snoozing(
    until: String = FUTURE_WAKE,
    pending: String? = null,
    status: String = "idle",
    updatedAt: String = SNOOZED_AT,
    completedAt: String? = null,
  ): Thread = thread(status, pending).copy(
    latestRunId = if (completedAt != null || status != "idle") "run" else null,
    latestRunCompletedAt = completedAt,
    snoozedUntil = until,
    snoozedAt = SNOOZED_AT,
    updatedAt = updatedAt,
  )
}

private val UTC: ZoneId = ZoneOffset.UTC

/** `localDate` in upstream's test: a wall-clock time in [UTC]. */
private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
  LocalDateTime.of(year, month, day, hour, minute).atZone(UTC).toInstant()

private fun clock(at: ZonedDateTime): String = "${at.hour}:${"%02d".format(at.minute)}"

private fun after(minutes: Int): SnoozePresetSetting = SnoozePresetSetting.After(minutes)

private fun at(day: SnoozePresetDay, time: String): SnoozePresetSetting =
  SnoozePresetSetting.At(day, time)
private val NOW: Instant = Instant.parse("2026-04-10T12:00:00Z")
private const val SNOOZED_AT: String = "2026-04-10T09:00:00Z"
private const val FUTURE_WAKE: String = "2026-04-11T09:00:00Z"
private const val PAST_WAKE: String = "2026-04-10T11:00:00Z"
