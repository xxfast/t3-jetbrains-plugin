package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FixtureClientTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val default: Path = scenarios.resolve("default")

  @Test
  fun `every file in every scenario decodes`() {
    val folders: List<Path> = list(scenarios).filter { it.isDirectory() }
    assertEquals(
      listOf("auth-error", "default", "disconnected", "empty-server", "pairing"),
      folders.map { it.name },
    )
    folders.forEach { folder ->
      val parts: List<Scenario> = jsonFiles(folder).map { file ->
        val scenario: Scenario = Scenario.decode(file.readText())
        // A known item missing a field decodes as Unknown rather than failing, so check for it
        val unknown: List<String> = scenario.items.values.flatten()
          .map { it.item }
          .filterIsInstance<TurnItem.Unknown>()
          .map { it.type }
        assertTrue(unknown.all { it == "some_future_type" }, "$folder/${file.name}: $unknown")
        scenario
      }
      assertTrue(parts.isNotEmpty(), "$folder is empty")
      assertTrue(Scenario.merge(parts).connection !is ConnectionState.Error, "$folder failed")
    }
  }

  @Test
  fun `default carries the list and every demo thread, connected`() {
    val parts: List<Scenario> = jsonFiles(default).map { Scenario.decode(it.readText()) }
    val scenario: Scenario = Scenario.merge(parts)
    assertIs<ConnectionState.Connected>(scenario.connection)
    assertEquals(19, scenario.threads.size)
    val demos: Set<String> = scenario.threads
      .filter { it.title.startsWith("Demo: ") }
      .map { it.id }
      .toSet()
    assertEquals(setOf(THREAD, WAITING, UNKNOWN, MANY), demos)
  }

  @Test
  fun `connection scenarios list the same threads as default, byte for byte`() {
    val expected: ByteArray = default.resolve("list.json").readBytes()
    listOf("disconnected", "pairing", "auth-error").forEach { name ->
      val copy: ByteArray = scenarios.resolve(name).resolve("list.json").readBytes()
      assertContentEquals(expected, copy, "$name/list.json drifted from default/list.json")
    }
  }

  @Test
  fun `unknown item types keep their raw json`() {
    val text: String = default.resolve("unknown-items.json").readText()
    val items: List<FixtureItem> = Scenario.decode(text).items.values.single()
    assertIs<TurnItem.WebSearch>(items[1].item)
    assertIs<TurnItem.TodoList>(items[2].item)
    val unknown: TurnItem.Unknown =
      assertIs(items.map { it.item }.single { it is TurnItem.Unknown })
    assertEquals("some_future_type", unknown.type)
    assertTrue("hologram" in unknown.raw)
  }

  @Test
  fun `many items has a few hundred`() {
    val text: String = default.resolve("many-items.json").readText()
    assertTrue(Scenario.decode(text).items.values.single().size >= 300)
  }

  @Test
  fun `files merge, threads dedupe by id and items append`(@TempDir directory: Path) = runTest {
    val threads: JsonArray = listJson()["threads"]!!.jsonArray
    val first: JsonObject = threads[0].jsonObject
    val second: JsonObject = threads[1].jsonObject
    val id: String = first.getValue("id").jsonPrimitive.content
    val items: List<JsonElement> = demoItems()
    directory.resolve("a.json").writeText(
      file(
        "threads" to JsonArray(listOf(retitled(first, "from a"))),
        "items" to JsonObject(mapOf(id to JsonArray(listOf(items[0])))),
      ),
    )
    directory.resolve("b.json").writeText(
      file(
        "threads" to JsonArray(listOf(retitled(first, "from b"), second)),
        "items" to JsonObject(mapOf(id to JsonArray(listOf(items[1])))),
      ),
    )

    val client = FixtureClient(directory, backgroundScope)
    val merged: List<String> = client.threads.first { it.isNotEmpty() }.map { it.title }

    assertEquals(listOf("from a", second.getValue("title").jsonPrimitive.content), merged)
    val view: ThreadView = client.thread(id).first()
    val expected: List<String> = items.take(2)
      .map { it.jsonObject.getValue("id").jsonPrimitive.content }
    assertEquals(expected, view.items.map { it.id })
  }

  @Test
  fun `the last file that sets a connection wins`(@TempDir directory: Path) = runTest {
    directory.resolve("a.json").writeText("""{ "connection": { "state": "authError" } }""")
    directory.resolve("b.json").writeText("""{ "connection": { "state": "unpaired" } }""")
    directory.resolve("c.json").writeText("""{ "threads": [] }""")

    val client = FixtureClient(directory, backgroundScope)

    val connection: ConnectionState = client.connection.first { it != ConnectionState.Connecting }
    assertEquals(ConnectionState.Unpaired, connection)
  }

  @Test
  fun `no file setting a connection means connected`(@TempDir directory: Path) = runTest {
    directory.resolve("a.json").writeText("""{ "threads": [] }""")

    val client = FixtureClient(directory, backgroundScope)

    val connection: ConnectionState = client.connection.first { it != ConnectionState.Connecting }
    assertIs<ConnectionState.Connected>(connection)
  }

  @Test
  fun `running thread replays the assistant message growing`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == THREAD } }

    val views: List<ThreadView> = client.thread(THREAD)
      .transformWhile { view ->
        emit(view)
        view.items.none { it.id == "a2" }
      }
      .toList()

    val texts: List<String?> = views
      .map { view -> view.items.find { it.id == "a1" } as TurnItem.AssistantMessage? }
      .map { it?.text }
      .distinct()
    assertEquals(
      listOf(
        null,
        "Looking at",
        "Looking at the version catalog first,",
        "Looking at the version catalog first, then the `Source` call sites",
        "Looking at the version catalog first, then the `Source` call sites in `:kstore-file`.",
      ),
      texts,
    )
    assertEquals(listOf("u1", "a1", "c1", "f1", "a2"), views.last().items.map { it.id })
    assertIs<TurnItem.FileChange>(views.last().items[3])
  }

  @Test
  fun `bad file surfaces as an error state`() {
    val scenario: Scenario = try {
      Scenario.decode("""{ "connection": { "state": "sideways" } }""")
    } catch (e: Exception) {
      Scenario.failed("broken.json", e.message.orEmpty())
    }
    assertIs<ConnectionState.Error>(scenario.connection)
  }

  private fun list(directory: Path): List<Path> =
    Files.list(directory).use { stream -> stream.toList().sortedBy { it.name } }

  private fun jsonFiles(directory: Path): List<Path> =
    list(directory).filter { it.extension == "json" }

  private fun listJson(): JsonObject =
    T3Json.parseToJsonElement(default.resolve("list.json").readText()).jsonObject

  /** The running demo's items without their `after` delays, so they all show up at once. */
  private fun demoItems(): List<JsonElement> =
    T3Json.parseToJsonElement(default.resolve("running.json").readText())
      .jsonObject.getValue("items").jsonObject.getValue(THREAD).jsonArray
      .map { JsonObject(it.jsonObject - "after") }

  private fun retitled(thread: JsonObject, title: String): JsonObject =
    JsonObject(thread + ("title" to JsonPrimitive(title)))

  private fun file(vararg entries: Pair<String, JsonElement>): String =
    JsonObject(mapOf(*entries)).toString()

  private companion object {
    const val THREAD = "thr_01JC4A1F0QWMIGRATE"
    const val WAITING = "thr_01JC49ZK2HWAITING"
    const val UNKNOWN = "thr_01JC4C0X1UNKNOWN"
    const val MANY = "thr_01JC5D0M4NYITEMS"
  }
}
