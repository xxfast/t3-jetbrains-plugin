package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

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
 * `backgroundWorkNowItem`: background commands and subagents, for the end of the timeline. A
 * terminal when it's all commands, else a dashed circle. One task is "Running" or "Waiting on"
 * its label, with what it's doing, how long it's run, and Open for its child thread. Several take
 * `presentPendingBackgroundWork`'s title and the newest subagent's activity, else their labels.
 * Then Stop. Null without tasks.
 */
fun backgroundWorkNowItem(
  threadId: String,
  tasks: List<PendingBackgroundTask>,
  turnItems: List<TurnItem>,
  stopDisabled: Boolean,
  stopping: Boolean,
  onStop: () -> Unit,
  onOpenThread: (threadId: String) -> Unit,
): ThreadNowItem? {
  val presentation: PendingBackgroundWorkPresentation =
    presentPendingBackgroundWork(tasks) ?: return null
  val rows: List<BackgroundWorkRow> = backgroundWorkRows(presentation.items, turnItems)
  val only: BackgroundWorkRow? = rows.singleOrNull()
  val newest: BackgroundWorkRow? = rows
    .filter { it.activity != null }
    .sortedByDescending { it.updatedAtMs }
    .firstOrNull()
  val commands: Boolean = rows.all { it.isCommand }
  val verb: String = if (presentation.waiting) "Waiting on" else "Running"
  val onlyThreadId: String? = only?.childThreadId
  val startedAtMs: Long? = only?.startedAtMs
  return ThreadNowItem(
    id = "background-work:$threadId",
    icon = { Glyph(if (commands) "terminal" else "circle-dashed", muted()) },
    title = if (only != null) {
      buildAnnotatedString {
        append("$verb ")
        if (only.isCommand) withStyle(MONO) { append(only.task.label) } else append(only.task.label)
      }
    } else {
      AnnotatedString(presentation.title)
    },
    description = when {
      only != null -> only.activity?.let(::AnnotatedString)
      newest != null -> AnnotatedString("${newest.task.label}: ${newest.activity}")
      else -> {
        val labels: String = rows.joinToString(", ") { it.task.label }
        if (commands) AnnotatedString(labels, MONO) else AnnotatedString(labels)
      }
    },
    actions = {
      if (startedAtMs != null) Elapsed(startedAtMs)
      if (onlyThreadId != null) {
        Link(text = "Open", onClick = { onOpenThread(onlyThreadId) }, textStyle = small())
      }
      Link(
        text = if (stopping) "Stopping..." else "Stop",
        onClick = onStop,
        enabled = !stopDisabled,
        textStyle = small(),
      )
    },
  )
}

/**
 * How long the one task has run, at least a second, upstream's `tickTextContent` over
 * `formatDuration`: it ticks every second, and only the label recomposes.
 */
@Composable
private fun Elapsed(startedAtMs: Long) {
  var nowMs: Long by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(startedAtMs) {
    while (true) {
      nowMs = System.currentTimeMillis()
      delay(1_000)
    }
  }
  Text(
    text = formatDuration(maxOf(1_000, nowMs - startedAtMs)),
    style = small().copy(fontFeatureSettings = "tnum"),
    color = muted(),
  )
}

/** `font-mono`. */
private val MONO: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace)
