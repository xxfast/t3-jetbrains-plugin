package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.FileChangeEntry
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.TurnItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ThreadScreenLogicTest {
  @Test
  fun `no runs is just the items`() {
    val rows: List<ThreadRow> = threadRows(emptyList(), listOf(notice("a"), notice("b")))
    assertEquals(listOf("item:a", "item:b"), rows.map { it.key })
  }

  @Test
  fun `items land under the last run requested before them`() {
    val runs: List<Run> = listOf(run("r2", 2, "10:05"), run("r1", 1, "10:00"))
    val items: List<TurnItem> = listOf(
      notice("a", "10:00"),
      notice("b", "10:03"),
      notice("c", "10:05"),
      notice("d", "10:09"),
    )
    assertEquals(
      listOf("run:r1", "item:a", "item:b", "run:r2", "item:c", "item:d"),
      threadRows(runs, items).map { it.key },
    )
  }

  @Test
  fun `items before the first run and without a time stay put`() {
    val runs: List<Run> = listOf(run("r1", 1, "10:00"), run("r2", 2, "10:05"))
    val items: List<TurnItem> = listOf(
      notice("early", "09:59"),
      notice("late", "10:06"),
      notice("untimed", updatedAt = ""),
      notice("stale", "10:01"),
    )
    assertEquals(
      listOf("run:r1", "item:early", "run:r2", "item:late", "item:untimed", "item:stale"),
      threadRows(runs, items).map { it.key },
    )
  }

  @Test
  fun `a run with no items still gets a header`() {
    val runs: List<Run> = listOf(run("r1", 1, "10:00"), run("r2", 2, "10:05"))
    val keys: List<String> = threadRows(runs, listOf(notice("a", "10:01"))).map { it.key }
    assertEquals(listOf("run:r1", "item:a", "run:r2"), keys)
  }

  @Test
  fun `blank and repeated ids get positional keys`() {
    val items: List<TurnItem> = listOf(notice("a"), notice(""), notice("a"))
    assertEquals(listOf("item:a", "at:1", "at:2"), threadRows(emptyList(), items).map { it.key })
  }

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
    val change = TurnItem.FileChange(id = "f", updatedAt = "", fileName = "A.kt")
    assertEquals("A.kt", change.openPath())
    val moved: TurnItem.FileChange =
      change.copy(changes = listOf(FileChangeEntry(operation = "update", path = "src/A.kt")))
    assertEquals("src/A.kt", moved.openPath())
  }

  private fun at(time: String): String = "2026-10-19T$time:00Z"

  private fun run(id: String, ordinal: Int, requested: String): Run =
    Run(id = id, ordinal = ordinal, status = "completed", requestedAt = at(requested))

  private fun notice(id: String, time: String? = null, updatedAt: String? = null): TurnItem =
    TurnItem.SystemNotice(
      id = id,
      updatedAt = updatedAt ?: time?.let(::at) ?: "",
      message = id,
    )
}
