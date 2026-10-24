package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.FileChangeDetail
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ChatViewLogicTest {
  @Test
  fun `pretty json is indented and round trips`() {
    val raw: JsonObject =
      T3Json.parseToJsonElement("""{"type":"todo_list","items":[{"text":"x"}]}""").jsonObject
    val pretty: String = prettyJson(raw)
    assertTrue(pretty.lines().size > 1)
    assertEquals(raw, T3Json.parseToJsonElement(pretty))
  }

  @Test
  fun `end gap counts the bottom inset`() {
    // last row ends at 900 in a 1000px viewport, with a 300px composer inset below it
    assertEquals(200, timelineEndGap(9, 900, 10, afterContentPadding = 300, viewportEnd = 1000))
    assertEquals(0, timelineEndGap(9, 700, 10, afterContentPadding = 300, viewportEnd = 1000))
    assertEquals(null, timelineEndGap(5, 900, 10, afterContentPadding = 300, viewportEnd = 1000))
    assertEquals(0, timelineEndGap(null, 0, 0, afterContentPadding = 300, viewportEnd = 1000))
  }

  @Test
  fun `at end within the re-arm band`() {
    assertTrue(isTimelineAtEnd(gap = 0, thresholdPx = 40))
    assertTrue(isTimelineAtEnd(gap = 40, thresholdPx = 40))
    assertFalse(isTimelineAtEnd(gap = 41, thresholdPx = 40))
    assertFalse(isTimelineAtEnd(gap = null, thresholdPx = 40))
  }

  @Test
  fun `rows regrouping or streaming never break the follow`() {
    val follow = TimelineFollow()
    assertTrue(follow.onLayout(gap = null, thresholdPx = 40)) // opened at the top
    assertFalse(follow.onLayout(gap = 0, thresholdPx = 40))
    // the last row grew past the band, or rows above it collapsed
    assertTrue(follow.onLayout(gap = 300, thresholdPx = 40))
    assertTrue(follow.onLayout(gap = null, thresholdPx = 40))
    assertTrue(follow.following)
  }

  @Test
  fun `only the user breaks the follow, and coming back to the end re-arms it`() {
    val follow = TimelineFollow()
    follow.onLayout(gap = 0, thresholdPx = 40)
    follow.cancelForUserNavigation()
    // still inside the band: no change into it, so it holds
    assertFalse(follow.onLayout(gap = 20, thresholdPx = 40))
    assertFalse(follow.onLayout(gap = 400, thresholdPx = 40))
    assertFalse(follow.following)
    assertTrue(follow.onLayout(gap = 30, thresholdPx = 40)) // back in the band: re-armed
    assertTrue(follow.following)
    assertTrue(follow.onLayout(gap = 200, thresholdPx = 40))
  }

  @Test
  fun `a send returns to the end after scrolling away`() {
    val follow = TimelineFollow()
    follow.cancelForUserNavigation()
    assertFalse(follow.onLayout(gap = 400, thresholdPx = 40))
    follow.anchorNewTurn("m-2")
    assertEquals(TimelineScrollMode.AnchoringNewTurn, follow.mode)
    assertTrue(follow.onLayout(gap = 400, thresholdPx = 40)) // a follow-up isn't anchored
  }

  @Test
  fun `an anchored first prompt holds while its reply streams past the end`() {
    val follow = TimelineFollow()
    follow.anchorNewTurn("m-1")
    assertFalse(follow.onLayout(gap = 0, thresholdPx = 40, anchored = true))
    assertFalse(follow.onLayout(gap = 400, thresholdPx = 40, anchored = true))
    // leaving the end without the user doesn't count, so coming back doesn't let go either
    assertFalse(follow.onLayout(gap = 0, thresholdPx = 40, anchored = true))
    assertEquals("m-1", follow.anchor)
    assertTrue(follow.positionAnchor("m-1"))
    assertFalse(follow.positionAnchor("m-1")) // scrolled into place once
  }

  @Test
  fun `scrolling away keeps the anchor, coming back to the end lets it go`() {
    val follow = TimelineFollow()
    follow.anchorNewTurn("m-1")
    follow.cancelForUserNavigation()
    assertFalse(follow.onLayout(gap = 400, thresholdPx = 40, anchored = true))
    assertEquals("m-1", follow.anchor)
    assertTrue(follow.onLayout(gap = 20, thresholdPx = 40, anchored = false))
    assertEquals(TimelineScrollMode.FollowingEnd, follow.mode)
    assertEquals(null, follow.anchor)
  }

  @Test
  fun `only the first prompt is anchored`() {
    val rows: List<TimelineRow> = listOf(prompt("m-1"), prompt("m-2"))
    assertEquals(0, anchoredIndex(rows, "m-1"))
    assertEquals(null, anchoredIndex(rows, "m-2"))
    assertEquals(null, anchoredIndex(rows, null))
  }

  @Test
  fun `the running run's work lets the anchor go`() {
    val work = TimelineEntry.Work(
      WorkLogEntry(
        item = item<TurnItem>("""{"type":"command_execution","runId":"r-1"}"""),
        label = "Ran command",
        tone = WorkTone.Tool,
        status = null,
      ),
    )
    assertTrue(shouldReleaseTimelineAnchor("m-1", true, "r-1", listOf(work)))
    assertFalse(shouldReleaseTimelineAnchor("m-1", true, "r-2", listOf(work)))
    assertFalse(shouldReleaseTimelineAnchor("m-1", false, "r-1", listOf(work)))
    assertFalse(shouldReleaseTimelineAnchor(null, true, "r-1", listOf(work)))
  }

  @Test
  fun `follow the end only when pinned and it's off screen`() {
    assertTrue(shouldFollowTimelineEnd(pinned = true, gap = 200))
    assertTrue(shouldFollowTimelineEnd(pinned = true, gap = null))
    assertFalse(shouldFollowTimelineEnd(pinned = true, gap = 0))
    assertFalse(shouldFollowTimelineEnd(pinned = true, gap = -50))
    assertFalse(shouldFollowTimelineEnd(pinned = false, gap = 200))
  }

  @Test
  fun `diff stat and open path`() {
    assertEquals("+2 -1", diffStat(2, 1))
    assertEquals("+2", diffStat(2, null))
    assertEquals("", diffStat(null, null))
    val change: TurnItem.FileChange = item("""{"type":"file_change","fileName":"A.kt"}""")
    assertEquals("A.kt", change.openPath())
    val moved: TurnItem.FileChange =
      change.copy(changes = listOf(FileChangeDetail(operation = "update", path = "src/A.kt")))
    assertEquals("src/A.kt", moved.openPath())
  }

  private fun prompt(messageId: String): TimelineRow = TimelineRow.Message(
    entry = TimelineEntry.Message(
      item<TurnItem>(
        """
          {
            "id": "$messageId", "type": "user_message", "text": "hi", "messageId": "$messageId",
            "createdBy": "user", "creationSource": "web", "inputIntent": "turn_start",
            "attachments": []
          }
        """,
      ),
    ),
    showMeta = false,
  )

  /** A turn item from [json] plus the fields every item requires. */
  private inline fun <reified T : TurnItem> item(json: String): T {
    val fields: JsonObject = T3Json.parseToJsonElement(json).jsonObject
    val common: JsonObject = T3Json.parseToJsonElement(
      """{"id":"i","threadId":"t","ordinal":1,"status":"completed","updatedAt":""}""",
    ).jsonObject
    return T3Json.decodeFromJsonElement(TurnItem.serializer(), JsonObject(common + fields)) as T
  }
}
