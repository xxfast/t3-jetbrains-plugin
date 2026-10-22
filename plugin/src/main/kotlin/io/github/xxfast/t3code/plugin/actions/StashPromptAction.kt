package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import io.github.xxfast.t3code.plugin.ui.AttachmentActions

/**
 * Upstream's `composer.stash` (`mod+s`, `packages/shared/src/keybindings.ts:176`): stash the
 * draft, or with an empty composer restore the only entry or open the stash. The IDE keymap
 * owns Cmd/Ctrl+S (`SaveAll`), so like [PasteAttachmentAction] this shares its shortcut and
 * [StashPromptPromoter] puts it first while the composer has focus.
 */
class StashPromptAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabledAndVisible = event.getData(T3DataKeys.ATTACHMENTS) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val target: AttachmentActions = event.getData(T3DataKeys.ATTACHMENTS) ?: return
    target.onStash(JBPopupFactory.getInstance().guessBestPopupLocation(event.dataContext))
  }
}

/** Ahead of `SaveAll` when the composer has focus; the IDE performs the first enabled action. */
class StashPromptPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    if (context.getData(T3DataKeys.ATTACHMENTS) == null) return null
    return actions.sortedByDescending { it is StashPromptAction }
  }
}
