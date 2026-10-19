package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.TurnItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FixtureClientTest {
  private val directory: Path = Path.of(System.getProperty("t3code.scenarios"))

  @Test
  fun `every scenario decodes`() {
    val files: List<Path> = Files.list(directory).use { stream ->
      stream.filter { it.extension == "json" }.toList()
    }
    assertEquals(9, files.size)
    files.forEach { file ->
      val scenario: Scenario = Scenario.decode(file.nameWithoutExtension, file.readText())
      assertTrue(scenario.connection !is ConnectionState.Error, "${file.fileName} failed")
    }
  }

  @Test
  fun `unknown item types keep their raw json`() {
    val text: String = directory.resolve("unknown-items.json").readText()
    val items: List<FixtureItem> = Scenario.decode("unknown-items", text).items.values.single()
    val unknown: List<TurnItem.Unknown> = items.map { it.item }.filterIsInstance<TurnItem.Unknown>()
    assertEquals(listOf("web_search", "todo_list", "some_future_type"), unknown.map { it.type })
    assertTrue("hologram" in unknown.last().raw)
  }

  @Test
  fun `many items has a few hundred`() {
    val text: String = directory.resolve("many-items.json").readText()
    assertTrue(Scenario.decode("many-items", text).items.values.single().size >= 300)
  }

  @Test
  fun `running thread replays the assistant message growing`() = runTest {
    val client = FixtureClient(directory, backgroundScope)
    client.select("running-thread")
    client.selected.first { it == "running-thread" }
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
      Scenario.decode("broken", """{ "connection": { "state": "sideways" } }""")
    } catch (e: Exception) {
      Scenario.failed("broken", e.message.orEmpty())
    }
    assertIs<ConnectionState.Error>(scenario.connection)
  }

  private companion object {
    const val THREAD = "thr_01JC4A1F0QWMIGRATE"
  }
}
