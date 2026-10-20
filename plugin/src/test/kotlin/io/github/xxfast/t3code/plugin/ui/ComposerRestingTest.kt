package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ComposerRestingTest {
  @Test
  fun `resting needs a fold, an overflowing timeline and a quiet one-line prompt`() {
    assertTrue(shouldUseRestingComposerLayout(true, true, false, false))
    assertFalse(shouldUseRestingComposerLayout(false, true, false, false))
    assertFalse(shouldUseRestingComposerLayout(true, false, false, false))
    assertFalse(shouldUseRestingComposerLayout(true, true, true, false))
    assertFalse(shouldUseRestingComposerLayout(true, true, false, true))
  }

  @Test
  fun `a gesture folds once it has gone the threshold`() {
    val gesture = ComposerScrollGestureState()
    assertFalse(gesture.record(0, 16f, true, true, false))
    assertTrue(gesture.record(10, 16f, true, true, false))
    // and starts over
    assertFalse(gesture.record(20, 16f, true, true, false))
  }

  @Test
  fun `a pause starts a new gesture`() {
    val gesture = ComposerScrollGestureState()
    assertFalse(gesture.record(0, 16f, true, true, false))
    assertFalse(gesture.record(500, 16f, true, true, false))
  }

  @Test
  fun `nothing folds past the end, against a wall, or when not eligible`() {
    assertFalse(ComposerScrollGestureState().record(0, 48f, true, true, true))
    assertFalse(ComposerScrollGestureState().record(0, 48f, true, false, false))
    assertFalse(ComposerScrollGestureState().record(0, 48f, false, true, false))
  }

  @Test
  fun `focusing the prompt mid-gesture keeps that gesture from folding again`() {
    val gesture = ComposerScrollGestureState()
    gesture.record(0, 8f, true, true, false)
    gesture.suppressActive(50)
    assertFalse(gesture.record(60, 48f, true, true, false))
    // a fresh gesture after the pause folds as usual
    assertTrue(gesture.record(400, 48f, true, true, false))
  }

  @Test
  fun `scrolling up folds, scrolling on at the end unfolds`() {
    val collapse = ComposerScrollCollapse()
    collapse.onWheel(0, -32f, canScrollBackward = true, canScrollForward = false, eligible = true)
    assertTrue(collapse.collapsed)
    collapse.onWheel(500, 16f, canScrollBackward = true, canScrollForward = false, eligible = true)
    assertFalse(collapse.collapsed)
  }

  @Test
  fun `the prompt taking focus unfolds`() {
    val collapse = ComposerScrollCollapse()
    collapse.onWheel(0, -32f, canScrollBackward = true, canScrollForward = true, eligible = true)
    assertTrue(collapse.collapsed)
    collapse.expandForEditor(10)
    assertFalse(collapse.collapsed)
  }
}
