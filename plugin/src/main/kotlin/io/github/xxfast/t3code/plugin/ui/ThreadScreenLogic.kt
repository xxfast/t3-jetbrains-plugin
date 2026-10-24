package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** When the item happened: when it started, else when it last changed. `null` if neither parses. */
fun TurnItem.time(): Instant? = startedAt?.instantOrNull() ?: updatedAt.instantOrNull()

private fun String.instantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private val PrettyJson: Json = Json(from = T3Json) { prettyPrint = true }

fun prettyJson(raw: JsonObject): String = PrettyJson.encodeToString(JsonObject.serializer(), raw)

private val FullJson: Json = Json(from = T3Json) { encodeDefaults = true }

/** The item as JSON: as received for [TurnItem.Unknown], re-encoded for the branches we know. */
fun TurnItem.json(): JsonObject = when (this) {
  is TurnItem.Unknown -> raw
  else -> FullJson.encodeToJsonElement(TurnItem.serializer(), this).jsonObject
}

/** `TIMELINE_FOLLOW_REARM_THRESHOLD_PX`, MessagesTimeline.logic.ts. */
const val TIMELINE_FOLLOW_REARM_THRESHOLD_DP: Int = 40

/**
 * How far the timeline's true end, bottom padding included, sits below the viewport's end, in
 * pixels. The padding is the composer's inset, which hides the same amount of viewport, so this
 * is the gap between the last row and the composer's top, as upstream's `resolveTimelineIsAtEnd`
 * (MessagesTimeline.logic.ts) measures it. `null` when the last row isn't laid out.
 */
fun timelineEndGap(
  lastVisibleIndex: Int?,
  lastVisibleEnd: Int, // offset plus size of the last visible row
  totalItems: Int,
  afterContentPadding: Int,
  viewportEnd: Int,
): Int? = when {
  totalItems == 0 -> 0
  lastVisibleIndex != totalItems - 1 -> null
  else -> lastVisibleEnd + afterContentPadding - viewportEnd
}

/** `resolveTimelineIsAtEnd`, MessagesTimeline.logic.ts: within the re-arm band of the end. */
fun isTimelineAtEnd(gap: Int?, thresholdPx: Int): Boolean = gap != null && gap <= thresholdPx

/** `TimelineScrollMode`, timelineScrollAnchoring.ts. */
enum class TimelineScrollMode { FollowingEnd, AnchoringNewTurn, FreeScrolling }

/**
 * How the timeline scrolls, upstream's `useTimelineFollow` (chat/useTimelineFollow.ts). It
 * follows its end until the user navigates away (`cancelTimelineLiveFollowForUserNavigation`);
 * rows arriving, regrouping or streaming never break it. Coming back into the end band re-arms
 * it, on the change only, as `onIsAtEndChange` does, so a small scroll up inside the band holds.
 * A send anchors its message ([anchorNewTurn]); the anchor only holds the row in place when it's
 * the thread's first prompt ([anchoredIndex]), otherwise the send just returns to the end. One
 * per thread.
 */
class TimelineFollow {
  var mode: TimelineScrollMode by mutableStateOf(TimelineScrollMode.FollowingEnd)
    private set

  /** The sent message's id, upstream's `timelineAnchor.messageId`. Free scrolling keeps it. */
  var anchor: String? by mutableStateOf(null)
    private set

  /** `timelineLiveFollowEnabled`: following the end, or anchoring a send. */
  val following: Boolean get() = mode != TimelineScrollMode.FreeScrolling

  /** `showScrollToBottom`: the user scrolled away and left the end band. */
  val awayFromEnd: Boolean get() = mode == TimelineScrollMode.FreeScrolling && !atEnd

  private var atEnd: Boolean by mutableStateOf(true)
  private var positioned: String? = null // `positionedTimelineAnchorRef`

  /** `cancelTimelineLiveFollowForUserNavigation`: a wheel step or scrollbar drag up. */
  fun cancelForUserNavigation() {
    mode = TimelineScrollMode.FreeScrolling
    positioned = null
  }

  /** `anchorNewTurn`: a message the user just sent, before its run exists. */
  fun anchorNewTurn(messageId: String) {
    atEnd = true
    mode = TimelineScrollMode.AnchoringNewTurn
    anchor = messageId
  }

  /** `scrollToEnd`: back to following the end, the anchor let go. */
  fun scrollToEnd() {
    atEnd = true
    mode = TimelineScrollMode.FollowingEnd
    anchor = null
  }

  /**
   * After a layout, [gap] as [timelineEndGap] has it: whether to scroll to the true end. An
   * [anchored] row holds instead, as `maintainScrollAtEnd` is off while the anchored end space
   * is (MessagesTimeline.tsx).
   */
  fun onLayout(gap: Int?, thresholdPx: Int, anchored: Boolean = false): Boolean {
    val nowAtEnd: Boolean = isTimelineAtEnd(gap, thresholdPx)
    // `onIsAtEndChange`: leaving the end only counts once the user has broken the follow
    if (nowAtEnd != atEnd && (nowAtEnd || mode == TimelineScrollMode.FreeScrolling)) {
      atEnd = nowAtEnd
      if (nowAtEnd) scrollToEnd()
    }
    return shouldFollowTimelineEnd(following && !anchored, gap)
  }

  /** `onTimelineAnchorReady`: whether the anchored row still has to be scrolled into place. */
  fun positionAnchor(messageId: String): Boolean {
    if (positioned == messageId) return false
    positioned = messageId
    return true
  }
}

/**
 * `resolveChatListAnchoredEndSpace`, packages/shared/src/chatList.ts: [anchor]'s index when it's
 * the first user message, else null. Only a thread's first prompt is held near the top; a
 * follow-up returns to the end (upstream #7897).
 */
fun anchoredIndex(rows: List<TimelineRow>, anchor: String?): Int? {
  if (anchor == null) return null
  val index: Int = rows.indexOfFirst { row -> row.userMessageId() != null }
  return index.takeIf { it >= 0 && rows[it].userMessageId() == anchor }
}

private fun TimelineRow.userMessageId(): String? =
  ((this as? TimelineRow.Message)?.entry?.item as? TurnItem.UserMessage)?.messageId

/**
 * `shouldReleaseTimelineAnchorForToolActivity`, ChatView.logic.ts, with `useTimelineFollow`'s
 * reasoning check: the running run did work, so the anchor gives way to following the end.
 * Every work entry here carries its item's type, as upstream's do, so any one of the run's
 * counts, reasoning included.
 */
fun shouldReleaseTimelineAnchor(
  anchor: String?,
  following: Boolean,
  runningRunId: String?,
  entries: List<TimelineEntry>,
): Boolean {
  if (anchor == null || !following || runningRunId == null) return false
  return entries.any { entry -> entry is TimelineEntry.Work && entry.runId == runningRunId }
}

/**
 * Whether to scroll to the true end after a layout: pinned and the end isn't on screen yet.
 * `TIMELINE_MAINTAIN_SCROLL_AT_END` (MessagesTimeline.tsx) does it on data change, item layout
 * and layout; every layout here covers all three.
 */
fun shouldFollowTimelineEnd(pinned: Boolean, gap: Int?): Boolean =
  pinned && (gap == null || gap > 0)

/** `+3 -1`, either side left out when absent. */
fun diffStat(additions: Long?, deletions: Long?): String =
  listOfNotNull(additions?.let { "+$it" }, deletions?.let { "-$it" }).joinToString(" ")

/** What "Open" on a file change opens: the first changed path, else the file name. */
fun TurnItem.FileChange.openPath(): String = changes?.firstOrNull()?.path ?: fileName
