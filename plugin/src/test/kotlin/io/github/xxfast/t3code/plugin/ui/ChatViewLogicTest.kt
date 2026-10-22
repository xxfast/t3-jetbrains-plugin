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
  fun `only moving up the timeline unpins it`() {
    val here = TimelineScrollPosition(index = 4, offset = 100)
    val up = TimelineScrollPosition(index = 4, offset = 20)
    val down = TimelineScrollPosition(index = 5, offset = 0)
    // the composer grew or the last row streamed: position unchanged, still pinned
    assertTrue(resolveTimelinePinned(pinned = true, atEnd = false, here, here))
    assertFalse(resolveTimelinePinned(pinned = true, atEnd = false, here, up))
    assertTrue(resolveTimelinePinned(pinned = true, atEnd = false, here, down))
    assertFalse(resolveTimelinePinned(pinned = false, atEnd = false, here, down))
    // the end clamping up after something shrank keeps it pinned
    assertTrue(resolveTimelinePinned(pinned = false, atEnd = true, here, up))
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

  /** A turn item from [json] plus the fields every item requires. */
  private inline fun <reified T : TurnItem> item(json: String): T {
    val fields: JsonObject = T3Json.parseToJsonElement(json).jsonObject
    val common: JsonObject = T3Json.parseToJsonElement(
      """{"id":"i","threadId":"t","ordinal":1,"status":"completed","updatedAt":""}""",
    ).jsonObject
    return T3Json.decodeFromJsonElement(TurnItem.serializer(), JsonObject(common + fields)) as T
  }
}
