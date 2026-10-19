package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Generated tagged unions pick their branch by discriminator and never throw on a bad item. */
class TurnItemDecodeTest {
  private val branches: Map<String, KClass<out TurnItem>> = mapOf(
    "user_message" to TurnItem.UserMessage::class,
    "assistant_message" to TurnItem.AssistantMessage::class,
    "reasoning" to TurnItem.Reasoning::class,
    "command_execution" to TurnItem.CommandExecution::class,
    "file_change" to TurnItem.FileChange::class,
    "error" to TurnItem.Error::class,
    "system_notice" to TurnItem.SystemNotice::class,
  )

  @Test
  fun runningThreadPicksTheBranchByType() {
    val items: List<Pair<JsonObject, TurnItem>> = decode("default/running.json")
    assertTrue(items.isNotEmpty())
    for ((raw, item) in items) {
      val expected: KClass<out TurnItem> = branches[item.type] ?: TurnItem.Unknown::class
      assertEquals(expected, item::class, "${raw["id"]} (${item.type})")
      assertEquals(raw["id"]?.toString()?.trim('"'), item.id)
    }
  }

  @Test
  fun knownTypeMissingARequiredFieldIsUnknown() {
    val json = """
      {"type":"assistant_message","id":"a1","threadId":"t1","ordinal":2,"status":"running",
       "streaming":true,"updatedAt":"2026-10-19T08:30:15.000Z"}
    """
    val item: TurnItem.Unknown = assertIs(T3Json.decodeFromString(TurnItem.serializer(), json))
    assertEquals("assistant_message", item.type)
    assertEquals("a1", item.id)
    assertEquals(2L, item.ordinal)
    assertEquals("running", item.status)
    assertTrue("streaming" in item.raw)
  }

  @Test
  fun unknownTypeKeepsItsRawJson() {
    val json = """
      {"type":"some_future_type","id":"x1","threadId":"t1","ordinal":4,"status":"running",
       "hologram":{"color":"teal"},"updatedAt":"2026-10-19T09:10:00.000Z"}
    """
    val item: TurnItem.Unknown = assertIs(T3Json.decodeFromString(TurnItem.serializer(), json))
    assertEquals("some_future_type", item.type)
    assertTrue("hologram" in item.raw)
  }

  @Test
  fun nestedUnionFallsBackOnItsOwn() {
    val json = """
      {"type":"fork","id":"f1","threadId":"t1","ordinal":1,"status":"completed",
       "updatedAt":"2026-10-19T09:10:00.000Z","targetThreadId":"t2",
       "source":{"type":"run","threadId":"t0"}}
    """
    val item: TurnItem.Fork = assertIs(T3Json.decodeFromString(TurnItem.serializer(), json))
    val source: TurnItem.Fork.Source.Unknown = assertIs(item.source)
    assertEquals("run", source.type)
  }

  /** Every item in every thread of a scenario, minus the harness-only `after` delay. */
  private fun decode(name: String): List<Pair<JsonObject, TurnItem>> {
    val scenario: JsonObject = T3Json.parseToJsonElement(scenario(name).readText()).jsonObject
    val threads: JsonObject = scenario["items"]?.jsonObject ?: JsonObject(emptyMap())
    return threads.values
      .flatMap { thread -> thread.jsonArray }
      .map { entry -> JsonObject(entry.jsonObject - "after") }
      .map { raw -> raw to T3Json.decodeFromJsonElement(TurnItem.serializer(), raw) }
  }

  private fun scenario(name: String): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      val file = File(dir, "harness/scenarios/$name")
      if (file.exists()) return file
      dir = dir.parentFile
    }
    error("harness/scenarios/$name not found above ${File("").absolutePath}")
  }
}
