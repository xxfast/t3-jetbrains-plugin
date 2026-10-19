package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import io.github.xxfast.t3code.plugin.ui.AttachmentActions
import io.github.xxfast.t3code.plugin.ui.attachFromClipboard
import java.awt.datatransfer.DataFlavor

/**
 * Every `$Paste` shortcut (Ctrl+V, Shift+Insert, Cmd+V) over the composer with files or an image
 * on the clipboard attaches them, the way the web composer's paste handler does.
 *
 * Why an action and not a key handler in Compose: the IDE's `$Paste` action takes those keys
 * before Compose sees them. Jewel's `ComposePasteProvider` is the paste provider for every Compose
 * text field, set first in `RootDataProviderNode`, and a data sink keeps the first value for a
 * key, so a node can't offer its own provider. This action shares `$Paste`'s shortcuts instead,
 * [PasteAttachmentPromoter] puts it first, and it stays disabled for a text clipboard so that
 * pastes as before. The composer offers [T3DataKeys.ATTACHMENTS] only while it has focus.
 */
class PasteAttachmentAction : DumbAwareAction() {
  // The clipboard is read here, and AWT wants that on the EDT
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    val target: AttachmentActions? = event.getData(T3DataKeys.ATTACHMENTS)
    val attachable: Boolean = CopyPasteManager.getInstance()
      .areDataFlavorsAvailable(DataFlavor.javaFileListFlavor, DataFlavor.imageFlavor)
    event.presentation.isEnabledAndVisible = target != null && attachable
  }

  override fun actionPerformed(event: AnActionEvent) {
    val target: AttachmentActions = event.getData(T3DataKeys.ATTACHMENTS) ?: return
    attachFromClipboard(CopyPasteManager.getInstance(), target)
  }
}

/** Ahead of `$Paste` when the composer has focus; the IDE performs the first enabled action. */
class PasteAttachmentPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    if (context.getData(T3DataKeys.ATTACHMENTS) == null) return null
    // A stable sort: ours first, the rest as they were
    return actions.sortedByDescending { it is PasteAttachmentAction }
  }
}
