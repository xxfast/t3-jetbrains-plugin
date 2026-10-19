package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TimelineExtentTest {
  @Test
  fun `unmeasured rows count as the measured average`() {
    val extent = TimelineExtent(listOf(100, null, 300, null), padding = 32)
    assertEquals(100 + 200 + 300 + 200 + 32, extent.total)
    assertEquals(300 + 10, extent.offsetOf(index = 2, offset = 10))
  }

  @Test
  fun `positions invert offsets`() {
    val extent = TimelineExtent(listOf(100, 0, 50, 200))
    assertEquals(0 to 0, extent.positionOf(0))
    assertEquals(0 to 99, extent.positionOf(99))
    assertEquals(2 to 0, extent.positionOf(100))
    assertEquals(3 to 25, extent.positionOf(175))
    assertEquals(3 to 300, extent.positionOf(450))
    (0..400 step 7).forEach { offset ->
      val (index: Int, into: Int) = extent.positionOf(offset)
      assertEquals(offset, extent.offsetOf(index, into))
    }
  }

  @Test
  fun `an empty timeline is just its padding`() {
    val extent = TimelineExtent(emptyList(), padding = 32)
    assertEquals(32, extent.total)
    assertEquals(0, extent.positionOf(40).first)
  }
}
