package io.github.xxfast.t3code.plugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.Presentation
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.pinned

/**
 * Stop: upstream's `stopThreadSession` (packages/client-runtime/src/operations/commands.ts:920
 * to 935), a `provider-session.detach` with reason `client-requested` for every provider session
 * attached to the thread. Not `thread.stop`: that's in upstream's internal command union and the
 * server rejects it from a client.
 */
class StopThreadAction : ThreadAction(ThreadActionKind.Stop) {
  override suspend fun perform(target: ThreadTarget) {
    val commands: List<Command.ProviderSessionDetach> =
      stopCommands(target.view(), target.commandId())
    if (commands.isEmpty()) {
      target.inform("No session to stop on ${target.row.title}")
      return
    }
    commands.forEach { command -> target.client.dispatch(command) }
  }
}

/**
 * Interrupt: upstream's `interruptThreadTurn` (commands.ts:769 to 833), `run.interrupt` with
 * `holdQueue` on the newest run that's preparing, starting, running or waiting, so queued
 * messages wait instead of starting.
 */
class InterruptRunAction : ThreadAction(ThreadActionKind.Interrupt) {
  override suspend fun perform(target: ThreadTarget) {
    val command: Command.RunInterrupt? = interruptCommand(target.view(), target.commandId())
    if (command == null) {
      target.inform("Nothing to interrupt on ${target.row.title}")
      return
    }
    target.client.dispatch(command)
  }
}

/** Archive or unarchive: upstream's `archiveThread` and `unarchiveThread`, commands.ts:426. */
class ArchiveThreadAction : ThreadAction(ThreadActionKind.Archive) {
  override fun present(presentation: Presentation, row: Thread?) {
    presentation.text = archiveText(row)
  }

  override suspend fun perform(target: ThreadTarget) {
    target.client.dispatch(archiveCommand(target.row, target.commandId()))
  }
}

/** Pin or unpin: upstream's `pinThread` and `unpinThread`, commands.ts:444 and 493. */
class PinThreadAction : ThreadAction(ThreadActionKind.Pin) {
  override fun present(presentation: Presentation, row: Thread?) {
    val pinned: Boolean = row?.pinned == true
    presentation.text = pinText(row)
    presentation.icon = if (pinned) AllIcons.General.PinSelected else AllIcons.General.Pin
  }

  override suspend fun perform(target: ThreadTarget) {
    target.client.dispatch(pinCommand(target.row, target.commandId()))
  }
}

/**
 * Mark unread: upstream's `markThreadUnread` (commands.ts:543), `thread.mark-unread`. The server
 * rewinds the visit to just before the latest run completed, so the row reads as unread again.
 */
class MarkUnreadAction : ThreadAction(ThreadActionKind.MarkUnread) {
  override suspend fun perform(target: ThreadTarget) {
    ThreadVisits.getInstance(target.project).markedUnread(target.row)
    val command = Command.ThreadMarkUnread(commandId = target.commandId(), threadId = target.row.id)
    target.client.dispatch(command)
  }
}
