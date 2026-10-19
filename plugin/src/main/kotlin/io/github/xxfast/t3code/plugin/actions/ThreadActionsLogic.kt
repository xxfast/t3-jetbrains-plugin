package io.github.xxfast.t3code.plugin.actions

import io.github.xxfast.t3code.plugin.ui.READ_ONLY as COMPOSER_READ_ONLY
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.has
import io.github.xxfast.t3code.protocol.pinned

// The rules below port packages/client-runtime/src/operations/commands.ts and
// apps/web/src/components/threadActionMenu.logic.ts at the pinned tag. Actions stay thin.

/** What every `orchestration.dispatchCommand` needs, apps/server/src/auth/RpcAuthorization.ts. */
const val OPERATE: String = "orchestration:operate"

const val NOT_CONNECTED: String = "Not connected"
const val READ_ONLY: String = "Pairing is read only"
const val NO_THREAD: String = "No thread selected"

/** Run statuses `interruptThreadTurn` looks for, commands.ts:787. */
val INTERRUPTIBLE: Set<String> = setOf("preparing", "starting", "running", "waiting")

enum class ThreadActionKind { Stop, Interrupt, Archive, Pin, MarkUnread }

/**
 * Why no command can go out on [connection], or null when one can: [operateBlockReason], the one
 * rule, with the short read-only text a tooltip has room for.
 */
fun blockedBy(connection: ConnectionState): String? = operateBlockReason(connection)
  ?.let { reason -> if (reason == COMPOSER_READ_ONLY) READ_ONLY else reason }

/** Why [kind] can't run on [row] right now, or null when it can. */
fun disabledReason(kind: ThreadActionKind, connection: ConnectionState, row: Thread?): String? {
  blockedBy(connection)?.let { return it }
  if (row == null) return NO_THREAD
  return when (kind) {
    // The shell row has no provider runtime, so Stop finds out from the thread itself
    ThreadActionKind.Stop, ThreadActionKind.Pin -> null
    ThreadActionKind.Interrupt -> if (row.interruptible) null else "Nothing running"
    // threadActionMenu.logic.ts: archive rejects a thread with an attached provider
    ThreadActionKind.Archive ->
      if (row.archived || row.canArchive) null else "Interrupt the run before archiving"
    // Orchestrator.ts:2815 rejects a mark-unread without a completed run
    ThreadActionKind.MarkUnread ->
      if (row.latestRunCompletedAt != null) null else "No completed run yet"
  }
}

/** A run in flight that Interrupt has a chance of finding. */
val Thread.interruptible: Boolean get() = activeRunId != null || status in INTERRUPTIBLE

/**
 * `threadRuntimeCanArchive`, packages/client-runtime/src/state/models.ts:66. The shell row's
 * `status` and `activeRunId` stand in for upstream's runtime summary.
 */
val Thread.canArchive: Boolean
  get() = if (status == "queued") activeRunId == null else status !in ARCHIVE_BLOCKING

private val ARCHIVE_BLOCKING: Set<String> = setOf("preparing", "starting", "running")

/** Upstream's labels, threadActionMenu.logic.ts. */
fun archiveText(row: Thread?): String =
  if (row?.archived == true) "Unarchive thread" else "Archive thread"

fun pinText(row: Thread?): String = if (row?.pinned == true) "Unpin thread" else "Pin thread"

/** The newest run in flight, what `interruptThreadTurn` picks with `findLast`, commands.ts:787. */
fun activeRun(view: ThreadView): Run? = view.runs.lastOrNull { it.status in INTERRUPTIBLE }

/**
 * `interruptThreadTurn`, commands.ts:769 to 833: `run.interrupt` on [activeRun], holding the
 * queue. Null when nothing is in flight. Upstream then falls back to a run with pending
 * background work, and failing that unwatches the thread's pull requests; neither is ported.
 */
fun interruptCommand(view: ThreadView, commandId: String): Command.RunInterrupt? =
  activeRun(view)?.let { run ->
    Command.RunInterrupt(
      commandId = commandId,
      threadId = view.thread.id,
      runId = run.id,
      holdQueue = true,
    )
  }

/**
 * `stopThreadSession`, commands.ts:920 to 935: one `provider-session.detach` per attached
 * session, each with upstream's derived command id. `thread.stop` is server-internal; a client
 * dispatching it is rejected.
 */
fun stopCommands(view: ThreadView, commandId: String): List<Command.ProviderSessionDetach> =
  view.providerSessions.map { session ->
    Command.ProviderSessionDetach(
      commandId = "$commandId:detach:${session.id}",
      threadId = view.thread.id,
      providerSessionId = session.id,
      reason = "client-requested",
    )
  }

/** `archiveThread` or `unarchiveThread`, by where [row] is now. */
fun archiveCommand(row: Thread, commandId: String): Command =
  if (row.archived) {
    Command.ThreadUnarchive(commandId = commandId, threadId = row.id)
  } else {
    Command.ThreadArchive(commandId = commandId, threadId = row.id)
  }

/** `pinThread` without an order key, or `unpinThread`. */
fun pinCommand(row: Thread, commandId: String): Command =
  if (row.pinned) {
    Command.ThreadUnpin(commandId = commandId, threadId = row.id)
  } else {
    Command.ThreadPin(commandId = commandId, threadId = row.id)
  }
