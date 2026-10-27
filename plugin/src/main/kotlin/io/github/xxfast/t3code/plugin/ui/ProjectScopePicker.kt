package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.intellij.ui.awt.RelativePoint
import io.github.xxfast.t3code.protocol.Project
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Upstream's project scope menu (Sidebar.tsx's `Combobox` under the header): "Search
 * projects..." over the [filterChoices], each project with its icon and a settings button.
 * Typing highlights the first match, as `autoHighlight`; Up and Down move it, wrapping, Enter
 * picks it. A right-click on a project asks [onContextMenu], and [onHighlight] says which
 * project is highlighted and where, for the host's Shift+F10. Environment badges are #20's.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips sit on Compose's TooltipArea
@Composable
fun ProjectScopePicker(
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  filter: ProjectFilter,
  open: Set<String>,
  onPick: (ProjectFilter) -> Unit,
  onSettings: (projectId: String) -> Unit,
  onContextMenu: (projectId: String, at: RelativePoint) -> Unit,
  onHighlight: (projectId: String?, at: RelativePoint?) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  query: TextFieldState = rememberTextFieldState(),
) {
  val text: String = query.text.toString()
  val choices: FilterChoices =
    remember(projects, filter, open, text) { filterChoices(projects, filter, open, text) }
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  // `autoHighlight`: the first match while typing, the current scope when the menu opens
  var highlighted: Int by remember(choices) {
    mutableIntStateOf(if (text.isBlank()) choices.selected.coerceAtLeast(0) else 0)
  }
  // Bumped by arrow keys only, so hovering never scrolls the list
  var revealed: Int by remember { mutableIntStateOf(0) }
  val component: JComponent = LocalComponent.current
  val density: Density = LocalDensity.current
  // Where each row sits in the hosting component, for a Shift+F10 menu under it
  val anchors: MutableMap<Int, Offset> = remember(choices) { HashMap() }
  var anchored: Int by remember { mutableIntStateOf(0) }
  val highlightedProject: String? = choices.filters.getOrNull(highlighted)?.singleProject(byId)?.id
  val report: (String?, RelativePoint?) -> Unit by rememberUpdatedState(onHighlight)
  LaunchedEffect(highlightedProject, highlighted, anchored) {
    val at: RelativePoint? = anchors[highlighted]?.let { offset ->
      RelativePoint(
        component,
        Point((offset.x / density.density).toInt(), (offset.y / density.density).toInt()),
      )
    }
    report(highlightedProject, at)
  }
  val focus: FocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }

  fun handleKeyDown(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when (event.key) {
      Key.DirectionDown, Key.DirectionUp -> {
        val next: Int = moveSearchHighlight(
          highlighted, choices.filters.size, down = event.key == Key.DirectionDown,
        ) ?: return true
        highlighted = next
        revealed++
      }

      Key.Enter, Key.NumPadEnter -> choices.filters.getOrNull(highlighted)?.let(onPick)
      Key.Escape -> onClose()
      else -> return false
    }
    return true
  }

  Column(modifier) {
    TextField(
      state = query,
      modifier = Modifier
        .fillMaxWidth()
        .padding(6.dp)
        .focusRequester(focus)
        .onPreviewKeyEvent(::handleKeyDown),
      placeholder = { Text("Search projects...") },
      leadingIcon = {
        Icon(AllIconsKeys.Actions.Search, contentDescription = null, Modifier.padding(end = 6.dp))
      },
    )
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    if (choices.filters.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Text("No matching projects.", color = muted())
      }
      return@Column
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
      choices.filters.forEachIndexed { index, choice ->
        if (index == choices.divider) {
          Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        ProjectScopeRow(
          label = choices.labels[index],
          project = choice.singleProject(byId),
          favicons = favicons,
          active = index == highlighted,
          revealed = revealed,
          onPlaced = { offset ->
            anchors[index] = offset
            if (index == highlighted) anchored++
          },
          onHover = { highlighted = index },
          onClick = { onPick(choice) },
          onSettings = onSettings,
          onContextMenu = onContextMenu,
        )
      }
    }
  }
}

/**
 * One `ComboboxItem`: the project's icon (a folder for "All projects"), the label, and the
 * project's settings button, "Project settings for X".
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ProjectScopeRow(
  label: String,
  project: Project?,
  favicons: Map<String, ImageBitmap>,
  active: Boolean,
  revealed: Int,
  onPlaced: (bottomLeft: Offset) -> Unit,
  onHover: () -> Unit,
  onClick: () -> Unit,
  onSettings: (projectId: String) -> Unit,
  onContextMenu: (projectId: String, at: RelativePoint) -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val requester: BringIntoViewRequester = remember { BringIntoViewRequester() }
  LaunchedEffect(revealed) { if (active && revealed > 0) requester.bringIntoView() }
  var placed: Offset by remember { mutableStateOf(Offset.Zero) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(28.dp)
      .bringIntoViewRequester(requester)
      .onGloballyPositioned { coordinates ->
        val bottomLeft: Offset =
          coordinates.positionInRoot() + Offset(0f, coordinates.size.height.toFloat())
        if (bottomLeft != placed) {
          placed = bottomLeft
          onPlaced(bottomLeft)
        }
      }
      .clip(RoundedCornerShape(4.dp))
      .background(if (active) subtle(0.1f) else Color.Transparent)
      .onPointerEvent(PointerEventType.Move) { onHover() }
      .onPointerEvent(PointerEventType.Press) { event ->
        val mouse: MouseEvent = event.awtEventOrNull ?: return@onPointerEvent
        if (project == null || !event.buttons.isSecondaryPressed) return@onPointerEvent
        event.changes.forEach { it.consume() }
        onContextMenu(project.id, RelativePoint(mouse))
      }
      .clickable(interactionSource = interactions, indication = null, onClick = onClick)
      .padding(start = 6.dp, end = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (project != null) {
      ProjectIcon(project, favicons[project.id])
    } else {
      Icon(AllIconsKeys.Nodes.Folder, contentDescription = null, Modifier.size(16.dp))
    }
    Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (project != null) {
      val settings = "Project settings for ${project.title}"
      IconActionButton(
        key = AllIconsKeys.General.Settings,
        contentDescription = settings,
        onClick = { onSettings(project.id) },
        tooltip = { Text(settings) },
      )
    }
  }
}

/** The one project a filter scopes to, if it's exactly one. */
internal fun ProjectFilter.singleProject(byId: Map<String, Project>): Project? =
  (this as? ProjectFilter.Only)?.ids?.singleOrNull()?.let(byId::get)
