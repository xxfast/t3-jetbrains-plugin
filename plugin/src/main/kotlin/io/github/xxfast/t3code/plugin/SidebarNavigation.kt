package io.github.xxfast.t3code.plugin

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ui.EMPTY_SELECTION
import io.github.xxfast.t3code.plugin.ui.ShortcutModifiers
import io.github.xxfast.t3code.plugin.ui.THREAD_JUMP_COUNT
import io.github.xxfast.t3code.plugin.ui.ThreadJumpHintVisibility
import io.github.xxfast.t3code.plugin.ui.ThreadTraversalDirection
import io.github.xxfast.t3code.plugin.ui.resolveAdjacentThreadId
import io.github.xxfast.t3code.plugin.ui.resolveSidebarRowThreadId
import io.github.xxfast.t3code.plugin.ui.shouldShowThreadJumpHints
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The jump actions' ids are this plus 1 to 9, see `plugin.xml`. */
const val JUMP_TO_THREAD_ACTION: String = "T3Code.JumpToThread"

/**
 * The Sidebar's keyboard navigation, upstream's `thread.previous`, `thread.next` and
 * `thread.jump.N` handling in Sidebar.tsx: the rows the Sidebar lists, for the actions to walk,
 * and whether the jump hints are up.
 */
@Service(Service.Level.PROJECT)
class SidebarNavigation(private val project: Project, scope: CoroutineScope) {
  private val order: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

  /** Upstream's `orderedThreadKeys`, as the Sidebar last listed them. */
  val ordered: StateFlow<List<String>> = order.asStateFlow()

  private val hintVisibility: ThreadJumpHintVisibility =
    ThreadJumpHintVisibility(CoroutineScope(scope.coroutineContext + Dispatchers.EDT))

  /** Whether rows show their jump shortcut: one's modifiers are held. */
  val hints: StateFlow<Boolean> = hintVisibility.visible

  fun order(threadIds: List<String>) {
    order.value = threadIds
  }

  /** Where prev or next goes from the open thread's row, null when nowhere. */
  fun adjacent(direction: ThreadTraversalDirection): String? {
    val current: String? = ThreadSelection.getInstance(project).selected.value
      ?.let { resolveSidebarRowThreadId(it, T3.get().client.threads.value) }
    return resolveAdjacentThreadId(order.value, current, direction)
  }

  /** The row jump N goes to, [index] from 0. */
  fun jump(index: Int): String? = order.value.getOrNull(index)

  /** `navigateToThread`: drops the multi-selection, anchors the next Shift range, opens it. */
  fun navigate(threadId: String) {
    SidebarMultiSelection.getInstance(project).change(EMPTY_SELECTION.setAnchor(threadId))
    T3Opener.getInstance(project).openThread(threadId)
  }

  /**
   * Watches the modifier keys for the jump hints while [toolWindow] is showing, as upstream's
   * `shortcutModifiers` does at the window. Not Compose key events: the keymap sees keys first.
   */
  fun watch(toolWindow: ToolWindow) {
    // Non-locked: it only reads keys, and the locked overload is deprecated from 253
    val dispatcher = object : IdeEventQueue.NonLockedEventDispatcher {
      override fun dispatch(e: AWTEvent): Boolean {
        when {
          e is KeyEvent && e.id != KeyEvent.KEY_TYPED -> {
            val showing: Boolean = toolWindow.isVisible && inWindowOf(toolWindow, e)
            hintVisibility.sync(showing && shouldShowThreadJumpHints(e.held(), jumpModifiers()))
          }

          e is WindowEvent && e.id == WindowEvent.WINDOW_DEACTIVATED -> hintVisibility.sync(false)
        }
        return false
      }
    }
    IdeEventQueue.getInstance().addDispatcher(dispatcher, toolWindow.disposable)
    Disposer.register(toolWindow.disposable) {
      hintVisibility.sync(false)
      order.value = emptyList()
    }
  }

  companion object {
    fun getInstance(project: Project): SidebarNavigation = project.service()
  }
}

private const val MODIFIERS: Int = InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK or
  InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

private fun modifiers(mask: Int): ShortcutModifiers = ShortcutModifiers(
  ctrl = mask and InputEvent.CTRL_DOWN_MASK != 0,
  meta = mask and InputEvent.META_DOWN_MASK != 0,
  alt = mask and InputEvent.ALT_DOWN_MASK != 0,
  shift = mask and InputEvent.SHIFT_DOWN_MASK != 0,
)

/** What's held once this key event lands: a released modifier is already out of the mask. */
private fun KeyEvent.held(): ShortcutModifiers = modifiers(modifiersEx and MODIFIERS)

/** The modifiers of every single-keystroke shortcut bound to a jump action in the keymap. */
private fun jumpModifiers(): List<ShortcutModifiers> {
  val keymap: Keymap = KeymapManager.getInstance()?.activeKeymap ?: return emptyList()
  return (1..THREAD_JUMP_COUNT).flatMap { n ->
    keymap.getShortcuts("$JUMP_TO_THREAD_ACTION$n")
      .filterIsInstance<KeyboardShortcut>()
      .filter { it.secondKeyStroke == null }
      .map { modifiers(it.firstKeyStroke.modifiers and MODIFIERS) }
  }
}

/** Whether [event] happened in the window [toolWindow] is in, and not another project's. */
private fun inWindowOf(toolWindow: ToolWindow, event: AWTEvent): Boolean {
  val source: Component = event.source as? Component ?: return false
  val window: Window? = source as? Window ?: SwingUtilities.getWindowAncestor(source)
  return window != null && window == SwingUtilities.getWindowAncestor(toolWindow.component)
}
