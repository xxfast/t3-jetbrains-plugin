package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.geometry.Rect
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadContextDragTest {
  // A Chat View panel at (400, 120) on screen, 1600 by 1300, its composer box near the bottom
  private val origin: Point = Point(400, 120)
  private val visible: Rectangle = Rectangle(0, 0, 1600, 1300)
  private val composer: Rect = Rect(left = 100f, top = 1060f, right = 1500f, bottom = 1180f)

  @Test
  fun `a point over the composer hits it, in screen coordinates`() {
    assertTrue(threadContextDropHit(Point(1280, 1197), origin, visible, composer, scale = 1f))
    assertTrue(threadContextDropHit(Point(500, 1180), origin, visible, composer, scale = 1f))
  }

  @Test
  fun `a point beside or above the composer misses it`() {
    assertEquals(false, threadContextDropHit(Point(450, 1197), origin, visible, composer, 1f))
    assertEquals(false, threadContextDropHit(Point(1280, 1100), origin, visible, composer, 1f))
    assertEquals(false, threadContextDropHit(Point(200, 416), origin, visible, composer, 1f))
  }

  @Test
  fun `a point off the panel's visible part misses, even inside the box`() {
    val clipped = Rectangle(0, 0, 1600, 1000)
    assertEquals(false, threadContextDropHit(Point(1280, 1197), origin, clipped, composer, 1f))
  }

  @Test
  fun `the box is in pixels, so a scaled screen scales the point`() {
    val pixels = Rect(left = 200f, top = 2120f, right = 3000f, bottom = 2360f)
    assertTrue(threadContextDropHit(Point(1280, 1197), origin, visible, pixels, scale = 2f))
    assertEquals(false, threadContextDropHit(Point(1280, 1197), origin, visible, composer, 2f))
  }
}
