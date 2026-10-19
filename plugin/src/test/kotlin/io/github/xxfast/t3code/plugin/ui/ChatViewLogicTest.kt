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
  fun `follow the tail only when the last row was visible`() {
    assertTrue(wasAtBottom(lastVisibleIndex = null, previousCount = 0))
    assertTrue(wasAtBottom(lastVisibleIndex = 9, previousCount = 10))
    assertFalse(wasAtBottom(lastVisibleIndex = 5, previousCount = 10))
    assertFalse(wasAtBottom(lastVisibleIndex = null, previousCount = 10))
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
