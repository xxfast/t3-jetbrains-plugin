package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.ceil
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

private fun MessageChipActions.perform(action: MessageChipAction) {
  when (action) {
    is MessageChipAction.OpenFile -> onOpenFile(action.path, action.line)
    is MessageChipAction.OpenUrl -> onOpenUrl(action.url)
    is MessageChipAction.OpenThread -> onOpenThread(action.threadId)
  }
}

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
  fun ink(kind: ContextChipKind, state: ContextChipState = ContextChipState.NORMAL): Color {
    if (message != null) return message
    return when (state) {
      ContextChipState.UNRESOLVED -> foreground
      ContextChipState.INVALID ->
        variables[CONTEXT_CHIP_INVALID_VARIABLE]?.let(::themeHexColor) ?: error
      ContextChipState.NORMAL -> kind.accent
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
            kind = ContextChipKind.SKILL,
            label = source.label,
            icon = ContextChipIcon.SKILL,
            tooltip = "$${source.name}",
          )

          is MessageChipSource.File -> fileLinkChip(source.file)
        }
      }
      return messageChipContent(chip)
    }
  }
}

// Whole pixels and a little slack: the placeholder's width rounds on its way through sp, and
// the label ellipsises on any shortfall, even a fraction of a pixel
private const val CHIP_WIDTH_SLACK_PX: Float = 1f

/**
 * The chip as inline text content: a placeholder the size of the chip, and the [ContextChip] in
 * it. The width is [ContextChip]'s own layout, measured ahead, since a placeholder can't wrap.
 */
@Composable
private fun messageChipContent(chip: MessageChip): InlineTextContent {
  val density: Density = LocalDensity.current
  val base: TextStyle = JewelTheme.defaultTextStyle
  val labelStyle: TextStyle = base.copy(fontWeight = FontWeight.Medium)
  val detailStyle: TextStyle = base.copy(fontSize = base.fontSize * CONTEXT_CHIP_DETAIL_SCALE)
  val measurer = rememberTextMeasurer()
  val em: Float = with(density) { base.fontSize.toPx() }
  val label: IntSize = measurer.measure(chip.label, labelStyle, maxLines = 1).size
  val labelWidth: Float =
    minOf(label.width.toFloat(), with(density) { CONTEXT_CHIP_LABEL_MAX_WIDTH.toPx() })
  val detailWidth: Float = chip.detail
    ?.let { detail ->
      val width: Int = measurer.measure(detail, detailStyle, maxLines = 1).size.width
      width + CONTEXT_CHIP_DETAIL_GAP_EM * em
    }
    ?: 0f
  val chrome: Float = CONTEXT_CHIP_ICON_EM + CONTEXT_CHIP_GAP_EM
  val width: Float = ceil(chrome * em + labelWidth + detailWidth) + CHIP_WIDTH_SLACK_PX
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
    val actions: MessageChipActions = LocalMessageChipActions.current
    val onClick: (() -> Unit)? = chip.action?.let { action -> { actions.perform(action) } }
    ContextChip(
      kind = chip.kind,
      label = chip.label,
      icon = chip.icon,
      modifier = Modifier.fillMaxSize(),
      iconPath = chip.iconPath,
      detail = chip.detail,
      state = chip.state,
      tooltip = chip.tooltip,
      textStyle = base,
      onClick = onClick,
    )
  }
}
