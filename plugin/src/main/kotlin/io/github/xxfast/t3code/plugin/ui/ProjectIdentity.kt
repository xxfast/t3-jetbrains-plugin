package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.graphics.Color
import java.text.Normalizer
import java.util.Locale

// Mirrors upstream apps/web/src/projectIdentity.ts and projectIconColors.ts: a project without a
// favicon or an icon override gets a two-glyph monogram and a colour, both derived from its title.

/** Upstream's palette, in order. The order matters: the colour is an index into it. */
val PROJECT_ICON_COLORS: List<String> = listOf(
  "gray", "red", "orange", "amber", "yellow", "lime", "green", "emerald", "teal", "cyan", "sky",
  "blue", "indigo", "violet", "purple", "fuchsia", "pink", "rose",
)

/** Tailwind's 600 shade on light themes and 400 on dark, what upstream's classes resolve to. */
private val LIGHT: Map<String, Color> = mapOf(
  "gray" to Color(0xFF4B5563), "red" to Color(0xFFDC2626), "orange" to Color(0xFFEA580C),
  "amber" to Color(0xFFD97706), "yellow" to Color(0xFFCA8A04), "lime" to Color(0xFF65A30D),
  "green" to Color(0xFF16A34A), "emerald" to Color(0xFF059669), "teal" to Color(0xFF0D9488),
  "cyan" to Color(0xFF0891B2), "sky" to Color(0xFF0284C7), "blue" to Color(0xFF2563EB),
  "indigo" to Color(0xFF4F46E5), "violet" to Color(0xFF7C3AED), "purple" to Color(0xFF9333EA),
  "fuchsia" to Color(0xFFC026D3), "pink" to Color(0xFFDB2777), "rose" to Color(0xFFE11D48),
)
private val DARK: Map<String, Color> = mapOf(
  "gray" to Color(0xFF9CA3AF), "red" to Color(0xFFF87171), "orange" to Color(0xFFFB923C),
  "amber" to Color(0xFFFBBF24), "yellow" to Color(0xFFFACC15), "lime" to Color(0xFFA3E635),
  "green" to Color(0xFF4ADE80), "emerald" to Color(0xFF34D399), "teal" to Color(0xFF2DD4BF),
  "cyan" to Color(0xFF22D3EE), "sky" to Color(0xFF38BDF8), "blue" to Color(0xFF60A5FA),
  "indigo" to Color(0xFF818CF8), "violet" to Color(0xFFA78BFA), "purple" to Color(0xFFC084FC),
  "fuchsia" to Color(0xFFE879F9), "pink" to Color(0xFFF472B6), "rose" to Color(0xFFFB7185),
)

fun projectIconColor(name: String, dark: Boolean): Color =
  (if (dark) DARK else LIGHT)[name] ?: (if (dark) DARK else LIGHT).getValue("blue")

private fun normalize(title: String): String =
  Normalizer.normalize(title, Normalizer.Form.NFKC).trim()

private val WORD: Regex = Regex("[\\p{L}\\p{N}]+")

/**
 * First glyph of the first word, then the first digit in that word, else the first glyph of the
 * last word, else the first word's last glyph. "t3-jetbrains-plugin" is T3, "KStore" is KE.
 */
fun projectMonogram(title: String): String {
  val words: List<String> = WORD.findAll(normalize(title)).map { it.value }.toList()
  val first: String = words.firstOrNull() ?: return "PR"
  val glyphs: List<String> = first.codePoints().toArray().map { Character.toString(it) }
  val head: String = glyphs.first()
  val second: String = glyphs.drop(1).firstOrNull { it.single().isDigit() }
    ?: (if (words.size > 1) Character.toString(words.last().codePointAt(0)) else glyphs.last())
  return (head + second).uppercase(Locale.ROOT).codePoints().toArray().take(2)
    .joinToString("") { Character.toString(it) }
}

/** A stable colour name for [title]: a 31-hash of its lowercase code points into the palette. */
fun projectColorName(title: String): String {
  val seed: String = normalize(title).lowercase(Locale.US).ifEmpty { "project" }
  var index = 0
  seed.codePoints().forEach { index = (index * 31 + it) % PROJECT_ICON_COLORS.size }
  return PROJECT_ICON_COLORS[index]
}
