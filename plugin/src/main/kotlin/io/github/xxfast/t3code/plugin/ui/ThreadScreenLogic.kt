package io.github.xxfast.t3code.plugin.ui

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

/**
 * Whether the timeline follows its end, upstream's follow mode (ChatView.tsx:4162). Only the
 * user's navigation away from the end breaks it (`cancelTimelineLiveFollowForUserNavigation`);
 * rows arriving, regrouping or streaming never do. Coming back into the end band re-arms it, on
 * the change only, as `onIsAtEndChange` does, so a small scroll up inside the band holds. One per
 * thread.
 */
class TimelineFollow {
  var following: Boolean = true
    private set

  private var atEnd: Boolean = true

  /** `cancelTimelineLiveFollowForUserNavigation`: a wheel step or scrollbar drag up. */
  fun cancelForUserNavigation() {
    following = false
  }

  /** After a layout, [gap] as [timelineEndGap] has it: whether to scroll to the true end. */
  fun onLayout(gap: Int?, thresholdPx: Int): Boolean {
    val nowAtEnd: Boolean = isTimelineAtEnd(gap, thresholdPx)
    if (nowAtEnd && !atEnd) following = true
    atEnd = nowAtEnd
    return shouldFollowTimelineEnd(following, gap)
  }
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
