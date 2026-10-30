package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/*
 * Upstream's `TimelineMinimap` (apps/web/src/components/chat/TimelineMinimap.tsx), placed by
 * `MessagesTimeline.tsx`: a rail of strips down the timeline's left edge, one per turn. The
 * hovered strip and its neighbours widen like a fisheye, a preview card hangs beside it, a click
 * jumps to the turn. Strips whose rows are on screen light up. The rail stays on when the gutter
 * is wide enough, otherwise it shows while the pointer is on it. Not ported: keyboard focus and
 * arrow keys, selecting text in the card, the drag pass-through (Compose keeps a drag on the
 * node it started on) and the wheel forwarding of `timelineMinimapWheel.ts`.
 */

private val STRIP_CONTAINER_LEFT: Dp = 12.dp // left-3
private val RAIL_LINE_LEFT: Dp = 12.dp // the line's left-3, inside the strip container
private val STRIP_WIDTH: Dp = 24.dp // w-6
private val STRIP_HEIGHT: Dp = 2.dp // h-0.5
private val PREVIEW_LEFT: Dp = 32.dp // left-8
private val PREVIEW_WIDTH: Dp = 320.dp // w-80
private val PREVIEW_SHAPE: Shape = RoundedCornerShape(12.dp) // rounded-xl
private val NAVIGATION_SIZE: Dp = 20.dp
private val NAVIGATION_CENTER: Dp = 4.dp // left-1 -translate-x-1/2, inside the strip container
private const val TRANSITION_MS: Int = 150

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TimelineMinimap(
  items: List<TimelineMinimapItem>,
  hasPersistentGutter: Boolean,
  hitStripWidth: Int,
  visibleRange: () -> TimelineMinimapVisibleRange, // read here, so a scroll recomposes only this
  viewportHeight: Dp, // what stands in for `100vh`
  onSelect: (TimelineMinimapItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  var activeIndex: Int? by remember { mutableStateOf(null) }
  var overStrip: Boolean by remember { mutableStateOf(false) }
  var overPreview: Boolean by remember { mutableStateOf(false) }
  var overNavigation: Boolean by remember { mutableStateOf(false) }
  // The pointer crossing from the strip onto the card leaves one and enters the other in the same
  // pass, so the hover only ends once both say so
  LaunchedEffect(overStrip, overPreview) {
    if (!overStrip && !overPreview) activeIndex = null
  }
  if (items.size < TIMELINE_MINIMAP_MIN_ITEMS) return

  val resolvedActiveIndex: Int? = activeIndex?.takeIf { it < items.size }
  val inView: TimelineMinimapVisibleRange = visibleRange()
  val currentIndex: Int? = resolveTimelineMinimapCurrentIndex(
    visibleStart = inView.start,
    visibleEnd = inView.end,
    rowIndices = items.map { it.rowIndex },
  )
  val activeItem: TimelineMinimapItem? = remember(resolvedActiveIndex, items) {
    resolveTimelineMinimapPreview(resolvedActiveIndex?.let { items.getOrNull(it) })
  }
  val navigationInteractive: Boolean = resolveTimelineMinimapNavigationInteractive(hitStripWidth)
  val resolvedCurrentIndex: Int? = currentIndex?.takeIf { it in items.indices }
  val previousItem: TimelineMinimapItem? = resolvedCurrentIndex?.let { items.getOrNull(it - 1) }
  val nextItem: TimelineMinimapItem? = resolvedCurrentIndex?.let { items.getOrNull(it + 1) }
  val railHeight: Dp = resolveTimelineMinimapHeightStyle(items.size, viewportHeight)
  val shown: Boolean = activeItem != null && (overStrip || overPreview)
  val hovered: Boolean = overStrip || overPreview || overNavigation
  val opacity: Float by animateFloatAsState(
    targetValue = if (hasPersistentGutter || hovered) 1f else 0f,
    animationSpec = tween(TRANSITION_MS),
  )
  val muted: Color = JewelTheme.globalColors.text.info
  val foreground: Color = JewelTheme.globalColors.text.normal

  fun topOf(index: Int): Dp =
    railHeight * (resolveTimelineMinimapTopPercent(index, items.size) / 100f)

  fun indexAt(y: Float, height: Int): Int? = resolveTimelineMinimapIndexFromPointer(
    itemCount = items.size,
    railTop = 0f,
    railHeight = height.toFloat(),
    pointerY = y,
  )

  Box(modifier.fillMaxHeight().graphicsLayer { alpha = opacity }) {
    // The strip container, `top-1/2 left-3 -translate-y-1/2`, wide enough for the card beside it
    Box(
      Modifier
        .align(Alignment.CenterStart)
        .padding(start = STRIP_CONTAINER_LEFT)
        .width(PREVIEW_LEFT + PREVIEW_WIDTH)
        .height(railHeight),
    ) {
      // The button: as wide as the gutter allows, the card's width while a preview shows
      if (hitStripWidth > 0) {
        Box(
          Modifier
            .width(resolveTimelineMinimapInteractiveWidth(hitStripWidth, expanded = shown))
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { overStrip = true }
            .onPointerEvent(PointerEventType.Exit) { overStrip = false }
            .onPointerEvent(PointerEventType.Move) { event ->
              val y: Float = event.changes.firstOrNull()?.position?.y ?: return@onPointerEvent
              activeIndex = indexAt(y, size.height)
            }
            .pointerInput(items) {
              detectTapGestures { offset ->
                indexAt(offset.y, size.height)?.let { items.getOrNull(it) }?.let(onSelect)
              }
            },
        )
      }
      Box(
        Modifier
          .offset(x = RAIL_LINE_LEFT)
          .width(1.dp)
          .fillMaxHeight()
          .background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.15f)),
      )
      items.forEachIndexed { index, item ->
        val activeDistance: Int? = resolvedActiveIndex?.let { abs(index - it) }
        MinimapStrip(
          active = activeDistance == 0,
          scale = timelineMinimapStripScale(activeDistance),
          inView = isTimelineMinimapRowVisible(item.rowIndex, inView.start, inView.end),
          muted = muted,
          foreground = foreground,
          modifier = Modifier.offset(y = topOf(index) - STRIP_HEIGHT / 2),
        )
      }
      if (shown && activeItem != null && resolvedActiveIndex != null) {
        MinimapPreview(
          item = activeItem,
          index = resolvedActiveIndex,
          count = items.size,
          onHover = { overPreview = it },
          modifier = Modifier.offset(x = PREVIEW_LEFT, y = topOf(resolvedActiveIndex)),
        )
      }
    }
    if (navigationInteractive) {
      // `bottom-[calc(100%+2px)]` and `top-[calc(100%+2px)]` of the strip container
      val reach: Dp = railHeight / 2 + 2.dp + NAVIGATION_SIZE / 2
      val left: Dp = STRIP_CONTAINER_LEFT + NAVIGATION_CENTER - NAVIGATION_SIZE / 2
      MinimapNavigationButton(
        previous = true,
        enabled = previousItem != null,
        onClick = { previousItem?.let(onSelect) },
        onHover = { overNavigation = it },
        modifier = Modifier.align(Alignment.CenterStart).offset(x = left, y = -reach),
      )
      MinimapNavigationButton(
        previous = false,
        enabled = nextItem != null,
        onClick = { nextItem?.let(onSelect) },
        onHover = { overNavigation = it },
        modifier = Modifier.align(Alignment.CenterStart).offset(x = left, y = reach),
      )
    }
  }
}

/**
 * One strip, `origin-left`: the hovered one at full width, its neighbours narrower. In view, the
 * `bg-foreground/90` layer fades in over it.
 */
@Composable
private fun MinimapStrip(
  active: Boolean,
  scale: Float,
  inView: Boolean,
  muted: Color,
  foreground: Color,
  modifier: Modifier = Modifier,
) {
  val scaleX: Float by animateFloatAsState(scale, tween(TRANSITION_MS))
  val lit: Float by animateFloatAsState(if (inView) 1f else 0f, tween(TRANSITION_MS))
  val base: Color = muted.copy(alpha = if (active) 0.75f else 0.35f)
  Box(
    modifier
      .requiredSize(STRIP_WIDTH, STRIP_HEIGHT)
      .graphicsLayer {
        this.scaleX = scaleX
        transformOrigin = TransformOrigin(0f, 0.5f)
      }
      .drawBehind {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(base, cornerRadius = radius)
        drawRoundRect(foreground.copy(alpha = 0.9f * lit), cornerRadius = radius)
      },
  )
}

/** `scale-x-*` by distance from the hovered strip. */
private fun timelineMinimapStripScale(distance: Int?): Float = when (distance) {
  0 -> 1f
  1 -> 0.667f
  2 -> 0.417f
  else -> 0.333f
}

/** The `data-minimap-preview` card: the prompt on one line, the turn's reply on three. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MinimapPreview(
  item: TimelineMinimapItem,
  index: Int,
  count: Int,
  onHover: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val outline: Color = if (JewelTheme.isDark) {
    Color.White.copy(alpha = 0.07f)
  } else {
    Color.Black.copy(alpha = 0.08f)
  }
  Column(
    modifier = modifier
      // The container is the rail's height; the card hangs off it and isn't measured against it
      .wrapContentHeight(align = Alignment.Top, unbounded = true)
      .requiredWidth(PREVIEW_WIDTH)
      .previewAnchor(index, count)
      // After the anchor, so the hover covers the card where it's drawn. Before it, it covered
      // the rail's height band and the card went once the pointer left that
      .onPointerEvent(PointerEventType.Enter) { onHover(true) }
      .onPointerEvent(PointerEventType.Exit) { onHover(false) }
      .shadow(12.dp, PREVIEW_SHAPE)
      .clip(PREVIEW_SHAPE)
      .background(composerGlassSurface())
      .border(1.dp, outline, PREVIEW_SHAPE)
      .padding(12.dp),
  ) {
    Text(
      text = item.userText ?: "User message",
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    item.assistantText?.let { text ->
      Text(
        text = text,
        color = JewelTheme.globalColors.text.info,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

/**
 * Upstream's `activeTooltipTranslate`: the card hangs below the first strip, above the last, and
 * is centred on the rest. A layout, so the card's own height is known.
 */
private fun Modifier.previewAnchor(index: Int, count: Int): Modifier = layout { measurable, c ->
  val placeable = measurable.measure(c)
  val shift: Int = when (index) {
    0 -> 0
    count - 1 -> -placeable.height
    else -> -placeable.height / 2
  }
  layout(placeable.width, placeable.height) { placeable.place(0, shift) }
}

/** `TimelineMinimapNavigationButton`: out of sight until the pointer finds it. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MinimapNavigationButton(
  previous: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  onHover: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var hovered: Boolean by remember { mutableStateOf(false) }
  val opacity: Float by animateFloatAsState(if (hovered) 1f else 0f, tween(TRANSITION_MS))
  val label: String = if (previous) "Previous turn" else "Next turn"
  Box(
    modifier
      .size(NAVIGATION_SIZE)
      .alpha(opacity)
      .onPointerEvent(PointerEventType.Enter) {
        hovered = true
        onHover(true)
      }
      .onPointerEvent(PointerEventType.Exit) {
        hovered = false
        onHover(false)
      },
  ) {
    Tooltip({ Text(label) }) {
      IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
        Icon(
          key = if (previous) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown,
          contentDescription = label,
        )
      }
    }
  }
}
