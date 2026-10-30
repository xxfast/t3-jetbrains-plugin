package io.github.xxfast.t3code.protocol

import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test

class SnoozePresetSettingsTest {
  private fun decode(json: String): List<SnoozePresetSetting> =
    decodeSnoozePresets(T3Json.parseToJsonElement(json))

  @Test
  fun `absent is upstream's defaults, empty stays empty`() {
    assertEquals(DEFAULT_SNOOZE_PRESETS, decodeSnoozePresets(null))
    assertEquals(DEFAULT_SNOOZE_PRESETS, decode("""{ "kind": "after", "minutes": 5 }"""))
    assertEquals(emptyList(), decodeSnoozePresets(JsonArray(emptyList<JsonElement>())))
  }

  @Test
  fun `both kinds, and entries the schema refuses are skipped`() {
    val presets: List<SnoozePresetSetting> = decode(
      """
      [
        { "kind": "after", "minutes": 90 },
        { "kind": "at", "day": "tomorrow", "time": "08:00" },
        { "kind": "after", "minutes": 0 },
        { "kind": "after", "minutes": 43201 },
        { "kind": "at", "day": "today", "time": "24:00" },
        { "kind": "at", "day": "today", "time": "9:00" },
        { "kind": "later" }
      ]
      """,
    )
    assertEquals(
      listOf(
        SnoozePresetSetting.After(90),
        SnoozePresetSetting.At(SnoozePresetDay.Tomorrow, "08:00"),
      ),
      presets,
    )
  }
}
