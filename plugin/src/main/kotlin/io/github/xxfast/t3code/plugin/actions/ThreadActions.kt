package io.github.xxfast.t3code.plugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.NewThread
import io.github.xxfast.t3code.plugin.SidebarFolders
import io.github.xxfast.t3code.plugin.SidebarNavigation
import io.github.xxfast.t3code.plugin.SidebarRename
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVirtualFile
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.settings.T3ProjectState
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.SnoozePreset
import io.github.xxfast.t3code.plugin.ui.THREAD_AUTO_SETTLE_OPT_OUT
import io.github.xxfast.t3code.plugin.ui.THREAD_PIN_REORDER
import io.github.xxfast.t3code.plugin.ui.THREAD_SETTLEMENT
import io.github.xxfast.t3code.plugin.ui.THREAD_SNOOZE
import io.github.xxfast.t3code.plugin.ui.THREAD_TITLE_REGENERATION
import io.github.xxfast.t3code.plugin.ui.ThreadSections
import io.github.xxfast.t3code.plugin.ui.cards
import io.github.xxfast.t3code.plugin.ui.keepEmptyFolders
import io.github.xxfast.t3code.plugin.ui.nextCard
import io.github.xxfast.t3code.plugin.ui.pickSnoozePreset
import io.github.xxfast.t3code.plugin.ui.resolveSnoozePresets
import io.github.xxfast.t3code.plugin.ui.runtimeStatus
import io.github.xxfast.t3code.plugin.ui.sections
import io.github.xxfast.t3code.plugin.ui.settled
import io.github.xxfast.t3code.plugin.ui.sidebarFolderGroups
import io.github.xxfast.t3code.plugin.ui.snoozed
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.plugin.ui.topOfPinnedRunOrderKey
import io.github.xxfast.t3code.protocol.DEFAULT_SNOOZE_PRESETS
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.SnoozePresetSetting
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.pinned
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.util.UUID
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

/**
 * "New thread on <branch>", `useThreadActionMenu.ts:195`: a thread in the same project on this
 * thread's branch and worktree, as a draft [NewThread] opens. Only on a thread with a branch.
 */
class NewThreadOnBranchAction : ThreadAction(ThreadActionKind.NewThreadOnBranch) {
  override fun visible(row: Thread?): Boolean = row?.branch != null

  override fun present(presentation: Presentation, row: Thread?) {
    presentation.text = newThreadOnBranchText(row) ?: templateText
  }

  override suspend fun perform(target: ThreadTarget) {
    val row: Thread = target.row
    NewThread.getInstance(target.project)
      .start(row.projectId, branch = row.branch, worktreePath = row.worktreePath)
  }
}

/**
 * Archive or unarchive: upstream's `archiveThread` and `unarchiveThread`, commands.ts:426.
 * Archiving asks first when "Confirm thread archiving" is on, as Sidebar.tsx:4387 does, and
 * offers an undo, as useThreadActions.ts:378.
 */
class ArchiveThreadAction : ThreadAction(ThreadActionKind.Archive) {
  override fun present(presentation: Presentation, row: Thread?) {
    presentation.text = archiveText(row)
  }

  override suspend fun perform(target: ThreadTarget) {
    val row: Thread = target.row
    val undos: ThreadUndos = ThreadUndos.getInstance(target.project)
    if (row.archived) {
      undos.undo.invalidate(UndoKind.ARCHIVE, row.id)
      target.client.dispatch(archiveCommand(row, target.commandId()))
      return
    }
    val asks: Boolean = T3Settings.get().confirmThreadArchive
    if (asks && !target.confirm(archivePrompt(row), null, "Archive")) return
    undos.undoable(
      UndoKind.ARCHIVE, row.id, UndoAction.Archived, "Failed to undo archive",
      send = { target.client.dispatch(archiveCommand(row, target.commandId())) },
      revert = { target.client.dispatch(unarchiveCommand(row, target.commandId())) },
    )
  }
}

/**
 * Pin or unpin: upstream's `pinThread` and `unpinThread`, commands.ts:444 and 493. A pin goes to
 * the top of the pinned rows on a server that stores their order. Unpinning offers an undo that
 * pins it back in place, useThreadActions.ts:681.
 */
class PinThreadAction : ThreadAction(ThreadActionKind.Pin) {
  override fun present(presentation: Presentation, row: Thread?) {
    val pinned: Boolean = row?.pinned == true
    presentation.text = pinText(row)
    presentation.icon = if (pinned) AllIcons.General.PinSelected else AllIcons.General.Pin
  }

  override suspend fun perform(target: ThreadTarget) {
    val row: Thread = target.row
    if (row.pinned) {
      target.unpin()
      return
    }
    ThreadUndos.getInstance(target.project).undo.invalidate(UndoKind.PIN, row.id)
    val catalog: ServerInfo? = ChatController.getInstance(target.project).catalog.value
    val orderKey: String? = if (catalog.supports(THREAD_PIN_REORDER)) {
      topOfPinnedRunOrderKey(target.client.threads.value)
    } else {
      null
    }
    target.client.dispatch(pinCommand(row, target.commandId(), orderKey))
  }
}

/** `attemptUnpin`: unpins a pinned [ThreadTarget.row], with an undo that pins it back. */
internal suspend fun ThreadTarget.unpin() {
  val undos: ThreadUndos = ThreadUndos.getInstance(project)
  undos.undoable(
    UndoKind.PIN, row.id, UndoAction.Unpinned, "Failed to undo unpin",
    send = { client.dispatch(pinCommand(row, commandId())) },
    revert = { client.dispatch(repinCommand(row, commandId())) },
  )
}

/**
 * Mark unread: upstream's `markThreadUnread` (commands.ts:543), `thread.mark-unread`. The server
 * rewinds the visit to just before the latest run completed, so the row reads as unread again.
 */
class MarkUnreadAction : ThreadAction(ThreadActionKind.MarkUnread) {
  override suspend fun perform(target: ThreadTarget) {
    target.markUnread()
  }
}

/** `markThreadUnread`, useThreadActions.ts:202: the local mark, then the command. */
internal suspend fun ThreadTarget.markUnread() {
  ThreadVisits.getInstance(project).markedUnread(row)
  // Read-only pairings keep the item, as upstream's menu does; there it stays a local mark,
  // where upstream's dispatch fails without a word
  if (blockedBy(client.connection.value) != null) return
  client.dispatch(Command.ThreadMarkUnread(commandId = commandId(), threadId = row.id))
}

/**
 * "Mute notifications" or "Unmute notifications". Plugin-only, upstream has no mute: a muted
 * thread gets no balloon from [io.github.xxfast.t3code.plugin.ThreadNotificationCoordinator] and
 * still counts on T3 Sidebar's badge. Kept per IDE project in [T3ProjectState].
 */
class MuteThreadAction : ThreadAction(ThreadActionKind.Mute) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    val project: Project = e.project ?: return
    val id: String = e.threadId(project) ?: return
    val muted: Boolean = T3ProjectState.getInstance(project).isMuted(id)
    e.presentation.text = if (muted) "Unmute notifications" else "Mute notifications"
  }

  override suspend fun perform(target: ThreadTarget) {
    val state: T3ProjectState = T3ProjectState.getInstance(target.project)
    state.setMuted(target.row.id, !state.isMuted(target.row.id))
  }
}

/**
 * "Filter by project" or "Show all projects" (threadActionMenu.logic.ts:162, Sidebar.tsx:4266):
 * the Sidebar's project filter, narrowed to the row's project or opened back up.
 */
class FilterByProjectAction : ThreadAction(ThreadActionKind.FilterByProject) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    val project: Project = e.project ?: return
    val row: Thread? = e.threadRow(project, T3.get().client)
    val scope: ProjectScope? = row?.let { scope(project, it) }
    if (scope == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    e.presentation.text = scope.text
  }

  override suspend fun perform(target: ThreadTarget) {
    val scope: ProjectScope = scope(target.project, target.row) ?: return
    ProjectAwareness.getInstance(target.project).choose(scope.next)
  }

  private fun scope(project: Project, row: Thread): ProjectScope? {
    val filter: ProjectFilter = ProjectAwareness.getInstance(project).filter.value
    return projectScope(row, T3.get().client.projects.value, filter)
  }
}

/** "Copy" (threadActionMenu.logic.ts:194): Path, Branch, Thread ID. Fine on a read-only pairing. */
class CopyGroup : ThreadActionGroup(capability = null, requiresOperate = false)

/** One of [CopyGroup]'s items: [copied] onto the clipboard, then upstream's toast. */
abstract class CopyAction : ThreadAction(ThreadActionKind.Copy) {
  protected abstract fun copied(row: Thread, projects: List<T3Project>): Copied?

  override suspend fun perform(target: ThreadTarget) {
    val copied: Copied? = copied(target.row, target.client.projects.value)
    if (copied == null) {
      notify(target.project, NO_PATH, NotificationType.ERROR, PATH_UNAVAILABLE)
      return
    }
    withContext(Dispatchers.EDT) {
      CopyPasteManager.getInstance().setContents(StringSelection(copied.text))
    }
    notify(target.project, copied.text, NotificationType.INFORMATION, copied.title)
  }
}

class CopyPathAction : CopyAction() {
  override fun copied(row: Thread, projects: List<T3Project>): Copied? = copyPath(row, projects)
}

/** Only on a thread with a branch, as upstream's menu. */
class CopyBranchAction : CopyAction() {
  override fun visible(row: Thread?): Boolean = row?.branch != null

  override fun copied(row: Thread, projects: List<T3Project>): Copied? = copyBranch(row)
}

class CopyThreadIdAction : CopyAction() {
  override fun copied(row: Thread, projects: List<T3Project>): Copied = copyThreadId(row)
}

/**
 * "Project settings" (threadActionMenu.logic.ts), after Copy: [openProjectSettings] on the
 * context's [T3DataKeys.PROJECT_ID], else the thread's project. Enabled on a read-only pairing,
 * as `threadActionRequiresOperate` leaves it; the dialog shows the values and can't save.
 */
class ProjectSettingsAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    e.presentation.isEnabledAndVisible = project != null && projectId(e, project) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val projectId: String = projectId(e, project) ?: return
    openProjectSettings(project, projectId)
  }

  private fun projectId(e: AnActionEvent, project: Project): String? {
    val client: T3Client = T3.get().client
    val id: String = e.getData(T3DataKeys.PROJECT_ID)
      ?: e.threadRow(project, client)?.projectId
      ?: return null
    return id.takeIf { client.projects.value.any { it.id == id } }
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
    val row: Thread = target.row
    if (!row.settled) {
      target.settle()
      return
    }
    ThreadUndos.getInstance(target.project).undo.invalidate(UndoKind.SETTLE, row.id)
    target.client.dispatch(settleCommand(row, target.commandId()))
  }
}

/**
 * `attemptSettle`: settles an unsettled [ThreadTarget.row], with an undo. [coParking] are the
 * other rows a bulk settle takes out with it, which the open thread doesn't move on to.
 */
internal suspend fun ThreadTarget.settle(coParking: Set<String> = emptySet()) {
  val command: Command = settleCommand(row, commandId())
  val undos: ThreadUndos = ThreadUndos.getInstance(project)
  // Settling drops the pin and the snooze; an older undo of either would un-settle it
  undos.undo.invalidate(UndoKind.PIN, row.id)
  undos.undo.invalidate(UndoKind.SNOOZE, row.id)
  undos.undoable(
    UndoKind.SETTLE, row.id, UndoAction.Settled, "Failed to undo settle",
    send = { park(command, coParking) },
    revert = { unsettleUndoCommands(row, commandId()).forEach { client.dispatch(it) } },
  )
}

/**
 * Sends [command], which takes the row out of the inbox (a settle or a snooze), and when it's the
 * thread this window has open moves on to the next card that isn't one of [coParking]. The card
 * is picked before the command goes, as upstream's `planForwardNavigation` (Sidebar.tsx:1522)
 * does: the rows the Sidebar lists ([SidebarNavigation.ordered], Working shelf and folders as
 * shown), minus the snoozed and settled ones. With no Sidebar listing the row, the inbox's cards.
 */
private suspend fun ThreadTarget.park(command: Command, coParking: Set<String> = emptySet()) {
  val next: String? = if (ThreadSelection.getInstance(project).selected.value == row.id) {
    val filter: ProjectFilter = ProjectAwareness.getInstance(project).filter.value
    val projects: List<T3Project> = client.projects.value
    val boring: Boolean = T3Settings.get().boringSidebarEnabled
    val threads: List<Thread> = client.threads.value
    val sections: ThreadSections = sections(
      threads, filter,
      folderGroups = sidebarFolderGroups(projects, filter, boring),
      keepEmptyFolders = keepEmptyFolders(projects, filter, query = ""),
    )
    val listed: List<String> = SidebarNavigation.getInstance(project).ordered.value
    val cards: List<Thread> = if (row.id in listed) {
      val parked: Set<String> = (sections.snoozed + sections.settled).map { it.id }.toSet()
      val byId: Map<String, Thread> = threads.associateBy { it.id }
      listed.filterNot { it in parked }.mapNotNull(byId::get)
    } else {
      sections.cards(SidebarFolders.get().projectExpandedById.value, row.id)
    }
    nextCard(cards, row.id, coParking)
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
  ThreadAction(ThreadActionKind.AutoSettle, THREAD_AUTO_SETTLE_OPT_OUT),
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
 * "Snooze" (threadActionMenu.logic.ts:140): the server's `snoozePresets` as of when the menu
 * opens, soonest first, then "Custom…". Only on a thread that's awake; a snoozed one has
 * [WakeThreadAction] instead.
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
    // `snoozeOptions` (Sidebar.snooze.ts:49): the environment's settings, else the defaults
    val settings: List<SnoozePresetSetting> = e?.project
      ?.let { project -> ChatController.getInstance(project).catalog.value?.snoozePresets }
      ?: DEFAULT_SNOOZE_PRESETS
    val shown: List<SnoozePreset> = resolveSnoozePresets(Instant.now(), settings)
    val presets: List<AnAction> = shown.map { SnoozePresetAction(it, shown, settings) }
    // "Custom…" has its separator only after a preset
    val separator: List<AnAction> =
      listOfNotNull(Separator.getInstance().takeIf { shown.isNotEmpty() })
    return (presets + separator + CustomSnoozeAction()).toTypedArray()
  }
}

/**
 * One of the menu's presets, `attemptSnooze` (Sidebar.tsx:3861). [pickSnoozePreset] resolves it
 * again when it's picked, as `Sidebar.snooze.ts` does: a duration from then, a set time as shown.
 */
class SnoozePresetAction(
  private val preset: SnoozePreset,
  private val shown: List<SnoozePreset>,
  private val settings: List<SnoozePresetSetting>,
) : ThreadAction(ThreadActionKind.Snooze, THREAD_SNOOZE) {
  init {
    templatePresentation.text = presetText(preset)
  }

  override suspend fun perform(target: ThreadTarget) {
    val picked: SnoozePreset? = pickSnoozePreset(shown, preset.id, Instant.now(), settings)
    if (picked == null) {
      notify(
        target.project,
        "That snooze time has passed. Choose another time.",
        NotificationType.WARNING,
        title = "Could not snooze thread",
      )
      return
    }
    target.snooze(picked.snoozedUntil)
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
    target.snooze(until)
  }
}

/**
 * `snoozeThread`, useThreadActions.ts:849, with "Wake" as its undo. [coParking] as for
 * [settle], upstream's `coSnoozingKeys`.
 */
internal suspend fun ThreadTarget.snooze(until: Instant, coParking: Set<String> = emptySet()) {
  val undos: ThreadUndos = ThreadUndos.getInstance(project)
  undos.undoable(
    UndoKind.SNOOZE, row.id, UndoAction.Snoozed, "Failed to wake thread",
    send = { park(snoozeCommand(row, until, commandId()), coParking) },
    revert = { client.dispatch(wakeCommand(row, commandId())) },
  )
}

/** "Wake thread", `attemptUnsnooze` (Sidebar.tsx:3299): back to the inbox now. */
class WakeThreadAction : ThreadAction(ThreadActionKind.Wake, THREAD_SNOOZE) {
  override fun visible(row: Thread?): Boolean = row?.snoozed(Instant.now()) == true

  override suspend fun perform(target: ThreadTarget) {
    ThreadUndos.getInstance(target.project).undo.invalidate(UndoKind.SNOOZE, target.row.id)
    target.client.dispatch(wakeCommand(target.row, target.commandId()))
  }
}

/**
 * "Rename thread": in place on a Sidebar row, as upstream's `startThreadRename` (Sidebar.tsx:3157),
 * or a dialog from anywhere else.
 */
class RenameThreadAction : ThreadAction(ThreadActionKind.Rename) {
  override suspend fun perform(target: ThreadTarget) {
    if (target.fromSidebar) {
      SidebarRename.getInstance(target.project).start(target.row.id)
      return
    }
    val typed: String = withContext(Dispatchers.EDT) {
      val title: String = target.row.title
      Messages.showInputDialog(target.project, "Title", "Rename Thread", null, title, null)
    } ?: return
    commitRename(target.project, target.client, target.row, typed)
  }
}

/** `commitThreadRename`, Sidebar.tsx:3163: a blank title warns, the same title sends nothing. */
suspend fun commitRename(project: Project, client: T3Client, row: Thread, typed: String) {
  when (val outcome: Rename = rename(row, typed)) {
    Rename.Empty -> notify(project, "Thread title cannot be empty", NotificationType.WARNING)
    Rename.Unchanged -> Unit
    is Rename.To ->
      client.dispatch(renameCommand(row, outcome.title, UUID.randomUUID().toString()))
  }
}

/** "Regenerate title" (Sidebar.tsx:4334): the server writes a new one from the conversation. */
class RegenerateTitleAction :
  ThreadAction(ThreadActionKind.RegenerateTitle, THREAD_TITLE_REGENERATION) {
  override fun present(presentation: Presentation, row: Thread?) {
    presentation.text = regenerateText(row)
  }

  override suspend fun perform(target: ThreadTarget) {
    target.client.dispatch(regenerateTitleCommand(target.row, target.commandId()))
  }
}

/**
 * "Delete": upstream's `deleteThread` (apps/web/src/hooks/useThreadActions.ts:398). Asks first
 * when "Confirm thread deletion" is on, stops a running session, then deletes. Upstream moves
 * off a deleted open thread; here its tab closes. Its offer to delete an orphaned worktree too
 * isn't ported.
 */
class DeleteThreadAction : ThreadAction(ThreadActionKind.Delete) {
  override suspend fun perform(target: ThreadTarget) {
    val asks: Boolean = T3Settings.get().confirmThreadDelete
    if (asks && !target.confirm(deletePrompt(target.row), DELETE_WARNING, "Delete")) return
    target.delete()
  }
}

/** [DeleteThreadAction] once it's confirmed: stop the session, delete, close its tab. */
internal suspend fun ThreadTarget.delete() {
  if (row.runtimeStatus() != null) {
    stopCommands(view(), commandId()).forEach { client.dispatch(it) }
  }
  client.dispatch(deleteCommand(row, commandId()))
  withContext(Dispatchers.EDT) {
    val editors: FileEditorManager = FileEditorManager.getInstance(project)
    editors.openFiles
      .filter { file -> file is ThreadVirtualFile && file.threadId == row.id }
      .forEach(editors::closeFile)
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    if (selection.selected.value == row.id) selection.select(null)
  }
}

private suspend fun ThreadTarget.confirm(prompt: String, detail: String?, yes: String): Boolean =
  confirm(project, prompt, detail, yes)

/** Upstream's confirm dialog: [prompt], then [detail], with [yes] as the button. */
internal suspend fun confirm(
  project: Project,
  prompt: String,
  detail: String?,
  yes: String,
): Boolean =
  withContext(Dispatchers.EDT) {
    MessageDialogBuilder.yesNo(prompt, detail.orEmpty())
      .yesText(yes)
      .noText("Cancel")
      .asWarning()
      .ask(project)
  }
