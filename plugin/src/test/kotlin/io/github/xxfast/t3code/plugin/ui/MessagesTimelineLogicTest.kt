package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class MessagesTimelineLogicTest {
  @Test
  fun `checkpoints and todo lists never become entries`() {
    val entries: List<TimelineEntry> = deriveTimelineEntries(
      listOf(
        user("u", "r1", "10:00"),
        item("""{"type":"todo_list","items":[]}""", "todo", "r1", "10:01"),
        item(
          """{"type":"checkpoint","checkpointId":"c","scopeId":"s","files":[]}""",
          "cp", "r1", "10:02",
        ),
        assistant("a", "r1", "10:03"),
      ),
    )
    assertEquals(listOf("u", "a"), entries.map { it.id })
  }

  @Test
  fun `a settled turn folds its work behind worked for, keeping the last reply`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(
        user("u", "r1", "10:00"),
        command("c1", "r1", "10:00:05"),
        assistant("a1", "r1", "10:00:10"),
        command("c2", "r1", "10:00:15"),
        assistant("a2", "r1", "10:00:24"),
      ),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:24")),
    )
    assertEquals(listOf("u", "turn-fold:r1", "a2"), rows.map { it.id })
    val fold: TimelineRow.TurnFold = assertIs(rows[1])
    assertEquals("Worked for 24s", fold.label)
    assertTrue(assertIs<TimelineRow.Message>(rows[2]).showMeta)
  }

  @Test
  fun `an expanded fold shows the work, grouped by run`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(
        user("u", "r1", "10:00"),
        command("c1", "r1", "10:00:05"),
        command("c2", "r1", "10:00:06"),
        assistant("a1", "r1", "10:00:10"),
      ),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:10")),
      expansion = TimelineExpansion(runIds = setOf("r1")),
    )
    assertEquals(listOf("u", "turn-fold:r1", "work-toggle:c1", "a1"), rows.map { it.id })
    val group: TimelineRow.WorkToggle = assertIs(rows[2])
    assertEquals("Ran 2 commands", group.summary)
  }

  @Test
  fun `a running turn shows working after its prompt and nothing folds`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val rows: List<TimelineRow> = rows(
      items = listOf(user("u", "r1", "10:00"), assistant("a1", "r1", "10:00:03")),
      runs = listOf(running),
      runningRun = running,
    )
    assertEquals(listOf("u", "working-indicator-row", "a1", "thinking-row"), rows.map { it.id })
    assertFalse(assertIs<TimelineRow.Message>(rows[2]).showMeta)
  }

  @Test
  fun `group summaries`() {
    val commands: List<WorkLogEntry> =
      listOf(command("a", "r", "10:00"), command("b", "r", "10:00")).map(::projectedWorkEntry)
    val edit: WorkLogEntry = projectedWorkEntry(
      item("""{"type":"file_change","fileName":"A.kt"}""", "e", "r", "10:00"),
    )
    val thought: WorkLogEntry = projectedWorkEntry(
      item("""{"type":"reasoning","text":"hm","streaming":false}""", "t", "r", "10:00"),
    )
    assertEquals("Ran 2 commands and changed 1 file", summarizeToolGroup(commands + edit).summary)
    assertEquals("Thought (×2)", summarizeToolGroup(listOf(thought, thought)).summary)
  }

  @Test
  fun `command labels drop the shell wrapper`() {
    assertEquals("git status", commandDisplayText("/bin/zsh -lc 'git status'"))
    assertEquals("git status", commandDisplayText("git status"))
    assertEquals("git", commandProgramName("bash -lc \"git pull --ff-only\""))
    val running: WorkLogEntry = projectedWorkEntry(command("c", "r", "10:00", status = "running"))
    assertEquals("Running ls", running.liveLabel(active = true))
  }

  @Test
  fun `durations as upstream formats them`() {
    assertEquals("500ms", formatDuration(500))
    assertEquals("2.5s", formatDuration(2_450))
    assertEquals("24s", formatDuration(24_000))
    assertEquals("1m 12s", formatDuration(72_000))
    assertEquals("1h 1m", formatDuration(3_660_000))
  }

  @Test
  fun `long prompts collapse`() {
    assertFalse(shouldCollapseUserMessage("short"))
    assertTrue(shouldCollapseUserMessage("x".repeat(601)))
    assertTrue(shouldCollapseUserMessage((1..9).joinToString("\n")))
  }

  @Test
  fun `timestamps are day aware`() {
    val now: Instant = Instant.parse("2026-10-20T15:00:00Z")
    val zone = ZoneOffset.UTC
    fun format(at: String): String = formatDayAwareTimestamp(Instant.parse(at), now, zone)
    assertFalse(format("2026-10-20T09:30:00Z").contains("/"))
    assertTrue(format("2026-10-19T09:30:00Z").startsWith("yesterday at"))
    assertTrue(format("2025-01-02T09:30:00Z").startsWith("1/2/2025"))
  }

  private fun rows(
    items: List<TurnItem>,
    runs: List<Run>,
    runningRun: Run? = null,
    expansion: TimelineExpansion = TimelineExpansion(),
  ): List<TimelineRow> =
    deriveMessagesTimelineRows(deriveTimelineEntries(items), runs, runningRun, expansion)

  private fun at(time: String): String =
    "2026-10-19T$time${if (time.count { it == ':' } == 1) ":00" else ""}Z"

  private fun run(
    id: String,
    ordinal: Long,
    started: String,
    completed: String?,
    status: String = "completed",
  ): Run = T3Json.decodeFromString(
    Run.serializer(),
    """
      {
        "id": "$id", "threadId": "t", "ordinal": $ordinal, "status": "$status",
        "providerInstanceId": "codex", "modelSelection": { "model": "gpt-5" },
        "userMessageId": "m", "requestedAt": "${at(started)}", "startedAt": "${at(started)}",
        "completedAt": ${completed?.let { "\"${at(it)}\"" } ?: "null"}
      }
    """,
  )

  private fun user(id: String, runId: String, time: String): TurnItem = item(
    """
      {
        "type": "user_message", "text": "hi", "messageId": "m-$id", "createdBy": "user",
        "creationSource": "web", "inputIntent": "turn_start", "attachments": []
      }
    """,
    id, runId, time,
  )

  private fun assistant(id: String, runId: String, time: String): TurnItem = item(
    """{"type":"assistant_message","text":"done","messageId":"m-$id","streaming":false}""",
    id, runId, time,
  )

  private fun command(
    id: String,
    runId: String,
    time: String,
    status: String = "completed",
  ): TurnItem = item(
    """{"type":"command_execution","input":"ls -la","exitCode":0,"status":"$status"}""",
    id, runId, time,
  )

  /** A turn item from [json] plus the fields every item requires. */
  private inline fun <reified T : TurnItem> item(
    json: String,
    id: String,
    runId: String,
    time: String,
  ): T {
    val fields: JsonObject = T3Json.parseToJsonElement(json).jsonObject
    val common: JsonObject = T3Json.parseToJsonElement(
      """
        {
          "id": "$id", "threadId": "t", "runId": "$runId", "nodeId": null,
          "providerThreadId": null, "providerTurnId": null, "nativeItemRef": null,
          "parentItemId": null, "ordinal": 1, "status": "completed", "title": null,
          "startedAt": "${at(time)}", "completedAt": null, "updatedAt": "${at(time)}"
        }
      """,
    ).jsonObject
    return T3Json.decodeFromJsonElement(TurnItem.serializer(), JsonObject(common + fields)) as T
  }
}
