package io.github.xxfast.t3code.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import io.github.xxfast.t3code.plugin.settings.ComposerDrafts
import io.github.xxfast.t3code.plugin.ui.AttachmentKind
import io.github.xxfast.t3code.plugin.ui.PromptStashEntry
import io.github.xxfast.t3code.plugin.ui.stashEntrySnippet
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/** The stash shortcut's action id, for its label in the menu. */
const val STASH_ACTION_ID: String = "T3Code.StashPrompt"

private const val MENU_WIDTH: Int = 420
private const val MENU_ROWS: Int = 8

/**
 * Upstream's `ComposerStashMenu` as an IDE popup list: newest first, Enter or a click restores
 * the highlighted entry into [threadId]'s draft, Cmd/Ctrl+Backspace (or Delete) deletes it, Esc
 * closes. Each row is `stashEntrySnippet`, then the attachment count and the relative time.
 * Opens above [at], where the composer's Stash badge sits.
 */
fun showComposerStash(controller: ChatController, threadId: String, at: RelativePoint) {
  val entries: List<PromptStashEntry> = ComposerDrafts.get().stash.value
  val model: CollectionListModel<PromptStashEntry> = CollectionListModel(entries)
  val list: JBList<PromptStashEntry> = JBList(model)
  list.selectionMode = ListSelectionModel.SINGLE_SELECTION
  list.cellRenderer = StashRenderer()
  list.visibleRowCount = MENU_ROWS
  val shortcut: String = KeymapUtil.getFirstKeyboardShortcutText(STASH_ACTION_ID)
  list.emptyText.text = "Nothing stashed yet."
  if (shortcut.isNotEmpty()) {
    list.emptyText.appendLine("Press $shortcut with a prompt in the composer to stash it.")
  }
  if (entries.isNotEmpty()) list.selectedIndex = 0

  val scroll: JComponent = ScrollPaneFactory.createScrollPane(list, true)
  scroll.preferredSize = JBUI.size(MENU_WIDTH, scroll.preferredSize.height)
  val delete: String = if (SystemInfo.isMac) "⌘⌫" else "Ctrl+Backspace"
  val popup: JBPopup = JBPopupFactory.getInstance()
    .createComponentPopupBuilder(scroll, list)
    .setTitle("Stash")
    .setRequestFocus(true)
    .setFocusable(true)
    .setCancelOnClickOutside(true)
    .setMovable(false)
    .setResizable(false)
    .setAdText(if (entries.isEmpty()) null else "Enter to restore, $delete to delete")
    .createPopup()

  fun restore() {
    val entry: PromptStashEntry = list.selectedValue ?: return
    popup.cancel()
    controller.restoreStash(threadId, entry.id)
  }

  fun delete() {
    val index: Int = list.selectedIndex.takeIf { it >= 0 } ?: return
    controller.deleteStash(model.getElementAt(index).id)
    model.remove(index)
    // Upstream closes the menu with its last entry
    if (model.isEmpty) return popup.cancel()
    list.selectedIndex = index.coerceAtMost(model.size - 1)
  }

  list.bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "restore") { restore() }
  val menuMask: Int = if (SystemInfo.isMac) {
    InputEvent.META_DOWN_MASK
  } else {
    InputEvent.CTRL_DOWN_MASK
  }
  list.bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, menuMask), "delete") { delete() }
  list.bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete") { delete() }
  // The shortcut again closes it, upstream's toggle
  list.bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask), "toggle") { popup.cancel() }
  list.addMouseListener(
    object : MouseAdapter() {
      override fun mouseClicked(event: MouseEvent) {
        if (list.locationToIndex(event.point) >= 0) restore()
      }
    },
  )

  // Bottom edge on [at], so the menu rises out of the badge over the timeline
  val height: Int = popup.content.preferredSize.height
  popup.show(RelativePoint(at.component, Point(at.point.x, at.point.y - height)))
}

private fun JComponent.bind(stroke: KeyStroke, name: String, perform: () -> Unit) {
  getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name)
  actionMap.put(
    name,
    object : AbstractAction() {
      override fun actionPerformed(event: ActionEvent) = perform()
    },
  )
}

private class StashRenderer : ColoredListCellRenderer<PromptStashEntry>() {
  override fun customizeCellRenderer(
    list: JList<out PromptStashEntry>,
    value: PromptStashEntry,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean,
  ) {
    icon = AllIcons.FileTypes.Text
    append(stashEntrySnippet(value))
    val images: Int = value.attachments.count { it.kind == AttachmentKind.IMAGE.wire }
    val files: Int = value.attachments.size - images
    if (images > 0) append("  $images image${if (images == 1) "" else "s"}", GRAYED)
    if (files > 0) append("  $files file${if (files == 1) "" else "s"}", GRAYED)
    append("  ${DateFormatUtil.formatPrettyDateTime(value.createdAt)}", GRAYED)
  }
}

private val GRAYED: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES
