package io.github.xxfast.t3code.plugin.ui

import org.jetbrains.jewel.foundation.code.MimeType

// A fenced code block's language, apps/web/src/components/chat/MarkdownCodeBlock.logic.ts

/**
 * `extractFenceLanguage`: the fence's first word, `text` when there's none. Upstream reads it off
 * the `language-x` class remark gives the `<code>`; here it's the info string commonmark keeps.
 */
fun extractFenceLanguage(info: String): String {
  val raw: String = languageOfInfoString(info).ifEmpty { "text" }
  // Shiki doesn't bundle a gitignore grammar; ini is a close match (#685)
  return if (raw == "gitignore") "ini" else raw
}

/**
 * The markdown processor's `languageRecognizer`: the fence's language as written, carried as a
 * [MimeType] because that's all Jewel hands its `CodeHighlighter`. Plain text is null, Jewel's
 * `UNKNOWN`. [chatCodeLanguage] turns it into the IDE's language.
 */
fun fenceMimeType(info: String): MimeType? =
  extractFenceLanguage(info).takeUnless { it == "text" }?.let { MimeType(it) }
