package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link

// The lines of thread activity, apps/web/src/components/chat/ThreadActivityBanners.tsx

/** What the work line shows for one thread: the provider's pending work and Stop's state. */
class BackgroundWorkState(
  val tasks: List<PendingBackgroundTask>,
  /** Upstream's `stopping`: a stop is on its way and hasn't been answered. */
  val stopping: Boolean = false,
  /** The pairing may operate the thread, upstream's `canOperateThread`. */
  val enabled: Boolean = true,
)

/** What the work line's actions do; the host dispatches. */
class BackgroundWorkActions(
  val onStop: () -> Unit = {},
  val onOpenThread: (threadId: String) -> Unit = {},
)

/**
 * `backgroundWorkNowItem`: background commands and subagents, for the end of the timeline, with
 * the presentation the drawer had (the new one is #89). A dot that pulses while the work holds
 * completion, the title `presentPendingBackgroundWork` gives it, the items as its description,
 * and Stop. A subagent item links to its child thread, "Show" when it's the only item; one task
 * with no thread to open has no description. Null without tasks.
 */
fun backgroundWorkNowItem(
  threadId: String,
  tasks: List<PendingBackgroundTask>,
  stopDisabled: Boolean,
  stopping: Boolean,
  onStop: () -> Unit,
  onOpenThread: (threadId: String) -> Unit,
): ThreadNowItem? {
  val presentation: PendingBackgroundWorkPresentation =
    presentPendingBackgroundWork(tasks) ?: return null
  return ThreadNowItem(
    id = "background-work:$threadId",
    icon = { StatusDot(pulsing = presentation.waiting) },
    title = AnnotatedString(presentation.title),
    description = description(presentation.items, onOpenThread),
    actions = {
      Link(
        text = if (stopping) "Stopping..." else "Stop",
        onClick = onStop,
        enabled = !stopDisabled,
        textStyle = small(),
      )
    },
  )
}

/** The items in order, separated by commas, a subagent's as a link to its child thread. */
private fun description(
  items: List<PendingBackgroundWorkItem>,
  onOpenThread: (String) -> Unit,
): AnnotatedString? {
  if (items.size == 1 && items.single().childThreadId == null) return null
  return buildAnnotatedString {
    items.forEachIndexed { index, item ->
      if (index > 0) append(", ")
      val childThreadId: String? = item.childThreadId
      if (childThreadId == null) {
        append(item.label)
      } else {
        withLink(LinkAnnotation.Clickable(childThreadId) { onOpenThread(childThreadId) }) {
          append(if (items.size == 1) "Show" else item.label)
        }
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

/** `size-1.5`. */
private val DOT: Dp = 6.dp
