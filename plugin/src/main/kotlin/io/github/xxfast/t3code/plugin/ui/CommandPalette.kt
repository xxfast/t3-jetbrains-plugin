package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The keys `resolveShortcutCommand` reads, as a browser's lowercase `event.key`. */
private val SHORTCUT_KEYS: Map<Key, String> = mapOf(
  Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4", Key.Five to "5",
  Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9", Key.N to "n",
)

/**
 * The command palette's "New thread in..." submenu (apps/web/src/components/CommandPalette.tsx,
 * `action:new-thread-in` at 1634): a search field with a back arrow, the projects, the legend.
 * [onPick] gets the project's id, null for "No project".
 */
@Composable
fun CommandPalette(
  projects: List<Project>,
  threads: List<Thread>,
  favicons: Map<String, ImageBitmap>,
  preferredProjectId: String?,
  locationLabel: String,
  isMac: Boolean,
  onPick: (projectId: String?) -> Unit,
  onBack: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val query: TextFieldState = rememberTextFieldState()
  val text: String = query.text.toString()
  val groups: List<CommandPaletteGroup> =
    remember(projects, threads, preferredProjectId, locationLabel, text) {
      newThreadInGroups(projects, threads, preferredProjectId, locationLabel, text)
    }
  val items: List<CommandPaletteItem> = groups.flatMap { it.items }
  // Upstream's `autoHighlight="always"`: a new query highlights the first row again
  var highlighted: Int by remember(text, groups) {
    mutableIntStateOf(nextEnabledIndex(items, from = -1, step = 1))
  }
  // Bumped by arrow keys only, so hovering never scrolls the list
  var revealed: Int by remember { mutableIntStateOf(0) }
  val focus: FocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }

  // `handleKeyDown`, CommandPalette.tsx:2894, plus the list's own arrows and Enter
  fun handleKeyDown(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command: String? = SHORTCUT_KEYS[event.key]?.let { key ->
      resolveShortcutCommand(
        key = key,
        meta = event.isMetaPressed,
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
        isMac = isMac,
      )
    }
    if (command != null) {
      items.firstOrNull { it.shortcutCommand == command && !it.disabled }
        ?.let { onPick(it.projectId) }
      return true
    }
    if (event.key == Key.Backspace && text.isEmpty()) {
      onBack()
      return true
    }
    if (event.isCtrlPressed || event.isShiftPressed || event.isAltPressed || event.isMetaPressed) {
      return false
    }
    when (event.key) {
      Key.DirectionDown, Key.DirectionUp -> {
        val step: Int = if (event.key == Key.DirectionDown) 1 else -1
        highlighted = nextEnabledIndex(items, highlighted, step)
        revealed++
      }
      Key.Enter, Key.NumPadEnter ->
        items.getOrNull(highlighted)?.takeUnless { it.disabled }?.let { onPick(it.projectId) }
      Key.Escape -> onClose()
      else -> return false
    }
    return true
  }

  Column(modifier.width(640.dp).heightIn(max = 480.dp)) {
    TextField(
      state = query,
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
        .focusRequester(focus)
        .onPreviewKeyEvent(::handleKeyDown),
      placeholder = { Text("Search...") },
      leadingIcon = {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 6.dp).size(20.dp)) {
          Icon(AllIconsKeys.Actions.Back, contentDescription = "Back")
        }
      },
    )
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    CommandPaletteContent(
      groups = groups,
      highlighted = items.getOrNull(highlighted)?.value,
      revealed = revealed,
      projects = projects,
      favicons = favicons,
      isMac = isMac,
      onHighlight = { value -> highlighted = items.indexOfFirst { it.value == value } },
      onPick = onPick,
      modifier = Modifier.weight(1f),
    )
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    CommandPaletteFooter()
  }
}

/**
 * The scrolling results, apps/web/src/components/CommandPaletteResults.tsx: each group's heading
 * then its rows, or the empty state.
 */
@Composable
private fun CommandPaletteContent(
  groups: List<CommandPaletteGroup>,
  highlighted: String?,
  revealed: Int,
  projects: List<Project>,
  favicons: Map<String, ImageBitmap>,
  isMac: Boolean,
  onHighlight: (value: String) -> Unit,
  onPick: (projectId: String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (groups.isEmpty()) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.TopCenter) {
      Text(
        "No matching commands, projects, or threads.",
        color = JewelTheme.globalColors.text.info,
      )
    }
    return
  }
  val byId: Map<String, Project> = remember(projects) { projects.associateBy { it.id } }
  Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
    groups.forEach { group ->
      Text(
        group.label,
        style = small(),
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      )
      group.items.forEach { item ->
        CommandPaletteResultRow(
          item = item,
          project = item.projectId?.let(byId::get),
          favicon = item.projectId?.let(favicons::get),
          active = item.value == highlighted,
          revealed = revealed,
          shortcutLabel = shortcutLabelForCommand(item.shortcutCommand, isMac),
          onHover = { if (!item.disabled) onHighlight(item.value) },
          onClick = { if (!item.disabled) onPick(item.projectId) },
        )
      }
    }
  }
}

/**
 * `CommandPaletteResultRow`, CommandPaletteResults.tsx:185: icon, title over a muted
 * description, the shortcut on the right. The highlighted row scrolls into view on [revealed].
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent
@Composable
private fun CommandPaletteResultRow(
  item: CommandPaletteItem,
  project: Project?,
  favicon: ImageBitmap?,
  active: Boolean,
  revealed: Int,
  shortcutLabel: String?,
  onHover: () -> Unit,
  onClick: () -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val requester: BringIntoViewRequester = remember { BringIntoViewRequester() }
  LaunchedEffect(revealed) { if (active && revealed > 0) requester.bringIntoView() }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(44.dp)
      .bringIntoViewRequester(requester)
      .clip(RoundedCornerShape(6.dp))
      .background(if (active) subtle(0.1f) else Color.Transparent)
      // The pointer moving, not a row sliding under it as the list scrolls to a key press.
      // cmdk highlights on `pointermove` for the same reason
      .onPointerEvent(PointerEventType.Move) { onHover() }
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = !item.disabled,
        onClick = onClick,
      )
      .alpha(if (item.disabled) 0.5f else 1f)
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (project != null) {
      ProjectIcon(project, favicon)
    } else {
      // Upstream's MessageSquareDashedIcon; the IDE bundles nothing closer
      Icon(
        AllIconsKeys.Nodes.Folder,
        contentDescription = null,
        modifier = Modifier.size(16.dp).alpha(0.5f),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (item.description != null) {
        Text(
          item.description,
          style = small(),
          color = JewelTheme.globalColors.text.info,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (shortcutLabel != null) {
      Text(shortcutLabel, style = small(), color = JewelTheme.globalColors.text.info)
    }
  }
}

/**
 * The legend, CommandPaletteContent.tsx:57. "Enter Select" because `footerActionLabel`
 * (CommandPalette.tsx:3229) says so outside the browse and create flows, Backspace because this
 * is always a submenu.
 */
@Composable
private fun CommandPaletteFooter() {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    KbdGroup(listOf("↑", "↓"), "Navigate")
    KbdGroup(listOf("Enter"), "Select")
    KbdGroup(listOf("Backspace"), "Back")
    KbdGroup(listOf("Esc"), "Close")
  }
}

/** Upstream's `KbdGroup`: key caps, then what they do. */
@Composable
private fun KbdGroup(keys: List<String>, label: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    keys.forEach { key -> Kbd(key) }
    Text(label, style = small(), color = JewelTheme.globalColors.text.info)
  }
}

@Composable
private fun Kbd(key: String) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(subtle(0.08f))
      .padding(horizontal = 5.dp, vertical = 1.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(key, style = small(), color = JewelTheme.globalColors.text.info)
  }
}
