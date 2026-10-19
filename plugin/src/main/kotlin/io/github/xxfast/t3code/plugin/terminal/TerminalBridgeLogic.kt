package io.github.xxfast.t3code.plugin.terminal

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalSessionSnapshot
import io.github.xxfast.t3code.protocol.has
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Upstream's `DEFAULT_TERMINAL_ID`, packages/contracts/src/terminal.ts. */
const val DEFAULT_TERMINAL_ID: String = "term-1"

/** Scopes from packages/contracts/src/auth.ts. */
const val TERMINAL_READ: String = "terminal:read"
const val TERMINAL_OPERATE: String = "terminal:operate"

/**
 * RIS, a full terminal reset: leaves the alternate screen, drops modes, mouse tracking and SGR,
 * clears the screen and the scrollback. What ghostty's `resetAndWrite` runs before a replay
 * (apps/web/src/terminal/ghostty/core.ts); jediterm answers it with `Terminal.reset(true)`.
 */
const val RESET: String = "\u001bc"

/** The line for a terminal that went away, whether the server said so or the stream just ended. */
val CLOSED_LINE: String = systemMessage("Terminal closed")

/**
 * What [event] puts on screen. Mirrors `writeTerminalOutputUpdate` and `writeSystemMessage` in
 * apps/web/src/components/ThreadTerminalDrawer.tsx, over the buffer rules of
 * `applyTerminalAttachStreamEvent` in packages/client-runtime/src/state/terminalSession.ts: a
 * snapshot or a restart resets the terminal and replays its history, output appends, a clear
 * resets to nothing (`resetOutput` with no data, so a reset too), and exits, closes and errors
 * get a `[terminal]` line.
 */
fun textOf(event: TerminalAttachStreamEvent): String = when (event) {
  is TerminalAttachStreamEvent.Snapshot -> replay(event.snapshot)
  is TerminalAttachStreamEvent.Restarted -> replay(event.snapshot)
  is TerminalAttachStreamEvent.Output -> event.data
  is TerminalAttachStreamEvent.Cleared -> RESET
  is TerminalAttachStreamEvent.Exited -> systemMessage("Process exited")
  is TerminalAttachStreamEvent.Closed -> CLOSED_LINE
  is TerminalAttachStreamEvent.Error -> systemMessage(event.message)
  is TerminalAttachStreamEvent.Activity -> ""
  is TerminalAttachStreamEvent.Unknown -> ""
}

/** Whether [event] is the last one: the server closed the terminal, nothing follows. */
fun ends(event: TerminalAttachStreamEvent): Boolean = event is TerminalAttachStreamEvent.Closed

/** Whether [event] resets the server's side, so the size has to be sent again. */
fun resets(event: TerminalAttachStreamEvent): Boolean =
  event is TerminalAttachStreamEvent.Snapshot || event is TerminalAttachStreamEvent.Restarted

/**
 * Whether [event] says the shell is gone: an exit, or a snapshot of one that already exited, the
 * way `shouldHandleTerminalExit` in `ThreadTerminalDrawer.tsx` sees it. Upstream then closes the
 * terminal (`onSessionExited`, `closeTerminalSession` in
 * apps/web/src/components/terminal/useThreadTerminalActions.ts) so the next open starts fresh.
 */
fun exits(event: TerminalAttachStreamEvent): Boolean = when (event) {
  is TerminalAttachStreamEvent.Exited -> true
  is TerminalAttachStreamEvent.Snapshot -> event.snapshot.status == "exited"
  is TerminalAttachStreamEvent.Restarted -> event.snapshot.status == "exited"
  else -> false
}

private fun replay(snapshot: TerminalSessionSnapshot): String {
  val exited: String = if (snapshot.status == "exited") systemMessage("Process exited") else ""
  return RESET + snapshot.history + exited
}

private fun systemMessage(message: String): String = "\r\n[terminal] $message\r\n"

/**
 * The input the web app attaches a thread's terminal with: no size, that follows the snapshot
 * as a resize. `cwd` and `env` mirror
 * `projectScriptCwd` and `projectScriptRuntimeEnv` in packages/shared/src/projectScripts.ts, the
 * rest `ThreadTerminalDrawer.tsx`. Like the web app it never sets `restartIfNotRunning`.
 */
fun attachInput(
  threadId: String,
  worktreePath: String?,
  workspaceRoot: String,
  terminalId: String = DEFAULT_TERMINAL_ID,
): TerminalAttachInput {
  val env: JsonObject = buildJsonObject {
    put("T3CODE_PROJECT_ROOT", workspaceRoot)
    if (worktreePath != null) put("T3CODE_WORKTREE_PATH", worktreePath)
  }
  return TerminalAttachInput(
    threadId = threadId,
    terminalId = terminalId,
    cwd = worktreePath ?: workspaceRoot,
    worktreePath = worktreePath,
    env = env,
  )
}

enum class TerminalAccess { OPERATE, READ, NONE }

/**
 * Mirrors `useAttachedTerminalSession` in apps/web/src/state/terminalSessions.ts: attach when the
 * session can operate terminals, observe when it can only read them.
 */
fun accessOf(session: ServerSession): TerminalAccess = when {
  session.has(TERMINAL_OPERATE) -> TerminalAccess.OPERATE
  session.has(TERMINAL_READ) -> TerminalAccess.READ
  else -> TerminalAccess.NONE
}

/** Why a thread's terminal can't open over [connection], or null when it can. */
fun unavailableReason(connection: ConnectionState): String? {
  if (connection !is ConnectionState.Connected) return "Not connected"
  if (accessOf(connection.session) == TerminalAccess.NONE) return "Pairing can't use terminals"
  return null
}
