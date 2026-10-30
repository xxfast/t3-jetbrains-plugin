package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class FixtureWireTest {
  @Test
  fun `a file change loses its diff and contents on the wire`() {
    val item = change(status = "completed", diffStr = "@@ -1 +1 @@\n-a\n+b\n")
      .copy(oldStr = "a", newStr = "b")

    val wire = projectTurnItemForWire(item) as TurnItem.FileChange

    assertNull(wire.diffStr)
    assertNull(wire.oldStr)
    assertNull(wire.newStr)
    assertEquals(item.fileName, wire.fileName)
  }

  @Test
  fun `a failed file change keeps a truncated diff`() {
    val long: String = "+" + "x".repeat(40_000)
    val wire = projectTurnItemForWire(change(status = "failed", diffStr = long))
      as TurnItem.FileChange

    val diff: String = wire.diffStr!!
    assertTrue(diff.endsWith("\n… output truncated for transport"))
    assertEquals(32_768, diff.removeSuffix("\n… output truncated for transport").length)
    assertNull((projectTurnItemForWire(change("failed", " ")) as TurnItem.FileChange).diffStr)
  }

  @Test
  fun `truncation never splits a character`() {
    val text: String = "é".repeat(20_000)

    val truncated: String = truncateDetail(text, maxBytes = 5)

    assertEquals("éé\n… output truncated for transport", truncated)
  }

  private fun change(status: String, diffStr: String?): TurnItem.FileChange {
    val json: JsonObject = T3Json.parseToJsonElement(
      """{"type":"file_change","id":"f","threadId":"t","ordinal":1,"status":"$status",
        |"updatedAt":"2026-10-19T06:01:30.000Z","fileName":"A.kt"}""".trimMargin(),
    ).jsonObject
    val item = T3Json.decodeFromJsonElement(TurnItem.serializer(), json) as TurnItem.FileChange
    return item.copy(diffStr = diffStr)
  }
}
