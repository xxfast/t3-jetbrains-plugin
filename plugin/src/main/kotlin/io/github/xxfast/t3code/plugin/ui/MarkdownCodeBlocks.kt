package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.text.AnnotatedString
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import org.jetbrains.jewel.foundation.code.MimeType
import org.jetbrains.jewel.foundation.code.highlighting.CodeHighlighter

// Code blocks in the ChatView's markdown. Upstream highlights them with Shiki
// (apps/web/src/lib/syntaxHighlighting.ts, components/chat/HighlightedCodeLines.tsx); here the
// IDE does, its lexers and the editor colour scheme, through Jewel's `LexerBasedCodeHighlighter`.

/**
 * The IDE's language for a fence's [MimeType] from [fenceMimeType], found the way the composer
 * finds its code blocks' ([composerCodeFileType]). Null for plain text or a language the IDE
 * has no file type for.
 */
fun chatCodeLanguage(mimeType: MimeType?): Language? {
  if (mimeType == null || mimeType == MimeType.Known.UNKNOWN) return null
  return (composerCodeFileType(mimeType.toString()) as? LanguageFileType)?.language
}

/**
 * Highlights a fenced block with [lexer], Jewel's `LexerBasedCodeHighlighter`, in the IDE's
 * language for its fence, and plain when there's none.
 *
 * Jewel finds a language by its mime type's display name, which loses `ts`, `tsx` and `zsh`,
 * so it's resolved here and handed over by id. And Jewel reads the global scheme when asked,
 * not when it re-highlights, so every [schemeChanges] asks again; that's what restyles a block
 * when the scheme or the theme changes.
 */
class ChatCodeHighlighter(
  private val lexer: CodeHighlighter,
  private val schemeChanges: Flow<Unit> = editorSchemeChanges(),
) : CodeHighlighter {
  @OptIn(ExperimentalCoroutinesApi::class)
  override fun highlight(code: String, mimeType: MimeType?): Flow<AnnotatedString> {
    val language: Language = chatCodeLanguage(mimeType) ?: return flowOf(AnnotatedString(code))
    val id = MimeType(language.id)
    return schemeChanges
      .onStart { emit(Unit) }
      .flatMapLatest { lexer.highlight(code, id) }
  }

  // 262's `CodeHighlighter` has this overload too, abstract, and its markdown renderer calls it
  // with the fence's word when Jewel has no mime type for it. We compile against 2025.3, which
  // doesn't, so it can't be an `override`; the JVM still finds it by descriptor on 262. Without
  // it a fence there throws `AbstractMethodError`. Don't add `override`.
  fun highlight(code: String, language: String): Flow<AnnotatedString> =
    highlight(code, fenceMimeType(language))
}

/** Every change of the editor's global colour scheme, a theme switch's included. */
fun editorSchemeChanges(): Flow<Unit> = callbackFlow {
  val connection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
  connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { trySend(Unit) })
  awaitClose { connection.disconnect() }
}
