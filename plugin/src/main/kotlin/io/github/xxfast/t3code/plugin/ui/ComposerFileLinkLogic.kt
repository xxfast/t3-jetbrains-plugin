package io.github.xxfast.t3code.plugin.ui

// Ported from upstream's packages/shared/src/composerTrigger.ts:16-37.

/** `composerFileLinkBasename`: the last segment of [path], by either separator. */
private fun composerFileLinkBasename(path: String): String {
  val separator: Int = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return if (separator >= 0) path.substring(separator + 1) else path
}

/** `escapeMarkdownLinkLabel`. */
private fun escapeMarkdownLinkLabel(label: String): String =
  label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

/** `encodeMarkdownLinkDestination`: `encodeURI`, then the characters a link target can't keep. */
private fun encodeMarkdownLinkDestination(path: String): String =
  encodeUri(path)
    .replace("(", "%28")
    .replace(")", "%29")
    .replace("#", "%23")
    .replace("?", "%3F")
    .replace("\\", "%5C")

/** `serializeComposerFileLink`: [path] as a markdown link labelled with its name. */
fun serializeComposerFileLink(path: String): String {
  val label: String = escapeMarkdownLinkLabel(composerFileLinkBasename(path))
  return "[$label](${encodeMarkdownLinkDestination(path)})"
}

/** What JavaScript's `encodeURI` leaves alone. */
private const val URI_UNESCAPED: String =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'();/?:@&=+$,#"

/** JavaScript's `encodeURI`: everything else as percent-encoded UTF-8, upper-case hex. */
private fun encodeUri(text: String): String = buildString {
  var index = 0
  while (index < text.length) {
    val chars: Int = Character.charCount(text.codePointAt(index))
    if (chars == 1 && text[index] in URI_UNESCAPED) {
      append(text[index])
    } else {
      val bytes: ByteArray = text.substring(index, index + chars).toByteArray(Charsets.UTF_8)
      for (byte in bytes) append('%').append("%02X".format(byte.toInt() and 0xFF))
    }
    index += chars
  }
}
