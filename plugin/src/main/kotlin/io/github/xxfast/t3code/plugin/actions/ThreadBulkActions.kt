package io.github.xxfast.t3code.plugin.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.SidebarMultiSelection
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.ui.SnoozePreset
import io.github.xxfast.t3code.plugin.ui.THREAD_PINNING
import io.github.xxfast.t3code.plugin.ui.THREAD_SNOOZE
import io.github.xxfast.t3code.plugin.ui.THREAD_TITLE_REGENERATION
import io.github.xxfast.t3code.plugin.ui.getThreadKeysToDeselectAfterDelete
import io.github.xxfast.t3code.plugin.ui.snoozePresets
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

// The Sidebar's bulk menu, `handleMultiSelectContextMenu` in apps/web/src/components/Sidebar.tsx
// at the pinned tag. Each entry runs the single action's body once per row, so undos group the
// way upstream's do ("Settled 3 threads"), and clears the selection when upstream does.

/** What a bulk action acts on: the picked rows still in the list, in the order they were picked. */
class BulkTarget(
  val project: Project,
  val client: T3Client,
  val rows: List<Thread>,
  val menu: BulkThreadMenu,
) {
  val selection: SidebarMultiSelection get() = SidebarMultiSelection.getInstance(project)
  val scope: CoroutineScope get() = ThreadActionScope.getInstance(project).scope

  fun target(row: Thread): ThreadTarget = ThreadTarget(project, client, row, null, true)
}

/** The bulk menu for [T3DataKeys.THREAD_IDS] as it stands, or null without picked rows. */
private fun AnActionEvent.bulkTarget(): BulkTarget? {
  val project: Project = this.project ?: return null
  val ids: List<String> = getData(T3DataKeys.THREAD_IDS) ?: return null
  val client: T3Client = T3.get().client
  val byId: Map<String, Thread> = client.threads.value.associateBy { it.id }
  val rows: List<Thread> = ids.mapNotNull(byId::get)
  if (rows.isEmpty()) return null
  val catalog: ServerInfo? = ChatController.getInstance(project).catalog.value
  val menu: BulkThreadMenu = bulkThreadMenu(
    rows = rows,
    pinning = catalog.supports(THREAD_PINNING),
    snooze = catalog.supports(THREAD_SNOOZE),
    titleRegeneration = catalog.supports(THREAD_TITLE_REGENERATION),
    operable = blockedBy(client.connection.value) == null,
    now = Instant.now(),
  )
  return BulkTarget(project, client, rows, menu)
}

/** One entry of the bulk menu, [entry] of it: hidden when that's null, greyed when disabled. */
abstract class BulkThreadAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  protected abstract fun entry(menu: BulkThreadMenu): BulkEntry?

  override fun update(e: AnActionEvent) {
    val item: BulkEntry? = e.bulkTarget()?.let { entry(it.menu) }
    e.presentation.isVisible = item != null
    e.presentation.isEnabled = item != null && !item.item.disabled
    if (item != null) e.presentation.text = item.item.label
  }

  override fun actionPerformed(e: AnActionEvent) {
    val bulk: BulkTarget = e.bulkTarget() ?: return
    val item: BulkEntry = entry(bulk.menu)?.takeUnless { it.item.disabled } ?: return
    perform(bulk, item.targets)
  }

  /** Runs on the EDT; anything that waits launches in [BulkTarget.scope]. */
  protected abstract fun perform(bulk: BulkTarget, targets: List<Thread>)
}

/** "Unpin (N)": `attemptUnpin` on each pinned row, each with its undo. */
class BulkUnpinAction : BulkThreadAction() {
  override fun entry(menu: BulkThreadMenu): BulkEntry? = menu.unpin

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    for (row in targets) {
      bulk.scope.launchReporting(bulk.project, "Couldn't unpin thread") { bulk.target(row).unpin() }
    }
    bulk.selection.clear()
  }
}

/**
 * "Settle (N)": `settleThreads`, `attemptSettle` on each unsettled row. The open thread moves
 * on past the others settling with it.
 */
class BulkSettleAction : BulkThreadAction() {
  override fun entry(menu: BulkThreadMenu): BulkEntry = menu.settle

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    val settling: Set<String> = targets.map { it.id }.toSet()
    for (row in targets) {
      bulk.scope.launchReporting(bulk.project, "Failed to settle thread") {
        bulk.target(row).settle(settling)
      }
    }
    bulk.selection.clear()
  }
}

/** "Snooze (N)": upstream's presets as of when the menu opens, then "Custom…". */
class BulkSnoozeGroup : DefaultActionGroup(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val item: BulkEntry? = e.bulkTarget()?.menu?.snooze
    e.presentation.isVisible = item != null
    e.presentation.isEnabled = item != null && !item.item.disabled
    if (item != null) e.presentation.text = item.item.label
  }

  override fun getChildren(e: AnActionEvent?): Array<AnAction> {
    val presets: List<AnAction> = snoozePresets(Instant.now()).map(::BulkSnoozePresetAction)
    return (presets + Separator.getInstance() + BulkCustomSnoozeAction()).toTypedArray()
  }
}

/** One preset for every picked row. */
class BulkSnoozePresetAction(private val preset: SnoozePreset) : BulkThreadAction() {
  init {
    templatePresentation.text = presetText(preset)
  }

  override fun entry(menu: BulkThreadMenu): BulkEntry? = menu.snooze

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = templatePresentation.text
  }

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    snoozeAll(bulk, targets, preset.snoozedUntil)
  }
}

/** "Custom…": upstream's `requestCustomSnooze`, one dialog for the lot. */
class BulkCustomSnoozeAction : BulkThreadAction() {
  init {
    templatePresentation.text = "Custom…"
  }

  override fun entry(menu: BulkThreadMenu): BulkEntry? = menu.snooze

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = templatePresentation.text
  }

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    val dialog = CustomSnoozeDialog(bulk.project)
    val until: Instant = dialog.takeIf { it.showAndGet() }?.until ?: return
    snoozeAll(bulk, targets, until)
  }
}

/**
 * The bulk snooze: the selection goes first, then every row snoozes, each with its undo. Failures
 * add up to one toast, as upstream's.
 */
private fun snoozeAll(bulk: BulkTarget, targets: List<Thread>, until: Instant) {
  val snoozing: Set<String> = targets.map { it.id }.toSet()
  bulk.selection.clear()
  bulk.scope.launchReporting(bulk.project, "Failed to snooze threads") {
    var snoozed = 0
    val failures: MutableList<Exception> = mutableListOf()
    for (row in targets) {
      try {
        bulk.target(row).snooze(until, snoozing)
        snoozed++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failures += e
      }
    }
    val first: Exception = failures.firstOrNull() ?: return@launchReporting
    val title: String = bulkSnoozeFailureTitle(failures.size, snoozed)
    notify(bulk.project, first.message ?: first.javaClass.simpleName, NotificationType.ERROR, title)
  }
}

/**
 * "Regenerate titles (N)": one row at a time, stopping at the first failure, which keeps the
 * selection. Only the rows that aren't regenerating already.
 */
class BulkRegenerateTitlesAction : BulkThreadAction() {
  override fun entry(menu: BulkThreadMenu): BulkEntry? = menu.regenerateTitles

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    bulk.scope.launchReporting(bulk.project, "Failed to regenerate thread titles") {
      for (row in targets) {
        bulk.client.dispatch(regenerateTitleCommand(row, bulk.target(row).commandId()))
      }
      bulk.selection.clear()
    }
  }
}

/**
 * "Mark unread (N)": `markThreadUnread` on each row. Fine on a read-only pairing, and failures
 * stay quiet, as upstream's `reportFailure: false`.
 */
class BulkMarkUnreadAction : BulkThreadAction() {
  override fun entry(menu: BulkThreadMenu): BulkEntry = menu.markUnread

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    for (row in targets) {
      bulk.scope.launchReporting(bulk.project, "Couldn't mark thread unread") {
        try {
          bulk.target(row).markUnread()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Upstream's mark unread doesn't report a failure
        }
      }
    }
    bulk.selection.clear()
  }
}

/**
 * "Delete (N)": asks once when "Confirm thread deletion" is on, then `deleteSelectedThreadEntries`,
 * one row at a time past failures. The first failure is the toast; deleted rows leave the
 * selection and failed ones stay picked.
 */
class BulkDeleteAction : BulkThreadAction() {
  override fun entry(menu: BulkThreadMenu): BulkEntry = menu.delete

  override fun perform(bulk: BulkTarget, targets: List<Thread>) {
    val picked: List<String> = targets.map { it.id }
    bulk.scope.launchReporting(bulk.project, "Failed to delete threads") {
      val asks: Boolean = T3Settings.get().confirmThreadDelete
      val prompt: String = bulkDeletePrompt(targets.size)
      if (asks && !confirm(bulk.project, prompt, BULK_DELETE_WARNING, "Delete")) {
        return@launchReporting
      }
      val deleted: MutableSet<String> = mutableSetOf()
      var failure: Exception? = null
      for (row in targets) {
        try {
          bulk.target(row).delete()
          deleted += row.id
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (failure == null) failure = e
        }
      }
      failure?.let { e ->
        val message: String = e.message ?: e.javaClass.simpleName
        notify(bulk.project, message, NotificationType.ERROR, "Failed to delete threads")
      }
      val live: Set<String> = bulk.client.threads.value.map { it.id }.toSet()
      bulk.selection.remove(getThreadKeysToDeselectAfterDelete(picked, deleted) { it in live })
    }
  }
}
