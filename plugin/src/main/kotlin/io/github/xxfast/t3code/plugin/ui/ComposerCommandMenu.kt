package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * One row of the composer's trigger menu, upstream's `ComposerCommandItem` reduced to what a
 * row draws: [label] then [description], an optional leading [icon] and trailing [badge].
 * [onPick] is whatever the trigger does with it, usually a replacement of the token.
 */
class ComposerMenuItem(
  val id: String,
  val label: String,
  val description: String,
  val icon: IconKey? = null,
  val badge: ComposerMenuBadge? = null,
  val onPick: () -> Unit,
)

/** A row's trailing badge, `SkillSourceBadge`'s shape: an icon and a label. */
class ComposerMenuBadge(val label: String, val icon: IconKey? = null)

/**
 * What the menu shows for one [kind] of trigger: its [items], or while there are none, the
 * loading or empty line [composerMenuStatusText] picks.
 */
class ComposerMenu(
  val kind: ComposerTriggerKind,
  val items: List<ComposerMenuItem>,
  val isLoading: Boolean = false,
  val emptyStateText: String? = null,
)

/** `SKILL_SOURCE_ICON_BY_KIND`, ComposerCommandMenu.tsx:249, in the IDE's icons. */
val ProviderSkillSourceKind.icon: IconKey
  get() = when (this) {
    ProviderSkillSourceKind.APP -> AllIconsKeys.Nodes.Plugin
    ProviderSkillSourceKind.REPO, ProviderSkillSourceKind.PROJECT -> AllIconsKeys.Nodes.Folder
    ProviderSkillSourceKind.PERSONAL -> AllIconsKeys.General.User
    ProviderSkillSourceKind.SYSTEM -> AllIconsKeys.General.Settings
    ProviderSkillSourceKind.OTHER -> AllIconsKeys.Nodes.Package
  }

/**
 * `ComposerCommandMenu`, apps/web/src/components/chat/ComposerCommandMenu.tsx: a drawer above
 * the prompt, its bottom [MENU_DRAWER_OVERLAP] tucked behind the box (`ComposerBanner.Surface`).
 * The rows scroll past 18rem (`max-h-72`); [activeItemId] is highlighted and kept in view. The
 * pointer moving over a row highlights it, a press picks it without taking the prompt's focus
 * (upstream's `onMouseDown` `preventDefault`).
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent
@Composable
fun ComposerCommandMenu(
  menu: ComposerMenu,
  activeItemId: String?,
  onHighlight: (itemId: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val dark: Boolean = JewelTheme.isDark
  val outline: Color =
    if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.08f)
  val shape: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = MENU_DRAWER_INSET)
      .clip(shape)
      .background(composerGlassSurface())
      .border(1.dp, outline, shape)
      .padding(bottom = MENU_DRAWER_OVERLAP),
  ) {
    if (menu.items.isEmpty()) {
      Text(
        text = composerMenuStatusText(menu.kind, menu.isLoading, menu.emptyStateText),
        style = small(),
        color = muted(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
      )
      return@Box
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = MENU_MAX_HEIGHT)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
      menu.items.forEach { item ->
        ComposerCommandMenuItem(
          item = item,
          active = item.id == activeItemId,
          onHover = { if (item.id != activeItemId) onHighlight(item.id) },
        )
      }
    }
  }
}

/**
 * `ComposerCommandMenuItem`: the icon, the label (`font-medium`, at most 45% of the row), the
 * muted description taking the rest, the badge at the end.
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent
@Composable
private fun ComposerCommandMenuItem(item: ComposerMenuItem, active: Boolean, onHover: () -> Unit) {
  val requester: BringIntoViewRequester = remember { BringIntoViewRequester() }
  // `scrollIntoView({ block: "nearest" })` on the active item
  LaunchedEffect(active) { if (active) requester.bringIntoView() }
  Layout(
    modifier = Modifier
      .fillMaxWidth()
      .bringIntoViewRequester(requester)
      .clip(RoundedCornerShape(8.dp))
      .background(if (active) subtle(0.1f) else Color.Transparent)
      // The pointer moving, not a row sliding under it as the list scrolls to a key press
      .onPointerEvent(PointerEventType.Move) { onHover() }
      .onPointerEvent(PointerEventType.Press) { event ->
        event.changes.forEach { it.consume() }
        item.onPick()
      }
      .padding(horizontal = 8.dp, vertical = 6.dp),
    contents = listOf(
      { item.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
      {
        Text(
          text = item.label,
          style = small().copy(fontWeight = FontWeight.Medium),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      {
        Text(
          text = item.description,
          style = small(),
          color = muted(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      { item.badge?.let { ComposerMenuBadgeChip(it) } },
    ),
  ) { (icon, label, description, badge), constraints ->
    // `gap-2` throughout; the label shrinks first to 45%, the description takes what's left
    val gap: Int = 8.dp.roundToPx()
    val width: Int = constraints.maxWidth
    val loose = Constraints(maxWidth = width)
    val iconPlaceable: Placeable? = icon.firstOrNull()?.measure(loose)
    val badgePlaceable: Placeable? = badge.firstOrNull()?.measure(loose)
    val fixed: Int = (iconPlaceable?.let { it.width + gap } ?: 0) +
      (badgePlaceable?.let { it.width + gap } ?: 0)
    val textWidth: Int = (width - fixed).coerceAtLeast(0)
    val labelPlaceable: Placeable = label.first().measure(
      Constraints(maxWidth = (textWidth * 0.45f).toInt()),
    )
    val descriptionWidth: Int = (textWidth - labelPlaceable.width - gap).coerceAtLeast(0)
    val descriptionPlaceable: Placeable = description.first().measure(
      Constraints(maxWidth = descriptionWidth),
    )
    val height: Int = listOfNotNull(
      iconPlaceable?.height,
      labelPlaceable.height,
      descriptionPlaceable.height,
      badgePlaceable?.height,
    ).max()
    layout(width, height) {
      var x = 0
      fun centred(placeable: Placeable, at: Int) =
        placeable.placeRelative(at, (height - placeable.height) / 2)
      iconPlaceable?.let { centred(it, x); x += it.width + gap }
      centred(labelPlaceable, x)
      x += labelPlaceable.width + gap
      centred(descriptionPlaceable, x)
      badgePlaceable?.let { centred(it, width - it.width) }
    }
  }
}

/** Upstream's `Badge variant="secondary"`: a quiet pill with the icon then the label. */
@Composable
private fun ComposerMenuBadgeChip(badge: ComposerMenuBadge) {
  Row(
    modifier = Modifier
      .widthIn(min = 0.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(subtle(0.08f))
      .padding(horizontal = 5.dp, vertical = 1.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    badge.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(12.dp)) }
    Text(badge.label, style = small(), color = muted(), maxLines = 1)
  }
}

/**
 * The menu hanging above [box]: the drawer's bottom [MENU_DRAWER_OVERLAP] sits behind the box
 * (upstream's `-mb-[calc(1rem+1px)]`), the box draws on top.
 */
@Composable
fun ComposerWithMenuDrawer(
  drawer: (@Composable () -> Unit)?,
  box: @Composable () -> Unit,
) {
  if (drawer == null) return box()
  Layout(contents = listOf(drawer, box)) { (menu, surface), constraints ->
    val menuPlaceable: Placeable = menu.first().measure(constraints.copy(minHeight = 0))
    val boxPlaceable: Placeable = surface.first().measure(constraints.copy(minHeight = 0))
    val overlap: Int = MENU_DRAWER_OVERLAP.roundToPx()
    val top: Int = (menuPlaceable.height - overlap).coerceAtLeast(0)
    layout(boxPlaceable.width, top + boxPlaceable.height) {
      menuPlaceable.placeRelative(0, 0)
      boxPlaceable.placeRelative(0, top)
    }
  }
}

/** `--chat-composer-attachment-overlap` for a top drawer, `calc(1rem+1px)`. */
private val MENU_DRAWER_OVERLAP: Dp = 17.dp

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val MENU_DRAWER_INSET: Dp = 22.dp

/** The list's `max-h-72`, 18rem. */
private val MENU_MAX_HEIGHT: Dp = 288.dp
