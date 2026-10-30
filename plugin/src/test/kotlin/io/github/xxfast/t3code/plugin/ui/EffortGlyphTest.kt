package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ProviderOptionChoice
import io.github.xxfast.t3code.protocol.generated.SelectProviderOptionDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// `EffortGlyph.test.ts`, case for case
class EffortGlyphTest {
  private fun descriptor(
    ids: List<String>,
    promptInjectedValues: List<String>? = null,
  ): SelectProviderOptionDescriptor = SelectProviderOptionDescriptor(
    id = "effort",
    label = "Reasoning",
    options = ids.map { id -> ProviderOptionChoice(id, id.replaceFirstChar(Char::uppercase)) },
    promptInjectedValues = promptInjectedValues,
  )

  private val claude: SelectProviderOptionDescriptor = descriptor(
    listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink"),
    listOf("ultrathink"),
  )

  @Test
  fun `fills bars up to the current level and leaves max and workflow levels off the scale`() {
    assertEquals(
      EffortGlyphState(count = 4, filled = 4, special = null, label = "Xhigh"),
      resolveEffortGlyph(claude, "xhigh"),
    )
  }

  @Test
  fun `names max instead of drawing it`() {
    assertEquals("Max", resolveEffortGlyph(claude, "max")?.special)
  }

  @Test
  fun `names Ultracode and Ultrathink instead of drawing a level`() {
    assertEquals("Ultracode", resolveEffortGlyph(claude, "ultracode")?.special)
    assertEquals("Ultrathink", resolveEffortGlyph(claude, "ultrathink")?.special)
  }

  @Test
  fun `draws the lowest level as one bar, and an off level as none`() {
    val low: EffortGlyphState? = resolveEffortGlyph(claude, "low")
    assertEquals(4 to 1, low?.let { it.count to it.filled })
    val medium: EffortGlyphState? = resolveEffortGlyph(claude, "medium")
    assertEquals(4 to 2, medium?.let { it.count to it.filled })
    assertEquals(
      EffortGlyphState(count = 2, filled = 0, special = null, label = "Off"),
      resolveEffortGlyph(descriptor(listOf("off", "low", "high")), "off"),
    )
  }

  @Test
  fun `falls back to the word when the value is unknown or the scale has one level`() {
    assertNull(resolveEffortGlyph(claude, "unknown"))
    assertNull(resolveEffortGlyph(descriptor(listOf("high")), "high"))
  }

  @Test
  fun `rises by one whole-pixel step and ends at full height`() {
    for (count in 3..7) {
      val heights: List<Int> = effortBarHeights(count, 12)
      assertEquals(count, heights.size, "$count bars")
      assertEquals(12, heights.last(), "$count bars")
      val steps: Set<Int> = heights.zipWithNext { a, b -> b - a }.toSet()
      assertEquals(1, steps.size, "$count bars")
      assertTrue(steps.single() > 0, "$count bars")
    }
  }
}
