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

/** The generated `TurnItem` decodes the harness scenarios the same way the plugin reads them. */
class GeneratedDecodeTest {
  private val branches: Map<String, KClass<out TurnItem>> = mapOf(
    "user_message" to TurnItem.UserMessage::class,
    "assistant_message" to TurnItem.AssistantMessage::class,
    "reasoning" to TurnItem.Reasoning::class,
    "command_execution" to TurnItem.CommandExecution::class,
    "file_change" to TurnItem.FileChange::class,
    "error" to TurnItem.Error::class,
    "system_notice" to TurnItem.SystemNotice::class,
    "web_search" to TurnItem.WebSearch::class,
    "todo_list" to TurnItem.TodoList::class,
  )

  @Test
  fun runningThread() {
    val items: List<Pair<JsonObject, TurnItem>> = decode("running-thread.json")
    assertTrue(items.isNotEmpty())
    for ((raw, item) in items) assertBranch(raw, item)
  }

  @Test
  fun unknownItems() {
    val items: List<Pair<JsonObject, TurnItem>> = decode("unknown-items.json")
    for ((raw, item) in items) assertBranch(raw, item)

    val unknown: TurnItem.Unknown =
      assertIs(items.map { (_, item) -> item }.single { item -> item is TurnItem.Unknown })
    assertEquals("some_future_type", unknown.type)
    assertEquals("x1", unknown.id)
    assertEquals(4L, unknown.ordinal)
    assertTrue("hologram" in unknown.raw)
  }

  private fun assertBranch(raw: JsonObject, item: TurnItem) {
    val type: String = item.type
    val expected: KClass<out TurnItem> = branches[type] ?: TurnItem.Unknown::class
    assertEquals(expected, item::class, "${raw["id"]} ($type)")
    assertEquals(raw["id"]?.toString()?.trim('"'), item.id)
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
