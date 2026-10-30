package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ItemDetailLogicTest {
  @Test
  fun `a live item keeps one revision`() {
    for (status in listOf("idle", "pending", "running", "waiting")) {
      assertEquals("live", turnItemDetailRevision(item(status, "2026-10-19T06:01:30Z")))
    }
  }

  @Test
  fun `a settled item's revision is its update time`() {
    for (status in listOf("completed", "failed", "declined", "interrupted")) {
      assertEquals(
        "2026-10-19T06:01:30.000Z",
        turnItemDetailRevision(item(status, "2026-10-19T06:01:30.000Z")),
      )
    }
    // `DateTime.formatIso` always writes milliseconds
    assertEquals(
      "2026-10-19T06:01:30.000Z",
      turnItemDetailRevision(item("completed", "2026-10-19T06:01:30Z")),
    )
  }

  private fun item(status: String, updatedAt: String): TurnItem = T3Json.decodeFromJsonElement(
    TurnItem.serializer(),
    T3Json.parseToJsonElement(
      """{"type":"file_change","id":"f","threadId":"t","ordinal":1,"status":"$status",
        |"updatedAt":"$updatedAt","fileName":"A.kt"}""".trimMargin(),
    ).jsonObject,
  )
}
