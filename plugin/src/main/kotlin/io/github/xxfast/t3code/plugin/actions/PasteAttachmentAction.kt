package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import io.github.xxfast.t3code.plugin.ui.AttachmentActions
import io.github.xxfast.t3code.plugin.ui.PasteFold
import io.github.xxfast.t3code.plugin.ui.attachFromClipboard
import java.awt.datatransfer.DataFlavor

/** `T3Code.PasteAsText` in `plugin.xml`. */
const val PASTE_AS_TEXT_ACTION_ID: String = "T3Code.PasteAsText"

/**
 * Every `$Paste` shortcut (Ctrl+V, Shift+Insert, Cmd+V) over the composer with files or an image
 * on the clipboard attaches them, the way the web composer's paste handler does, and a text paste
 * large enough for upstream's `foldPastedText` becomes a text file.
 *
 * Why an action and not a key handler in Compose: the IDE's `$Paste` action takes those keys
 * before Compose sees them. Jewel's `ComposePasteProvider` is the paste provider for every Compose
 * text field, set first in `RootDataProviderNode`, and a data sink keeps the first value for a
 * key, so a node can't offer its own provider. This action shares `$Paste`'s shortcuts instead,
 * [PasteAttachmentPromoter] puts it first, and it stays disabled for a text clipboard that pastes
 * inline, so that pastes as before. The composer offers [T3DataKeys.ATTACHMENTS] only while it
 * has focus.
 */
class PasteAttachmentAction : DumbAwareAction() {
  // The clipboard is read here, and AWT wants that on the EDT
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    val target: AttachmentActions? = event.getData(T3DataKeys.ATTACHMENTS)
    event.presentation.isEnabledAndVisible = target != null && handles(target)
  }

  override fun actionPerformed(event: AnActionEvent) {
    val target: AttachmentActions = event.getData(T3DataKeys.ATTACHMENTS) ?: return
    val clipboard: CopyPasteManager = CopyPasteManager.getInstance()
    if (clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor, DataFlavor.imageFlavor)) {
      return attachFromClipboard(clipboard, target)
    }
    val text: String = clipboardText(clipboard) ?: return
    target.onFoldPaste(text)
  }

  private fun handles(target: AttachmentActions): Boolean {
    val clipboard: CopyPasteManager = CopyPasteManager.getInstance()
    if (clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor, DataFlavor.imageFlavor)) {
      return true
    }
    val text: String = clipboardText(clipboard) ?: return false
    return target.pasteFold(text) != PasteFold.Inline
  }
}

/**
 * Upstream's paste-as-text shortcut (`isPasteAsTextShortcut`, Cmd+Shift+V or Ctrl+Shift+V,
 * armed at `ChatComposer.tsx:2515`): the clipboard's text pastes inline however large it is.
 * Those keys are the IDE's Paste from History, so this is an action too, promoted over it while
 * the composer has focus. With no text on the clipboard it attaches like a plain paste, as
 * upstream's bypassed paste still attaches files.
 */
class PasteAsTextAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    val target: AttachmentActions? = event.getData(T3DataKeys.ATTACHMENTS)
    val pasteable: Boolean = CopyPasteManager.getInstance().areDataFlavorsAvailable(
      DataFlavor.stringFlavor,
      DataFlavor.javaFileListFlavor,
      DataFlavor.imageFlavor,
    )
    event.presentation.isEnabledAndVisible = target != null && pasteable
  }

  override fun actionPerformed(event: AnActionEvent) {
    val target: AttachmentActions = event.getData(T3DataKeys.ATTACHMENTS) ?: return
    val clipboard: CopyPasteManager = CopyPasteManager.getInstance()
    val text: String? = clipboardText(clipboard)
    if (text != null) target.onPasteInline(text) else attachFromClipboard(clipboard, target)
  }
}

private fun clipboardText(clipboard: CopyPasteManager): String? {
  if (!clipboard.areDataFlavorsAvailable(DataFlavor.stringFlavor)) return null
  return try {
    clipboard.getContents<String>(DataFlavor.stringFlavor)?.takeIf { it.isNotEmpty() }
  } catch (_: Exception) {
    null
  }
}

/**
 * Ahead of `$Paste` and Paste from History when the composer has focus; the IDE performs the
 * first enabled action.
 */
class PasteAttachmentPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    if (context.getData(T3DataKeys.ATTACHMENTS) == null) return null
    // A stable sort: ours first, the rest as they were
    return actions.sortedByDescending { it is PasteAttachmentAction || it is PasteAsTextAction }
  }
}
