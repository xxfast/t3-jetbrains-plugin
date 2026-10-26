package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.ui.SidebarListMarker.ActivePlaceholder
import io.github.xxfast.t3code.plugin.ui.SidebarListMarker.PinnedDivider
import io.github.xxfast.t3code.plugin.ui.SidebarListMarker.PinnedHeader
import io.github.xxfast.t3code.plugin.ui.SidebarListMarker.SettledHeader
import io.github.xxfast.t3code.plugin.ui.SidebarListMarker.SettledPlaceholder
import io.github.xxfast.t3code.plugin.ui.SidebarListMarker.SnoozedHeader
import io.github.xxfast.t3code.plugin.ui.SidebarSection.Active
import io.github.xxfast.t3code.plugin.ui.SidebarSection.Pinned
import io.github.xxfast.t3code.plugin.ui.SidebarSection.Settled
import io.github.xxfast.t3code.plugin.ui.SidebarSection.Snoozed
import io.github.xxfast.t3code.protocol.Thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// The drag cases of apps/web/src/components/Sidebar.logic.test.ts, without the Working shelf
class SidebarDragLogicTest {
  private fun marker(marker: SidebarListMarker): SidebarListItem = SidebarListItem.Marker(marker)

  private fun row(key: String, section: SidebarSection): SidebarListItem =
    SidebarListItem.Row(key, section)

  private val items: List<SidebarListItem> = listOf(
    marker(PinnedHeader),
    row("p1", Pinned),
    marker(PinnedDivider),
    marker(ActivePlaceholder),
    row("a1", Active),
    row("a2", Active),
    marker(SnoozedHeader),
    row("z1", Snoozed),
    marker(SettledHeader),
    marker(SettledPlaceholder),
    row("s1", Settled),
  )

  @Test
  fun `drops land where arrayMove puts the row`() {
    assertEquals(
      SidebarDropTarget(Active, pinnedOrder = emptyList(), activeOrder = listOf("a1", "a2", "p1")),
      resolveSidebarDropTarget(items, "p1", "a2"),
    )
    assertEquals(
      SidebarDropTarget(Pinned, pinnedOrder = listOf("a2", "p1"), activeOrder = listOf("a1")),
      resolveSidebarDropTarget(items, "a2", "p1"),
    )
    // Over the pinned header, it heads the pins
    val pinnedHeader: String = sidebarMarkerId(PinnedHeader)
    val headed: SidebarDropTarget? = resolveSidebarDropTarget(items, "a1", pinnedHeader)
    assertEquals(listOf("a1", "p1"), headed?.pinnedOrder)
    // From above, the settled header is the top of the shelf
    val settledHeader: String = sidebarMarkerId(SettledHeader)
    assertEquals(Settled, resolveSidebarDropTarget(items, "a2", settledHeader)?.section)
    // From below, the snoozed header is the bottom of the inbox
    val snoozedHeader: String = sidebarMarkerId(SnoozedHeader)
    assertEquals(Active, resolveSidebarDropTarget(items, "s1", snoozedHeader)?.section)
  }

  @Test
  fun `never drops into the snoozed shelf`() {
    assertNull(resolveSidebarDropTarget(items, "a1", "z1"))
    assertNull(resolveSidebarDropTarget(items, "a1", "sidebar-marker-snoozed-header"))
    assertNull(resolveSidebarDropVerb(Active, Snoozed))
    assertNull(resolveSidebarDropTarget(items, "sidebar-marker-pinned-header", "a1"))
  }

  @Test
  fun `drop verbs`() {
    assertEquals(SidebarDropVerb.Pin, resolveSidebarDropVerb(Active, Pinned))
    assertEquals(SidebarDropVerb.Settle, resolveSidebarDropVerb(Pinned, Settled))
    assertEquals(SidebarDropVerb.Unpin, resolveSidebarDropVerb(Pinned, Active))
    assertEquals(SidebarDropVerb.Unsettle, resolveSidebarDropVerb(Settled, Active))
    assertEquals(SidebarDropVerb.Wake, resolveSidebarDropVerb(Snoozed, Active))
    assertNull(resolveSidebarDropVerb(Active, Active))
    assertNull(resolveSidebarDropVerb(Active, null))
  }

  @Test
  fun `arranges the rows it can write when another row's server cannot store an order`() {
    val plan: SidebarThreadDropPlan = planSidebarThreadDrop(
      activeKey = "a2",
      activeSection = Active,
      target = SidebarDropTarget(Active, emptyList(), listOf("offline", "a2", "a1")),
      pinnedOrder = emptyList(),
      pinnedKeysById = emptyMap(),
      activeOrder = listOf("offline", "a1", "a2"),
      activeKeysById = mapOf("offline" to null, "a1" to null, "a2" to null),
      activeReorderableKeys = setOf("a1", "a2"),
    )
    assertIs<SidebarThreadDropPlan.MoveActive>(plan)
    assertEquals(listOf("a2", "a1"), plan.assignments.map { it.id })
    val (a2: String, a1: String) = plan.assignments.map { it.orderKey }
    assertTrue(a2 < a1)

    val above: SidebarThreadDropPlan = planSidebarThreadDrop(
      activeKey = "a2",
      activeSection = Active,
      target = SidebarDropTarget(Active, emptyList(), listOf("a2", "offline")),
      pinnedOrder = emptyList(),
      pinnedKeysById = emptyMap(),
      activeOrder = listOf("offline", "a2"),
      activeKeysById = mapOf("offline" to "m", "a2" to "t"),
      activeReorderableKeys = setOf("a2"),
    )
    assertIs<SidebarThreadDropPlan.MoveActive>(above)
    assertTrue(above.assignments[0].orderKey < "m")
  }

  @Test
  fun `a drop that changes nothing plans nothing`() {
    val target: SidebarDropTarget = assertNotNull(resolveSidebarDropTarget(items, "a1", "a1"))
    assertEquals(SidebarThreadDropPlan.None, plan("a1", Active, target))
    val settled = SidebarDropTarget(Settled, listOf("p1"), listOf("a1", "a2"))
    assertEquals(SidebarThreadDropPlan.None, plan("s1", Settled, settled))
  }

  @Test
  fun `reordering pins writes the moved row's key`() {
    val target: SidebarDropTarget = SidebarDropTarget(Pinned, listOf("p2", "p1"), listOf("a1"))
    val plan: SidebarThreadDropPlan = planSidebarThreadDrop(
      activeKey = "p2",
      activeSection = Pinned,
      target = target,
      pinnedOrder = listOf("p1", "p2"),
      pinnedKeysById = mapOf("p1" to "m", "p2" to "t"),
      activeOrder = listOf("a1"),
      activeKeysById = emptyMap(),
    )
    assertIs<SidebarThreadDropPlan.ReorderPinned>(plan)
    assertEquals("p2", plan.assignments.single().id)
    assertTrue(plan.assignments.single().orderKey < "m")
  }

  @Test
  fun `pinning by drop carries the key, and an already pinned snoozed row rewrites its own`() {
    val target: SidebarDropTarget = SidebarDropTarget(Pinned, listOf("p1", "a1"), listOf("a2"))
    val pin: SidebarThreadDropPlan = plan("a1", Active, target)
    assertIs<SidebarThreadDropPlan.Pin>(pin)
    assertTrue(assertNotNull(pin.orderKey) > "m")
    assertEquals(emptyList(), pin.extraAssignments)

    val snoozedPin: SidebarThreadDropPlan = planSidebarThreadDrop(
      activeKey = "z1",
      activeSection = Snoozed,
      activePinned = true,
      target = SidebarDropTarget(Pinned, listOf("z1", "p1"), listOf("a1")),
      pinnedOrder = listOf("p1"),
      pinnedKeysById = mapOf("p1" to "m", "z1" to "t"),
      activeOrder = listOf("a1"),
      activeKeysById = emptyMap(),
    )
    assertIs<SidebarThreadDropPlan.Pin>(snoozedPin)
    assertEquals(listOf("z1"), snoozedPin.extraAssignments.map { it.id })
  }

  @Test
  fun `leaving a section unpins, un-settles or wakes on the way into active`() {
    val unpin: SidebarThreadDropPlan =
      plan("p1", Pinned, SidebarDropTarget(Active, emptyList(), listOf("p1", "a1", "a2")))
    assertIs<SidebarThreadDropPlan.MoveActive>(unpin)
    assertTrue(unpin.unpin)
    val unsettle: SidebarThreadDropPlan =
      plan("s1", Settled, SidebarDropTarget(Active, listOf("p1"), listOf("a1", "s1", "a2")))
    assertIs<SidebarThreadDropPlan.MoveActive>(unsettle)
    assertTrue(unsettle.unsettle)
    val wake: SidebarThreadDropPlan =
      plan("z1", Snoozed, SidebarDropTarget(Active, listOf("p1"), listOf("z1", "a1", "a2")))
    assertIs<SidebarThreadDropPlan.MoveActive>(wake)
    assertTrue(wake.unsnooze)
    assertEquals(
      SidebarThreadDropPlan.Settle,
      plan("a1", Active, SidebarDropTarget(Settled, listOf("p1"), listOf("a2"))),
    )
  }

  @Test
  fun `no settling or un-settling where the server doesn't settle`() {
    val settle = SidebarDropTarget(Settled, listOf("p1"), listOf("a2"))
    assertEquals(SidebarThreadDropPlan.None, plan("a1", Active, settle, settlement = false))
    val unsettle = SidebarDropTarget(Active, listOf("p1"), listOf("s1", "a1", "a2"))
    assertEquals(SidebarThreadDropPlan.None, plan("s1", Settled, unsettle, settlement = false))
  }

  @Test
  fun `without active orders on the server nothing drops into active`() {
    val target = SidebarDropTarget(Active, emptyList(), listOf("a2", "a1"))
    val plan: SidebarThreadDropPlan = planSidebarThreadDrop(
      activeKey = "a2",
      activeSection = Active,
      target = target,
      pinnedOrder = emptyList(),
      pinnedKeysById = emptyMap(),
      activeOrder = listOf("a1", "a2"),
      activeKeysById = mapOf("a1" to "f", "a2" to "t"),
      activeReorderableKeys = emptySet(),
    )
    assertEquals(SidebarThreadDropPlan.None, plan)
  }

  @Test
  fun `the insertion line sits on the side the row lands`() {
    assertEquals(true, dropsBelow(items, "p1", "a1"))
    assertEquals(false, dropsBelow(items, "a2", "a1"))
    assertEquals(true, dropsBelow(items, "a1", "sidebar-marker-pinned-header"))
    assertEquals(false, dropsBelow(items, "a1", "sidebar-marker-pinned-divider"))
    assertNull(dropsBelow(items, "gone", "a1"))
  }

  @Test
  fun `the list has upstream's markers around the plugin's sections`() {
    val pinned: Thread = BASE_THREAD.copy(id = "p", pinnedAt = AT)
    val active: Thread = BASE_THREAD.copy(id = "a")
    val snoozed: Thread = BASE_THREAD.copy(id = "z")
    val settled: Thread = BASE_THREAD.copy(id = "s")
    val sections = ThreadSections(
      inbox = listOf(pinned, active),
      settled = listOf(settled),
      snoozed = listOf(snoozed),
    )
    assertEquals(
      listOf(
        marker(PinnedHeader),
        row("p", Pinned),
        marker(PinnedDivider),
        marker(ActivePlaceholder),
        row("a", Active),
        marker(SnoozedHeader),
        marker(SettledHeader),
        marker(SettledPlaceholder),
        row("s", Settled),
      ),
      sidebarListItems(sections, snoozedShown = emptyList(), settledShown = listOf(settled)),
    )
    val none = ThreadSections(emptyList(), emptyList())
    assertEquals(emptyList(), sidebarListItems(none, emptyList(), emptyList()))
  }

  private fun plan(
    key: String,
    from: SidebarSection,
    target: SidebarDropTarget,
    settlement: Boolean = true,
  ): SidebarThreadDropPlan = planSidebarThreadDrop(
    activeKey = key,
    activeSection = from,
    target = target,
    pinnedOrder = listOf("p1"),
    pinnedKeysById = mapOf("p1" to "m"),
    activeOrder = listOf("a1", "a2"),
    activeKeysById = mapOf("a1" to "f", "a2" to "t"),
    supportsSettlement = settlement,
  )

  @Test
  fun `only the list's sides make a context drag`() {
    assertTrue(pointerOutsideThreadList(x = -1f, left = 0f, right = 300f))
    assertTrue(pointerOutsideThreadList(x = 301f, left = 0f, right = 300f))
    assertEquals(false, pointerOutsideThreadList(x = 0f, left = 0f, right = 300f))
    assertEquals(false, pointerOutsideThreadList(x = 300f, left = 0f, right = 300f))
  }

  @Test
  fun `a context drag carries the selection only when the row is in it`() {
    val selected: Set<String> = linkedSetOf("b", "a")
    assertEquals(listOf("b", "a"), contextDragThreads("a", selected))
    assertEquals(listOf("c"), contextDragThreads("c", selected))
    assertEquals(emptyList(), contextDragThreads(null, selected))
  }

  @Test
  fun `the ghost falls back to Thread`() {
    assertEquals("Fix login", threadContextDragTitle("  Fix login "))
    assertEquals("Thread", threadContextDragTitle("   "))
    assertEquals("Thread", threadContextDragTitle(null))
  }
}

private const val AT: String = "2026-10-27T00:00:00Z"
