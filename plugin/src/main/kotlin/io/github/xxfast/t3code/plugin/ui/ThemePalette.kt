package io.github.xxfast.t3code.plugin.ui

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

// The OKLCH maths of upstream's `themePalette.ts` (apps/web/src/themePalette.ts) that
// `contextChipVariables` needs, ported one to one. IDE-free; `MessageChips.kt` feeds it the
// theme's surfaces as colour strings, the way upstream's `ThemeColors` holds them.

/** `ThemeRgbColor`: sRGB channels in 0..255, unrounded until something rounds them. */
internal data class ThemeRgbColor(val r: Double, val g: Double, val b: Double)

/** `ThemeOklch`. */
internal data class ThemeOklch(val L: Double, val C: Double, val h: Double)

/** `ParsedThemeColor`. */
internal data class ParsedThemeColor(val color: ThemeOklch, val alpha: Double)

/** `solveOklchLightness`'s `"lighter" | "darker"`. */
internal enum class LightnessDirection { LIGHTER, DARKER }

/** The roles of upstream's `ThemeColors` (`themePalettes.ts`) that `contextChipVariables` reads. */
data class ThemeColors(
  val canvas: String,
  val surface: String,
  val surfaceRaised: String,
  val messageSurface: String,
  val messageForeground: String,
  val errorForeground: String,
)

internal val THEME_WHITE_FOREGROUND: ThemeRgbColor = ThemeRgbColor(255.0, 255.0, 255.0)
internal val THEME_BLACK_FOREGROUND: ThemeRgbColor = ThemeRgbColor(0.0, 0.0, 0.0)

/**
 * `parseThemeColor`. Upstream hands any CSS colour to Culori's `parse`; this reads the two forms
 * the plugin has, `oklch(...)` and `#hex`, and is null for anything else.
 */
internal fun parseThemeColor(value: String?): ParsedThemeColor? {
  if (value == null) return null
  val input: String = value.trim()
  val parsed: ParsedThemeColor = parseOklch(input) ?: parseHex(input) ?: return null
  val lightness: Double = parsed.color.L
  val chroma: Double = parsed.color.C
  val hue: Double = parsed.color.h
  // Missing CSS components are zero; Culori omits none alpha, so distinguish it from absent alpha.
  val alpha: Double = if (NONE_ALPHA.containsMatchIn(input)) 0.0 else parsed.alpha
  if (!listOf(lightness, chroma, hue, alpha).all(Double::isFinite)) return null
  return ParsedThemeColor(
    color = ThemeOklch(L = lightness.coerceIn(0.0, 1.0), C = maxOf(0.0, chroma), h = hue),
    alpha = alpha.coerceIn(0.0, 1.0),
  )
}

private val NONE_ALPHA: Regex = Regex("""/\s*none\s*\)$""", RegexOption.IGNORE_CASE)

private val OKLCH: Regex =
  Regex("""^oklch\(\s*(\S+)\s+(\S+)\s+(\S+?)\s*(?:/\s*(\S+?)\s*)?\)$""", RegexOption.IGNORE_CASE)

private val HEX: Regex =
  Regex("""^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$""", RegexOption.IGNORE_CASE)

/** Culori's `oklch()` parsing: `none` is zero, a percent lightness is of 1 and chroma of 0.4. */
private fun parseOklch(input: String): ParsedThemeColor? {
  val match: MatchResult = OKLCH.matchEntire(input) ?: return null
  val (l, c, h, a) = match.destructured
  fun component(value: String, percent: Double): Double? = when {
    value.equals("none", ignoreCase = true) -> 0.0
    value.endsWith("%") -> value.dropLast(1).toDoubleOrNull()?.let { it / 100 * percent }
    else -> value.toDoubleOrNull()
  }
  // A hue in degrees, or in any of CSS's other angle units
  fun hue(value: String): Double? {
    val unit: String? = HUE_UNITS.keys.firstOrNull { value.endsWith(it, ignoreCase = true) }
    if (unit == null) return component(value, percent = Double.NaN)
    return value.dropLast(unit.length).toDoubleOrNull()?.let { it * HUE_UNITS.getValue(unit) }
  }
  return ParsedThemeColor(
    color = ThemeOklch(
      L = component(l, percent = 1.0) ?: return null,
      C = component(c, percent = 0.4) ?: return null,
      h = hue(h) ?: return null,
    ),
    alpha = if (a.isEmpty()) 1.0 else component(a, percent = 1.0) ?: return null,
  )
}

/** Degrees per CSS angle unit, `grad` ahead of `rad` so the longer suffix wins. */
private val HUE_UNITS: Map<String, Double> =
  linkedMapOf("deg" to 1.0, "grad" to 0.9, "rad" to 180 / PI, "turn" to 360.0)

/** Culori's hex parsing and its `rgb` to `oklch` conversion, an absent alpha being 1. */
private fun parseHex(input: String): ParsedThemeColor? {
  val digits: String = HEX.matchEntire(input)?.groupValues?.get(1) ?: return null
  val full: String =
    if (digits.length <= 4) digits.map { "$it$it" }.joinToString("") else digits
  fun channel(index: Int): Double = full.substring(index * 2, index * 2 + 2).toInt(16).toDouble()
  val color: ThemeOklch = themeRgbToOklch(ThemeRgbColor(channel(0), channel(1), channel(2)))
  return ParsedThemeColor(
    color = color.copy(h = ((color.h % 360) + 360) % 360),
    alpha = if (full.length == 8) channel(3) / 255 else 1.0,
  )
}

/** `themeRgbToHexColor`. */
internal fun themeRgbToHexColor(color: ThemeRgbColor): String =
  "#" + listOf(color.r, color.g, color.b).joinToString("") { channel ->
    "%02x".format(channel.coerceIn(0.0, 255.0).roundToLong())
  }

/** `mixThemeRgbColors`. */
internal fun mixThemeRgbColors(
  base: ThemeRgbColor,
  overlay: ThemeRgbColor,
  amount: Double,
): ThemeRgbColor = ThemeRgbColor(
  r = base.r + (overlay.r - base.r) * amount,
  g = base.g + (overlay.g - base.g) * amount,
  b = base.b + (overlay.b - base.b) * amount,
)

/** `themeRelativeLuminance`: WCAG's, with its 0.03928 threshold. */
internal fun themeRelativeLuminance(color: ThemeRgbColor): Double {
  fun linearize(channel: Double): Double {
    val normalized: Double = channel / 255
    return if (normalized <= 0.03928) {
      normalized / 12.92
    } else {
      ((normalized + 0.055) / 1.055).pow(2.4)
    }
  }
  return 0.2126 * linearize(color.r) + 0.7152 * linearize(color.g) + 0.0722 * linearize(color.b)
}

/** `srgbChannelToLinear`. */
private fun srgbChannelToLinear(channel: Double): Double {
  val c: Double = channel / 255
  return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/** `linearChannelToSrgb`. */
private fun linearChannelToSrgb(channel: Double, round: Boolean = true): Double {
  val c: Double =
    if (channel <= 0.0031308) channel * 12.92 else 1.055 * channel.pow(1 / 2.4) - 0.055
  val srgb: Double = c.coerceIn(0.0, 1.0) * 255
  return if (round) srgb.roundToLong().toDouble() else srgb
}

/** `themeRgbToOklch`. */
internal fun themeRgbToOklch(color: ThemeRgbColor): ThemeOklch {
  val r: Double = srgbChannelToLinear(color.r)
  val g: Double = srgbChannelToLinear(color.g)
  val b: Double = srgbChannelToLinear(color.b)
  val l: Double = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
  val m: Double = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
  val s: Double = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
  val lightness: Double = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s
  val a: Double = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
  val bb: Double = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
  return ThemeOklch(L = lightness, C = hypot(a, bb), h = atan2(bb, a) * 180 / PI)
}

/** `oklchToRgbUnclamped`: linear sRGB, out of 0..1 when the colour is out of gamut. */
private fun oklchToRgbUnclamped(color: ThemeOklch): ThemeRgbColor {
  val hr: Double = color.h * PI / 180
  val a: Double = color.C * cos(hr)
  val bb: Double = color.C * sin(hr)
  val l: Double = cube(color.L + 0.3963377774 * a + 0.2158037573 * bb)
  val m: Double = cube(color.L - 0.1055613458 * a - 0.0638541728 * bb)
  val s: Double = cube(color.L - 0.0894841775 * a - 1.291485548 * bb)
  return ThemeRgbColor(
    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    b = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  )
}

private fun cube(value: Double): Double = value * value * value

/** `mapThemeOklchToSrgbGamut`: the most chroma that stays in sRGB, lightness and hue kept. */
private fun mapThemeOklchToSrgbGamut(color: ThemeOklch): ThemeOklch {
  fun isInGamut(chroma: Double): Boolean {
    val linear: ThemeRgbColor = oklchToRgbUnclamped(color.copy(C = chroma))
    return listOf(linear.r, linear.g, linear.b).all { channel -> channel in -0.0001..1.0001 }
  }
  if (isInGamut(color.C)) return color

  var low = 0.0
  var high: Double = color.C
  val chromaResolution = 0.000001
  val steps: Int = maxOf(
    1,
    ceil(log2(maxOf(color.C, chromaResolution)) - log2(chromaResolution)).toInt(),
  )
  repeat(steps) {
    val mid: Double = (low + high) / 2
    if (isInGamut(mid)) low = mid else high = mid
  }
  return color.copy(C = low)
}

/** `themeOklchToRgb`. */
internal fun themeOklchToRgb(color: ThemeOklch, round: Boolean = true): ThemeRgbColor {
  val linear: ThemeRgbColor = oklchToRgbUnclamped(mapThemeOklchToSrgbGamut(color))
  return ThemeRgbColor(
    r = linearChannelToSrgb(linear.r, round),
    g = linearChannelToSrgb(linear.g, round),
    b = linearChannelToSrgb(linear.b, round),
  )
}

/** `themeContrastRatio`: WCAG's, 1 to 21. */
internal fun themeContrastRatio(first: ThemeRgbColor, second: ThemeRgbColor): Double {
  val firstLuminance: Double = themeRelativeLuminance(first)
  val secondLuminance: Double = themeRelativeLuminance(second)
  val lighter: Double = maxOf(firstLuminance, secondLuminance)
  val darker: Double = minOf(firstLuminance, secondLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

/**
 * `solveOklchLightness`: [base] with only its lightness moved in [direction], just far enough to
 * reach [minContrast] on every one of [against], or as far as it goes.
 */
internal fun solveOklchLightness(
  base: ThemeOklch,
  against: List<ThemeRgbColor>,
  minContrast: Double,
  direction: LightnessDirection,
): ThemeOklch {
  fun contrastOf(color: ThemeOklch): Double =
    against.minOf { surface -> themeContrastRatio(themeOklchToRgb(color), surface) }
  val lighter: Boolean = direction == LightnessDirection.LIGHTER
  var low: Double = if (lighter) base.L else 0.0
  var high: Double = if (lighter) 1.0 else base.L
  if (contrastOf(base) >= minContrast) return base
  repeat(18) {
    val mid: Double = (low + high) / 2
    val candidate: ThemeOklch = base.copy(L = mid)
    if (contrastOf(candidate) >= minContrast) {
      if (lighter) high = mid else low = mid
    } else {
      if (lighter) low = mid else high = mid
    }
  }
  return base.copy(L = if (lighter) high else low)
}

const val CONTEXT_CHIP_CONTRAST: Double = 5.0
const val CONTEXT_CHIP_MESSAGE_VARIABLE: String = "--context-chip-message"
const val CONTEXT_CHIP_INVALID_VARIABLE: String = "--context-chip-invalid"

/**
 * `contextChipVariables`: each accent of [CONTEXT_CHIP_ACCENTS], and the error foreground for the
 * invalid state, as an 8-bit hex ink whose lightness is solved to [CONTEXT_CHIP_CONTRAST] (or the
 * most the theme allows) on the canvas, surface and raised surface, plus the message surface when
 * it's as dark as the canvas. A bubble of the other shade gets the message foreground for its
 * chips instead, in [CONTEXT_CHIP_MESSAGE_VARIABLE].
 */
fun contextChipVariables(colors: ThemeColors): Map<String, String> {
  // CSS paints surfaces from their unrounded OKLCH, so measure them unrounded.
  val canvas: ThemeRgbColor = parseThemeColor(colors.canvas)
    ?.let { parsed -> themeOklchToRgb(parsed.color, round = false) }
    ?: THEME_WHITE_FOREGROUND
  fun onCanvas(value: String): ThemeRgbColor {
    val parsed: ParsedThemeColor = parseThemeColor(value) ?: return canvas
    return mixThemeRgbColors(canvas, themeOklchToRgb(parsed.color, round = false), parsed.alpha)
  }
  fun isDark(color: ThemeRgbColor): Boolean = themeRelativeLuminance(color) < 0.179
  val messageMatchesCanvas: Boolean = isDark(onCanvas(colors.messageSurface)) == isDark(canvas)
  val surfaces: List<ThemeRgbColor> = buildList {
    add(canvas)
    add(onCanvas(colors.surface))
    add(onCanvas(colors.surfaceRaised))
    if (messageMatchesCanvas) add(onCanvas(colors.messageSurface))
  }
  fun contrastOnSurfaces(color: ThemeRgbColor): Double =
    surfaces.minOf { surface -> themeContrastRatio(color, surface) }
  val blackContrast: Double = contrastOnSurfaces(THEME_BLACK_FOREGROUND)
  val whiteContrast: Double = contrastOnSurfaces(THEME_WHITE_FOREGROUND)
  val direction: LightnessDirection =
    if (blackContrast >= whiteContrast) LightnessDirection.DARKER else LightnessDirection.LIGHTER
  val minContrast: Double = minOf(CONTEXT_CHIP_CONTRAST, maxOf(blackContrast, whiteContrast))
  val accents: List<Pair<String, String>> =
    CONTEXT_CHIP_ACCENTS.map { (kind, accent) -> contextChipColorVariable(kind) to accent } +
      (CONTEXT_CHIP_INVALID_VARIABLE to colors.errorForeground)
  val variables: MutableMap<String, String> = linkedMapOf()
  for ((variable, accent) in accents) {
    val parsed: ParsedThemeColor = parseThemeColor(accent) ?: continue
    val solved: ThemeOklch = solveOklchLightness(parsed.color, surfaces, minContrast, direction)
    variables[variable] = themeRgbToHexColor(themeOklchToRgb(solved))
  }
  if (!messageMatchesCanvas) variables[CONTEXT_CHIP_MESSAGE_VARIABLE] = colors.messageForeground
  return variables
}
