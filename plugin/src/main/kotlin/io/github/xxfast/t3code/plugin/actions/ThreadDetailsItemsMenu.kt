package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.awt.RelativePoint
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.ui.RESET_THREAD_DETAILS_ITEMS
import io.github.xxfast.t3code.plugin.ui.ThreadDetailsItem
import io.github.xxfast.t3code.plugin.ui.isDefaultThreadDetailsVisibility
import io.github.xxfast.t3code.plugin.ui.resolveThreadDetailsVisibility
import io.github.xxfast.t3code.plugin.ui.setThreadDetailsItem

/**
 * The items T3 Thread Details draws, in `THREAD_DETAILS_ITEMS` order. Automations and Usage
 * join once the plugin renders them.
 */
private val DRAWN_ITEMS: List<ThreadDetailsItem> = listOf(
  ThreadDetailsItem.Environment,
  ThreadDetailsItem.OpenIn,
  ThreadDetailsItem.Scripts,
  ThreadDetailsItem.Branch,
  ThreadDetailsItem.GitActions,
  ThreadDetailsItem.Changes,
  ThreadDetailsItem.Servers,
  ThreadDetailsItem.Lineage,
)

/**
 * Upstream's `ThreadDetailsItemsMenuItems` (ThreadDetailsItemsMenu.tsx): "Show in thread
 * details" with a checkbox per item, a separator, then "Reset to default", disabled while the
 * visibility is already the default. The card's right click and the tool window's gear share it.
 */
class ThreadDetailsItemsMenuItems : ActionGroup(), DumbAware {
  private val children: Array<AnAction> = arrayOf(
    Separator.create("Show in thread details"),
    *DRAWN_ITEMS.map(::ThreadDetailsItemToggle).toTypedArray(),
    Separator.create(),
    ResetThreadDetailsItems(),
  )

  override fun getChildren(event: AnActionEvent?): Array<AnAction> = children
}

/** Opens [ThreadDetailsItemsMenuItems] at [at], as upstream's `Menu` at the pointer. */
fun showThreadDetailsItemsMenu(at: RelativePoint) {
  ActionManager.getInstance()
    .createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, ThreadDetailsItemsMenuItems())
    .component
    .show(at.component, at.point.x, at.point.y)
}

/** `MenuCheckboxItem`: the menu stays open, so several items can change in one go. */
private class ThreadDetailsItemToggle(
  private val item: ThreadDetailsItem,
) : DumbAwareToggleAction(item.label) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    super.update(event)
    event.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
  }

  override fun isSelected(event: AnActionEvent): Boolean =
    resolveThreadDetailsVisibility(T3Settings.get().threadDetailsItems)[item]

  override fun setSelected(event: AnActionEvent, state: Boolean) {
    val settings: T3Settings = T3Settings.get()
    settings.threadDetailsItems = setThreadDetailsItem(settings.threadDetailsItems, item, state)
  }
}

private class ResetThreadDetailsItems : DumbAwareAction("Reset to default") {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    val items: Map<String, Boolean> = T3Settings.get().threadDetailsItems
    event.presentation.isEnabled =
      !isDefaultThreadDetailsVisibility(resolveThreadDetailsVisibility(items))
  }

  override fun actionPerformed(event: AnActionEvent) {
    T3Settings.get().threadDetailsItems = RESET_THREAD_DETAILS_ITEMS
  }
}
