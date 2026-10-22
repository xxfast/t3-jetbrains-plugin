package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.tree.IElementType
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import java.awt.Font
import java.awt.Point
import javax.swing.JComponent
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

// Code blocks in the composer. Upstream highlights them with Shiki
// (composerCodeBlockHighlight.ts) and picks the language from `CODE_BLOCK_LANGUAGES`
// (ComposerCodeBlockLanguagePicker.tsx); here the IDE does both, its lexers and file types.

/**
 * The IDE's file type for a fence language, through upstream's
 * [syntheticFileNameForLanguageId], or null when the IDE doesn't know it.
 */
fun composerCodeFileType(language: String): FileType? {
  if (language.isEmpty()) return null
  val id: String = codeLanguageEntry(language)?.id ?: language
  val type: FileType =
    FileTypeManager.getInstance().getFileTypeByFileName(syntheticFileNameForLanguageId(id))
  return type.takeUnless { it is UnknownFileType || it == PlainTextFileType.INSTANCE }
}

/**
 * The picker's languages, `CODE_BLOCK_LANGUAGES` the IDE knows, with a [current] one that
 * isn't listed first, as upstream's picker does.
 */
fun composerCodeLanguages(current: String): List<ComposerCodeLanguage> {
  val known: List<ComposerCodeLanguage> =
    CODE_BLOCK_LANGUAGES.filter { it.id.isEmpty() || composerCodeFileType(it.id) != null }
  val id: String = codeLanguageEntry(current)?.id ?: current
  return if (known.any { it.id == id }) known else listOf(ComposerCodeLanguage(id, id)) + known
}

/**
 * Highlights code with the IDE's lexer for its file type and the global colour scheme's
 * attributes. Lexing runs as the field transforms its text, so results are cached by language
 * and code; a block is re-lexed only when its code changes.
 */
class IdeComposerCodeHighlighter : ComposerCodeHighlighter {
  private val cache: LinkedHashMap<Pair<String, String>, List<ComposerCodeHighlight>> =
    object : LinkedHashMap<Pair<String, String>, List<ComposerCodeHighlight>>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<Pair<String, String>, List<ComposerCodeHighlight>>?,
      ): Boolean = size > CACHE_SIZE
    }

  override fun highlight(language: String, code: String): List<ComposerCodeHighlight> {
    if (language.isEmpty() || code.isEmpty()) return emptyList()
    return cache.getOrPut(language to code) { lex(language, code) }
  }

  private fun lex(language: String, code: String): List<ComposerCodeHighlight> {
    val type: FileType = composerCodeFileType(language) ?: return emptyList()
    val highlighter: SyntaxHighlighter = runCatching {
      SyntaxHighlighterFactory.getSyntaxHighlighter(type, null, null)
    }.getOrNull() ?: return emptyList()
    val scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme
    val styles: MutableMap<IElementType, SpanStyle?> = mutableMapOf()
    val tokens: MutableList<ComposerCodeHighlight> = mutableListOf()
    return runCatching {
      val lexer = highlighter.highlightingLexer
      lexer.start(code)
      while (true) {
        val token: IElementType = lexer.tokenType ?: break
        val style: SpanStyle? = styles.getOrPut(token) {
          spanStyleOf(highlighter.getTokenHighlights(token), scheme)
        }
        if (style != null && lexer.tokenEnd > lexer.tokenStart) {
          tokens += ComposerCodeHighlight(lexer.tokenStart, lexer.tokenEnd, style)
        }
        lexer.advance()
      }
      tokens.toList()
    }.getOrDefault(emptyList())
  }

  private fun spanStyleOf(keys: Array<TextAttributesKey>, scheme: EditorColorsScheme): SpanStyle? {
    var colour: java.awt.Color? = null
    var fontType: Int = Font.PLAIN
    for (key in keys) {
      val attributes: TextAttributes = scheme.getAttributes(key) ?: continue
      attributes.foregroundColor?.let { colour = it }
      if (attributes.fontType != Font.PLAIN) fontType = attributes.fontType
    }
    if (colour == null && fontType == Font.PLAIN) return null
    return SpanStyle(
      color = colour?.let { Color(it.rgb) } ?: Color.Unspecified,
      fontWeight = if (fontType and Font.BOLD != 0) FontWeight.Bold else null,
      fontStyle = if (fontType and Font.ITALIC != 0) FontStyle.Italic else null,
    )
  }

  private companion object {
    const val CACHE_SIZE: Int = 64
  }
}

/** The language popup, upstream's `ComposerCodeBlockLanguagePicker`, at [point] in [component]. */
fun showComposerCodeLanguagePopup(
  component: JComponent,
  point: Point,
  current: String,
  onPick: (String) -> Unit,
) {
  val languages: List<ComposerCodeLanguage> = composerCodeLanguages(current)
  val id: String = codeLanguageEntry(current)?.id ?: current
  JBPopupFactory.getInstance()
    .createPopupChooserBuilder(languages)
    .setRenderer(
      SimpleListCellRenderer.create { label, language, _ ->
        label.text = language.label
        label.icon = composerCodeFileType(language.id)?.icon ?: PlainTextFileType.INSTANCE.icon
      },
    )
    .setNamerForFiltering { it.label }
    .setSelectedValue(languages.find { it.id == id }, true)
    .setItemChosenCallback { language -> if (language.id != id) onPick(language.id) }
    .createPopup()
    .show(RelativePoint(component, point))
}

/**
 * The clickable parts of the blocks, over the prompt: each code block's language picker at the
 * end of its first line (upstream's header) and each task's checkbox. Positions come from the
 * [layout] of the transformed text, placed as [composerPromptDecorationBackgrounds] draws.
 */
@Composable
fun ComposerPromptBlockControls(
  decorations: ComposerPromptDecorations,
  draft: TextFieldState,
  layout: State<(() -> TextLayoutResult?)?>,
  scroll: ScrollState,
  contentPadding: PaddingValues,
  enabled: Boolean,
  checkboxSize: Dp,
  modifier: Modifier = Modifier,
) {
  val text: String = draft.text.toString()
  val presentation: ComposerPromptPresentation =
    decorations.presentationFor(text, draft.selection)
  val blocks: List<ComposerPromptDecoration> = presentation.decorations
    .filter { it is ComposerCodeBlockDecoration || it is ComposerTaskDecoration }
  if (blocks.isEmpty()) return
  val result: TextLayoutResult = layout.value?.invoke() ?: return
  if (result.layoutInput.text.length != presentation.displayLength(text.length)) return
  val density: Density = LocalDensity.current
  val component: JComponent = LocalComponent.current
  val left: Float = with(density) {
    contentPadding.calculateLeftPadding(LocalLayoutDirection.current).toPx()
  }
  val top: Float = with(density) { contentPadding.calculateTopPadding().toPx() } - scroll.value
  Box(modifier) {
    for (block in blocks) {
      when (block) {
        is ComposerTaskDecoration -> {
          val size: Float = with(density) { checkboxSize.toPx() }
          val bounds: Rect = composerTaskCheckboxBounds(block, presentation, result, size)
          Box(
            Modifier
              .offset {
                IntOffset((left + bounds.left).roundToInt(), (top + bounds.top).roundToInt())
              }
              .size(checkboxSize)
              .clickable(enabled = enabled) { draft.apply(composerTaskToggleEdit(block)) },
          )
        }

        is ComposerCodeBlockDecoration -> {
          val code: ComposerRichBlock.CodeBlock = block.block
          val line: Int = result.getLineForOffset(presentation.displayOffset(code.start))
          val language: String = languageOfInfoString(code.info)
          var anchor: LayoutCoordinates? = null
          val width: Dp = with(density) { result.size.width.toDp() }
          Box(
            Modifier
              .offset { IntOffset(left.roundToInt(), (top + result.getLineTop(line)).roundToInt()) }
              .width(width),
          ) {
            Text(
              text = "${codeLanguageLabel(language).ifEmpty { "Plain text" }} \u25BE",
              color = JewelTheme.globalColors.text.info,
              style = JewelTheme.defaultTextStyle.copy(
                fontSize = JewelTheme.defaultTextStyle.fontSize * 0.85f,
              ),
              modifier = Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned { anchor = it }
                .clickable(enabled = enabled) {
                  val at: LayoutCoordinates = anchor ?: return@clickable
                  val origin: Offset = at.positionInRoot()
                  val point = Point(
                    (origin.x / density.density).roundToInt(),
                    ((origin.y + at.size.height) / density.density).roundToInt(),
                  )
                  showComposerCodeLanguagePopup(component, point, language) { picked ->
                    val current: String = draft.text.toString()
                    val now: ComposerRichBlock.CodeBlock = ComposerRichDocument(current).blocks
                      .filterIsInstance<ComposerRichBlock.CodeBlock>()
                      .find { it.start == code.start } ?: return@showComposerCodeLanguagePopup
                    draft.apply(composerCodeLanguageEdit(current, now, picked))
                  }
                }
                .padding(horizontal = 6.dp),
            )
          }
        }

        else -> Unit
      }
    }
  }
}

