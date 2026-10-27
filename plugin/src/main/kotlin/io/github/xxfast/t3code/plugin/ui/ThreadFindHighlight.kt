package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.toSize
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.Text

/** The selected row supplies this only to its message/plan renderer, never the composer. */
class ThreadFindHighlight(
  val document: ThreadFindDocument,
  val query: String,
  val match: ThreadFindMatch?,
  val navigation: Int,
  val onReveal: suspend (Rect) -> Unit,
)

val LocalThreadFindHighlight = staticCompositionLocalOf<ThreadFindHighlight?> { null }

/** Adds spans to the rendered text. Source, links, and existing formatting stay intact. */
fun highlightThreadFindText(
  text: AnnotatedString,
  query: String,
  activeStart: Int?,
  normal: Color,
  active: Color,
): AnnotatedString = AnnotatedString.Builder(text).apply {
  findThreadSearchOccurrences(text.text, query.trim()).forEach { start ->
    addStyle(SpanStyle(background = if (start == activeStart) active else normal),
      start, start + query.trim().length)
  }
}.toAnnotatedString()

private class FindInlineRenderer(
  val delegate: InlineMarkdownRenderer,
  private val find: ThreadFindHighlight,
  private val colors: FindColors,
) : InlineMarkdownRenderer {
  override fun renderAsAnnotatedString(
    inlineMarkdown: Iterable<InlineMarkdown>,
    styling: InlinesStyling,
    enabled: Boolean,
    onUrlClicked: ((String) -> Unit)?,
  ): AnnotatedString {
    val rendered: AnnotatedString = delegate.renderAsAnnotatedString(
      inlineMarkdown, styling, enabled, onUrlClicked,
    )
    val segment: Int = find.document.segmentFor(inlineMarkdown) ?: return rendered
    val selected: ThreadFindMatch? = find.match?.takeIf { it.segment == segment }
    return highlightThreadFindText(rendered, find.query, selected?.start, colors.normal,
      colors.active)
  }
}

private class FindColors(val normal: Color, val active: Color)

/** Use the IDE's search colours, with readable fallbacks for schemes missing those keys. */
@Composable
private fun rememberFindColors(): FindColors {
  val scheme = EditorColorsManager.getInstance().globalScheme
  val normal: Color = scheme.getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)
    ?.backgroundColor?.let { Color(it.rgb) } ?: Color(0x66FFD54F)
  val active: Color = scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
    ?.backgroundColor?.let { Color(it.rgb) } ?: Color(0x99FF9800)
  return remember(normal, active) { FindColors(normal, active) }
}

@Composable
fun threadFindRenderer(
  base: MarkdownBlockRenderer,
  find: ThreadFindHighlight,
): MarkdownBlockRenderer {
  val colors: FindColors = rememberFindColors()
  return remember(base, find.document, find.query, find.match, find.navigation, colors) {
    ThreadFindMarkdownRenderer(base.rootStyling, base.rendererExtensions,
      FindInlineRenderer(base.inlineRenderer, find, colors), find, colors)
  }
}

/** The table block being rendered, so its cells bind to that table's segments only. */
private val LocalFindTable = staticCompositionLocalOf<MarkdownBlock?> { null }

private class ThreadFindMarkdownRenderer(
  rootStyling: MarkdownStyling,
  rendererExtensions: List<MarkdownRendererExtension>,
  inlineRenderer: FindInlineRenderer,
  private val find: ThreadFindHighlight,
  private val colors: FindColors,
) : DefaultMarkdownBlockRenderer(rootStyling, rendererExtensions, inlineRenderer) {
  // Jewel's table renderer draws every cell through a copy of this renderer, restyled for the
  // header. Keeping the copy ours routes cells through `RenderParagraph`, so they can reveal.
  override fun createCopy(
    rootStyling: MarkdownStyling?,
    rendererExtensions: List<MarkdownRendererExtension>?,
    inlineRenderer: InlineMarkdownRenderer?,
  ): MarkdownBlockRenderer {
    val inlines: InlineMarkdownRenderer = inlineRenderer ?: this.inlineRenderer
    return ThreadFindMarkdownRenderer(
      rootStyling ?: this.rootStyling,
      rendererExtensions ?: this.rendererExtensions,
      inlines as? FindInlineRenderer ?: FindInlineRenderer(inlines, find, colors),
      find,
      colors,
    )
  }

  @Composable
  override fun RenderBlock(
    block: MarkdownBlock,
    enabled: Boolean,
    onUrlClick: (String) -> Unit,
    modifier: Modifier,
  ) {
    if (block is MarkdownBlock.CustomBlock && block in find.document.tableCells) {
      CompositionLocalProvider(LocalFindTable provides block) {
        super.RenderBlock(block, enabled, onUrlClick, modifier)
      }
    } else {
      super.RenderBlock(block, enabled, onUrlClick, modifier)
    }
  }

  @Composable
  override fun RenderParagraph(
    block: MarkdownBlock.Paragraph,
    styling: MarkdownStyling.Paragraph,
    enabled: Boolean,
    onUrlClick: (String) -> Unit,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier,
    overflow: TextOverflow,
    softWrap: Boolean,
    maxLines: Int,
  ) {
    val table: MarkdownBlock? = LocalFindTable.current
    val segment: Int? = find.document.segmentFor(block.inlineContent) ?: table?.let {
      val text: String = (inlineRenderer as FindInlineRenderer).delegate
        .renderAsAnnotatedString(block.inlineContent, styling.inlinesStyling, enabled, null).text
      find.document.bindTableCell(it, block.inlineContent, text)
    }
    val selected: ThreadFindMatch? = find.match?.takeIf { it.segment == segment }
    val reveal: FindReveal = rememberFindReveal(selected, find.navigation, find.onReveal)
    super.RenderParagraph(
      block, styling, enabled, onUrlClick,
      onTextLayout = { layout -> onTextLayout(layout); reveal.onTextLayout(layout) },
      modifier = modifier.then(reveal.modifier),
      overflow = overflow,
      softWrap = softWrap,
      maxLines = maxLines,
    )
  }

  // Jewel's heading keeps its text layout to itself, so a heading reveals as a whole. It's one
  // block of large text, short enough that its bounds are as good as the glyphs'.
  @Composable
  override fun RenderHeading(
    block: MarkdownBlock.Heading,
    styling: MarkdownStyling.Heading.HN,
    enabled: Boolean,
    onUrlClick: (String) -> Unit,
    modifier: Modifier,
  ) {
    val segment: Int? = find.document.segmentFor(block.inlineContent)
    val selected: ThreadFindMatch? = find.match?.takeIf { it.segment == segment }
    val reveal: FindReveal =
      rememberFindReveal(selected, find.navigation, find.onReveal, wholeNode = true)
    super.RenderHeading(block, styling, enabled, onUrlClick, modifier.then(reveal.modifier))
  }

  @Composable
  override fun RenderFencedCodeBlock(
    block: MarkdownBlock.CodeBlock.FencedCodeBlock,
    styling: MarkdownStyling.Code.Fenced,
    enabled: Boolean,
    modifier: Modifier,
  ) {
    if (findThreadSearchOccurrences(block.content, find.query.trim()).isEmpty()) {
      super.RenderFencedCodeBlock(block, styling, enabled, modifier)
    } else {
      RenderFindCode(block, styling.background, styling.padding, modifier)
    }
  }

  @Composable
  override fun RenderIndentedCodeBlock(
    block: MarkdownBlock.CodeBlock.IndentedCodeBlock,
    styling: MarkdownStyling.Code.Indented,
    enabled: Boolean,
    modifier: Modifier,
  ) {
    if (findThreadSearchOccurrences(block.content, find.query.trim()).isEmpty()) {
      super.RenderIndentedCodeBlock(block, styling, enabled, modifier)
    } else {
      RenderFindCode(block, styling.background, styling.padding, modifier)
    }
  }

  @Composable
  private fun RenderFindCode(
    block: MarkdownBlock.CodeBlock,
    background: Color,
    padding: PaddingValues,
    modifier: Modifier,
  ) {
    val segment: Int? = find.document.blockSegments[block]
    val selected: ThreadFindMatch? = find.match?.takeIf { it.segment == segment }
    val reveal: FindReveal = rememberFindReveal(selected, find.navigation, find.onReveal)
    Text(
      text = highlightThreadFindText(AnnotatedString(block.content),
        find.query, selected?.start, colors.normal, colors.active),
      style = JewelTheme.editorTextStyle,
      modifier = modifier.fillMaxWidth().background(background)
        .padding(padding).then(reveal.modifier),
      onTextLayout = reveal.onTextLayout,
    )
  }
}

/** One plain-text find segment outside markdown, like the plan title, highlighted and revealed. */
@Composable
fun ThreadFindSegmentText(
  text: String,
  segment: Int,
  modifier: Modifier = Modifier,
  fontWeight: FontWeight? = null,
) {
  val find: ThreadFindHighlight? = LocalThreadFindHighlight.current
    ?.takeIf { it.document.segments.getOrNull(segment) == text }
  val selected: ThreadFindMatch? = find?.match?.takeIf { it.segment == segment }
  val reveal: FindReveal = rememberFindReveal(selected, find?.navigation ?: 0,
    find?.onReveal ?: {})
  val colors: FindColors = rememberFindColors()
  Text(
    text = if (find == null) AnnotatedString(text) else highlightThreadFindText(
      AnnotatedString(text), find.query, selected?.start, colors.normal, colors.active,
    ),
    fontWeight = fontWeight,
    modifier = modifier.then(reveal.modifier),
    onTextLayout = reveal.onTextLayout,
  )
}

private class FindReveal(val modifier: Modifier, val onTextLayout: (TextLayoutResult) -> Unit)

/**
 * Report the selected glyphs in window coordinates, including text inside a tall lazy row. With
 * [wholeNode], for renderers that keep their text layout, report the composable's bounds instead.
 */
@Composable
private fun rememberFindReveal(
  match: ThreadFindMatch?,
  navigation: Int,
  onReveal: suspend (Rect) -> Unit,
  wholeNode: Boolean = false,
): FindReveal {
  var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
  var bounds: Rect? by remember(match) { mutableStateOf(null) }
  val reveal by rememberUpdatedState(onReveal)
  LaunchedEffect(match, navigation, bounds, coordinates) {
    if (match == null) return@LaunchedEffect
    if (bounds == null && !wholeNode) return@LaunchedEffect
    // The timeline first materialises the row; wait for its position after that jump.
    withFrameNanos { }
    val placed: LayoutCoordinates = coordinates?.takeIf { it.isAttached }
      ?: return@LaunchedEffect
    val target: Rect = bounds ?: Rect(Offset.Zero, placed.size.toSize())
    reveal(Rect(placed.localToWindow(target.topLeft), placed.localToWindow(target.bottomRight)))
  }
  return FindReveal(Modifier.onGloballyPositioned { coordinates = it }) { layout ->
    if (match != null && match.start < layout.layoutInput.text.length) {
      val first: Rect = layout.getBoundingBox(match.start)
      val last: Rect = layout.getBoundingBox((match.end - 1)
        .coerceAtMost(layout.layoutInput.text.lastIndex))
      bounds = Rect(first.left, first.top, maxOf(first.right, last.right), first.bottom)
    }
  }
}
