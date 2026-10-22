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
