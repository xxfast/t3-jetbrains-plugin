package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAwareAction
import io.github.xxfast.t3code.plugin.ui.ComposerControlKind
import io.github.xxfast.t3code.plugin.ui.ComposerShortcuts

/**
 * Upstream's composer keybindings (`packages/shared/src/keybindings.ts:176` to 206) as IDE
 * actions with the same default keys, so they show in the keymap and can be rebound there.
 * Each is enabled only while the composer has focus and can act ([T3DataKeys.COMPOSER]), and
 * [ComposerShortcutPromoter] puts it ahead of the IDE's own action on the same key for as long
 * as that holds. Anywhere else the key does what it always did. Keys that would shadow a global
 * IDE action (Find Action, Recent Locations) deviate from upstream, see Effort and Mode.
 */
abstract class ComposerShortcutAction : DumbAwareAction() {
  // Reads and changes Compose state, which lives on the EDT
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  /** Whether [shortcuts] has something to do for this key. */
  protected abstract fun canPerform(shortcuts: ComposerShortcuts): Boolean

  protected abstract fun perform(shortcuts: ComposerShortcuts)

  override fun update(event: AnActionEvent) {
    val shortcuts: ComposerShortcuts? = event.getData(T3DataKeys.COMPOSER)
    event.presentation.isEnabledAndVisible = shortcuts != null && canPerform(shortcuts)
  }

  override fun actionPerformed(event: AnActionEvent) {
    val shortcuts: ComposerShortcuts = event.getData(T3DataKeys.COMPOSER) ?: return
    if (canPerform(shortcuts)) perform(shortcuts)
  }
}

/** `modelPicker.toggle`, mod+shift+m. */
class ComposerModelPickerAction : ComposerShortcutAction() {
  override fun canPerform(shortcuts: ComposerShortcuts): Boolean =
    shortcuts.onToggleModelPicker != null

  override fun perform(shortcuts: ComposerShortcuts) {
    shortcuts.onToggleModelPicker?.invoke()
  }
}

/** `openComposerControl` for one [kind], ChatView.tsx:5776. */
abstract class ComposerControlAction(private val kind: ComposerControlKind) :
  ComposerShortcutAction() {
  override fun canPerform(shortcuts: ComposerShortcuts): Boolean = shortcuts.canOpen(kind)

  override fun perform(shortcuts: ComposerShortcuts) {
    shortcuts.open(kind)
  }
}

/**
 * `composer.effort`: the traits picker. Deviates from upstream's mod+shift+e, which is Recent
 * Locations in the IDE keymaps: mod+shift+y.
 */
class ComposerEffortAction : ComposerControlAction(ComposerControlKind.EFFORT)

/**
 * `composer.mode`: the runtime mode select. Deviates from upstream's mod+shift+a, which is Find
 * Action in the IDE keymaps: mod+alt+shift+y.
 */
class ComposerModeAction : ComposerControlAction(ComposerControlKind.MODE)

/** `composer.workspace`, mod+shift+x: the Workspace select in the context strip. */
class ComposerWorkspaceAction : ComposerControlAction(ComposerControlKind.WORKSPACE)

/** `composer.branch`, mod+shift+g: the branch picker in the context strip. */
class ComposerBranchAction : ComposerControlAction(ComposerControlKind.BRANCH)

/** `thread.steerQueuedMessage`, mod+shift+enter: the first queued message as a steer. */
class ComposerSteerQueuedAction : ComposerShortcutAction() {
  override fun canPerform(shortcuts: ComposerShortcuts): Boolean = shortcuts.onSteerQueued != null

  override fun perform(shortcuts: ComposerShortcuts) {
    shortcuts.onSteerQueued?.invoke()
  }
}

/**
 * `thread.editQueuedMessage`, alt+arrowup: the latest queued message back into the composer,
 * only with the caret at the start (ChatView.tsx:5890) so Alt+Up still moves through the text.
 */
class ComposerEditQueuedAction : ComposerShortcutAction() {
  override fun canPerform(shortcuts: ComposerShortcuts): Boolean =
    shortcuts.onEditQueued != null && shortcuts.isCaretAtStart()

  override fun perform(shortcuts: ComposerShortcuts) {
    shortcuts.onEditQueued?.invoke()
  }
}

/** Puts the composer's shortcuts first while it has focus; the IDE runs the first enabled one. */
class ComposerShortcutPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    if (context.getData(T3DataKeys.COMPOSER) == null) return null
    // A stable sort: ours first, the rest as they were
    return actions.sortedByDescending { it is ComposerShortcutAction }
  }
}
