package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.rememberResourcePainterProvider

// Upstream's `ContextChip` (`apps/web/src/components/ContextChip.tsx`, `contextChipVariants`)
// with `ContextChipShell` and `ContextChipAction` from `contextChipParts.tsx` and
// `ContextChip.tsx`: one chip for the composer, a user message and an assistant reply. The
// composer prompt's own chips are drawn behind a text field's text rather than composed, so they
// take the inks, ems and icons from here instead (`ComposerPromptDecorations.kt`).

// `contextChipVariants` at v0.1.5: no frame, an icon of `1em` and a `gap-[0.2em]`, the label at
// the surrounding font size in medium; a size after it at `text-3xs` with `gap-[0.33em]`
const val CONTEXT_CHIP_ICON_EM: Float = 1f
const val CONTEXT_CHIP_GAP_EM: Float = 0.2f
const val CONTEXT_CHIP_DETAIL_GAP_EM: Float = 0.33f
const val CONTEXT_CHIP_DETAIL_SCALE: Float = 0.7f

// `ContextChipAction`: `ml-[0.17em]`, `size-[1em]`, its glyph at `0.85em`, and a hover fill of
// the accent at 17%
private const val CONTEXT_CHIP_ACTION_MARGIN_EM: Float = 0.17f
private const val CONTEXT_CHIP_ACTION_GLYPH_EM: Float = 0.85f
private const val CONTEXT_CHIP_ACTION_HOVER_ALPHA: Float = 0.17f

/** The widest a chip's label gets before it ellipsises, upstream's `max-w-72` on a name. */
val CONTEXT_CHIP_LABEL_MAX_WIDTH: Dp = 288.dp

/**
 * The lucide glyph (in `resources/icons/lucide`) [icon] draws, as upstream's chips do, or null
 * for a file, folder or image, which the IDE's own icons draw ([contextChipIconKey],
 * [fileTypeIcon]).
 */
fun contextChipGlyph(icon: ContextChipIcon): String? = when (icon) {
  ContextChipIcon.SKILL -> "box" // `SKILL_CHIP_ICON_SVG`
  ContextChipIcon.VIDEO -> "film"
  ContextChipIcon.THREAD -> "layers"
  ContextChipIcon.TERMINAL -> "terminal"
  ContextChipIcon.ELEMENT -> "mouse-pointer-click"
  ContextChipIcon.PULL_REQUEST -> "git-pull-request"
  ContextChipIcon.PULL_REQUEST_DRAFT -> "git-pull-request-draft"
  ContextChipIcon.PULL_REQUEST_MERGED -> "git-merge"
  ContextChipIcon.PULL_REQUEST_CLOSED -> "git-pull-request-closed"
  ContextChipIcon.REVIEW_COMMENT -> "message-circle"
  ContextChipIcon.UNAVAILABLE -> "circle-dashed"
  ContextChipIcon.FILE, ContextChipIcon.FOLDER, ContextChipIcon.IMAGE -> null
}

/**
 * The key for [icon]: its lucide glyph, else the IDE's icon where upstream asks the workspace
 * (`WorkspaceEntryIcon`). A file with a known type paints that type's icon instead.
 */
fun contextChipIconKey(icon: ContextChipIcon): IconKey {
  val glyph: String? = contextChipGlyph(icon)
  if (glyph != null) return PathIconKey("icons/lucide/$glyph.svg", ProjectIconMarker::class.java)
  return when (icon) {
    ContextChipIcon.FOLDER -> AllIconsKeys.Nodes.Folder
    ContextChipIcon.IMAGE -> AllIconsKeys.FileTypes.Image
    else -> AllIconsKeys.FileTypes.Any_type
  }
}

/**
 * `ContextChipShell`: [icon] ([iconPath] names the file whose type icon a [ContextChipIcon.FILE]
 * shows, a [thumbnail] stands in for it, `ImageChipButton`'s preview), [label] and a smaller
 * [detail], all in [kind]'s ink for [state] ([currentContextChipInks]), with no frame. With
 * [onRemove], `ContextChipAction`'s remove button. Sized in ems of [textStyle], the text it sits
 * in or beside. [onClick] makes it a button, whose label underlines on hover
 * (`ContextChipLabel`'s `decoration-transparent`).
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
fun ContextChip(
  kind: ContextChipKind,
  label: String,
  icon: ContextChipIcon,
  modifier: Modifier = Modifier,
  iconPath: String? = null,
  detail: String? = null,
  state: ContextChipState = ContextChipState.NORMAL,
  tooltip: String? = null,
  textStyle: TextStyle = JewelTheme.defaultTextStyle,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  onRemove: (() -> Unit)? = null,
  thumbnail: ImageBitmap? = null,
) {
  val em: Dp = with(LocalDensity.current) { textStyle.fontSize.toDp() }
  val ink: Color = currentContextChipInks().ink(kind, state)
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val click: (() -> Unit)? = onClick?.takeIf { enabled }
  val frame: Modifier = if (click == null) {
    modifier
  } else {
    modifier
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interactionSource = interactions, indication = null, onClick = click)
  }
  val decoration: TextDecoration? = TextDecoration.Underline.takeIf { click != null && hovered }
  val labelStyle: TextStyle =
    textStyle.copy(fontWeight = FontWeight.Medium, textDecoration = decoration)
  val detailStyle: TextStyle = textStyle.copy(
    fontSize = textStyle.fontSize * CONTEXT_CHIP_DETAIL_SCALE,
    textDecoration = decoration,
  )
  Tooltip({ Text(tooltip.orEmpty()) }, enabled = !tooltip.isNullOrEmpty()) {
    Box(frame, contentAlignment = Alignment.CenterStart) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(em * CONTEXT_CHIP_GAP_EM),
      ) {
        val size: Dp = em * CONTEXT_CHIP_ICON_EM
        if (thumbnail != null) {
          // `ImageChipButton`'s `size-[1em] rounded-sm object-cover`
          Image(
            bitmap = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(2.dp)),
          )
        } else {
          ChipIcon(kind, icon, iconPath, ink, size)
        }
        // `items-baseline`: the size sits on the label's baseline
        Row(horizontalArrangement = Arrangement.spacedBy(em * CONTEXT_CHIP_DETAIL_GAP_EM)) {
          Text(
            text = label,
            style = labelStyle,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = CONTEXT_CHIP_LABEL_MAX_WIDTH).alignByBaseline(),
          )
          if (detail != null) {
            Text(
              text = detail,
              style = detailStyle,
              color = ink,
              maxLines = 1,
              modifier = Modifier.alignByBaseline(),
            )
          }
        }
        if (onRemove != null) ContextChipAction(ink, em, enabled, onRemove)
      }
    }
  }
}

/** `ContextChipAction`: the remove button, an `x` in the ink that fills a little on hover. */
@Composable
private fun ContextChipAction(ink: Color, em: Dp, enabled: Boolean, onRemove: () -> Unit) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val shape = RoundedCornerShape(2.dp)
  val fill: Color =
    if (enabled && hovered) ink.copy(alpha = CONTEXT_CHIP_ACTION_HOVER_ALPHA) else Color.Transparent
  Box(
    modifier = Modifier
      .padding(start = em * CONTEXT_CHIP_ACTION_MARGIN_EM)
      .size(em * CONTEXT_CHIP_ICON_EM)
      .clip(shape)
      .background(fill)
      .then(
        if (enabled) {
          Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onRemove)
        } else {
          Modifier
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    Glyph("x", ink, em * CONTEXT_CHIP_ACTION_GLYPH_EM)
  }
}

/**
 * The chip's icon: [contextChipGlyph] in the [ink], a file's type icon, else
 * [contextChipIconKey]. The IDE's icons keep their colours, but a document's, which upstream
 * strokes in the ink (`--document-icon-stroke`).
 */
@Composable
private fun ChipIcon(
  kind: ContextChipKind,
  icon: ContextChipIcon,
  iconPath: String?,
  ink: Color,
  size: Dp,
) {
  val glyph: String? = contextChipGlyph(icon)
  if (glyph != null) {
    Glyph(glyph, ink, size)
    return
  }
  val filter: ColorFilter? = ColorFilter.tint(ink).takeIf { kind.isDocument }
  val scale: Float = LocalDensity.current.density
  val bitmap: ImageBitmap? = remember(icon, iconPath, scale) {
    iconPath?.takeIf { icon == ContextChipIcon.FILE }?.let { fileTypeIcon(it, scale) }
  }
  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = null,
      modifier = Modifier.size(size),
      colorFilter = filter,
    )
    return
  }
  Icon(
    key = contextChipIconKey(icon),
    contentDescription = null,
    modifier = Modifier.size(size),
    colorFilter = filter,
  )
}

/**
 * The chips' icons as painters, for chips that are drawn rather than composed: the composer
 * prompt's. A [ContextChipIcon.FILE] with a path gets its type's icon, painted once per name.
 * Tint the glyphs ([contextChipGlyph] isn't null) in the chip's ink; they're black.
 */
@Composable
fun rememberContextChipPainters(): (icon: ContextChipIcon, iconPath: String?) -> Painter? {
  val painters: Map<ContextChipIcon, Painter> = ContextChipIcon.entries.associateWith { icon ->
    val key: IconKey = contextChipIconKey(icon)
    rememberResourcePainterProvider(key, key.iconClass).getPainter().value
  }
  val scale: Float = LocalDensity.current.density
  val files: MutableMap<String, Painter?> = remember(scale) { HashMap() }
  return { icon, iconPath ->
    val file: Painter? = iconPath?.takeIf { icon == ContextChipIcon.FILE }?.let { path ->
      files.getOrPut(fileBasename(path)) {
        fileTypeIcon(path, scale)?.let { BitmapPainter(it) }
      }
    }
    file ?: painters[icon]
  }
}

/**
 * The IDE's icon for the type of the file at [path], painted to a bitmap at [scale], or null
 * when the IDE doesn't know the type. Painted rather than wrapped as an `IconKey`: on 2025.3
 * `IntelliJIconKey.fromPlatformIcon` loads the icon's resource through this plugin's
 * classloader, which can't see the Kotlin or Markdown plugin's, and logs an error per chip.
 */
fun fileTypeIcon(path: String, scale: Float): ImageBitmap? {
  val type: FileType = FileTypeManager.getInstance().getFileTypeByFileName(fileBasename(path))
  if (type is UnknownFileType) return null
  val icon: Icon = type.icon ?: return null
  val width: Int = maxOf(1, (icon.iconWidth * scale).roundToInt())
  val height: Int = maxOf(1, (icon.iconHeight * scale).roundToInt())
  val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
  val graphics: Graphics2D = image.createGraphics()
  try {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.scale(scale.toDouble(), scale.toDouble())
    icon.paintIcon(null, graphics, 0, 0)
  } finally {
    graphics.dispose()
  }
  return image.toComposeImageBitmap()
}
