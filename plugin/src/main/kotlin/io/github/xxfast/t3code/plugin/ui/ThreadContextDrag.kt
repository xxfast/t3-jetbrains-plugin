package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.jewel.foundation.LocalComponent

// Sidebar rows dragged out of the list and onto a composer, apps/web/src/components/chat/
// threadContextDrag.ts. Upstream doesn't use the browser's drag and drop for it: the Sidebar's
// pointer sensor tracks the drag, `elementFromPoint` finds the composer and a custom event hands
// it the threads. Same here, with the row's own gesture doing the tracking. The Sidebar and a
// ChatView are separate Compose panels, so composers register where they are instead of being
// found by an attribute. EDT only, like the gestures that call it.

private class ThreadContextDropTarget(
  val component: JComponent,
  val onOver: State<(Boolean) -> Unit>,
  val onDrop: State<(List<String>) -> Unit>,
) {
  var bounds: Rect = Rect.Zero // in the component's root, in pixels

  fun contains(screen: Point): Boolean {
    if (!component.isShowing) return false
    // What the panel lays out in: the screen's pixels per point. Not `LocalDensity`, which the
    // bridge multiplies by the IDE's zoom.
    val scale: Float = component.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    val origin: Point = component.locationOnScreen
    return threadContextDropHit(screen, origin, component.visibleRect, bounds, scale)
  }
}

/**
 * Whether [screen] lands on [bounds], a box in its panel's root in pixels, for a panel whose top
 * left is at [origin] on screen with [visible] of it showing, laid out at [scale] pixels a point.
 */
fun threadContextDropHit(
  screen: Point,
  origin: Point,
  visible: Rectangle,
  bounds: Rect,
  scale: Float,
): Boolean {
  val x: Int = screen.x - origin.x
  val y: Int = screen.y - origin.y
  if (!visible.contains(x, y)) return false
  return bounds.contains(Offset(x * scale, y * scale))
}

private val targets: MutableList<ThreadContextDropTarget> = mutableListOf()
private var overTarget: ThreadContextDropTarget? = null
private var ghost: ThreadContextDragGhost? = null

/** Whether a row is out of the list as context right now, from the first move out to the end. */
var threadContextDragActive: Boolean by mutableStateOf(false)
  private set

private fun findDropTarget(screen: Point): ThreadContextDropTarget? =
  targets.firstOrNull { it.contains(screen) }

/**
 * `moveThreadContextDrag`: the pointer is at [screen] carrying [count] threads labelled
 * [title]. Flags the composer under it and moves the ghost along.
 */
fun moveThreadContextDrag(screen: Point, title: String, count: Int) {
  threadContextDragActive = true
  val target: ThreadContextDropTarget? = findDropTarget(screen)
  if (target !== overTarget) {
    overTarget?.onOver?.value?.invoke(false)
    target?.onOver?.value?.invoke(true)
    overTarget = target
  }
  val shown: ThreadContextDragGhost = ghost?.takeIf { it.title == title && it.count == count }
    ?: ThreadContextDragGhost(title, count).also { next ->
      ghost?.popup?.cancel()
      ghost = next
    }
  shown.moveTo(screen)
}

/** `endThreadContextDrag`: no composer flagged, no ghost. */
fun endThreadContextDrag() {
  threadContextDragActive = false
  overTarget?.onOver?.value?.invoke(false)
  overTarget = null
  ghost?.popup?.cancel()
  ghost = null
}

/** `dropThreadContext`: hands [threadIds] to the composer under [screen], if there's one. */
fun dropThreadContext(screen: Point, threadIds: List<String>): Boolean {
  val target: ThreadContextDropTarget = findDropTarget(screen) ?: return false
  if (threadIds.isEmpty()) return false
  target.onDrop.value(threadIds)
  return true
}

/**
 * `threadContextDropTargetProps` and `useThreadContextDrop` together: the modified box takes
 * dropped threads while [enabled], [onOver] says when a drag is over it.
 */
@Composable
fun Modifier.threadContextDropTarget(
  enabled: Boolean,
  onOver: (Boolean) -> Unit,
  onDrop: (threadIds: List<String>) -> Unit,
): Modifier {
  val component: JComponent = LocalComponent.current
  val over: State<(Boolean) -> Unit> = rememberUpdatedState(onOver)
  val drop: State<(List<String>) -> Unit> = rememberUpdatedState(onDrop)
  val target: ThreadContextDropTarget =
    remember(component) { ThreadContextDropTarget(component, over, drop) }
  DisposableEffect(target, enabled) {
    if (enabled) targets += target
    onDispose {
      targets -= target
      if (overTarget === target) {
        overTarget = null
        over.value(false)
      }
    }
  }
  return onGloballyPositioned { coordinates -> target.bounds = coordinates.boundsInRoot() }
}

/**
 * Swallows the pointer while a row is out of the list as context. Crossing another panel with
 * the button held, Swing's enter and exit carry the button and Compose makes a press of it,
 * which a `SelectionContainer` takes for a selection drag. Upstream's drag never selects text.
 */
fun Modifier.ignoresThreadContextDrag(): Modifier = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) {
      val event: PointerEvent = awaitPointerEvent(PointerEventPass.Initial)
      if (threadContextDragActive) event.changes.forEach { change -> change.consume() }
    }
  }
}

/**
 * `ThreadContextDragGhost`: the chat icon, the title and `+N` for the rest, 12px off the
 * pointer. A popup that never takes focus, so it floats over whichever window the composer is
 * in, the way the IDE's own tab drags show theirs.
 */
private class ThreadContextDragGhost(val title: String, val count: Int) {
  val popup: JBPopup

  init {
    val panel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(6, 10)
      val label = JBLabel(title, AllIcons.Toolwindows.ToolWindowMessages, JBLabel.LEFT)
      add(label, BorderLayout.CENTER)
      if (count > 1) {
        val more = JBLabel("+${count - 1}").apply {
          foreground = UIUtil.getContextHelpForeground()
        }
        add(more, BorderLayout.EAST)
      }
    }
    // `max-w-72`: a long title ellipsises
    val preferred: Dimension = panel.preferredSize
    panel.preferredSize = Dimension(minOf(preferred.width, JBUI.scale(288)), preferred.height)
    popup = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, null)
      .setFocusable(false)
      .setRequestFocus(false)
      .setCancelOnClickOutside(false)
      .setCancelOnOtherWindowOpen(false)
      .setCancelOnWindowDeactivation(false)
      .setCancelKeyEnabled(false)
      .setMovable(false)
      .setResizable(false)
      .setShowShadow(true)
      .createPopup()
  }

  fun moveTo(screen: Point) {
    val at = Point(screen.x + JBUI.scale(GHOST_OFFSET), screen.y + JBUI.scale(GHOST_OFFSET))
    if (popup.isVisible) popup.setLocation(at) else popup.show(RelativePoint.fromScreen(at))
  }
}

/** The ghost's `translate(x + 12px, y + 12px)`. */
private const val GHOST_OFFSET: Int = 12
