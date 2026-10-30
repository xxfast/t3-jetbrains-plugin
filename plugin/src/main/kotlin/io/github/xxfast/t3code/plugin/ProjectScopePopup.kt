package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.xxfast.t3code.plugin.actions.openProjectSettings
import io.github.xxfast.t3code.plugin.actions.showProjectMenu
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.ProjectScopePicker
import io.github.xxfast.t3code.protocol.T3Client
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import org.jetbrains.jewel.bridge.JewelComposePanel

/**
 * The Sidebar's project scope menu at [at]: [ProjectScopePicker] in the IDE's popup. A pick goes
 * to [onPick] and closes the menu; a Shift toggle goes to [onPick] too but leaves it open, as
 * upstream cancels Base UI's close for it. A row's settings button and its context menu open
 * project settings, closing the menu first as upstream's `project-settings-opened` does.
 * Shift+F10 and the context menu key open the highlighted project's menu: upstream's search box
 * takes both, and Shift+F10 is Run in the IDE's keymap, so the popup claims them for itself.
 */
fun showProjectScope(project: Project, at: RelativePoint, onPick: (ProjectFilter) -> Unit) {
  val client: T3Client = T3.get().client
  val awareness: ProjectAwareness = ProjectAwareness.getInstance(project)
  val icons: ProjectIcons = ProjectIcons.get()
  var popup: JBPopup? = null
  var highlighted: Pair<String, RelativePoint?>? = null
  fun settings(projectId: String) {
    popup?.cancel()
    openProjectSettings(project, projectId)
  }
  fun menu(projectId: String, where: RelativePoint) =
    showProjectMenu(project, projectId, where) { popup?.cancel() }
  val panel: JComponent = JewelComposePanel {
    val projects by client.projects.collectAsState()
    val filter: ProjectFilter by awareness.filter.collectAsState()
    val open: Set<String> by awareness.open.collectAsState()
    val favicons by icons.favicons.collectAsState()
    LaunchedEffect(projects) { icons.ensure(projects) }
    ProjectScopePicker(
      projects = projects,
      favicons = favicons,
      filter = filter,
      open = open,
      onPick = { choice ->
        popup?.cancel()
        if (choice != filter) onPick(choice)
      },
      onToggle = { choice -> if (choice != filter) onPick(choice) },
      onSettings = ::settings,
      onContextMenu = ::menu,
      onHighlight = { projectId, where -> highlighted = projectId?.let { it to where } },
      onClose = { popup?.cancel() },
      modifier = Modifier.fillMaxSize(),
    )
  }
  panel.preferredSize = Dimension(JBUI.scale(288), JBUI.scale(pickerHeight(client)))
  // Focus has to land on the ComposePanel inside the wrapper for Compose to see keys at all
  val focusable: JComponent = UIUtil.findComponentOfType(panel, ComposePanel::class.java) ?: panel
  val created: JBPopup = JBPopupFactory.getInstance()
    .createComponentPopupBuilder(panel, focusable)
    .setRequestFocus(true)
    .setFocusable(true)
    .setCancelOnClickOutside(true)
    .setCancelKeyEnabled(true)
    .setResizable(false)
    .setMovable(false)
    .createPopup()
  popup = created
  object : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = highlighted != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val (projectId: String, where: RelativePoint?) = highlighted ?: return
      menu(projectId, where ?: RelativePoint.getCenterOf(panel))
    }
  }.registerCustomShortcutSet(CONTEXT_MENU_KEYS, panel, created)
  created.show(at)
}

private val CONTEXT_MENU_KEYS: CustomShortcutSet = CustomShortcutSet(
  KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), null),
  KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), null),
)

/**
 * A Swing popup doesn't follow its Compose content's size, so this sizes it for every row: the
 * search box, a row each for "All projects" and every project, and a divider. At most 360.
 */
private fun pickerHeight(client: T3Client): Int {
  val rows: Int = client.projects.value.size + 1
  return (44 + 8 + 9 + rows * 28).coerceAtMost(360)
}
