package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.ui.CommandPalette
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.protocol.T3Client
import java.awt.Dimension
import java.net.URI
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/**
 * Opens the "New thread in..." picker centred on [project]'s window. [onPick] gets the T3
 * project's id, null for "No project", after the popup has closed.
 */
fun showNewThreadIn(project: Project, onPick: (projectId: String?) -> Unit) {
  val client: T3Client = T3.get().client
  val icons: ProjectIcons = ProjectIcons.get()
  // The IDE's open project, matched to a T3 project, is upstream's contextual project
  val filter: ProjectFilter = ProjectAwareness.getInstance(project).filter.value
  val preferredProjectId: String? = when (filter) {
    ProjectFilter.All -> null
    is ProjectFilter.Only -> filter.ids.firstOrNull()
  }
  // Upstream labels a remote environment by name; the plugin has one server, named by its host
  val locationLabel: String =
    if (T3.serverIsLocal()) "Local" else hostOf(T3Settings.get().serverUrl)
  var popup: JBPopup? = null
  val panel: JComponent = JewelComposePanel {
    val projects by client.projects.collectAsState()
    val threads by client.threads.collectAsState()
    val favicons by icons.favicons.collectAsState()
    LaunchedEffect(projects) { icons.ensure(projects) }
    CommandPalette(
      projects = projects,
      threads = threads,
      favicons = favicons,
      preferredProjectId = preferredProjectId,
      locationLabel = locationLabel,
      isMac = SystemInfo.isMac,
      onPick = { projectId ->
        popup?.cancel()
        onPick(projectId)
      },
      // Upstream goes back to the root palette; the plugin has none, so back closes too
      onBack = { popup?.cancel() },
      onClose = { popup?.cancel() },
      modifier = Modifier.fillMaxSize(),
    )
  }
  panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(paletteHeight(client)))
  // The wrapper isn't focusable; Swing focus has to land on the ComposePanel inside it for
  // Compose to see keys at all, else nothing works until a click inside. Found by type, since
  // the wrapper's own `composePanel` getter is internal Jewel API and fails `verifyPlugin`
  val focusable: JComponent = UIUtil.findComponentOfType(panel, ComposePanel::class.java) ?: panel
  popup = JBPopupFactory.getInstance()
    .createComponentPopupBuilder(panel, focusable)
    .setRequestFocus(true)
    .setFocusable(true)
    .setCancelOnClickOutside(true)
    .setCancelKeyEnabled(true)
    .setResizable(false)
    .setMovable(false)
    .createPopup()
  popup.showCenteredInCurrentWindow(project)
}

/**
 * A Swing popup doesn't follow its Compose content's size, so this sizes it for every row up
 * front: the search field, the heading, a row per project plus "No project", the legend. At
 * most 480, as upstream's `max-h-[min(28rem,70vh)]` list.
 */
private fun paletteHeight(client: T3Client): Int {
  val rows: Int = client.projects.value.size + 1
  return (52 + 16 + 28 + rows * 44 + 36).coerceAtMost(480)
}

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
