package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.BackgroundTask
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.Monitor
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.PendingBackgroundTaskCommand
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask.PendingBackgroundTaskSubagent as Subagent
import java.nio.file.Path
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
  // The harness scenario is the fixture: a settled run waiting on two commands and a subagent
  private val view: ThreadView = run {
    val path: Path = Path.of(System.getProperty("t3code.scenarios"))
      .resolve("default")
      .resolve("background-work.json")
    val scenario: Scenario = Scenario.decode(path.readText())
    ThreadView(
      thread = scenario.threads.single(),
      runs = scenario.runs.getValue(THREAD),
      items = scenario.items.getValue(THREAD).map { it.item },
      providerSessions = scenario.providerSessions.getValue(THREAD),
    )
  }

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
  }
}
