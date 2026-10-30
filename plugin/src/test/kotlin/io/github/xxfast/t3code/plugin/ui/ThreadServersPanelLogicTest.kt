package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.DiscoveredLocalServer
import io.github.xxfast.t3code.protocol.generated.TerminalClearInput
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupCancelInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `useThreadDiscoveredPorts` and the rules of upstream's ThreadServersPanel.tsx. */
class ThreadServersPanelLogicTest {
  private val terminal: DiscoveredLocalServer =
    server(port = 5173, pid = 11, processName = "node", terminalThread = "thread-1")
  private val agent: DiscoveredLocalServer =
    server(port = 8000, pid = 12, processName = "python3", agentThread = "thread-1")
  private val other: DiscoveredLocalServer =
    server(port = 3000, pid = 13, terminalThread = "thread-2", agentThread = "thread-3")
  private val unowned: DiscoveredLocalServer = server(port = 9000, pid = null)

  @Test
  fun `keeps the servers the thread's terminals or agent started`() {
    val all: List<DiscoveredLocalServer> = listOf(terminal, agent, other, unowned)
    assertEquals(listOf(terminal, agent), useThreadDiscoveredPorts(all, "thread-1"))
    assertEquals(listOf(other), useThreadDiscoveredPorts(all, "thread-2"))
    assertEquals(listOf(other), useThreadDiscoveredPorts(all, "thread-3"))
    assertEquals(emptyList(), useThreadDiscoveredPorts(all, "thread-4"))
    assertEquals(emptyList(), useThreadDiscoveredPorts(all, null))
  }

  @Test
  fun `stoppable needs the scope, the capability and a pid`() {
    assertTrue(stoppable(terminal, canStop = true, supportsStop = true))
    assertFalse(stoppable(terminal, canStop = false, supportsStop = true))
    assertFalse(stoppable(terminal, canStop = true, supportsStop = false))
    assertFalse(stoppable(unowned, canStop = true, supportsStop = true))
  }

  @Test
  fun `the second line says who started it, then the process`() {
    assertEquals("Terminal · node", serverDetail(terminal))
    assertEquals("Agent · python3", serverDetail(agent))
    assertEquals("Terminal", serverDetail(other))
    assertEquals("Agent", serverDetail(server(port = 1, pid = 1, processName = "")))
  }

  @Test
  fun `a server's key is its pid and port`() {
    assertEquals("11:5173", serverKey(terminal))
    assertEquals("null:9000", serverKey(unowned))
  }

  private fun server(
    port: Long,
    pid: Long?,
    processName: String? = null,
    terminalThread: String? = null,
    agentThread: String? = null,
  ): DiscoveredLocalServer = DiscoveredLocalServer(
    host = "127.0.0.1",
    port = port,
    url = "http://localhost:$port/",
    processName = processName,
    pid = pid,
    terminal = terminalThread?.let { TerminalClearInput(threadId = it, terminalId = "default") },
    agent = agentThread?.let { WorktreeSetupCancelInput(threadId = it) },
  )
}
