package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.FixtureItem
import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputAttachmentAnswerPayload
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupStage
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class MessagesTimelineLogicTest {
  @Test
  fun `checkpoints never become entries, todo lists are work rows`() {
    val entries: List<TimelineEntry> = deriveTimelineEntries(
      listOf(
        user("u", "r1", "10:00"),
        todo("todo", "r1", "10:01", "completed" to "Read", "running" to "Write"),
        item(
          """{"type":"checkpoint","checkpointId":"c","scopeId":"s","files":[]}""",
          "cp", "r1", "10:02",
        ),
        assistant("a", "r1", "10:03"),
      ),
    )
    assertEquals(listOf("u", "todo", "a"), entries.map { it.id })
    assertEquals("Tasks 1/2 · Write", assertIs<TimelineEntry.Work>(entries[1]).entry.label)
  }

  @Test
  fun `a todo list is labelled by what's done and the running step, else the next pending one`() {
    fun label(vararg steps: Pair<String, String>): String =
      projectedWorkEntry(todo("todo", "r1", "10:00", *steps)).label

    assertEquals(
      "Tasks 1/3 · Second",
      label("completed" to "First", "running" to "Second", "pending" to "Third"),
    )
    assertEquals(
      "Tasks 1/3 · Second",
      label("pending" to "Third", "completed" to "First", "running" to "Second"),
    )
    assertEquals("Tasks 1/3 · B", label("completed" to "A", "pending" to "B", "pending" to "C"))
    assertEquals("Tasks 2/2", label("completed" to "A", "completed" to "B"))
    assertEquals("Tasks 0/0", label())
  }

  @Test
  fun `every todo update keeps its own row, nothing picks one plan for the thread`() {
    val entries: List<TimelineEntry> = deriveTimelineEntries(
      listOf(
        user("u", "r1", "10:00"),
        todo("todo-1", "r1", "10:01", "running" to "Read", "pending" to "Write"),
        command("c1", "r1", "10:02"),
        todo("todo-2", "r1", "10:03", "completed" to "Read", "running" to "Write"),
      ),
    )
    val tasks: List<WorkLogEntry> = entries
      .filterIsInstance<TimelineEntry.Work>()
      .map { it.entry }
      .filter { it.item is TurnItem.TodoList }
    assertEquals(listOf("todo-1", "todo-2"), tasks.map { it.id })
    assertEquals(listOf("Tasks 0/2 · Read", "Tasks 1/2 · Write"), tasks.map { it.label })
  }

  @Test
  fun `a todo list opens to its steps, ticked, running and to do`() {
    val item: TurnItem.TodoList = todo(
      "todo", "r1", "10:00", "completed" to "Read", "running" to "Write", "pending" to "Ship",
    )
    assertEquals("✓ Read\n◉ Write\n○ Ship", todoListSteps(item))
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
  fun `a pending approval stays out of its settled turn's fold`() {
    val items: List<TurnItem> = listOf(
      user("u", "r1", "10:00"),
      command("c1", "r1", "10:00:05"),
      approval("p1", "r1", "10:00:06"),
      command("c2", "r1", "10:00:15"),
      assistant("a1", "r1", "10:00:24"),
    )
    val runs: List<Run> = listOf(run("r1", 1, started = "10:00", completed = "10:00:24"))

    val pending: List<TimelineRow> = rows(items, runs, pendingApprovalRequestIds = setOf("req-p1"))
    assertEquals(listOf("u", "turn-fold:r1", "p1", "a1"), pending.map { it.id })
    val approvalRow: TimelineRow.Work = assertIs(pending[2])
    assertIs<TurnItem.ApprovalRequest>(approvalRow.entries.single().item)
    assertEquals(listOf("u", "turn-fold:r1", "a1"), rows(items, runs).map { it.id })
  }

  @Test
  fun `a pending approval never joins a tool group, it splits the groups around it`() {
    val items: List<TurnItem> = listOf(
      user("u", "r1", "10:00"),
      command("c1", "r1", "10:00:05"),
      command("c2", "r1", "10:00:06"),
      approval("p1", "r1", "10:00:07"),
      command("c3", "r1", "10:00:08"),
      command("c4", "r1", "10:00:09"),
      assistant("a1", "r1", "10:00:10"),
    )
    val runs: List<Run> = listOf(run("r1", 1, started = "10:00", completed = "10:00:10"))
    val expanded = TimelineExpansion(runIds = setOf("r1"))

    val pending: List<TimelineRow> =
      rows(items, runs, expansion = expanded, pendingApprovalRequestIds = setOf("req-p1"))
    assertEquals(
      listOf("u", "turn-fold:r1", "work-toggle:c1", "p1", "work-toggle:c3", "a1"),
      pending.map { it.id },
    )
    assertEquals("Ran 2 commands", assertIs<TimelineRow.WorkToggle>(pending[2]).summary)
    assertEquals("Ran 2 commands", assertIs<TimelineRow.WorkToggle>(pending[4]).summary)

    // A resolved approval groups like any other entry
    val resolved: List<TimelineRow> = rows(
      items.map { if (it.id == "p1") approval("p1", "r1", "10:00:07", "completed") else it },
      runs,
      expansion = expanded,
    )
    assertEquals(listOf("u", "turn-fold:r1", "work-toggle:c1", "a1"), resolved.map { it.id })
  }

  @Test
  fun `a pending approval keeps the activity row from saying the latest tool succeeded`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "waiting")
    val items: List<TurnItem> = listOf(
      user("u", "r1", "10:00"),
      approval("p1", "r1", "10:00:05"),
      command("c1", "r1", "10:00:06"),
    )

    val pending: List<TimelineRow> = rows(
      items,
      listOf(running),
      runningRun = running,
      pendingApprovalRequestIds = setOf("req-p1"),
    )
    assertEquals(listOf("u", "working-indicator-row", "p1", "work-live:c1"), pending.map { it.id })
    assertFalse(assertIs<TimelineRow.WorkLive>(pending[3]).active)

    val live: List<TimelineRow> = rows(
      items.filterNot { it.id == "p1" },
      listOf(running),
      runningRun = running,
    )
    assertEquals(listOf("u", "working-indicator-row", "live-activity-row"), live.map { it.id })
  }

  @Test
  fun `nothing thinks while the run waits on an approval`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "waiting")
    val items: List<TurnItem> = listOf(user("u", "r1", "10:00"), approval("p1", "r1", "10:00:05"))

    val pending: List<TimelineRow> = rows(
      items,
      listOf(running),
      runningRun = running,
      pendingApprovalRequestIds = setOf("req-p1"),
    )

    assertEquals(listOf("u", "working-indicator-row", "p1"), pending.map { it.id })
  }

  @Test
  fun `pending approvals no work row shows are unmatched`() {
    val items: List<TurnItem> = listOf(user("u", "r1", "10:00"), approval("p1", "r1", "10:00:05"))
    val runs: List<Run> = listOf(run("r1", 1, started = "10:00", completed = "10:00:05"))
    val timeline: List<TimelineRow> =
      rows(items, runs, pendingApprovalRequestIds = setOf("req-p1", "req-gone"))
    val approvals: List<PendingApproval> = listOf("req-p1", "req-gone").map { requestId ->
      PendingApproval(requestId, "command", null, null, null, resumable = true)
    }

    val unmatched: List<PendingApproval> = unmatchedPendingApprovals(timeline, approvals)

    assertEquals(listOf("req-gone"), unmatched.map { it.requestId })
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
    val default: Locale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      assertEquals("2.5s", formatDuration(2_450))
    } finally {
      Locale.setDefault(default)
    }
    assertEquals("24s", formatDuration(24_000))
    assertEquals("59s", formatDuration(59_499))
    assertEquals("1m", formatDuration(59_500))
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

  @Test
  fun `a compaction command is a bare compact without attachments`() {
    assertTrue(isCompactionCommandMessage(compact("u", "r1", "10:00", text = " /COMPACT ")))
    assertFalse(isCompactionCommandMessage(compact("u", "r1", "10:00", text = "/compact now")))
    assertFalse(isCompactionCommandMessage(user("u", "r1", "10:00")))
    val attached: TurnItem = item(
      """
        {
          "type": "user_message", "text": "/compact", "messageId": "m", "createdBy": "user",
          "creationSource": "web", "inputIntent": "turn_start",
          "attachments": [
            {"type": "file", "id": "f", "name": "a.txt", "mimeType": "text/plain", "sizeBytes": 1}
          ]
        }
      """,
      "u", "r1", "10:00",
    )
    assertFalse(isCompactionCommandMessage(attached))
  }

  @Test
  fun `idle compactions say why, older servers by who sent them`() {
    assertEquals("onReturn", idleCompactionOf(compact("u", "r1", "10:00", idle = "onReturn")))
    assertEquals(
      "beforeCacheExpires",
      idleCompactionOf(compact("u", "r1", "10:00", createdBy = "agent", source = "server")),
    )
    val agentFromWeb: TurnItem = compact("u", "r1", "10:00", createdBy = "agent", source = "web")
    assertEquals(null, idleCompactionOf(agentFromWeb))
    assertEquals(null, idleCompactionOf(compact("u", "r1", "10:00")))
  }

  @Test
  fun `compaction labels`() {
    fun label(status: String, before: Long? = 120_000, after: Long? = 40_000): String =
      contextCompactionLabel(compaction("c", "r1", "10:00", status, before, after))
    assertEquals("Context compacted 120k → 40k tokens", label("completed"))
    assertEquals("Context compacted", label("completed", before = null))
    assertEquals("Compacting context", label("running"))
    assertEquals("Context compaction failed", label("failed"))
    assertEquals("Context compaction stopped", label("interrupted"))
    assertEquals("Context compaction stopped", label("cancelled"))
    assertEquals(
      CompactionTokenCounts("120k", "40k"),
      contextCompactionTokenCounts(compaction("c", "r1", "10:00", "completed")),
    )
    assertEquals(null, contextCompactionTokenCounts(compaction("c", "r1", "10:00", "running")))
  }

  @Test
  fun `a settled compact turn is only its divider`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(compact("u", "r1", "10:00"), compaction("c", "r1", "10:00:05")),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:20")),
    )
    assertEquals(listOf("c"), rows.map { it.id })
    val divider: TimelineRow.ContextCompaction = assertIs(rows.single())
    assertEquals("Context compacted 120k → 40k tokens", divider.label)
    assertEquals(CompactionTokenCounts("120k", "40k"), divider.tokenCounts)
    assertEquals(null, divider.title)
    assertFalse(divider.active)
  }

  @Test
  fun `a compaction T3 started on its own leads with why`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(
        compact("u", "r1", "10:00", idle = "beforeCacheExpires", createdBy = "agent"),
        compaction("c", "r1", "10:00:05"),
      ),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:20")),
    )
    val divider: TimelineRow.ContextCompaction = assertIs(rows.single())
    assertEquals("Context automatically compacted before cache expiry", divider.title)
  }

  @Test
  fun `a compact turn without its item yet is one pending divider`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val rows: List<TimelineRow> = rows(
      items = listOf(compact("u", "r1", "10:00")),
      runs = listOf(running),
      runningRun = running,
    )
    assertEquals(listOf("compaction:r1"), rows.map { it.id })
    val divider: TimelineRow.ContextCompaction = assertIs(rows.single())
    assertEquals("Compacting context", divider.label)
    assertTrue(divider.active)
  }

  @Test
  fun `a running idle compaction's divider says compacting, with no working row`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val rows: List<TimelineRow> = rows(
      items = listOf(
        compact("u", "r1", "10:00", idle = "beforeCacheExpires"),
        compaction("c", "r1", "10:00:05", status = "running"),
      ),
      runs = listOf(running),
      runningRun = running,
    )
    assertEquals(listOf("c"), rows.map { it.id })
    val divider: TimelineRow.ContextCompaction = assertIs(rows.single())
    assertEquals("Compacting before the cache expires…", divider.label)
    assertTrue(divider.active)
  }

  @Test
  fun `compacting on return owns the working row, then leaves only its divider`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val prompt: TurnItem = compact("u", "r1", "10:00", idle = "onReturn", createdBy = "agent")
    val waiting: List<TimelineRow> =
      rows(items = listOf(prompt), runs = listOf(running), runningRun = running)
    assertEquals(
      listOf(TimelineRow.Working(startedAt = at("10:00"), compacting = true)),
      waiting,
    )
    val compacting: List<TimelineRow> = rows(
      items = listOf(prompt, compaction("c", "r1", "10:00:05", status = "running")),
      runs = listOf(running),
      runningRun = running,
    )
    assertEquals(
      listOf(TimelineRow.Working(startedAt = at("10:00"), compacting = true)),
      compacting,
    )
    // The item finishes before its run settles
    val finished: List<TimelineRow> = rows(
      items = listOf(prompt, compaction("c", "r1", "10:00:12")),
      runs = listOf(running),
      runningRun = running,
    )
    assertEquals(listOf("c"), finished.map { it.id })
    val divider: TimelineRow.ContextCompaction = assertIs(finished.single())
    assertEquals("Context compacted before sending", divider.title)
  }

  @Test
  fun `a failed compaction carries its reason`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(
        compact("u", "r1", "10:00"),
        compaction(
          "c", "r1", "10:00:05", status = "failed", before = null, after = null,
          summary = "  Conversation too long to summarise  ",
        ),
      ),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:10", status = "failed")),
    )
    val divider: TimelineRow.ContextCompaction = assertIs(rows.single())
    assertEquals("Context compaction failed", divider.label)
    assertTrue(divider.failed)
    assertEquals("Conversation too long to summarise", divider.failure)
  }

  @Test
  fun `a stopped compaction's divider says so, without the interrupt divider`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(
        compact("u", "r1", "10:00"),
        compaction("c", "r1", "10:00:05", status = "interrupted"),
        item("""{"type":"run_interrupt_result","message":"Stopped"}""", "i", "r1", "10:00:08"),
      ),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:08", "interrupted")),
    )
    assertEquals(listOf("c"), rows.map { it.id })
    val divider: TimelineRow.ContextCompaction = assertIs(rows[0])
    assertEquals("Context compaction stopped", divider.label)
  }

  @Test
  fun `an answered resume question folds into the compaction it asked for`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val question: String =
      "This session is 2h 5m old and uses 412,000 tokens. Compact it before continuing?"
    val answered: TurnItem = item(
      """
        {
          "type": "user_input_request", "requestId": "q", "questions": [],
          "questionAnswer": {
            "requestId": "q", "answers": {"resume": ["Compact and continue"]},
            "questionTextById": {"resume": "$question"}, "attachmentsByQuestionId": {}
          }
        }
      """,
      "q", "r1", "10:00:02",
    )
    val compacting: List<TimelineRow> = rows(
      items = listOf(
        user("u", "r1", "10:00"),
        answered,
        compaction("c", "r1", "10:00:05", status = "running"),
      ),
      runs = listOf(running),
      runningRun = running,
    )
    assertEquals(
      listOf("u", "working-indicator-row"),
      compacting.map { it.id },
    )
    assertEquals(
      TimelineRow.Working(startedAt = at("10:00:05"), compacting = true),
      compacting[1],
    )
    val settled: List<TimelineRow> = rows(
      items = listOf(user("u", "r1", "10:00"), answered, compaction("c", "r1", "10:00:05")),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:20")),
    )
    assertEquals(listOf("u", "c"), settled.map { it.id })
    assertEquals(
      "Context compacted after 2h 5m idle",
      assertIs<TimelineRow.ContextCompaction>(settled[1]).title,
    )
  }

  @Test
  fun `the resume age needs the compact answer`() {
    fun answer(answers: String, texts: String? = null): UserInputAttachmentAnswerPayload =
      T3Json.decodeFromString(
        UserInputAttachmentAnswerPayload.serializer(),
        """
          {
            "requestId": "q", "answers": $answers, "attachmentsByQuestionId": {}
            ${texts?.let { ", \"questionTextById\": $it" }.orEmpty()}
          }
        """,
      )
    val question = "This session is 45m old and uses 1,200 tokens. Compact it before continuing?"
    val direct: UserInputAttachmentAnswerPayload =
      answer("""{"$question":"Compact and continue"}""")
    assertEquals("45m", claudeResumeCompactionAge(direct))
    assertEquals(
      "45m",
      claudeResumeCompactionAge(
        answer("""{"q1":["Compact and continue"]}""", """{"q1":"$question"}"""),
      ),
    )
    assertEquals(null, claudeResumeCompactionAge(answer("""{"$question":"Don't ask again"}""")))
    assertEquals(null, claudeResumeCompactionAge(answer("""{"other":"Compact and continue"}""")))
  }

  @Test
  fun `the compaction scenario reads as one row per compaction`() {
    val path: Path = Path.of(System.getProperty("t3code.scenarios"))
      .resolve("default")
      .resolve("compaction.json")
    val scenario: Scenario = Scenario.decode(path.readText())
    val thread: String = scenario.threads.single().id
    val runs: List<Run> = scenario.runs.getValue(thread)
    val running: Run = runs.single { it.status == "running" }
    val entries: List<FixtureItem> = scenario.items.getValue(thread)
    fun rowsAfter(replayed: List<FixtureItem>): List<TimelineRow> {
      val items: LinkedHashMap<String, TurnItem> = LinkedHashMap()
      replayed.forEach { items[it.item.id] = it.item }
      return deriveMessagesTimelineRows(
        deriveTimelineEntries(items.values.toList()), runs, running,
      )
    }
    val settled: List<String> =
      listOf("cp-u1", "cp-a1", "cp-c2", "cp-u3", "cp-a3", "cp-c4", "cp-c5", "cp-c6")
    val waiting: List<TimelineRow> = rowsAfter(entries.takeWhile { it.after == 0L })
    assertEquals(settled + "working-indicator-row", waiting.map { it.id })
    assertTrue(assertIs<TimelineRow.Working>(waiting.last()).compacting)
    val labels: List<String> = waiting.filterIsInstance<TimelineRow.ContextCompaction>()
      .map { it.title ?: it.label }
    assertEquals(
      listOf(
        "Context compacted 182.4k → 31.2k tokens",
        "Context automatically compacted before cache expiry",
        "Context compaction stopped",
        "Context compaction failed",
      ),
      labels,
    )
    val done: List<TimelineRow> = rowsAfter(entries)
    assertEquals(settled + "cp-c7", done.map { it.id })
    assertEquals(
      "Context compacted before sending",
      assertIs<TimelineRow.ContextCompaction>(done.last()).title,
    )
  }

  @Test
  fun `consecutive subagents of one provider turn are one row`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val rows: List<TimelineRow> = rows(
      items = listOf(
        user("u", "r1", "10:00"),
        subagent("s1", "r1", "10:01", turn = "p1"),
        subagent("s2", "r1", "10:02", turn = "p1"),
        subagent("s3", "r1", "10:03", turn = "p2"),
        assistant("a", "r1", "10:04"),
        subagent("s4", "r1", "10:05", turn = "p2"),
      ),
      runs = listOf(running),
      runningRun = running,
    )
    val events: List<TimelineRow.Event> = rows.filterIsInstance<TimelineRow.Event>()
    assertEquals(listOf("s1", "s3", "s4"), events.map { it.id })
    assertEquals(listOf("s1", "s2"), events[0].subagents?.map { it.id })
    // Another provider turn, or anything in between, starts a new row
    assertEquals(null, events[1].subagents)
    assertEquals(null, events[2].subagents)
  }

  @Test
  fun `a delegate call goes once its task id names a child of the run`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    fun ids(vararg extra: TurnItem): List<String> = rows(
      items = listOf(user("u", "r1", "10:00"), *extra, subagent("s", "r1", "10:05", sub = "task")),
      runs = listOf(running),
      runningRun = running,
    ).flatMap { row -> (row as? TimelineRow.Work)?.entries?.map { it.id } ?: listOf(row.id) }
    val matched: TurnItem = delegate("d", """{"structuredContent":{"taskId":"task"}}""")
    assertFalse("d" in ids(matched))
    // Wrapped in a JSON string inside a content block, as some agents send it
    val wrapped: TurnItem =
      delegate("d", """{"content":[{"type":"text","text":"{\"taskId\":\"task\"}"}]}""")
    assertFalse("d" in ids(wrapped))
    assertTrue("d" in ids(delegate("d", """{"taskId":"another"}""")))
    assertTrue("d" in ids(delegate("d", """{"isError":true,"taskId":"task"}""")))
    assertTrue("d" in ids(delegate("d", """{"taskId":"task"}""", status = "failed")))
    assertTrue("d" in ids(delegate("d", """{"taskId":"task"}""", tool = "delegate")))
  }

  @Test
  fun `knows the delegate tool however the agent prefixes it`() {
    listOf(
      "mcp__t3-code__delegate_task",
      "t3-code.delegate_task",
      "t3_code·delegate_task",
      "t3code__delegate_task",
      "delegate_task",
      "t3-code-thr_1_delegate_task",
      "mcp__t3-code__delegate_task completed",
    ).forEach { name -> assertEquals("delegate_task", resolveT3McpToolName(name), name) }
    assertEquals(null, resolveT3McpToolName("mcp__other__delegate_task"))
    assertEquals(null, resolveT3McpToolName("Task"))
  }

  @Test
  fun `a subagent still working keeps its turn's cards out of the fold`() {
    val rows: List<TimelineRow> = rows(
      items = listOf(
        user("u", "r1", "10:00"),
        command("c", "r1", "10:00:05"),
        subagent("s1", "r1", "10:00:06", turn = "p1", status = "running"),
        subagent("s2", "r1", "10:00:07", turn = "p1"),
        subagent("s3", "r1", "10:00:08", turn = "p2"),
        assistant("a", "r1", "10:00:10"),
      ),
      runs = listOf(run("r1", 1, started = "10:00", completed = "10:00:10")),
    )
    assertEquals(listOf("u", "turn-fold:r1", "s1", "a"), rows.map { it.id })
    assertEquals(listOf("s1", "s2"), assertIs<TimelineRow.Event>(rows[2]).subagents?.map { it.id })
  }

  private fun subagent(
    id: String,
    runId: String,
    time: String,
    turn: String = "p1",
    sub: String = "sub-$id",
    status: String = "completed",
  ): TurnItem = item(
    """
      {
        "type": "subagent", "providerTurnId": "$turn", "status": "$status",
        "subagentId": "$sub", "origin": "app_owned", "driver": "codex",
        "providerInstanceId": "codex", "childThreadId": "child-$id", "prompt": "Look",
        "result": null
      }
    """,
    id, runId, time,
  )

  private fun delegate(
    id: String,
    output: String,
    status: String = "completed",
    tool: String = "mcp__t3-code__delegate_task",
  ): TurnItem = item(
    """{"type":"dynamic_tool","toolName":"$tool","input":{},"output":$output,"status":"$status"}""",
    id, "r1", "10:01",
  )

  private fun rows(
    items: List<TurnItem>,
    runs: List<Run>,
    runningRun: Run? = null,
    expansion: TimelineExpansion = TimelineExpansion(),
    pendingApprovalRequestIds: Set<String> = emptySet(),
  ): List<TimelineRow> = deriveMessagesTimelineRows(
    timelineEntries = deriveTimelineEntries(items),
    runs = runs,
    runningRun = runningRun,
    expansion = expansion,
    pendingApprovalRequestIds = pendingApprovalRequestIds,
  )

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

  /** A `todo_list` of plan `p` with a step per status and text pair. */
  private fun todo(
    id: String,
    runId: String,
    time: String,
    vararg steps: Pair<String, String>,
  ): TurnItem.TodoList = item(
    """
      {
        "type": "todo_list", "planId": "p", "status": "running",
        "steps": [${steps.mapIndexed { index, (status, text) ->
          """{"id":"s$index","text":"$text","status":"$status"}"""
        }.joinToString(",")}]
      }
    """,
    id, runId, time,
  )

  private fun compact(
    id: String,
    runId: String,
    time: String,
    text: String = "/compact",
    idle: String? = null,
    createdBy: String = "user",
    source: String = if (createdBy == "agent") "server" else "web",
  ): TurnItem = item(
    """
      {
        "type": "user_message", "text": "$text", "messageId": "m-$id", "createdBy": "$createdBy",
        "creationSource": "$source", "inputIntent": "turn_start", "attachments": []
        ${idle?.let { ", \"idleCompaction\": \"$it\"" }.orEmpty()}
      }
    """,
    id, runId, time,
  )

  private fun compaction(
    id: String,
    runId: String,
    time: String,
    status: String = "completed",
    before: Long? = 120_000,
    after: Long? = 40_000,
    summary: String? = null,
  ): TurnItem.Compaction = item(
    """
      {
        "type": "compaction", "driver": "claude", "status": "$status"
        ${before?.let { ", \"beforeTokenCount\": $it" }.orEmpty()}
        ${after?.let { ", \"afterTokenCount\": $it" }.orEmpty()}
        ${summary?.let { ", \"summary\": \"$it\"" }.orEmpty()}
      }
    """,
    id, runId, time,
  )

  private fun assistant(id: String, runId: String, time: String): TurnItem = item(
    """{"type":"assistant_message","text":"done","messageId":"m-$id","streaming":false}""",
    id, runId, time,
  )

  /** An `approval_request` for request `req-<id>`. */
  private fun approval(id: String, runId: String, time: String, status: String = "waiting") =
    item<TurnItem>(
      """{"type":"approval_request","requestId":"req-$id","requestKind":"command",
        |"prompt":"./gradlew check","status":"$status"}""".trimMargin(),
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

  @Test
  fun `a running setup takes the working slot after the first prompt, nothing thinks yet`() {
    val preparing: Run = run("r1", 1, started = "10:00", completed = null, status = "preparing")
    val rows: List<TimelineRow> = deriveMessagesTimelineRows(
      timelineEntries = deriveTimelineEntries(listOf(user("u", "r1", "10:00"))),
      runs = listOf(preparing),
      runningRun = preparing,
      worktreeSetup = setup("running", agent = "pending"),
    )
    assertEquals(listOf("u", "working-indicator-row", "worktree-setup-row"), rows.map { it.id })
    assertFalse(assertIs<TimelineRow.WorktreeSetup>(rows[2]).embedded)
  }

  @Test
  fun `once the agent runs the turn, the setup folds in after the prompt`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val rows: List<TimelineRow> = deriveMessagesTimelineRows(
      timelineEntries = deriveTimelineEntries(listOf(user("u", "r1", "10:00"))),
      runs = listOf(running),
      runningRun = running,
      worktreeSetup = setup("done", agent = "done"),
    )
    // The handed-off card no longer owns the working slot, so it sits right under the prompt
    assertEquals(
      listOf("u", "worktree-setup-row", "working-indicator-row", "thinking-row"),
      rows.map { it.id },
    )
    assertTrue(assertIs<TimelineRow.WorktreeSetup>(rows[1]).embedded)
  }

  @Test
  fun `a setup still running after the handoff isn't a row`() {
    val running: Run = run("r1", 1, started = "10:00", completed = null, status = "running")
    val rows: List<TimelineRow> = deriveMessagesTimelineRows(
      timelineEntries = deriveTimelineEntries(listOf(user("u", "r1", "10:00"))),
      runs = listOf(running),
      runningRun = running,
      worktreeSetup = setup("running", agent = "done"),
    )
    assertEquals(listOf("u", "working-indicator-row", "thinking-row"), rows.map { it.id })
  }

  @Test
  fun `a failed setup sits after the prompt without a working row`() {
    val rows: List<TimelineRow> = deriveMessagesTimelineRows(
      timelineEntries = deriveTimelineEntries(listOf(user("u", "r1", "10:00"))),
      runs = emptyList(),
      runningRun = null,
      worktreeSetup = setup("failed", agent = "pending"),
    )
    assertEquals(listOf("u", "worktree-setup-row"), rows.map { it.id })
  }

  private fun setup(phase: String, agent: String): WorktreeSetupSnapshot = WorktreeSetupSnapshot(
    threadId = "t",
    phase = phase,
    startedAt = at("10:00"),
    endedAt = null,
    branch = null,
    baseRef = "main",
    worktreePath = null,
    setupScript = null,
    stages = listOf(WorktreeSetupStage("agent", agent, null, null, null, null, emptyList())),
    error = null,
    sequence = 1,
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
