package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.TerminalMetadataStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalSummary
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SidebarRowDetailsTest {
  // The running demo: on kotlinx-io-0.8 in the project's checkout
  private val running: Scenario = Scenario.decode(
    Path.of(System.getProperty("t3code.scenarios"), "default", "running.json").readText(),
  )
  private val row: Thread = running.threads.single()

  @Test
  fun `the running demo has one terminal running a process`() {
    assertEquals(mapOf(row.id to listOf("default")), runningTerminals(running.terminals))
  }

  @Test
  fun `metadata events upsert and remove, sorted with numbers by value`() {
    val snapshot = TerminalMetadataStreamEvent.Snapshot(
      terminals = listOf(terminal("t", "term-10"), terminal("t", "term-2")),
    )
    val loaded: List<TerminalSummary> = applyTerminalMetadata(emptyList(), snapshot)
    assertEquals(listOf("term-2", "term-10"), loaded.map { it.terminalId })
    val upsert = TerminalMetadataStreamEvent.Upsert(terminal = terminal("t", "term-2", false))
    val upserted: List<TerminalSummary> = applyTerminalMetadata(loaded, upsert)
    assertEquals(mapOf("t" to listOf("term-10")), runningTerminals(upserted))
    val remove = TerminalMetadataStreamEvent.Remove(threadId = "t", terminalId = "term-10")
    assertEquals(listOf("term-2"), applyTerminalMetadata(upserted, remove).map { it.terminalId })
  }

  @Test
  fun `terminal labels follow upstream`() {
    assertEquals("1 terminal process running. Open terminal", terminalButtonLabel(1))
    assertEquals("2 terminal processes running. Open terminal", terminalButtonLabel(2))
  }

  @Test
  fun `a branch mismatch is only in the project's own checkout`() {
    assertEquals(BranchMismatch("kotlinx-io-0.8", "main"), branchMismatch(row, "main"))
    assertNull(branchMismatch(row, "kotlinx-io-0.8"))
    assertNull(branchMismatch(row, null))
    assertNull(branchMismatch(row.copy(worktreePath = "/wt"), "main"))
    assertNull(branchMismatch(row.copy(branch = null), "main"))
  }
}

private fun terminal(thread: String, id: String, running: Boolean = true): TerminalSummary =
  TerminalSummary(
    threadId = thread,
    terminalId = id,
    cwd = "/code",
    worktreePath = null,
    status = "running",
    pid = 1,
    exitCode = null,
    exitSignal = null,
    hasRunningSubprocess = running,
    label = id,
    updatedAt = "2026-10-20T00:00:00Z",
  )
