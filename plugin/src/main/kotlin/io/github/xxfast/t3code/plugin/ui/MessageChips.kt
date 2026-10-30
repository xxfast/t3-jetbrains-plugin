package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URLDecoder
import java.net.URLEncoder
import javax.swing.Icon
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Image as CommonmarkImage
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text as CommonmarkText
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.extensions.ImageRendererExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// Chips in a message's markdown, upstream's `ContextChip` (`ContextChip.tsx`) where
// `ChatMarkdown.tsx` draws one: a context link (`renderContextReference`), a `$skill`
// (`renderSkillInlineMarkdownChildren`), a file link or an inline code path (`fileLinkChip`).
// Jewel's text carries inline content for images only, so the parser gets a post-processor that
// turns each of those into a synthetic `t3-chip://` image, and the renderer an image extension
// that draws the chip in its place. The rules live in `MessageChipsLogic.kt`.

/** The scheme of the synthetic images: `t3-chip://<what>?<fields>`. */
private const val CHIP_SCHEME: String = "t3-chip://"

/** A `t3-chip://` image, decoded. */
sealed interface MessageChipSource {
  data class Context(val kind: String, val contextId: String, val label: String) : MessageChipSource

  data class Skill(val name: String, val label: String) : MessageChipSource

  data class File(val file: FilePathPosition) : MessageChipSource
}

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)

private fun chipSource(what: String, vararg fields: Pair<String, String?>): String =
  CHIP_SCHEME + what + "?" + fields
    .mapNotNull { (key, value) -> value?.let { "$key=${encode(it)}" } }
    .joinToString("&")

/** The chip a `t3-chip://` image [source] stands for, null for any other image. */
fun messageChipSource(source: String): MessageChipSource? {
  if (!source.startsWith(CHIP_SCHEME)) return null
  val what: String = source.removePrefix(CHIP_SCHEME).substringBefore('?')
  val fields: Map<String, String> = source.substringAfter('?', "")
    .split('&')
    .filter { it.isNotEmpty() }
    .associate { field -> field.substringBefore('=') to decode(field.substringAfter('=', "")) }
  return when (what) {
    "context" -> MessageChipSource.Context(
      kind = fields["kind"] ?: return null,
      contextId = fields["id"] ?: return null,
      label = fields["label"] ?: return null,
    )

    "skill" -> MessageChipSource.Skill(
      name = fields["name"] ?: return null,
      label = fields["label"] ?: return null,
    )

    "file" -> MessageChipSource.File(
      FilePathPosition(
        path = fields["path"] ?: return null,
        line = fields["line"]?.toIntOrNull(),
        column = fields["column"]?.toIntOrNull(),
      ),
    )

    else -> null
  }
}

private fun textOf(node: Node): String = buildString {
  var child: Node? = node.firstChild
  while (child != null) {
    when (child) {
      is CommonmarkText -> append(child.literal)
      is Code -> append(child.literal)
      else -> append(textOf(child))
    }
    child = child.next
  }
}

private fun chipImage(source: String, alt: String): CommonmarkImage =
  CommonmarkImage(source, null).also { it.appendChild(CommonmarkText(alt)) }

/**
 * Plants a `t3-chip://` image for every context link and image, `$skill` (one of [skills]),
 * file link and inline code path, which [MessageChipsRendererExtension] draws. A file link
 * whose label isn't the file keeps its text and gets the chip after it, as `ChatMarkdown`'s
 * `a` does. Goes in ahead of other processors that touch images.
 */
class MessageChipsProcessorExtension(
  private val skills: List<ServerProviderSkill>,
) : MarkdownProcessorExtension {
  override val parserExtension: Parser.ParserExtension = Parser.ParserExtension { builder ->
    builder.postProcessor(ChipPostProcessor())
  }

  private inner class ChipPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
      node.accept(
        object : AbstractVisitor() {
          override fun visit(link: Link) {
            val destination: String = link.destination.orEmpty()
            val context: Pair<String, String>? = parseComposerContextHref(destination)
            if (context != null) {
              val (kind: String, contextId: String) = context
              val label: String = textOf(link).ifEmpty { contextId }
              val source: String =
                chipSource("context", "kind" to kind, "id" to contextId, "label" to label)
              link.insertBefore(chipImage(source, label))
              link.unlink()
              return
            }
            val file: FilePathPosition? = parseMarkdownFileLink(destination)
            if (file != null) {
              val label: String = textOf(link)
              val image: CommonmarkImage = chipImage(fileSource(file), fileLinkLabel(file))
              if (!isMarkdownFileLinkLabel(label, destination)) {
                // `{children} {chip}`
                while (link.firstChild != null) link.insertBefore(link.firstChild)
                link.insertBefore(CommonmarkText(" "))
              }
              link.insertBefore(image)
              link.unlink()
              return
            }
            // Skills don't chip inside a link, `renderSkillInlineMarkdownChildren` skips `a`
          }

          override fun visit(image: CommonmarkImage) {
            val context: Pair<String, String> =
              parseComposerContextHref(image.destination.orEmpty()) ?: return
            val (kind: String, contextId: String) = context
            val label: String = textOf(image).ifEmpty { contextId }
            image.destination =
              chipSource("context", "kind" to kind, "id" to contextId, "label" to label)
          }

          override fun visit(code: Code) {
            val candidate: String = inlineCodeFilePathCandidate(code.literal) ?: return
            val file: FilePathPosition = parseMarkdownFileLink(candidate) ?: return
            code.insertBefore(chipImage(fileSource(file), fileLinkLabel(file)))
            code.unlink()
          }

          override fun visit(text: CommonmarkText) {
            val matches: List<InlineSkillMatch> = matchInlineSkills(text.literal, skills)
            if (matches.isEmpty()) return
            val literal: String = text.literal
            var cursor = 0
            for (match in matches) {
              if (match.start > cursor) {
                text.insertBefore(CommonmarkText(literal.substring(cursor, match.start)))
              }
              val label: String = formatProviderSkillDisplayName(match.skill)
              val source: String =
                chipSource("skill", "name" to match.skill.name, "label" to label)
              text.insertBefore(chipImage(source, label))
              cursor = match.end
            }
            if (cursor < literal.length) {
              text.insertBefore(CommonmarkText(literal.substring(cursor)))
            }
            text.unlink()
          }
        },
      )
      return node
    }

    private fun fileSource(file: FilePathPosition): String = chipSource(
      "file",
      "path" to file.path,
      "line" to file.line?.toString(),
      "column" to file.column?.toString(),
    )
  }
}

/** What a click on a chip does in this window. */
class MessageChipActions(
  val onOpenFile: (path: String, line: Int?) -> Unit = { _, _ -> },
  val onOpenUrl: (url: String) -> Unit = {},
  val onOpenThread: (threadId: String) -> Unit = {},
)

/** The chips' actions, from the view that hosts the markdown. */
val LocalMessageChipActions: ProvidableCompositionLocal<MessageChipActions> =
  staticCompositionLocalOf { MessageChipActions() }

/** The message's context records by context id, for its context chips. */
val LocalMessageContextRecords: ProvidableCompositionLocal<Map<String, JsonElement>> =
  staticCompositionLocalOf { emptyMap() }

/** [content] with [records] as the context records its chips resolve against. */
@Composable
fun ProvideMessageContextRecords(
  records: Map<String, JsonElement>,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(LocalMessageContextRecords provides records, content = content)
}

/** The theme's chip inks, [contextChipVariables], from the view that hosts the markdown. */
val LocalContextChipVariables: ProvidableCompositionLocal<Map<String, String>> =
  staticCompositionLocalOf { emptyMap() }

/**
 * True for chips in a user bubble, which `TimelineUserMessageRow` inks in
 * [CONTEXT_CHIP_MESSAGE_VARIABLE] when the theme sets it.
 */
val LocalUserMessageChips: ProvidableCompositionLocal<Boolean> =
  staticCompositionLocalOf { false }

/**
 * [contextChipVariables] for the theme: [canvas] is the page the timeline sits on, the surfaces
 * are the ChatView's. A theme change changes them, and so solves the inks again.
 */
@Composable
fun rememberContextChipVariables(canvas: Color): Map<String, String> {
  val colors = ThemeColors(
    canvas = canvas.themeHex(),
    surface = subtle(0.04f).themeHex(), // a card, `ChangedFilesCard`
    surfaceRaised = composerGlassSurface().themeHex(),
    messageSurface = subtle(0.06f).themeHex(), // the user bubble
    messageForeground = JewelTheme.globalColors.text.normal.themeHex(),
    errorForeground = JewelTheme.globalColors.text.error.themeHex(),
  )
  return remember(colors) { contextChipVariables(colors) }
}

/** `#rrggbbaa`, what [parseThemeColor] reads. */
private fun Color.themeHex(): String = "#%08x".format(Integer.rotateLeft(toArgb(), 8))

/** A chip variable's `#rrggbb`, or `#rrggbbaa` for the message foreground, as a colour. */
private fun themeHexColor(value: String): Color? {
  val digits: Long = value.removePrefix("#").toLongOrNull(16) ?: return null
  return when (value.length) {
    7 -> Color(0xFF000000L or digits)
    9 -> Color(((digits and 0xFF) shl 24) or (digits ushr 8))
    else -> null
  }
}

/**
 * What a chip's `--context-chip-ink` comes to on this theme: the solved [variables],
 * `currentColor` as [foreground], `--destructive-foreground` as [error], and the bubble's
 * [message] ink when the chip is in one.
 */
@Immutable
data class ContextChipInks(
  val variables: Map<String, String> = emptyMap(),
  val foreground: Color = Color.Unspecified,
  val error: Color = Color.Unspecified,
  val message: Color? = null,
) {
  /**
   * `contextChipVariants`' `--context-chip-accent` for [kind] and [state], then the bubble's
   * `--context-chip-message` over it (`TimelineUserMessageRow.tsx`'s `BUBBLE_CHIP_CLASS_NAME`).
   */
  fun ink(kind: MessageChipKind, state: ContextChipState? = null): Color {
    if (message != null) return message
    return when (state) {
      ContextChipState.UNRESOLVED -> foreground
      ContextChipState.INVALID ->
        variables[CONTEXT_CHIP_INVALID_VARIABLE]?.let(::themeHexColor) ?: error
      null -> kind.accent
        ?.let { variables[contextChipColorVariable(it)] }
        ?.let(::themeHexColor)
        ?: foreground
    }
  }
}

/** The chip inks where this is composed: [LocalContextChipVariables], in a bubble or not. */
@Composable
fun currentContextChipInks(): ContextChipInks {
  val variables: Map<String, String> = LocalContextChipVariables.current
  val message: Color? = variables[CONTEXT_CHIP_MESSAGE_VARIABLE]
    ?.takeIf { LocalUserMessageChips.current }
    ?.let(::themeHexColor)
  return ContextChipInks(
    variables = variables,
    foreground = JewelTheme.globalColors.text.normal,
    error = JewelTheme.globalColors.text.error,
    message = message,
  )
}

/**
 * Draws the `t3-chip://` images [MessageChipsProcessorExtension] plants; any other image goes to
 * [fallback], since Jewel only asks the first image renderer it finds.
 */
class MessageChipsRendererExtension(
  private val fallback: ImageRendererExtension,
) : MarkdownRendererExtension {
  override val imageRendererExtension: ImageRendererExtension = object : ImageRendererExtension {
    @Composable
    override fun renderImageContent(image: InlineMarkdown.Image): InlineTextContent {
      val source: MessageChipSource =
        messageChipSource(image.source) ?: return fallback.renderImageContent(image)
      val records: Map<String, JsonElement> = LocalMessageContextRecords.current
      val chip: MessageChip = remember(source, records) {
        when (source) {
          is MessageChipSource.Context ->
            messageContextChip(
              source.kind,
              source.contextId,
              source.label,
              records[source.contextId],
            )

          is MessageChipSource.Skill -> MessageChip(
            kind = MessageChipKind.SKILL,
            label = source.label,
            icon = MessageChipIcon.SKILL,
            tooltip = "$${source.name}",
          )

          is MessageChipSource.File -> fileLinkChip(source.file)
        }
      }
      return messageChipContent(chip)
    }
  }
}

// `contextChipVariants`: no frame, an icon of `1em` and a `gap-[0.2em]`, the label at the
// paragraph's size in medium; an attachment's size after it at `text-3xs` with `gap-[0.33em]`
private const val CHIP_ICON_EM: Float = 1f
private const val CHIP_GAP_EM: Float = 0.2f
private const val CHIP_DETAIL_GAP_EM: Float = 0.33f
private const val CHIP_DETAIL_SCALE: Float = 0.7f
private const val CHIP_WIDTH_SLACK_PX: Float = 1f

/** The widest a chip's label gets before it ellipsises, upstream's `max-w-72` on a name. */
private val CHIP_LABEL_MAX_WIDTH: Dp = 288.dp

/** The chip as inline text content: a placeholder the size of the chip, and the chip in it. */
@Composable
private fun messageChipContent(chip: MessageChip): InlineTextContent {
  val density: Density = LocalDensity.current
  val base: TextStyle = JewelTheme.defaultTextStyle
  val labelStyle: TextStyle = base.copy(fontWeight = FontWeight.Medium)
  val detailStyle: TextStyle = base.copy(fontSize = base.fontSize * CHIP_DETAIL_SCALE)
  val measurer = rememberTextMeasurer()
  val em: Float = with(density) { base.fontSize.toPx() }
  val label: IntSize = measurer.measure(chip.label, labelStyle, maxLines = 1).size
  val labelWidth: Float =
    minOf(label.width.toFloat(), with(density) { CHIP_LABEL_MAX_WIDTH.toPx() })
  val detailWidth: Float = chip.detail
    ?.let { measurer.measure(it, detailStyle, maxLines = 1).size.width + CHIP_DETAIL_GAP_EM * em }
    ?: 0f
  // Whole pixels and a little slack: the placeholder's width rounds on its way through sp, and
  // the label ellipsises on any shortfall, even a fraction of a pixel
  val width: Float =
    ceil((CHIP_ICON_EM + CHIP_GAP_EM) * em + labelWidth + detailWidth) + CHIP_WIDTH_SLACK_PX
  // A line of the label's own text, centred on the paragraph's: the same font at the same size,
  // so the label sits on the paragraph's baseline, upstream's `self-baseline`
  val height: Float = label.height.toFloat()
  return InlineTextContent(
    Placeholder(
      width = with(density) { width.toSp() },
      height = with(density) { height.toSp() },
      placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
    ),
  ) {
    MessageChipView(chip, labelStyle, detailStyle, em)
  }
}

/**
 * `ContextChip`: the icon and the label in the kind's ink, no frame. A chip that does something
 * underlines its label in the ink on hover, `ContextChipLabel`'s `decoration-transparent`.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
private fun MessageChipView(
  chip: MessageChip,
  labelStyle: TextStyle,
  detailStyle: TextStyle,
  em: Float,
) {
  val density: Density = LocalDensity.current
  val state: ContextChipState? = ContextChipState.UNRESOLVED.takeIf { chip.unresolved }
  val ink: Color = currentContextChipInks().ink(chip.kind, state)
  val actions: MessageChipActions = LocalMessageChipActions.current
  val action: MessageChipAction? = chip.action
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val frame: Modifier = Modifier
    .fillMaxSize()
    .then(
      if (action == null) {
        Modifier
      } else {
        Modifier
          .pointerHoverIcon(PointerIcon.Hand)
          .clickable(interactionSource = interactions, indication = null) {
            when (action) {
              is MessageChipAction.OpenFile -> actions.onOpenFile(action.path, action.line)
              is MessageChipAction.OpenUrl -> actions.onOpenUrl(action.url)
              is MessageChipAction.OpenThread -> actions.onOpenThread(action.threadId)
            }
          }
      },
    )
  val decoration: TextDecoration? = TextDecoration.Underline.takeIf { action != null && hovered }
  Tooltip({ Text(chip.tooltip.orEmpty()) }, enabled = !chip.tooltip.isNullOrEmpty()) {
    Box(frame, contentAlignment = Alignment.CenterStart) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(with(density) { (CHIP_GAP_EM * em).toDp() }),
      ) {
        MessageChipIconView(chip, ink, with(density) { (CHIP_ICON_EM * em).toDp() })
        // `items-baseline`: the size sits on the label's baseline
        Row(
          horizontalArrangement =
            Arrangement.spacedBy(with(density) { (CHIP_DETAIL_GAP_EM * em).toDp() }),
        ) {
          Text(
            text = chip.label,
            style = labelStyle.copy(textDecoration = decoration),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = CHIP_LABEL_MAX_WIDTH).alignByBaseline(),
          )
          chip.detail?.let { detail ->
            Text(
              text = detail,
              style = detailStyle.copy(textDecoration = decoration),
              color = ink,
              maxLines = 1,
              modifier = Modifier.alignByBaseline(),
            )
          }
        }
      }
    }
  }
}

/**
 * The chip's icon: the IDE's icon for the file's type where upstream asks the workspace
 * (`WorkspaceEntryIcon`), lucide's glyphs where upstream draws them.
 */
@Composable
private fun MessageChipIconView(chip: MessageChip, tint: Color, size: Dp) {
  val glyph: String? = when (chip.icon) {
    MessageChipIcon.SKILL -> "box" // `SKILL_CHIP_ICON_SVG`
    MessageChipIcon.VIDEO -> "film"
    MessageChipIcon.THREAD -> "layers"
    MessageChipIcon.TERMINAL -> "terminal"
    MessageChipIcon.ELEMENT -> "mouse-pointer-click"
    MessageChipIcon.PULL_REQUEST -> "git-pull-request"
    MessageChipIcon.PULL_REQUEST_DRAFT -> "git-pull-request-draft"
    MessageChipIcon.PULL_REQUEST_MERGED -> "git-merge"
    MessageChipIcon.PULL_REQUEST_CLOSED -> "git-pull-request-closed"
    MessageChipIcon.REVIEW_COMMENT -> "message-circle"
    MessageChipIcon.UNAVAILABLE -> "circle-dashed"
    MessageChipIcon.FILE, MessageChipIcon.FOLDER, MessageChipIcon.IMAGE -> null
  }
  if (glyph != null) {
    Glyph(glyph, tint, size)
    return
  }
  val scale: Float = LocalDensity.current.density
  val bitmap: ImageBitmap? = remember(chip.icon, chip.iconPath, scale) {
    chip.iconPath?.takeIf { chip.icon == MessageChipIcon.FILE }?.let { fileTypeIcon(it, scale) }
  }
  // A document's icon is stroked in the ink, `--document-icon-stroke`; other types keep theirs
  val filter: ColorFilter? = ColorFilter.tint(tint).takeIf { chip.kind.isDocument }
  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = null,
      modifier = Modifier.size(size),
      colorFilter = filter,
    )
    return
  }
  val key: IconKey = when (chip.icon) {
    MessageChipIcon.FOLDER -> AllIconsKeys.Nodes.Folder
    MessageChipIcon.IMAGE -> AllIconsKeys.FileTypes.Image
    else -> AllIconsKeys.FileTypes.Any_type
  }
  Icon(key = key, contentDescription = null, modifier = Modifier.size(size), colorFilter = filter)
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
