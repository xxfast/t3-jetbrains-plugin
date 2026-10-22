package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.theme.scrollbarStyle

/**
 * The chat view's scroll container. Rows are remembered by key once measured, the way upstream's
 * `LegendList` (MessagesTimeline.tsx) keeps sizes, so the thumb holds its size while scrolling.
 * Jewel's lazy adapter only sees the rows on screen and re-estimates the whole list from them.
 *
 * Jewel on 253 builds its adapter from the state's type and takes no custom one, so the thumb
 * runs off a shadow [ScrollState] laid out at the remembered height, kept in step both ways.
 */
@Composable
internal fun TimelineScrollContainer(
  state: LazyListState,
  keys: List<Any>,
  onScrollUp: () -> Unit = {}, // the thumb dragged up, the user navigating away from the end
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val heights: SnapshotStateMap<Any, Int> = remember { mutableStateMapOf() }
  val currentKeys: List<Any> by rememberUpdatedState(keys)
  val currentOnScrollUp: () -> Unit by rememberUpdatedState(onScrollUp)
  val padding: Int by remember {
    derivedStateOf { state.layoutInfo.beforeContentPadding + state.layoutInfo.afterContentPadding }
  }
  val extent: TimelineExtent by remember {
    derivedStateOf { TimelineExtent(currentKeys.map { heights[it] }, padding) }
  }
  val listOffset: Int by remember {
    derivedStateOf { extent.offsetOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
  }

  val shadow: ScrollState = rememberScrollState()
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val dragged: State<Boolean> = interactions.collectIsDraggedAsState()
  var synced: Int by remember { mutableIntStateOf(0) } // the shadow's value as the list last set it

  LaunchedEffect(state) {
    snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { items ->
      items.forEach { item -> if (heights[item.key] != item.size) heights[item.key] = item.size }
    }
  }

  // List to thumb. Held open while the list scrolls, so the thumb reports scrolling and shows.
  LaunchedEffect(state, shadow) {
    snapshotFlow { dragged.value to state.isScrollInProgress }.collectLatest { (held, scrolling) ->
      if (held) return@collectLatest
      fun ScrollScope.moveTo(offset: Int) {
        scrollBy((offset - shadow.value).toFloat())
        synced = shadow.value
      }
      if (scrolling) shadow.scroll { snapshotFlow { listOffset }.collect { moveTo(it) } }
      else snapshotFlow { listOffset }.collect { shadow.scroll { moveTo(it) } }
    }
  }

  // Thumb to list, only while it's in hand. A shadow clamped by a late layout isn't a drag.
  LaunchedEffect(state, shadow) {
    snapshotFlow { shadow.value }.collect { value ->
      val clamped: Boolean = value == shadow.maxValue && listOffset > value
      if (value == synced || clamped || !(hovered || dragged.value)) return@collect
      if (value < synced) currentOnScrollUp()
      val (index: Int, offset: Int) = extent.positionOf(value)
      state.scrollToItem(index, offset)
    }
  }

  val visibility: ScrollbarVisibility = JewelTheme.scrollbarStyle.scrollbarVisibility
  val gutter: Dp =
    if (visibility is ScrollbarVisibility.AlwaysVisible) visibility.trackThicknessExpanded else 0.dp
  Box(modifier) {
    Box(Modifier.matchParentSize().verticalScroll(shadow, enabled = false)) {
      Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { extent.total.toDp() }))
    }
    Box(Modifier.matchParentSize().padding(end = gutter)) { content() }
    VerticalScrollbar(
      scrollState = shadow,
      modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
      interactionSource = interactions,
    )
  }
}

/**
 * Where each row starts in the list, from remembered heights. A row not measured yet counts as
 * the average of the ones that have been. Offsets are [LazyListState]'s: the first visible row
 * and how far into it.
 */
internal class TimelineExtent(heights: List<Int?>, padding: Int = 0) {
  private val starts: IntArray = IntArray(heights.size + 1)
  val total: Int

  init {
    val measured: List<Int> = heights.filterNotNull()
    val estimate: Int = if (measured.isEmpty()) 0 else measured.sum() / measured.size
    heights.forEachIndexed { index, height -> starts[index + 1] = starts[index] + (height ?: estimate) }
    total = starts.last() + padding
  }

  fun offsetOf(index: Int, offset: Int): Int = starts[index.coerceIn(0, starts.lastIndex)] + offset

  fun positionOf(offset: Int): Pair<Int, Int> {
    val index: Int = (0 until starts.lastIndex).lastOrNull { starts[it] <= offset } ?: 0
    return index to (offset - starts[index])
  }
}
