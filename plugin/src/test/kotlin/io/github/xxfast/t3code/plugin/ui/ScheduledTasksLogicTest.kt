package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.ScheduledTask
import io.github.xxfast.t3code.protocol.generated.ScheduledTaskSchedule
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class ScheduledTasksLogicTest {
  private val now: Instant = Instant.parse("2026-10-27T09:00:00Z")

  @Test
  fun `scheduleLabel says what upstream's does`() {
    assertEquals("On webhook", scheduleLabel(ScheduledTaskSchedule.Webhook(signature = null)))
    assertEquals("Every 30 min", scheduleLabel(ScheduledTaskSchedule.Interval(everyMs = 1_800_000)))
    assertEquals("Every 90 sec", scheduleLabel(ScheduledTaskSchedule.Interval(everyMs = 90_000)))
    assertEquals("Every 2 sec", scheduleLabel(ScheduledTaskSchedule.Interval(everyMs = 1_500)))
    assertEquals("Daily at 09:00", scheduleLabel(fixed()))
    assertEquals("Weekdays at 09:00", scheduleLabel(fixed(1, 2, 3, 4, 5)))
    assertEquals("Mon, Wed at 09:00", scheduleLabel(fixed(1, 3)))
    assertEquals("Sun, Sat at 09:00", scheduleLabel(fixed(0, 6)))
    // Five days that aren't Monday to Friday spell them out
    assertEquals("Sun, Mon, Tue, Wed, Thu at 09:00", scheduleLabel(fixed(0, 1, 2, 3, 4)))
    val unknown = ScheduledTaskSchedule.Unknown(type = "cron", raw = JsonObject(emptyMap()))
    assertEquals("cron", scheduleLabel(unknown))
  }

  @Test
  fun `relativeLabel reads ahead and behind`() {
    fun at(offset: Duration): String = now.plus(offset).toString()

    assertEquals("Not scheduled", relativeLabel(null, now))
    assertEquals("Not scheduled", relativeLabel("soon", now))
    assertEquals("in under a minute", relativeLabel(at(Duration.ofSeconds(59)), now))
    assertEquals("in 2m", relativeLabel(at(Duration.ofSeconds(61)), now), "Minutes round up")
    assertEquals("in 59m", relativeLabel(at(Duration.ofMinutes(59)), now))
    assertEquals("in 1h", relativeLabel(at(Duration.ofMinutes(60)), now))
    assertEquals("in 2h", relativeLabel(at(Duration.ofMinutes(90)), now), "Hours round")
    assertEquals("in 1d", relativeLabel(at(Duration.ofHours(24)), now))
    assertEquals("in 3d", relativeLabel(at(Duration.ofHours(60)), now), "60h is 2.5 days")
    assertEquals("just now", relativeLabel(now.toString(), now))
    assertEquals("5m ago", relativeLabel(at(Duration.ofMinutes(-5)), now))
    assertEquals("2d ago", relativeLabel(at(Duration.ofDays(-2)), now))
  }

  @Test
  fun `the tab's line says paused, listening or when it runs next`() {
    val next: String = now.plus(Duration.ofMinutes(30)).toString()
    val every = ScheduledTaskSchedule.Interval(everyMs = 1_800_000)
    val hook = ScheduledTaskSchedule.Webhook(signature = null)

    assertEquals("Every 30 min · Next run in 30m", scheduledTaskStatusLine(task(every, next), now))
    assertEquals(
      "Every 30 min · Paused",
      scheduledTaskStatusLine(task(every, next, enabled = false), now),
    )
    assertEquals("Every 30 min · Not scheduled", scheduledTaskStatusLine(task(every), now))
    assertEquals("On webhook · Listening", scheduledTaskStatusLine(task(hook), now))
  }

  @Test
  fun `the automations line says next or paused, else only the schedule`() {
    val next: String = now.plus(Duration.ofHours(3)).toString()
    val daily: ScheduledTaskSchedule = fixed()

    assertEquals("Daily at 09:00 · next in 3h", taskScheduleLine(task(daily, next), now))
    assertEquals("Daily at 09:00", taskScheduleLine(task(daily), now))
    assertEquals(
      "Daily at 09:00 · paused",
      taskScheduleLine(task(daily, next, enabled = false), now),
    )
  }

  @Test
  fun `a webhook task can't run now`() {
    assertFalse(task(ScheduledTaskSchedule.Webhook(signature = null)).canRunNow)
    assertTrue(task(fixed()).canRunNow)
  }

  @Test
  fun `the last run's dot follows upstream's colours`() {
    assertEquals(ThreadRelationshipTone.Muted, scheduledTaskStatusTone("never"))
    assertEquals(ThreadRelationshipTone.Info, scheduledTaskStatusTone("running"))
    assertEquals(ThreadRelationshipTone.Success, scheduledTaskStatusTone("succeeded"))
    assertEquals(ThreadRelationshipTone.Destructive, scheduledTaskStatusTone("failed"))
  }

  private fun fixed(vararg days: Long): ScheduledTaskSchedule = ScheduledTaskSchedule.FixedTime(
    timeOfDay = "09:00",
    weekdays = days.toList().takeIf { it.isNotEmpty() },
  )

  private fun task(
    schedule: ScheduledTaskSchedule,
    nextRunAt: String? = null,
    enabled: Boolean = true,
  ): ScheduledTask {
    val base: JsonObject = buildJsonObject {
      put("id", "sched_1")
      put("title", "Triage")
      put("prompt", "Triage new issues.")
      put("enabled", enabled)
      put("schedule", T3Json.encodeToJsonElement(ScheduledTaskSchedule.serializer(), schedule))
      put("projectId", "prj_1")
      put("threadId", null as String?)
      put("workspaceStrategy", buildJsonObject { put("type", "root") })
      put("modelSelection", buildJsonObject { put("model", "gpt-5") })
      put("runtimeMode", "approval-required")
      put("interactionMode", "default")
      put("createdBy", "user")
      put("creationSource", "web")
      put("createdAt", now.toString())
      put("updatedAt", now.toString())
      put("nextRunAt", nextRunAt)
      put("lastRunAt", null as String?)
      put("lastRunStatus", "never")
      put("lastRunError", null as String?)
      put("runCount", 0)
    }
    return T3Json.decodeFromJsonElement(ScheduledTask.serializer(), base)
  }
}
