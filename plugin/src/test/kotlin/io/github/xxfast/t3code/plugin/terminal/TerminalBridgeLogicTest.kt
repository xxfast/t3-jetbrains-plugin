package io.github.xxfast.t3code.plugin.terminal

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TerminalBridgeLogicTest {
  @Test
  fun snapshotResetsThenReplaysHistory() {
    val event = TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ls\r\n$ "))
    assertEquals("\u001bc$ ls\r\n$ ", textOf(event))
    assertTrue(resets(event))
    assertFalse(ends(event))
    assertFalse(exits(event))
  }

  @Test
  fun snapshotOfAnExitedTerminalSaysSo() {
    val event = TerminalAttachStreamEvent.Snapshot(
      snapshot = snapshot(history = "bye", status = "exited"),
    )
    assertEquals("${RESET}bye\r\n[terminal] Process exited\r\n", textOf(event))
    assertTrue(exits(event), "Upstream closes a terminal it finds exited")
  }

  @Test
  fun restartedReplaysLikeASnapshot() {
    val event = TerminalAttachStreamEvent.Restarted(
      threadId = "thread-1",
      terminalId = DEFAULT_TERMINAL_ID,
      snapshot = snapshot(history = "fresh\r\n$ "),
    )
    assertEquals("${RESET}fresh\r\n$ ", textOf(event))
    assertTrue(resets(event))
  }

  @Test
  fun outputIsItsData() {
    val event = TerminalAttachStreamEvent.Output(
      threadId = "thread-1",
      terminalId = DEFAULT_TERMINAL_ID,
      data = "README.md\r\n",
    )
    assertEquals("README.md\r\n", textOf(event))
    assertFalse(resets(event))
  }

  @Test
  fun clearedResetsLikeAnEmptyReplay() {
    val event = TerminalAttachStreamEvent.Cleared(threadId = "thread-1", terminalId = "term-1")
    assertEquals("\u001bc", textOf(event))
    assertFalse(exits(event))
  }

  @Test
  fun exitedSaysTheProcessExited() {
    val event = TerminalAttachStreamEvent.Exited(
      threadId = "thread-1",
      terminalId = DEFAULT_TERMINAL_ID,
      exitCode = 1,
      exitSignal = null,
    )
    assertEquals("\r\n[terminal] Process exited\r\n", textOf(event))
    assertTrue(exits(event))
    assertFalse(ends(event), "The Closed that follows terminal.close ends it")
  }

  @Test
  fun restartedIntoAnExitedShellExits() {
    val event = TerminalAttachStreamEvent.Restarted(
      threadId = "thread-1",
      terminalId = DEFAULT_TERMINAL_ID,
      snapshot = snapshot(history = "", status = "exited"),
    )
    assertTrue(exits(event))
  }

  @Test
  fun closedSaysSoAndEnds() {
    val event = TerminalAttachStreamEvent.Closed(threadId = "thread-1", terminalId = "term-1")
    assertEquals("\r\n[terminal] Terminal closed\r\n", textOf(event))
    assertTrue(ends(event))
  }

  @Test
  fun errorShowsItsMessage() {
    val event = TerminalAttachStreamEvent.Error(
      threadId = "thread-1",
      terminalId = DEFAULT_TERMINAL_ID,
      message = "pty died",
    )
    assertEquals("\r\n[terminal] pty died\r\n", textOf(event))
    assertFalse(ends(event))
  }

  @Test
  fun activityAndUnknownShowNothing() {
    val activity = TerminalAttachStreamEvent.Activity(
      threadId = "thread-1",
      terminalId = DEFAULT_TERMINAL_ID,
      hasRunningSubprocess = true,
      label = "vim",
    )
    val unknown = TerminalAttachStreamEvent.Unknown(
      type = "some-future-event",
      raw = JsonObject(mapOf("type" to JsonPrimitive("some-future-event"))),
    )
    assertEquals("", textOf(activity))
    assertEquals("", textOf(unknown))
  }

  @Test
  fun attachInputUsesTheWorktreeWhenThereIsOne() {
    val input: TerminalAttachInput = attachInput("thread-1", "/wt/feature", "/repo")
    assertEquals(DEFAULT_TERMINAL_ID, input.terminalId)
    assertEquals("/wt/feature", input.cwd)
    assertEquals("/wt/feature", input.worktreePath)
    val env: JsonObject = checkNotNull(input.env)
    assertEquals(JsonPrimitive("/repo"), env["T3CODE_PROJECT_ROOT"])
    assertEquals(JsonPrimitive("/wt/feature"), env["T3CODE_WORKTREE_PATH"])
    assertNull(input.restartIfNotRunning, "The web app never sets it")
    assertNull(input.cols)
  }

  @Test
  fun attachInputFallsBackToTheProjectRoot() {
    val input: TerminalAttachInput = attachInput("thread-1", null, "/repo")
    assertEquals("/repo", input.cwd)
    assertNull(input.worktreePath)
    assertEquals(setOf("T3CODE_PROJECT_ROOT"), checkNotNull(input.env).keys)
  }

  @Test
  fun accessPrefersOperateThenRead() {
    assertEquals(TerminalAccess.OPERATE, accessOf(session(TERMINAL_READ, TERMINAL_OPERATE)))
    assertEquals(TerminalAccess.OPERATE, accessOf(session(TERMINAL_OPERATE)))
    assertEquals(TerminalAccess.READ, accessOf(session(TERMINAL_READ)))
    assertEquals(TerminalAccess.NONE, accessOf(session("orchestration:read")))
  }

  @Test
  fun unavailableSaysWhy() {
    assertEquals("Not connected", unavailableReason(ConnectionState.Disconnected))
    assertEquals("Not connected", unavailableReason(ConnectionState.Unpaired))
    val withoutTerminals = ConnectionState.Connected(session("orchestration:read"))
    assertEquals("Pairing can't use terminals", unavailableReason(withoutTerminals))
    assertNull(unavailableReason(ConnectionState.Connected(session(TERMINAL_READ))))
    assertNull(unavailableReason(ConnectionState.Connected(session(TERMINAL_OPERATE))))
  }
}

internal fun snapshot(history: String, status: String = "running") = TerminalSessionSnapshot(
  threadId = "thread-1",
  terminalId = DEFAULT_TERMINAL_ID,
  cwd = "/repo",
  worktreePath = null,
  status = status,
  pid = 4242,
  history = history,
  exitCode = null,
  exitSignal = null,
  label = "zsh",
  updatedAt = "2026-10-20T00:00:00.000Z",
)

private fun session(vararg permissions: String) = ServerSession(
  authenticated = true,
  auth = ServerAuthDescriptor(
    policy = "desktop-managed-local",
    bootstrapMethods = listOf("desktop-bootstrap"),
    sessionMethods = listOf("bearer-access-token"),
    sessionCookieName = "t3_session_3773",
  ),
  permissions = permissions.toList(),
)
