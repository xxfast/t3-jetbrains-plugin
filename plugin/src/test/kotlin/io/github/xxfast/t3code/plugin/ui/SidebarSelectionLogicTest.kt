package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// The first five port apps/web/src/threadSelectionStore.test.ts at the pinned tag
class SidebarSelectionLogicTest {
  @Test
  fun `bulk delete clears missing rows and completed deletions, keeping a failed one`() {
    val selection: ThreadSelectionState = EMPTY_SELECTION
      .toggleThread(A).toggleThread(B).toggleThread(C)
    val selected: List<String> = selection.selectedThreadKeys.toList()
    val existing: Set<String> = setOf(A, B)
    val after: ThreadSelectionState = selection.removeFromSelection(
      getThreadKeysToDeselectAfterDelete(selected, setOf(A)) { it in existing },
    )
    assertEquals(listOf(B), after.selectedThreadKeys.toList())
    assertNull(after.anchorThreadKey)
  }

  @Test
  fun `bulk delete keeps unprocessed rows and ones picked while it was pending`() {
    val before: ThreadSelectionState = EMPTY_SELECTION
      .toggleThread(A).toggleThread(B).toggleThread(C)
    val selected: List<String> = before.selectedThreadKeys.toList()
    val after: ThreadSelectionState = before.toggleThread(D)
      .removeFromSelection(getThreadKeysToDeselectAfterDelete(selected, setOf(A)) { true })
    assertEquals(listOf(B, C, D), after.selectedThreadKeys.toList())
    assertEquals(D, after.anchorThreadKey)
  }

  @Test
  fun `a plain click's anchor starts a range`() {
    val state: ThreadSelectionState = EMPTY_SELECTION.setAnchor(B).rangeSelectTo(D, ORDERED)
    assertEquals(setOf(B, C, D), state.selectedThreadKeys)
  }

  @Test
  fun `the anchor holds across ranges`() {
    val state: ThreadSelectionState = EMPTY_SELECTION
      .toggleThread(B)
      .rangeSelectTo(D, ORDERED)
      .rangeSelectTo(E, ORDERED)
    assertEquals(B, state.anchorThreadKey)
    assertEquals(setOf(B, C, D, E), state.selectedThreadKeys)
  }

  @Test
  fun `an anchor the order doesn't have adds the row alone and anchors there`() {
    val state: ThreadSelectionState =
      EMPTY_SELECTION.toggleThread(A).rangeSelectTo(C, listOf(B, C, D))
    assertTrue(C in state.selectedThreadKeys)
    assertEquals(C, state.anchorThreadKey)
  }

  @Test
  fun `a range keeps what was picked outside it`() {
    val state: ThreadSelectionState =
      EMPTY_SELECTION.toggleThread(A).toggleThread(B).rangeSelectTo(D, ORDERED)
    assertEquals(setOf(A, B, C, D), state.selectedThreadKeys)
  }

  @Test
  fun `a range runs upwards too`() {
    val state: ThreadSelectionState = EMPTY_SELECTION.setAnchor(D).rangeSelectTo(B, ORDERED)
    assertEquals(setOf(B, C, D), state.selectedThreadKeys)
    assertEquals(D, state.anchorThreadKey)
  }

  @Test
  fun `a range without an anchor picks the row and anchors it`() {
    val state: ThreadSelectionState = EMPTY_SELECTION.rangeSelectTo(C, ORDERED)
    assertEquals(setOf(C), state.selectedThreadKeys)
    assertEquals(C, state.anchorThreadKey)
  }

  @Test
  fun `toggling out keeps the anchor where it was`() {
    val state: ThreadSelectionState = EMPTY_SELECTION.toggleThread(A).toggleThread(B)
    val out: ThreadSelectionState = state.toggleThread(B)
    assertEquals(setOf(A), out.selectedThreadKeys)
    assertEquals(B, out.anchorThreadKey)
    assertFalse(out.toggleThread(A).hasSelection)
  }

  @Test
  fun `clearing drops the anchor too, and removing nothing changes nothing`() {
    val state: ThreadSelectionState = EMPTY_SELECTION.toggleThread(A)
    assertEquals(EMPTY_SELECTION, state.clearSelection())
    assertEquals(state, state.removeFromSelection(listOf(B)))
  }

  @Test
  fun `a click toggles with the platform's modifier, ranges with Shift, else opens`() {
    assertEquals(SidebarRowClick.Toggle, sidebarRowClick(isMac = true, true, false, false))
    assertEquals(SidebarRowClick.Open, sidebarRowClick(isMac = true, false, true, false))
    assertEquals(SidebarRowClick.Toggle, sidebarRowClick(isMac = false, false, true, false))
    assertEquals(SidebarRowClick.Open, sidebarRowClick(isMac = false, true, false, false))
    assertEquals(SidebarRowClick.Range, sidebarRowClick(isMac = false, false, false, true))
    assertEquals(SidebarRowClick.Toggle, sidebarRowClick(isMac = false, false, true, true))
    assertEquals(SidebarRowClick.Open, sidebarRowClick(isMac = false, false, false, false))
  }

  @Test
  fun `regenerate titles counts the rows it can, and greys when they're all at it`() {
    assertNull(buildBulkTitleRegenerationContextMenuItem(supportedCount = 0, actionableCount = 0))
    assertEquals(
      BulkMenuItem("Regenerating… (2)", disabled = true),
      buildBulkTitleRegenerationContextMenuItem(supportedCount = 2, actionableCount = 0),
    )
    assertEquals(
      BulkMenuItem("Regenerate titles (1)"),
      buildBulkTitleRegenerationContextMenuItem(supportedCount = 2, actionableCount = 1),
    )
  }

  @Test
  fun `unpin only with a pinned row`() {
    assertNull(buildBulkUnpinContextMenuItem(0))
    assertEquals(BulkMenuItem("Unpin (2)"), buildBulkUnpinContextMenuItem(2))
  }
}

private const val A: String = "thread-a"
private const val B: String = "thread-b"
private const val C: String = "thread-c"
private const val D: String = "thread-d"
private const val E: String = "thread-e"
private val ORDERED: List<String> = listOf(A, B, C, D, E)
