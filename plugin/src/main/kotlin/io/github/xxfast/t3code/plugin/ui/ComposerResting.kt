package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// The composer's resting layout: scrolling the timeline folds the composer to one line with
// its controls in a strip underneath, and reaching the end or focusing the prompt unfolds it.
// Ported from upstream's `apps/web/src/components/composerFooterLayout.ts`,
// `apps/web/src/components/chat/composerScrollGesture.ts` and the wheel handling in
// `ChatComposer.tsx` (around line 5040).

/** `COMPOSER_SCROLL_COLLAPSE_THRESHOLD_PX`: how far a wheel gesture goes before it folds. */
const val COMPOSER_SCROLL_COLLAPSE_THRESHOLD_PX: Float = 24f

/** `COMPOSER_SCROLL_GESTURE_RESET_MS`: a pause this long starts a new gesture. */
const val COMPOSER_SCROLL_GESTURE_RESET_MS: Long = 120

/** `COMPOSER_RESTING_TRANSITION_EASING`, `cubic-bezier(0.32, 0.72, 0, 1)`. */
val COMPOSER_RESTING_TRANSITION_EASING: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * The fold and unfold's length. Upstream's `panelAnimationDurationMs` setting runs 0 to 400 and
 * is 0 by default; the plugin keeps no copy of it, so this picks a value in its range.
 */
const val COMPOSER_RESTING_TRANSITION_MS: Int = 250

/**
 * `shouldUseRestingComposerLayout`, composerFooterLayout.ts:26: an existing thread whose
 * timeline overflows, folded by a scroll, with a one-line prompt and nothing else open. The
 * plugin has no mobile layout and only shows existing threads.
 */
fun shouldUseRestingComposerLayout(
  isScrollCollapsed: Boolean,
  timelineOverflows: Boolean,
  hasMultilinePrompt: Boolean,
  hasExpandedChrome: Boolean,
): Boolean = timelineOverflows && isScrollCollapsed && !hasMultilinePrompt && !hasExpandedChrome

/** `ComposerScrollGestureState`. */
class ComposerScrollGestureState {
  var accumulatedDeltaPx: Float = 0f
  var collapseSuppressed: Boolean = false
  var lastEventAt: Long = Long.MIN_VALUE
}

/** `resetComposerScrollGesture`. */
fun ComposerScrollGestureState.reset() {
  accumulatedDeltaPx = 0f
  collapseSuppressed = false
  lastEventAt = Long.MIN_VALUE
}

/**
 * `suppressActiveComposerScrollGesture`: a gesture going on when the prompt took focus doesn't
 * fold it again.
 */
fun ComposerScrollGestureState.suppressActive(now: Long) {
  if (lastEventAt != Long.MIN_VALUE && now - lastEventAt <= COMPOSER_SCROLL_GESTURE_RESET_MS) {
    collapseSuppressed = true
  }
}

/**
 * `recordComposerScrollGestureEvent`, composerScrollGesture.ts:50: true once a gesture that can
 * scroll the timeline, and isn't pushing past its end, has gone [threshold] pixels.
 */
fun ComposerScrollGestureState.record(
  now: Long,
  deltaPx: Float,
  collapseEligible: Boolean,
  canScrollInGestureDirection: Boolean,
  scrollsTowardLogicalEnd: Boolean,
  threshold: Float = COMPOSER_SCROLL_COLLAPSE_THRESHOLD_PX,
): Boolean {
  // The `finishScrollGesture` timeout, kept as a clock check instead
  if (lastEventAt != Long.MIN_VALUE && now - lastEventAt > COMPOSER_SCROLL_GESTURE_RESET_MS) {
    reset()
  }
  lastEventAt = now
  if (
    collapseSuppressed ||
    !collapseEligible ||
    !canScrollInGestureDirection ||
    scrollsTowardLogicalEnd
  ) {
    accumulatedDeltaPx = 0f
    return false
  }
  accumulatedDeltaPx += deltaPx
  if (accumulatedDeltaPx < threshold) return false
  accumulatedDeltaPx = 0f
  return true
}

/**
 * Whether the composer is folded by a scroll, `isComposerScrollCollapsed` in
 * `useComposerFocusState`, plus the timeline wheel handling that sets it. One per thread.
 */
class ComposerScrollCollapse {
  var collapsed: Boolean by mutableStateOf(false)
    private set

  private val gesture: ComposerScrollGestureState = ComposerScrollGestureState()

  /**
   * A wheel step over the timeline, `handleTimelineWheel`: [deltaY] is positive toward the end.
   * At the end, scrolling further toward it unfolds, as ChatView.tsx:4218 does.
   */
  fun onWheel(
    now: Long,
    deltaY: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    eligible: Boolean,
  ) {
    val atEnd: Boolean = !canScrollForward
    if (deltaY > 0 && atEnd) restore()
    val canScroll: Boolean = if (deltaY < 0) canScrollBackward else canScrollForward
    val fold: Boolean = gesture.record(
      now = now,
      deltaPx = kotlin.math.abs(deltaY),
      collapseEligible = eligible && !collapsed,
      canScrollInGestureDirection = canScroll,
      scrollsTowardLogicalEnd = deltaY > 0 && atEnd,
    )
    if (fold) collapsed = true
  }

  /** `restoreAfterTimelineReachedEnd`: the timeline got back to its end, or "Scroll to end". */
  fun restore() {
    collapsed = false
  }

  /** `expandComposerForEditorChange`: the prompt took focus or changed. */
  fun expandForEditor(now: Long) {
    gesture.suppressActive(now)
    collapsed = false
  }
}

/**
 * `resolveComposerTimelineInset`, composerFooterLayout.ts:57: how much of the timeline's bottom
 * the composer covers. Resting keeps the larger of what it had and the overlay, so folding and
 * unfolding never resize the timeline; the first resting measure reserves upstream's
 * `COMPOSER_RESTING_EXPANSION_MIN_PX` for the unfold.
 */
fun resolveComposerTimelineInset(
  current: Int,
  overlayHeight: Int,
  resting: Boolean,
  expansionMin: Int,
): Int = if (resting) {
  maxOf(current, overlayHeight + if (current == 0) expansionMin else 0)
} else {
  overlayHeight
}

/**
 * The composer's fold and unfold, `useComposerRestingTransition` (ChatComposer.tsx:448), as one
 * clock per change. Everything here is read in layout and drawing, never in composition, so a
 * frame of it only re-places the composer.
 *
 * - [expansion] eases from where the composer was (a reversal mid-way starts from there) to 0
 *   resting or 1 expanded, with upstream's easing. Height, prompt and actions follow it.
 * - The departing controls go at once. Unfolding, the footer's fade in over the second half
 *   from 4px below; folding, the strip's fade in over the whole change from 4px above.
 */
@Stable
class ComposerRestingMotion(resting: Boolean) {
  private val clock: Animatable<Float, AnimationVector1D> = Animatable(1f)
  private var from: Float = if (resting) 0f else 1f

  var resting: Boolean by mutableStateOf(resting)
    private set

  val expansion: Float
    get() {
      val to: Float = if (resting) 0f else 1f
      return from + (to - from) * COMPOSER_RESTING_TRANSITION_EASING.transform(clock.value)
    }

  /** The footer's controls: in over the second half of an unfold. */
  val footerAlpha: Float
    get() = if (resting) 0f else ease(((clock.value - 0.5f) * 2).coerceIn(0f, 1f))

  /** The resting strip: in over the whole fold. */
  val stripAlpha: Float
    get() = if (resting) ease(clock.value) else 0f

  /** No change under way, so the composer has its final height. */
  val settled: Boolean
    get() = !clock.isRunning

  suspend fun moveTo(next: Boolean) {
    if (next == resting) return
    from = expansion
    resting = next
    clock.snapTo(0f)
    clock.animateTo(1f, tween(COMPOSER_RESTING_TRANSITION_MS, easing = LinearEasing))
  }

  private fun ease(fraction: Float): Float = COMPOSER_RESTING_TRANSITION_EASING.transform(fraction)
}

/** `COMPOSER_RESTING_EXPANSION_MIN_PX`: the empty expanded composer over its resting row. */
const val COMPOSER_RESTING_EXPANSION_MIN_DP: Int = 94
