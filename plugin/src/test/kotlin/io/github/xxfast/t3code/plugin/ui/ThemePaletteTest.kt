package io.github.xxfast.t3code.plugin.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// Cases from upstream's `themePalette.test.ts` ("context chip colors"), plus the solver's own.
class ThemePaletteTest {
  private fun rgb(value: String): ThemeRgbColor =
    themeOklchToRgb(assertNotNull(parseThemeColor(value)).color)

  private fun contrastRatio(first: String, second: String): Double =
    themeContrastRatio(rgb(first), rgb(second))

  // Surfaces stay unrounded, as CSS paints them; the chip is the 8-bit hex the solver returns.
  private fun paintedContrastRatio(chip: String, surface: String, canvas: String): Double {
    val back: ThemeRgbColor = themeOklchToRgb(assertNotNull(parseThemeColor(canvas)).color, false)
    val parsed: ParsedThemeColor = assertNotNull(parseThemeColor(surface))
    val painted: ThemeRgbColor =
      mixThemeRgbColors(back, themeOklchToRgb(parsed.color, false), parsed.alpha)
    return themeContrastRatio(rgb(chip), painted)
  }

  private fun ThemeColors.chipSurfaces(): List<String> =
    listOf(canvas, surface, surfaceRaised, messageSurface)

  // `T3_CODE_LIGHT_THEME_COLORS` and `T3_CODE_DARK_THEME_COLORS`,
  // packages/shared/src/themePalettes.ts
  private val standardLight = ThemeColors(
    canvas = "#fcfcfc",
    surface = "#ffffff",
    surfaceRaised = "#fcfcfc",
    messageSurface = "#f4f4f5",
    messageForeground = "#27272a",
    errorForeground = "#c10007",
  )
  private val standardDark = ThemeColors(
    canvas = "#0a0a0a",
    surface = "#111111",
    surfaceRaised = "#111111",
    messageSurface = "#141414",
    messageForeground = "#f5f5f5",
    errorForeground = "#ff6467",
  )

  // `BUILT_IN_THEMES`, both modes of each
  private val builtIns: List<ThemeColors> = listOf(
    ThemeColors( // T3 Chat
      canvas = "oklch(0.982446 0.010114 325.653)",
      surface = "oklch(0.971835 0.012884 321.894)",
      surfaceRaised = "oklch(0.988235 0.005049 325.615)",
      messageSurface = "oklch(0.926746 0.037898 332.6)",
      messageForeground = "oklch(0.354591 0.093575 307.568)",
      errorForeground = "oklch(0.458704 0.169677 3.815)",
    ),
    ThemeColors(
      canvas = "oklch(0.22813 0.020366 307.469)",
      surface = "oklch(0.267101 0.02016 311.799)",
      surfaceRaised = "oklch(0.279864 0.021572 309.532)",
      messageSurface = "oklch(0.273791 0.025541 309.079)",
      messageForeground = "oklch(0.949872 0.021269 306.838)",
      errorForeground = "oklch(0.901233 0.057189 343.694)",
    ),
    ThemeColors( // Grove
      canvas = "oklch(0.972369 0.005497 157.15)",
      surface = "oklch(0.972369 0.005497 157.15)",
      surfaceRaised = "oklch(0.949276 0.004496 159.002)",
      messageSurface = "oklch(0.891377 0.026164 164.929)",
      messageForeground = "oklch(0.222003 0.03479 328.979)",
      errorForeground = "oklch(0.509494 0.208583 28.513)",
    ),
    ThemeColors(
      canvas = "oklch(0.260865 0.02152 162.75)",
      surface = "oklch(0.260865 0.02152 162.75)",
      surfaceRaised = "oklch(0.363192 0.016572 165.32)",
      messageSurface = "oklch(0.470111 0.067221 158.676)",
      messageForeground = "oklch(0.990339 0.008411 325.64)",
      errorForeground = "oklch(0.704237 0.187511 22.228)",
    ),
    ThemeColors( // Ocean
      canvas = "oklch(0.974199 0.002856 241.597)",
      surface = "oklch(0.974199 0.002856 241.597)",
      surfaceRaised = "oklch(0.951058 0.002962 258.339)",
      messageSurface = "oklch(0.895373 0.023469 241.913)",
      messageForeground = "oklch(0.222003 0.03479 328.979)",
      errorForeground = "oklch(0.509494 0.208583 28.513)",
    ),
    ThemeColors(
      canvas = "oklch(0.242641 0.024125 250.573)",
      surface = "oklch(0.242641 0.024125 250.573)",
      surfaceRaised = "oklch(0.348439 0.019942 253.696)",
      messageSurface = "oklch(0.445224 0.056936 243.413)",
      messageForeground = "oklch(0.990339 0.008411 325.64)",
      errorForeground = "oklch(0.702184 0.189226 22.228)",
    ),
    ThemeColors( // Ember
      canvas = "oklch(0.976527 0.002685 60.725)",
      surface = "oklch(0.976527 0.002685 60.725)",
      surfaceRaised = "oklch(0.953321 0.002701 42.266)",
      messageSurface = "oklch(0.899296 0.022939 49.163)",
      messageForeground = "oklch(0.222003 0.03479 328.979)",
      errorForeground = "oklch(0.509494 0.208583 28.513)",
    ),
    ThemeColors(
      canvas = "oklch(0.245899 0.019144 42.044)",
      surface = "oklch(0.245899 0.019144 42.044)",
      surfaceRaised = "oklch(0.351262 0.01565 37.592)",
      messageSurface = "oklch(0.447961 0.061874 50.849)",
      messageForeground = "oklch(0.990339 0.008411 325.64)",
      errorForeground = "oklch(0.702184 0.189226 22.228)",
    ),
    ThemeColors( // Iris
      canvas = "oklch(0.976531 0.003855 303.226)",
      surface = "oklch(0.976531 0.003855 303.226)",
      surfaceRaised = "oklch(0.953326 0.004536 307.676)",
      messageSurface = "oklch(0.897143 0.028558 299.758)",
      messageForeground = "oklch(0.222003 0.03479 328.979)",
      errorForeground = "oklch(0.509494 0.208583 28.513)",
    ),
    ThemeColors(
      canvas = "oklch(0.225975 0.031062 293.741)",
      surface = "oklch(0.225975 0.031062 293.741)",
      surfaceRaised = "oklch(0.335291 0.026008 296.394)",
      messageSurface = "oklch(0.399975 0.086965 294.177)",
      messageForeground = "oklch(0.990339 0.008411 325.64)",
      errorForeground = "oklch(0.702184 0.189226 22.228)",
    ),
  )

  // Int UI as `rememberContextChipVariables` hands it over: the editor's canvas, the text colour
  // at 4% and 6% for a card and the user bubble, the composer's raised surface
  private val intUi: List<ThemeColors> = listOf(
    ThemeColors( // Light
      canvas = "#ffffffff",
      surface = "#0000000a",
      surfaceRaised = "#f7f8faff",
      messageSurface = "#0000000f",
      messageForeground = "#000000ff",
      errorForeground = "#db3b4bff",
    ),
    ThemeColors( // Dark
      canvas = "#1e1f22ff",
      surface = "#dfe1e50a",
      surfaceRaised = "#323438ff",
      messageSurface = "#dfe1e50f",
      messageForeground = "#dfe1e5ff",
      errorForeground = "#db5c5cff",
    ),
    ThemeColors( // High Contrast
      canvas = "#000000ff",
      surface = "#ffffff0a",
      surfaceRaised = "#0a0a0aff",
      messageSurface = "#ffffff0f",
      messageForeground = "#ffffffff",
      errorForeground = "#ff6464ff",
    ),
  )

  private val palettes: List<ThemeColors> = listOf(standardLight, standardDark) + builtIns + intUi

  @Test
  fun `parses oklch with its alpha, none and percentages`() {
    assertEquals(
      ParsedThemeColor(ThemeOklch(0.62, 0.136, 237.0), 1.0),
      parseThemeColor("oklch(0.62 0.136 237)"),
    )
    assertEquals(
      ParsedThemeColor(ThemeOklch(0.5, 0.2, 90.0), 0.25),
      parseThemeColor(" OKLCH(50% 50% 90deg / 25%) "),
    )
    assertEquals(0.0, parseThemeColor("oklch(0.5 0.1 20 / none)")?.alpha)
    listOf("180", "180deg", "200grad", "0.5turn", "${PI}rad").forEach { angle ->
      assertEquals(180.0, assertNotNull(parseThemeColor("oklch(0.5 0.1 $angle)")).color.h, 1e-9)
    }
    assertEquals(ThemeOklch(0.0, 0.0, 0.0), parseThemeColor("oklch(none none none)")?.color)
    // Out of range lightness and chroma clamp
    assertEquals(ThemeOklch(1.0, 0.0, 10.0), parseThemeColor("oklch(1.4 -0.1 10)")?.color)
    assertNull(parseThemeColor("oklch(0.5 0.1)"))
    assertNull(parseThemeColor("rgb(0 0 0)"))
    assertNull(parseThemeColor(null))
  }

  @Test
  fun `parses hex and round trips it`() {
    listOf("#ffffff", "#000000", "#1b4ed8", "#c10007", "#767676").forEach { hex ->
      assertEquals(hex, themeRgbToHexColor(rgb(hex)))
    }
    assertEquals("#aabbcc", themeRgbToHexColor(rgb("#abc")))
    assertEquals(0x80 / 255.0, parseThemeColor("#00000080")?.alpha)
    assertEquals(1.0, assertNotNull(parseThemeColor("#fff")).color.L, 1e-6)
    assertNull(parseThemeColor("#12345"))
  }

  @Test
  fun `luminance and contrast are WCAG's`() {
    assertEquals(1.0, themeRelativeLuminance(THEME_WHITE_FOREGROUND), 1e-12)
    assertEquals(0.0, themeRelativeLuminance(THEME_BLACK_FOREGROUND))
    assertEquals(21.0, themeContrastRatio(THEME_WHITE_FOREGROUND, THEME_BLACK_FOREGROUND), 1e-9)
    assertEquals(21.0, themeContrastRatio(THEME_BLACK_FOREGROUND, THEME_WHITE_FOREGROUND), 1e-9)
    assertEquals(4.54, contrastRatio("#767676", "#ffffff"), 0.01)
  }

  @Test
  fun `out of gamut chroma maps into sRGB keeping lightness`() {
    val color: ThemeRgbColor = themeOklchToRgb(ThemeOklch(0.62, 2.0, 237.0))
    listOf(color.r, color.g, color.b).forEach { channel -> assertTrue(channel in 0.0..255.0) }
    val back: ThemeOklch = themeRgbToOklch(color)
    assertEquals(0.62, back.L, 0.01)
  }

  @Test
  fun `the solver leaves a colour that already reads alone`() {
    val base = ThemeOklch(0.3, 0.1, 237.0)
    val solved: ThemeOklch =
      solveOklchLightness(base, listOf(THEME_WHITE_FOREGROUND), 5.0, LightnessDirection.DARKER)
    assertEquals(base, solved)
  }

  @Test
  fun `the solver moves only lightness, just far enough`() {
    val base = ThemeOklch(0.62, 0.136, 237.0)
    val darker: ThemeOklch =
      solveOklchLightness(base, listOf(THEME_WHITE_FOREGROUND), 5.0, LightnessDirection.DARKER)
    assertEquals(base.copy(L = darker.L), darker)
    assertTrue(darker.L < base.L)
    assertTrue(themeContrastRatio(themeOklchToRgb(darker), THEME_WHITE_FOREGROUND) >= 5)
    // A step lighter would miss
    val lighter: ThemeOklch = darker.copy(L = darker.L + 0.001)
    assertTrue(themeContrastRatio(themeOklchToRgb(lighter), THEME_WHITE_FOREGROUND) < 5)

    val black: ThemeRgbColor = THEME_BLACK_FOREGROUND
    val dim = ThemeOklch(0.2, 0.1, 163.0)
    val up: ThemeOklch = solveOklchLightness(dim, listOf(black), 7.0, LightnessDirection.LIGHTER)
    assertTrue(themeContrastRatio(themeOklchToRgb(up), black) >= 7)
  }

  @Test
  fun `the solver stops at the end of its range when the target is out of reach`() {
    val gray: ThemeRgbColor = rgb("#777777")
    val base = ThemeOklch(0.62, 0.1, 20.0)
    val solved: ThemeOklch =
      solveOklchLightness(base, listOf(gray), 21.0, LightnessDirection.DARKER)
    assertTrue(solved.L < 0.0001)
  }

  // Upstream: "chooses readable chip lightness on %s when the text is medium gray"
  @Test
  fun `chooses readable chip lightness when the text is medium gray`() {
    listOf("#ffffff" to 5.0, "#777777" to 4.5).forEach { (surface, minimum) ->
      val colors: ThemeColors = standardLight.copy(
        canvas = surface,
        surface = surface,
        surfaceRaised = surface,
        messageSurface = surface,
        messageForeground = "#767676",
      )
      contextChipVariables(colors).values.forEach { color ->
        assertTrue(contrastRatio(color, surface) >= minimum, "$color on $surface")
      }
    }
  }

  // Upstream: "reads at 5:1 on every chip surface of every built-in theme and keeps each hue"
  @Test
  fun `reads at 5 to 1 on every chip surface of every theme and keeps each hue`() {
    palettes.forEach { colors ->
      val variables: Map<String, String> = contextChipVariables(colors)
      assertNull(variables[CONTEXT_CHIP_MESSAGE_VARIABLE])
      CONTEXT_CHIP_ACCENTS.forEach { (kind, accent) ->
        val chip: String = assertNotNull(variables[contextChipColorVariable(kind)])
        colors.chipSurfaces().forEach { surface ->
          val ratio: Double = paintedContrastRatio(chip, surface, colors.canvas)
          assertTrue(ratio >= 5, "$kind $chip on $surface of $colors: $ratio")
        }
        val solved: ThemeOklch = assertNotNull(parseThemeColor(chip)).color
        if (solved.C > 0.04) {
          val hue: Double = assertNotNull(parseThemeColor(accent)).color.h
          val drift: Double = abs((((solved.h - hue + 540) % 360) + 360) % 360 - 180)
          assertTrue(drift < 4, "$kind $chip drifts $drift from $accent")
        }
      }
    }
  }

  // Upstream: "reads at 5:1 on the standard surfaces as index.css paints them"
  @Test
  fun `reads at 5 to 1 on the standard surfaces as painted`() {
    // `getStandardPaintedThemeColors`
    val painted: Map<ThemeColors, List<String>> = mapOf(
      standardLight.copy(messageSurface = "#ebebed") to listOf("#fcfcfc", "#ffffff", "#ebebed"),
      standardDark.copy(canvas = "#000000", messageSurface = "#1e1e1e") to
        listOf("#000000", "#0a0a0a", "#111111", "#141414", "#1e1e1e"),
    )
    painted.forEach { (colors, surfaces) ->
      val variables: Map<String, String> = contextChipVariables(colors)
      CONTEXT_CHIP_ACCENTS.keys.forEach { kind ->
        val chip: String = assertNotNull(variables[contextChipColorVariable(kind)])
        surfaces.forEach { surface ->
          assertTrue(contrastRatio(chip, surface) >= 5, "$kind $chip on $surface")
        }
      }
    }
  }

  // Upstream: "solves the invalid state's error color to 5:1 on every chip surface"
  @Test
  fun `solves the invalid state's error colour to 5 to 1 on every chip surface`() {
    palettes.forEach { colors ->
      val variables: Map<String, String> = contextChipVariables(colors)
      val invalid: String = assertNotNull(variables[CONTEXT_CHIP_INVALID_VARIABLE])
      colors.chipSurfaces().forEach { surface ->
        val ratio: Double = paintedContrastRatio(invalid, surface, colors.canvas)
        assertTrue(ratio >= 5, "$invalid on $surface")
      }
    }
  }

  // Upstream: "leaves a bubble with opposite-shade text out of the shared solve"
  @Test
  fun `leaves a bubble with opposite-shade text out of the shared solve`() {
    val colors: ThemeColors =
      standardLight.copy(messageSurface = "#1b4ed8", messageForeground = "#ffffff")
    val variables: Map<String, String> = contextChipVariables(colors)
    assertEquals("#ffffff", variables[CONTEXT_CHIP_MESSAGE_VARIABLE])
    CONTEXT_CHIP_ACCENTS.keys.forEach { kind ->
      val chip: String = assertNotNull(variables[contextChipColorVariable(kind)])
      assertTrue(contrastRatio(chip, colors.canvas) >= 5, "$kind $chip")
    }
    val file: String = assertNotNull(variables[contextChipColorVariable("file")])
    assertTrue(assertNotNull(parseThemeColor(file)).color.C > 0.08)
  }

  @Test
  fun `every chip kind with an accent has one in the table`() {
    MessageChipKind.entries.forEach { kind ->
      kind.accent?.let { accent -> assertNotNull(CONTEXT_CHIP_ACCENTS[accent], kind.name) }
    }
    assertNull(MessageChipKind.NEUTRAL.accent)
  }
}
