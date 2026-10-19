package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tailwind's `transition-*` default: 150ms, `cubic-bezier(0.4, 0, 0.2, 1)`. */
internal val Transition: TweenSpec<Float> = tween(150, easing = FastOutSlowInEasing)

/** One child of a [MotionColumn]. A [fill] item takes the leftover height and never moves. */
internal class MotionItem(
  val key: String,
  val fill: Boolean = false,
  val content: @Composable () -> Unit = {},
)

/**
 * A column whose rows slide to their new place, fade in and fade out when the order of [items]
 * changes, as upstream's `createSidebarListMotion` (apps/web/src/components/Sidebar.motion.ts)
 * does for the sidebar list. A [MotionItem.fill] item is the `mt-auto` gap: the column is at
 * least its min height and that item takes what the others leave.
 */
@Composable
internal fun MotionColumn(items: List<MotionItem>, modifier: Modifier = Modifier) {
  val scope: CoroutineScope = rememberCoroutineScope()
  val motion: ListMotion = remember { ListMotion(scope) }
  motion.compose(items)
  Layout(
    modifier = modifier,
    content = {
      for (item in items) {
        key(item.key) { Box(Modifier.layoutId(item.key)) { item.content() } }
      }
      for (ghost in motion.ghosts) {
        key(ghost) { Box(Modifier.layoutId(ghost)) { ghost.content() } }
      }
    },
  ) { measurables: List<Measurable>, constraints: Constraints ->
    val loose = Constraints(maxWidth = constraints.maxWidth)
    val fills: Set<String> = items.filter { it.fill }.map { it.key }.toSet()
    val rows: List<Pair<String, Placeable>> = measurables
      .filter { it.layoutId is String && it.layoutId !in fills }
      .map { it.layoutId as String to it.measure(loose) }
    val used: Int = rows.sumOf { (_, placeable) -> placeable.height }
    val gap: Int = (constraints.minHeight - used).coerceAtLeast(0)
    val ghosts: List<Pair<Ghost, Placeable>> = measurables
      .filter { it.layoutId is Ghost }
      .map { it.layoutId as Ghost to it.measure(loose) }
    val placed: Map<String, Placeable> = rows.toMap()
    val height: Int = maxOf(constraints.minHeight, used)
    layout(constraints.maxWidth, height) {
      val next: LinkedHashMap<String, Span> = linkedMapOf()
      var top = 0
      for (item in items) {
        if (item.fill) {
          top += gap
          continue
        }
        val placeable: Placeable = placed[item.key] ?: continue
        next[item.key] = Span(top.toFloat(), placeable.height.toFloat())
        top += placeable.height
      }
      motion.place(next, travelCap = 40.dp.toPx())
      // Under the rows, so a row sliding into the gap covers the one fading out of it
      for ((ghost, placeable) in ghosts) {
        placeable.placeWithLayer(0, ghost.top.toInt(), zIndex = -1f) {
          translationY = ghost.offset.floatValue
          alpha = ghost.alpha.floatValue
        }
      }
      for ((key, placeable) in rows) {
        val row: RowMotion = motion.row(key)
        placeable.placeWithLayer(0, next.getValue(key).top.toInt()) {
          translationY = row.offset.floatValue
          alpha = row.alpha.floatValue
        }
      }
    }
  }
}

internal class Span(val top: Float, val height: Float)

internal class RowMotion {
  val offset: MutableFloatState = mutableFloatStateOf(0f)
  val alpha: MutableFloatState = mutableFloatStateOf(1f)
  var moving: Job? = null
  var entering: Job? = null

  fun cancel() {
    moving?.cancel()
    entering?.cancel()
    offset.floatValue = 0f
    alpha.floatValue = 1f
  }
}

/** A row that left, drawn from its last content where it was until its fade ends. */
internal class Ghost(
  val key: String,
  val content: @Composable () -> Unit,
  val top: Float,
  val height: Float,
  from: Float,
) {
  val offset: MutableFloatState = mutableFloatStateOf(0f)
  val alpha: MutableFloatState = mutableFloatStateOf(from)
  var fading: Job? = null
}

/**
 * The bookkeeping half of `createSidebarListMotion`. Upstream runs `update` after a layout whose
 * order changed; here composition notes the change (and takes a ghost of each row that left, since
 * a removed composable can only be drawn again if it's composed this frame) and the placement
 * that follows runs the pass. Placements without an order change only refresh the positions, so a
 * clock ticking mid-animation neither cancels a slide nor leaves a stale origin for the next one.
 */
internal class ListMotion(private val scope: CoroutineScope) {
  val ghosts: SnapshotStateList<Ghost> = mutableStateListOf()
  private val rows: MutableMap<String, RowMotion> = mutableMapOf()
  private val contents: MutableMap<String, @Composable () -> Unit> = mutableMapOf()
  private var positions: Map<String, Span>? = null
  private var keys: List<String> = emptyList()
  private var pending: Boolean? = null // set when the order changed: whether to animate it

  fun row(key: String): RowMotion = rows.getOrPut(key) { RowMotion() }

  fun compose(items: List<MotionItem>) {
    val next: List<String> = items.filterNot { it.fill }.map { it.key }
    val previous: Map<String, Span>? = positions
    if (next != keys) {
      val nextSet: Set<String> = next.toSet()
      val keySet: Set<String> = keys.toSet()
      val left: List<String> = keys.filter { it !in nextSet }
      val fades: Int = left.count { (previous?.get(it)?.height ?: 0f) > 0f } +
        next.count { it !in keySet }
      val animate: Boolean = previous != null && fades <= MAX_FADED_ROWS_PER_UPDATE
      if (previous != null && animate) {
        for (key in left) {
          val span: Span = previous[key] ?: continue
          val content: @Composable () -> Unit = contents[key] ?: continue
          if (span.height == 0f) continue
          val row: RowMotion? = rows[key]
          val from: Float = row?.alpha?.floatValue ?: 1f
          val top: Float = span.top + (row?.offset?.floatValue ?: 0f)
          ghosts += Ghost(key, content, top, span.height, from)
        }
      }
      pending = animate
      keys = next
    }
    for (item in items) if (!item.fill) contents[item.key] = item.content
    contents.keys.retainAll(next.toSet())
  }

  fun place(next: LinkedHashMap<String, Span>, travelCap: Float) {
    val animate: Boolean? = pending
    val previous: Map<String, Span>? = positions
    pending = null
    positions = next
    if (animate == null) return

    val gone: List<String> = rows.keys.filter { it !in next }
    for (key in gone) rows.remove(key)?.cancel()
    if (!animate || previous == null) {
      for (ghost in ghosts) ghost.fading?.cancel()
      ghosts.clear()
      for (row in rows.values) row.cancel()
      return
    }

    fun travel(height: Float): Float = minOf(height, travelCap)
    fun remaining(key: String): Float = rows[key]?.offset?.floatValue ?: 0f
    val moved: MutableMap<String, Float> = mutableMapOf()
    fun riding(order: List<String>, index: Int, retained: (String) -> Boolean): Float? {
      for (cursor in index - 1 downTo 0) {
        val key: String = order[cursor]
        moved[key]?.let { return it }
        if (retained(key)) return remaining(key)
      }
      return null
    }

    for ((key, span) in next) {
      val previousTop: Float = previous[key]?.top ?: continue
      if (previousTop == span.top) continue
      moved[key] = previousTop + remaining(key) - span.top
    }
    val oldOrder: List<String> = previous.keys.toList()
    for (ghost in ghosts) {
      if (ghost.fading != null) continue
      val delta: Float? = riding(oldOrder, oldOrder.indexOf(ghost.key)) { it in next }
      fadeOut(ghost, delta?.let { -it } ?: travel(ghost.height))
    }
    val nextOrder: List<String> = next.keys.toList()
    for ((index, key) in nextOrder.withIndex()) {
      val span: Span = next.getValue(key)
      if (key !in previous) {
        if (span.height > 0f) {
          val delta: Float? = riding(nextOrder, index) { it in previous }
          enter(row(key), delta ?: -travel(span.height))
        }
        continue
      }
      moved[key]?.let { move(row(key), it) }
    }
  }

  private fun move(row: RowMotion, offset: Float) {
    row.moving?.cancel()
    row.offset.floatValue = offset
    if (offset == 0f) return
    row.moving = scope.launch {
      animate(offset, 0f, animationSpec = Motion) { value, _ -> row.offset.floatValue = value }
    }
  }

  private fun enter(row: RowMotion, travel: Float) {
    move(row, travel)
    row.alpha.floatValue = 0f
    row.entering = scope.launch {
      animate(0f, 1f, animationSpec = Motion) { value, _ -> row.alpha.floatValue = value }
    }
  }

  private fun fadeOut(ghost: Ghost, travel: Float) {
    val from: Float = ghost.alpha.floatValue
    ghost.fading = scope.launch {
      animate(0f, 1f, animationSpec = Motion) { progress, _ ->
        ghost.offset.floatValue = travel * progress
        ghost.alpha.floatValue = from * (1f - progress)
      }
      ghosts.remove(ghost)
    }
  }

  private companion object {
    /** Upstream's `motionTiming`: 150ms, CSS `ease-out`. */
    val Motion: TweenSpec<Float> = tween(150, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))

    // Every fade composes a ghost or animates a row; past this many the pass just snaps
    const val MAX_FADED_ROWS_PER_UPDATE = 40
  }
}
