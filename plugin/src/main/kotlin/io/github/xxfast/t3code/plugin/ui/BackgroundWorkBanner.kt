package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** What the banner shows for one thread: the provider's pending work and Stop's state. */
class BackgroundWorkState(
  val tasks: List<PendingBackgroundTask>,
  /** Upstream's `stopping`: a stop is on its way and hasn't been answered. */
  val stopping: Boolean = false,
  /** The pairing may operate the thread, upstream's `canOperateThread`. */
  val enabled: Boolean = true,
)

/** What the banner's controls do; the host dispatches. */
class BackgroundWorkActions(
  val onStop: () -> Unit = {},
  val onOpenThread: (threadId: String) -> Unit = {},
)

/**
 * The background work banner above the composer, upstream's `backgroundWorkBannerItem`
 * (`ThreadActivityBanners.tsx`) in its `ComposerBannerStack` slot: a drawer over the box with a
 * dot that pulses while the work holds completion, the title `presentPendingBackgroundWork`
 * gives it, the items as a muted line, and Stop. A subagent item is a link to its child thread,
 * "Show" when it's the only item. One nameless task has no description line. Nothing without
 * tasks.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun BackgroundWorkBanner(
  state: BackgroundWorkState,
  actions: BackgroundWorkActions,
  modifier: Modifier = Modifier,
) {
  val presentation: PendingBackgroundWorkPresentation =
    presentPendingBackgroundWork(state.tasks) ?: return
  val dark: Boolean = JewelTheme.isDark
  val outline: Color =
    if (dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f)
  val shape: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = DRAWER_INSET)
      .clip(shape)
      .background(composerGlassSurface())
      .border(1.dp, outline, shape)
      .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = DRAWER_OVERLAP + 4.dp)
      .heightIn(min = ROW_MIN_HEIGHT),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
      StatusDot(pulsing = presentation.waiting)
    }
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = presentation.title,
        style = small().copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val items: List<PendingBackgroundWorkItem> = presentation.items
      // Upstream drops the description for one item with no thread to open
      if (items.size != 1 || items.single().childThreadId != null) {
        Description(items, actions.onOpenThread, modifier = Modifier.weight(1f, fill = false))
      }
    }
    Link(
      text = if (state.stopping) "Stopping..." else "Stop",
      onClick = actions.onStop,
      enabled = state.enabled && !state.stopping,
      textStyle = small(),
    )
  }
}

/**
 * The items in order, separated by commas: a plain label, or a link to a subagent's child
 * thread ("Show" alone, its label among others). The whole line ellipsises and a tooltip
 * carries the full text, upstream's info popover.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun Description(
  items: List<PendingBackgroundWorkItem>,
  onOpenThread: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val full: String = items.joinToString(", ") { it.label }
  Tooltip(tooltip = { Text(full) }, modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      items.forEachIndexed { index, item ->
        val separator: String = if (index < items.lastIndex) "," else ""
        val childThreadId: String? = item.childThreadId
        if (childThreadId == null) {
          Text(
            text = item.label + separator,
            style = small(),
            color = muted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
        } else {
          Link(
            text = if (items.size == 1) "Show" else item.label,
            onClick = { onOpenThread(childThreadId) },
            textStyle = small(),
          )
          if (separator.isNotEmpty()) Text(separator, style = small(), color = muted())
        }
        if (index < items.lastIndex) Text(" ", style = small())
      }
    }
  }
}

/** Upstream's `size-1.5 rounded-full bg-foreground`, with `animate-status-pulse` while waiting. */
@Composable
private fun StatusDot(pulsing: Boolean) {
  val alpha: Float = if (pulsing) {
    val pulse: Float by rememberInfiniteTransition()
      .animateFloat(1f, 0.5f, infiniteRepeatable(tween(1_000), RepeatMode.Reverse))
    pulse
  } else {
    1f
  }
  Box(
    Modifier
      .size(DOT)
      .alpha(alpha)
      .background(JewelTheme.globalColors.text.normal, CircleShape),
  )
}

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val DRAWER_INSET: Dp = 22.dp

/** `--chat-composer-attachment-overlap`, `calc(1rem+1px)`: the part behind the box. */
private val DRAWER_OVERLAP: Dp = 17.dp

/** `--composer-banner-icon-column`, `--spacing(6)`. */
private val ICON_COLUMN: Dp = 24.dp

/** A row's `min-h-5`. */
private val ROW_MIN_HEIGHT: Dp = 20.dp

/** `size-1.5`. */
private val DOT: Dp = 6.dp
