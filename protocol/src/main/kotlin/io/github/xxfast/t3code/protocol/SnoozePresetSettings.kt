package io.github.xxfast.t3code.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// The snooze menu's settings, as packages/contracts/src/settings.ts:86 to 122 at the pinned tag.
// Settings aren't in the generated contracts, so these are by hand like `ProjectScriptSettings`.

/** `MAX_SNOOZE_PRESET_MINUTES`: thirty days. */
public const val MAX_SNOOZE_PRESET_MINUTES: Int = 30 * 24 * 60

/** `SnoozePresetDay`, [wire] as the server writes it. */
public enum class SnoozePresetDay(public val wire: String) {
  Today("today"),
  Tomorrow("tomorrow"),
  Monday("monday"),
  Tuesday("tuesday"),
  Wednesday("wednesday"),
  Thursday("thursday"),
  Friday("friday"),
  Saturday("saturday"),
  Sunday("sunday"),
}

/**
 * `SnoozePresetSetting`: [After] wakes a fixed time from now. [At] wakes at a local `HH:mm`; a
 * weekday is its next occurrence after today.
 */
public sealed interface SnoozePresetSetting {
  public data class After(val minutes: Int) : SnoozePresetSetting

  public data class At(val day: SnoozePresetDay, val time: String) : SnoozePresetSetting
}

/** `DEFAULT_SNOOZE_PRESETS`, what a server that never saved `snoozePresets` decodes to. */
public val DEFAULT_SNOOZE_PRESETS: List<SnoozePresetSetting> = listOf(
  SnoozePresetSetting.After(minutes = 60),
  SnoozePresetSetting.After(minutes = 180),
  SnoozePresetSetting.At(SnoozePresetDay.Today, "18:00"),
  SnoozePresetSetting.At(SnoozePresetDay.Tomorrow, "09:00"),
  SnoozePresetSetting.At(SnoozePresetDay.Monday, "09:00"),
)

private val SNOOZE_PRESET_TIME: Regex = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

/**
 * `settings.snoozePresets` of `server.getConfig`, with upstream's decoding default when it's
 * absent. An empty list stays empty, the user removed them all. An entry that doesn't pass the
 * schema's checks is skipped.
 */
public fun decodeSnoozePresets(element: JsonElement?): List<SnoozePresetSetting> {
  val entries: JsonArray = element as? JsonArray ?: return DEFAULT_SNOOZE_PRESETS
  return entries.mapNotNull { entry -> decodeSnoozePreset(entry as? JsonObject) }
}

private fun decodeSnoozePreset(entry: JsonObject?): SnoozePresetSetting? {
  fun field(key: String): JsonPrimitive? = entry?.get(key) as? JsonPrimitive
  return when (field("kind")?.contentOrNull) {
    "after" -> {
      val minutes: Int = field("minutes")?.intOrNull ?: return null
      if (minutes !in 1..MAX_SNOOZE_PRESET_MINUTES) return null
      SnoozePresetSetting.After(minutes)
    }

    "at" -> {
      val wire: String? = field("day")?.contentOrNull
      val day: SnoozePresetDay = SnoozePresetDay.entries.find { it.wire == wire } ?: return null
      val time: String = field("time")?.contentOrNull ?: return null
      if (!SNOOZE_PRESET_TIME.matches(time)) return null
      SnoozePresetSetting.At(day, time)
    }

    else -> null
  }
}
