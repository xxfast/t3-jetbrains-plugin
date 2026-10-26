package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Ported from packages/client-runtime/src/state/threadSort.test.ts and the `pinOrderKeyBetween`
// cases of apps/web/src/components/Sidebar.logic.test.ts at the pinned tag.
class ThreadSortLogicTest {
  @Test
  fun `stays strictly ordered under repeated top insertion`() {
    var head: String? = null
    val keys: MutableList<String> = mutableListOf()
    repeat(100) {
      val key: String = assertNotNull(pinOrderKeyBetween(null, head))
      head?.let { assertTrue(key < it) }
      keys += key
      head = key
    }
    assertEquals(100, keys.toSet().size)
  }

  @Test
  fun `stays strictly ordered under repeated middle insertion`() {
    var low: String = assertNotNull(pinOrderKeyBetween(null, null))
    var high: String = assertNotNull(pinOrderKeyBetween(low, null))
    for (i in 0 until 100) {
      val key: String = assertNotNull(pinOrderKeyBetween(low, high))
      assertTrue(low < key && key < high)
      if (i % 2 == 0) low = key else high = key
    }
  }

  @Test
  fun `returns null for corrupt or out-of-order bounds instead of throwing`() {
    assertNull(pinOrderKeyBetween("z", "a"))
    assertNull(pinOrderKeyBetween("A!", null))
    assertNull(pinOrderKeyBetween(null, "ma"))
    assertNull(pinOrderKeyBetween("m", "m"))
  }

  @Test
  fun `keeps hidden slots available when inserting between visible neighbors`() {
    val midpoint: String = assertNotNull(pinOrderKeyBetween("f", "t"))
    val keysById: Map<String, String?> =
      mapOf("a" to "f", "b" to "t", "moved" to "z", "snoozed" to midpoint)
    val assignments: List<OrderKeyAssignment> =
      planPinnedReorder(listOf("a", "moved", "b"), keysById, "moved")
    assertEquals(1, assignments.size)
    val key: String = assignments[0].orderKey
    assertTrue(key > "f" && key < "t")
    assertNotEquals(midpoint, key)
    assertEquals("moved", assignments[0].id)
  }

  @Test
  fun `materializes keyless rows without overwriting hidden slots`() {
    val reserved: List<String> = generateSpreadPinOrderKeys(6)
    val keysById: Map<String, String?> = mapOf("a" to null, "b" to null, "c" to null) +
      reserved.mapIndexed { i, key -> "hidden-$i" to key }
    val assignments: List<OrderKeyAssignment> =
      planPinnedReorder(listOf("c", "a", "b"), keysById, "c")
    assertEquals(listOf("c", "a", "b"), assignments.map { it.id })
    val keys: List<String> = assignments.map { it.orderKey }
    assertEquals(keys.sorted(), keys)
    assertEquals(3, keys.toSet().size)
    assertTrue(keys.none { it in reserved })
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, 650, 675, 676, 1_001, 2_000])
  fun `leaves unique, insertable keys`(count: Int) {
    val keys: List<String> = generateSpreadPinOrderKeys(count)
    assertEquals(count, keys.size)
    assertEquals(count, keys.toSet().size)
    assertEquals(keys.sorted(), keys)
    for (index in keys.indices) {
      val before: String? = keys.getOrNull(index - 1)
      val after: String = keys[index]
      assertTrue(Regex("^[a-z]*[b-z]$").matches(after), after)
      val between: String = assertNotNull(pinOrderKeyBetween(before, after))
      assertTrue(between < after)
      if (before != null) assertTrue(between > before)
    }
  }

  @Test
  fun `moves a keyless thread into the arranged run with one write`() {
    val assignments: List<OrderKeyAssignment> = assertNotNull(
      planPinnedMove(
        orderedIds = listOf("new", "reopened", "first", "last"),
        keysById = mapOf("new" to null, "reopened" to null, "first" to "f", "last" to "t"),
        movedId = "reopened",
        direction = MoveDirection.Down,
      ),
    )
    assertEquals(1, assignments.size)
    assertEquals("reopened", assignments[0].id)
    assertTrue(assignments[0].orderKey > "f")
    assertTrue(assignments[0].orderKey < "t")
  }

  @Test
  fun `materializes a large active list without changing the requested order`() {
    val ids: List<String> = List(1_200) { it.toString() }
    val orderedIds: List<String> = ids.reversed()
    val assignments: List<OrderKeyAssignment> =
      planPinnedReorder(orderedIds, ids.associateWith { null }, orderedIds[0])
    val keys: Map<String, String> = assignments.associate { it.id to it.orderKey }
    assertEquals(orderedIds, ids.sortedBy { keys.getValue(it) })
  }

  @Test
  fun `a fresh pin heads every pinned row, hidden ones too`() {
    val threads = listOf(
      BASE_THREAD.copy(id = "a", pinnedAt = PINNED_AT, pinOrderKey = "m"),
      BASE_THREAD.copy(id = "snoozed", pinnedAt = PINNED_AT, pinOrderKey = "f"),
      BASE_THREAD.copy(id = "unpinned", pinOrderKey = "b"),
    )
    val key: String = assertNotNull(topOfPinnedRunOrderKey(threads))
    assertTrue(key < "f")
    assertEquals("n", topOfPinnedRunOrderKey(emptyList()))
  }
}

private const val PINNED_AT: String = "2026-10-27T00:00:00Z"
