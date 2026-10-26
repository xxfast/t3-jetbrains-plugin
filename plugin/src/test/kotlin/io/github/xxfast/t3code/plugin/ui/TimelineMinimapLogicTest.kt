package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class TimelineMinimapLogicTest {
  @Test
  fun `a tick per user message, with its turn's final reply`() {
    val rows: List<TimelineRow> = listOf(
      message("u1", "user_message", "first"),
      message("a1", "assistant_message", "draft"),
      TimelineRow.TurnFold("r1", "Worked for 2s", expanded = false),
      message("a2", "assistant_message", "final  one\n"),
      message("u2", "user_message", "second"),
    )
    assertEquals(
      listOf(
        TimelineMinimapItem("u1", 0, "first", "final  one\n"),
        TimelineMinimapItem("u2", 4, "second", null),
      ),
      deriveTimelineMinimapItems(rows),
    )
  }

  @Test
  fun `the preview compacts both texts and reads the reply's markdown as text`() {
    val item = TimelineMinimapItem(
      id = "u",
      rowIndex = 0,
      userText = "  fix\n\n the   tests ",
      assistantText = "Done. **All** `20` pass.\n\n| Module | Status |\n| --- | --- |\n" +
        "| `:kstore` | ~~flaky~~ pass |\n\n> See [the issue](https://example.com).<br/>Thanks" +
        "<!-- hidden -->",
    )
    val preview: TimelineMinimapItem = resolveTimelineMinimapPreview(item)!!
    assertEquals("fix the tests", preview.userText)
    assertEquals(
      "Done. All 20 pass. Module Status :kstore flaky pass See the issue. Thanks",
      preview.assistantText,
    )
    assertNull(resolveTimelineMinimapPreview(item.copy(userText = " ", assistantText = null))!!
      .userText)
    assertNull(compactMinimapPreview("  \n "))
  }

  @Test
  fun `ticks spread down the rail and the pointer picks the nearest`() {
    assertEquals(0f, resolveTimelineMinimapTopPercent(0, 1))
    assertEquals(50f, resolveTimelineMinimapTopPercent(1, 3))
    assertEquals(100f, resolveTimelineMinimapTopPercent(5, 3))
    assertEquals(1, resolveTimelineMinimapIndexFromPointer(3, 0f, 100f, 49f))
    assertEquals(2, resolveTimelineMinimapIndexFromPointer(3, 0f, 100f, 300f))
    assertEquals(0, resolveTimelineMinimapIndexFromPointer(1, 0f, 100f, 300f))
    assertNull(resolveTimelineMinimapIndexFromPointer(0, 0f, 100f, 10f))
    assertNull(resolveTimelineMinimapIndexFromPointer(3, 0f, 0f, 10f))
  }

  @Test
  fun `the rail is 8dp a turn, capped by the viewport`() {
    assertEquals(1.dp, resolveTimelineMinimapHeightStyle(1, 1000.dp))
    assertEquals(32.dp, resolveTimelineMinimapHeightStyle(5, 1000.dp))
    assertEquals(312.dp, resolveTimelineMinimapHeightStyle(80, 600.dp))
    assertEquals(0.dp, resolveTimelineMinimapHeightStyle(80, 200.dp))
  }

  @Test
  fun `the visible range is the rows that overlap the viewport`() {
    val rows: List<LazyListItemInfo> = listOf(row(3, -60, 40), row(4, -20, 30), row(5, 10, 50))
    assertEquals(TimelineMinimapVisibleRange(4, 5), resolveTimelineMinimapVisibleRange(rows, 0, 40))
    assertEquals(
      TimelineMinimapVisibleRange(null, null),
      resolveTimelineMinimapVisibleRange(rows, 100, 200),
    )
    assertTrue(isTimelineMinimapRowVisible(4, 4, 5))
    assertFalse(isTimelineMinimapRowVisible(6, 4, 5))
    assertFalse(isTimelineMinimapRowVisible(4, null, null))
  }

  @Test
  fun `the current tick is the first in view, else the turn being read`() {
    val rowIndices: List<Int> = listOf(0, 5, 9)
    assertEquals(1, resolveTimelineMinimapCurrentIndex(4, 6, rowIndices))
    assertEquals(1, resolveTimelineMinimapCurrentIndex(6, 8, rowIndices))
    assertEquals(0, resolveTimelineMinimapCurrentIndex(1, 3, rowIndices))
    assertEquals(2, resolveTimelineMinimapCurrentIndex(10, 12, rowIndices))
    assertNull(resolveTimelineMinimapCurrentIndex(null, null, rowIndices))
  }

  @Test
  fun `the gutter decides whether the rail stays and how much of it takes the pointer`() {
    assertTrue(resolveTimelineMinimapHasPersistentGutter(1000f, 640f))
    assertFalse(resolveTimelineMinimapHasPersistentGutter(700f, 676f))
    assertEquals(40, resolveTimelineMinimapHitStripWidth(1000f, 640f))
    assertEquals(20, resolveTimelineMinimapHitStripWidth(700f, 636f))
    assertEquals(0, resolveTimelineMinimapHitStripWidth(500f, 476f))
    assertTrue(resolveTimelineMinimapNavigationInteractive(14))
    assertFalse(resolveTimelineMinimapNavigationInteractive(13))
    assertEquals(352.dp, resolveTimelineMinimapInteractiveWidth(20, expanded = true))
    assertEquals(20.dp, resolveTimelineMinimapInteractiveWidth(20, expanded = false))
  }

  private fun message(id: String, type: String, text: String): TimelineRow =
    TimelineRow.Message(TimelineEntry.Message(item(id, type, text)), showMeta = false)

  private fun item(id: String, type: String, text: String): TurnItem = T3Json.decodeFromString(
    TurnItem.serializer(),
    """
    {"type":"$type","id":"$id","threadId":"t","runId":"r1","ordinal":1,"status":"completed",
     "updatedAt":"2026-10-26T00:00:00.000Z","createdBy":"user","creationSource":"web",
     "messageId":"m-$id","inputIntent":"turn_start","attachments":[],"streaming":false,
     "text":${JsonPrimitive(text)}}
    """.trimIndent(),
  )

  private fun row(index: Int, offset: Int, size: Int): LazyListItemInfo =
    object : LazyListItemInfo {
      override val index: Int = index
      override val key: Any = index
      override val offset: Int = offset
      override val size: Int = size
    }
}
