package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
  fun `presets on a weekday morning`() {
    val tuesday: Instant = Instant.parse("2026-10-20T10:00:00Z")
    val presets: List<SnoozePreset> = snoozePresets(tuesday, UTC, Locale.US)
    assertEquals(
      listOf("hour", "three-hours", "evening", "tomorrow", "next-week"),
      presets.map { it.id },
    )
    assertEquals(
      listOf(
        "2026-10-20T11:00:00Z",
        "2026-10-20T13:00:00Z",
        "2026-10-20T18:00:00Z",
        "2026-10-21T09:00:00Z",
        "2026-10-26T09:00:00Z",
      ),
      presets.map { it.snoozedUntil.toString() },
    )
    assertEquals("Mon 9:00 AM", presets.last().whenLabel.replace(' ', ' '))
  }

  @Test
  fun `no evening within the hour, no next week when it's tomorrow`() {
    val sundayEvening: Instant = Instant.parse("2026-10-25T17:30:00Z")
    val presets: List<SnoozePreset> = snoozePresets(sundayEvening, UTC, Locale.US)
    assertEquals(listOf("hour", "three-hours", "tomorrow"), presets.map { it.id })
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
private val NOW: Instant = Instant.parse("2026-04-10T12:00:00Z")
private const val SNOOZED_AT: String = "2026-04-10T09:00:00Z"
private const val FUTURE_WAKE: String = "2026-04-11T09:00:00Z"
private const val PAST_WAKE: String = "2026-04-10T11:00:00Z"
