package io.github.xxfast.t3code.plugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.SnoozePreset
import io.github.xxfast.t3code.plugin.ui.THREAD_AUTO_SETTLE_OPT_OUT
import io.github.xxfast.t3code.plugin.ui.THREAD_SETTLEMENT
import io.github.xxfast.t3code.plugin.ui.THREAD_SNOOZE
import io.github.xxfast.t3code.plugin.ui.nextCard
import io.github.xxfast.t3code.plugin.ui.sections
import io.github.xxfast.t3code.plugin.ui.settled
import io.github.xxfast.t3code.plugin.ui.snoozePresets
import io.github.xxfast.t3code.plugin.ui.snoozed
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.pinned
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/**
 * Settle or un-settle: upstream's `attemptSettle` and `attemptUnsettle`, Sidebar.tsx:3238 and
 * 3284. Settling the thread this window has open moves on to the next card, the way upstream's
 * `planForwardNavigation` does: picked before the command, followed once it lands.
 */
class SettleThreadAction : ThreadAction(ThreadActionKind.Settle, THREAD_SETTLEMENT) {
  override fun present(presentation: Presentation, row: Thread?) {
    presentation.text = settleText(row)
  }

  override suspend fun perform(target: ThreadTarget) {
    val command: Command = settleCommand(target.row, target.commandId())
    if (target.row.settled) target.client.dispatch(command) else target.park(command)
  }
}

/**
 * Sends [command], which takes the row out of the inbox (a settle or a snooze), and when it's the
 * thread this window has open moves on to the next card. The card is picked before the command
 * goes, as upstream's `planForwardNavigation` (Sidebar.tsx:3213) does.
 */
private suspend fun ThreadTarget.park(command: Command) {
  val next: String? = if (ThreadSelection.getInstance(project).selected.value == row.id) {
    val filter: ProjectFilter = ProjectAwareness.getInstance(project).filter.value
    nextCard(sections(client.threads.value, query = "", filter).inbox, row.id)
  } else {
    null
  }
  client.dispatch(command)
  if (next != null) T3Opener.getInstance(project).openThread(next)
}

/** "Auto-settle behavior", threadActionMenu.logic.ts:173: Enabled and Disabled, one checked. */
class AutoSettleGroup : ThreadActionGroup(THREAD_AUTO_SETTLE_OPT_OUT)

/** One of [AutoSettleGroup]'s choices, `setThreadAutoSettle` with [enabled]. */
abstract class AutoSettleAction(private val enabled: Boolean) :
  ThreadAction(ThreadActionKind.AutoSettle, THREAD_AUTO_SETTLE_OPT_OUT, menuOnly = true),
  Toggleable {
  override fun present(presentation: Presentation, row: Thread?) {
    Toggleable.setSelected(presentation, row?.autoSettles == enabled)
  }

  override suspend fun perform(target: ThreadTarget) {
    if (target.row.autoSettles == enabled) return
    target.client.dispatch(autoSettleCommand(target.row, enabled, target.commandId()))
  }
}

class AutoSettleOnAction : AutoSettleAction(enabled = true)

class AutoSettleOffAction : AutoSettleAction(enabled = false)

/**
 * "Snooze" (threadActionMenu.logic.ts:131): upstream's presets as of when the menu opens, then
 * "Custom…". Only on a thread that's awake; a snoozed one has [WakeThreadAction] instead.
 */
class SnoozeGroup : ThreadActionGroup(THREAD_SNOOZE) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    val project: Project = e.project ?: return
    val client: T3Client = T3.get().client
    val row: Thread? = e.threadRow(project, client)
    val now: Instant = Instant.now()
    if (row?.snoozed(now) == true) e.presentation.isVisible = false
    val reason: String? = disabledReason(ThreadActionKind.Snooze, client.connection.value, row, now)
    e.presentation.isEnabled = e.presentation.isVisible && reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun getChildren(e: AnActionEvent?): Array<AnAction> {
    val presets: List<AnAction> = snoozePresets(Instant.now()).map(::SnoozePresetAction)
    return (presets + Separator.getInstance() + CustomSnoozeAction()).toTypedArray()
  }
}

/** One of upstream's presets, `attemptSnooze` (Sidebar.tsx:3861) until [preset]'s time. */
class SnoozePresetAction(private val preset: SnoozePreset) :
  ThreadAction(ThreadActionKind.Snooze, THREAD_SNOOZE) {
  init {
    templatePresentation.text = presetText(preset)
  }

  override suspend fun perform(target: ThreadTarget) {
    target.park(snoozeCommand(target.row, preset.snoozedUntil, target.commandId()))
  }
}

/** "Custom…": upstream's `CustomSnoozeDialog`, a date and time or a duration. */
class CustomSnoozeAction : ThreadAction(ThreadActionKind.Snooze, THREAD_SNOOZE) {
  init {
    templatePresentation.text = "Custom…"
  }

  override suspend fun perform(target: ThreadTarget) {
    val until: Instant = withContext(Dispatchers.EDT) {
      CustomSnoozeDialog(target.project).takeIf { it.showAndGet() }?.until
    } ?: return
    target.park(snoozeCommand(target.row, until, target.commandId()))
  }
}

/** "Wake thread", `attemptUnsnooze` (Sidebar.tsx:3299): back to the inbox now. */
class WakeThreadAction : ThreadAction(ThreadActionKind.Wake, THREAD_SNOOZE) {
  override fun visible(row: Thread?): Boolean = row?.snoozed(Instant.now()) == true

  override suspend fun perform(target: ThreadTarget) {
    target.client.dispatch(wakeCommand(target.row, target.commandId()))
  }
}
