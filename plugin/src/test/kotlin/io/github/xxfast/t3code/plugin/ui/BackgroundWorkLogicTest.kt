package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.BackgroundTask
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.Monitor
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.PendingBackgroundTaskCommand
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.PendingBackgroundTaskSubagent as Subagent
import io.github.xxfast.t3code.protocol.generated.ProviderRef
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class BackgroundWorkLogicTest {
  // The harness scenario is the fixture: a settled run waiting on two commands and a subagent,
  // and another waiting on one subagent whose progress moves on
  private val scenario: Scenario = run {
    val path: Path = Path.of(System.getProperty("t3code.scenarios"))
      .resolve("default")
      .resolve("background-work.json")
    Scenario.decode(path.readText())
  }

  private val view: ThreadView = view(THREAD)

  private fun view(id: String): ThreadView = ThreadView(
    thread = scenario.threads.single { it.id == id },
    runs = scenario.runs.getValue(id),
    // Each item as it stands once the replay is over
    items = scenario.items.getValue(id).map { it.item }.associateBy { it.id }.values.toList(),
    providerSessions = scenario.providerSessions[id].orEmpty(),
  )

  @Test
  fun `the scenario waits on a subagent and two commands, subagent first`() {
    val shown: PendingBackgroundWorkPresentation =
      assertNotNull(presentPendingBackgroundWork(view.thread.pendingBackgroundTasks.orEmpty()))
    assertEquals("Waiting on 1 subagent and 2 commands", shown.title)
    assertTrue(shown.waiting)
    assertEquals(listOf("subagent", "command", "command"), shown.items.map { it.kind })
    val reviewer: PendingBackgroundWorkItem = shown.items.first()
    assertEquals("Code Reviewer", reviewer.label)
    assertEquals("thr_01JC5L1S0RUNAPP1", reviewer.childThreadId)
    assertEquals("Wait for CI on the pushed commit to finish", shown.items[1].label)
    assertNull(shown.items[1].childThreadId)
  }

  @Test
  fun `nothing without tasks`() {
    assertNull(presentPendingBackgroundWork(emptyList()))
  }

  @Test
  fun `one command runs by its description, or as a command`() {
    assertEquals(
      "Running: ./gradlew check",
      presentPendingBackgroundWork(listOf(command("./gradlew check")))?.title,
    )
    assertEquals("Running a command", presentPendingBackgroundWork(listOf(command(null)))?.title)
    assertEquals("Running a command", presentPendingBackgroundWork(listOf(command("  ")))?.title)
    assertFalse(presentPendingBackgroundWork(listOf(command(null)))!!.waiting)
  }

  @Test
  fun `one subagent is waited on, named through its display title`() {
    assertEquals(
      "Waiting on subagent Code Reviewer",
      presentPendingBackgroundWork(listOf(subagent("Subagent: /root/review/code_reviewer")))?.title,
    )
    assertEquals(
      "Waiting on a subagent",
      presentPendingBackgroundWork(listOf(subagent(null)))?.title,
    )
  }

  @Test
  fun `one named monitor is its description alone`() {
    assertEquals(
      "Watching pull request #142",
      presentPendingBackgroundWork(listOf(monitor("Watching pull request #142")))?.title,
    )
    assertEquals("Waiting on a monitor", presentPendingBackgroundWork(listOf(monitor(null)))?.title)
  }

  @Test
  fun `several are counted per kind in kind order`() {
    assertEquals(
      "Running 2 commands",
      presentPendingBackgroundWork(listOf(command("a"), command("b")))?.title,
    )
    assertEquals(
      "Waiting on 1 subagent, 1 command and 1 background task",
      presentPendingBackgroundWork(listOf(task("x"), command("a"), subagent("s")))?.title,
    )
  }

  @Test
  fun `a kind this build doesn't know reads as a background task`() {
    val unknown = PendingBackgroundTask.Unknown(
      taskId = "t",
      description = null,
      parentTaskId = null,
      awaited = null,
      kind = "scheduled",
      raw = JsonObject(emptyMap()),
    )
    val shown: PendingBackgroundWorkPresentation =
      assertNotNull(presentPendingBackgroundWork(listOf(unknown)))
    assertEquals("Waiting on a background task", shown.title)
    assertEquals("background task", shown.items.single().label)
  }

  @Test
  fun `the banner's tasks are the shell's, and none while a run is going`() {
    assertEquals(3, activeBackgroundTasks(view, working = false).size)
    assertTrue(activeBackgroundTasks(view, working = true).isEmpty())
    assertTrue(activeBackgroundTasks(null, working = false).isEmpty())
  }

  @Test
  fun `the timeline has a footer while the settled thread waits, none while a run is going`() {
    fun footer(working: Boolean): ThreadNowItem? = work(activeBackgroundTasks(view, working))
    val line: ThreadNowItem = assertNotNull(footer(working = false))
    assertTrue(hasTimelineFooter(work = line))
    assertEquals("background-work:$THREAD", line.id)
    assertEquals("Waiting on 1 subagent and 2 commands", line.title.text)
    assertNull(footer(working = true))
    assertFalse(hasTimelineFooter(work = footer(working = true)))
    assertFalse(hasTimelineFooter())
  }

  @Test
  fun `several take the title and the newest subagent's activity`() {
    val tasks: List<PendingBackgroundTask> = activeBackgroundTasks(view, working = false)
    val line: ThreadNowItem = assertNotNull(work(tasks, view.items))
    assertEquals("Waiting on 1 subagent and 2 commands", line.title.text)
    assertEquals(
      "Code Reviewer: Reading the retry logic in FileCodecTest.kt",
      line.description?.text,
    )
    // Not all commands, so the icon is the dashed circle
    assertFalse(rows(tasks, view.items).all { it.isCommand })
  }

  @Test
  fun `several without activity list their labels, monospace when all commands`() {
    val tasks: List<PendingBackgroundTask> =
      listOf(command("./gradlew check"), command("./gradlew detekt"))
    val line: ThreadNowItem = assertNotNull(work(tasks))
    assertEquals("Running 2 commands", line.title.text)
    val description: AnnotatedString = assertNotNull(line.description)
    assertEquals("./gradlew check, ./gradlew detekt", description.text)
    assertEquals(FontFamily.Monospace, description.spanStyles.single().item.fontFamily)

    val mixed: ThreadNowItem =
      assertNotNull(work(listOf(command("./gradlew check"), subagent("Explore"))))
    assertEquals("Explore, ./gradlew check", mixed.description?.text)
    assertTrue(mixed.description!!.spanStyles.isEmpty())
  }

  @Test
  fun `several show the newest subagent with activity, not the newest item`() {
    val tasks: List<PendingBackgroundTask> = listOf(
      Subagent(taskId = "a", description = "Explore"),
      Subagent(taskId = "b", description = "Review"),
      Subagent(taskId = "c", description = "Quiet"),
    )
    val items: List<TurnItem> = listOf(
      subagentItem("a", progress = "older", updatedAt = "2026-10-24T09:00:00.000Z"),
      subagentItem("b", progress = "  newer\n  one ", updatedAt = "2026-10-24T09:01:00.000Z"),
      subagentItem("c", progress = " ", updatedAt = "2026-10-24T09:02:00.000Z"),
    )
    assertEquals("Review: newer one", work(tasks, items)?.description?.text)
  }

  @Test
  fun `one command runs by its label in monospace, timed from its command item`() {
    val tasks: List<PendingBackgroundTask> = listOf(
      PendingBackgroundTaskCommand(taskId = "task_01JC6B1K2CI1", description = "./gradlew check"),
    )
    val line: ThreadNowItem = assertNotNull(work(tasks, view.items))
    assertEquals("Running ./gradlew check", line.title.text)
    val mono: AnnotatedString.Range<SpanStyle> = line.title.spanStyles.single()
    assertEquals(FontFamily.Monospace, mono.item.fontFamily)
    assertEquals("./gradlew check", line.title.text.substring(mono.start, mono.end))
    assertNull(line.description)
    val row: BackgroundWorkRow = rows(tasks, view.items).single()
    assertTrue(row.isCommand)
    assertEquals(Instant.parse("2026-10-24T08:40:12.000Z").toEpochMilli(), row.startedAtMs)
    // Progress and updatedAt only come from a subagent item
    assertNull(row.activity)
    assertEquals(0, row.updatedAtMs)
    assertNull(row.childThreadId)
  }

  @Test
  fun `one subagent waits on its label with its progress and its item's child thread`() {
    val single: ThreadView = view(SUBAGENT_THREAD)
    val tasks: List<PendingBackgroundTask> = activeBackgroundTasks(single, working = false)
    val line: ThreadNowItem = assertNotNull(work(tasks, single.items))
    assertEquals("Waiting on Write the migration guide", line.title.text)
    assertTrue(line.title.spanStyles.isEmpty())
    assertEquals("Checking the snippets compile against 1.0", line.description?.text)
    val row: BackgroundWorkRow = rows(tasks, single.items).single()
    assertFalse(row.isCommand)
    assertEquals(Instant.parse("2026-10-24T09:00:08.000Z").toEpochMilli(), row.startedAtMs)
    // The task has no child thread; the subagent item does, so Open has one to open
    assertNull((tasks.single() as Subagent).childThreadId)
    assertEquals("thr_01JC5L1S1RUNCLD1", row.childThreadId)
  }

  @Test
  fun `the replay's progress collapses its whitespace`() {
    val tasks: List<PendingBackgroundTask> =
      activeBackgroundTasks(view(SUBAGENT_THREAD), working = false)
    val replayed: List<TurnItem> = scenario.items.getValue(SUBAGENT_THREAD).map { it.item }
      .filter { it.id == "bs-s1" }
    val activities: List<String?> =
      replayed.map { item -> rows(tasks, listOf(item)).single().activity }
    assertEquals(
      listOf(
        "Reading the 0.9 to 1.0 diff",
        "Listing the breaking changes in `KStore` and `Codec`",
        "Drafting the Codec section with before and after snippets",
        "Checking the snippets compile against 1.0",
      ),
      activities,
    )
  }

  @Test
  fun `an empty native id matches on the item id, the last match wins`() {
    val tasks: List<PendingBackgroundTask> = listOf(Subagent(taskId = "item-1", description = "x"))
    val items: List<TurnItem> = listOf(
      subagentItem(nativeId = "", progress = "first", id = "item-1"),
      subagentItem(nativeId = "other", progress = "not this one", id = "item-2"),
      subagentItem(nativeId = "", progress = "second", id = "item-1"),
    )
    assertEquals("second", rows(tasks, items).single().activity)
    // A non-empty native id is the key, not the id
    val native: List<TurnItem> = listOf(subagentItem("native", progress = "p", id = "item-1"))
    assertNull(rows(tasks, native).single().activity)
  }

  @Test
  fun `a task's own child thread wins over its item's`() {
    val tasks: List<PendingBackgroundTask> =
      listOf(Subagent(taskId = "a", description = "x", childThreadId = "thr_task"))
    val items: List<TurnItem> = listOf(subagentItem("a", progress = null, child = "thr_item"))
    assertEquals("thr_task", rows(tasks, items).single().childThreadId)
  }

  @Test
  fun `one task without an item has no timer`() {
    assertNull(rows(listOf(command("./gradlew check")), emptyList()).single().startedAtMs)
  }

  @Test
  fun `stop is thread stop when the server has it`() {
    val command: Command? = stopBackgroundWorkCommand(view, server(THREAD_STOP), "cmd")
    val stop: Command.ThreadStop = assertIs(command)
    assertEquals(THREAD, stop.threadId)
    assertEquals("cmd", stop.commandId)
  }

  @Test
  fun `stop interrupts the latest run on an older server while work is pending`() {
    val command: Command? = stopBackgroundWorkCommand(view, server(), "cmd")
    val interrupt: Command.RunInterrupt = assertIs(command)
    assertEquals(RUN, interrupt.runId)
    assertNull(interrupt.holdQueue)
    // Not yet known is unsupported too, as upstream reads a missing config
    assertIs<Command.RunInterrupt>(stopBackgroundWorkCommand(view, null, "cmd"))
  }

  @Test
  fun `stop has nothing to send once the work is gone`() {
    val settled: ThreadView = view.copy(thread = view.thread.copy(pendingBackgroundTasks = null))
    assertNull(stopBackgroundWorkCommand(settled, server(), "cmd"))
  }

  private fun work(
    tasks: List<PendingBackgroundTask>,
    turnItems: List<TurnItem> = emptyList(),
    onOpenThread: (String) -> Unit = {},
  ): ThreadNowItem? = backgroundWorkNowItem(
    threadId = THREAD,
    tasks = tasks,
    turnItems = turnItems,
    stopDisabled = false,
    stopping = false,
    onStop = {},
    onOpenThread = onOpenThread,
  )

  private fun rows(
    tasks: List<PendingBackgroundTask>,
    items: List<TurnItem>,
  ): List<BackgroundWorkRow> =
    backgroundWorkRows(assertNotNull(presentPendingBackgroundWork(tasks)).items, items)

  private fun subagentItem(
    nativeId: String,
    progress: String?,
    updatedAt: String = "2026-10-24T09:00:00.000Z",
    id: String = "item-$nativeId",
    child: String? = null,
  ): TurnItem = TurnItem.TurnItemSubagent(
    id = id,
    threadId = THREAD,
    runId = RUN,
    nodeId = null,
    providerThreadId = null,
    providerTurnId = null,
    nativeItemRef = ProviderRef(driver = "claude", nativeId = nativeId, strength = "stable"),
    parentItemId = null,
    ordinal = 1,
    status = "running",
    title = null,
    startedAt = "2026-10-24T08:59:00.000Z",
    completedAt = null,
    updatedAt = updatedAt,
    subagentId = "node-$nativeId",
    origin = "provider_native",
    driver = "claude",
    providerInstanceId = "claude",
    childThreadId = child,
    prompt = "",
    progress = progress,
    result = null,
  )

  private fun server(vararg capabilities: String): ServerInfo =
    ServerInfo(
      providers = emptyList(),
      serverResolvedCommandContext = false,
      capabilities = capabilities.toSet(),
    )

  private fun command(description: String?): PendingBackgroundTask =
    PendingBackgroundTaskCommand(taskId = "c-${description.hashCode()}", description = description)

  private fun subagent(description: String?): PendingBackgroundTask =
    Subagent(taskId = "s-${description.hashCode()}", description = description)

  private fun monitor(description: String?): PendingBackgroundTask =
    Monitor(taskId = "m-${description.hashCode()}", description = description)

  private fun task(description: String?): PendingBackgroundTask =
    BackgroundTask(taskId = "b-${description.hashCode()}", description = description)

  private companion object {
    const val THREAD: String = "thr_01JC6B1K0BGWORK01"
    const val RUN: String = "run_01JC6B1K1DONE"
    const val SUBAGENT_THREAD: String = "thr_01JC6B2S0BGAGENT1"
  }
}
