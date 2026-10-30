package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.pinned

// Dragging rows between the Sidebar's sections, one to one with the drag half of
// apps/web/src/components/Sidebar.logic.ts at the pinned tag. Upstream's Working shelf is a beta
// the plugin doesn't have, so its section, marker and `activeTimeOrdered` are left out.
// Snoozed rows can leave the shelf, but nothing drops into it: snoozing needs a wake time.

/** `SidebarSection`. */
enum class SidebarSection { Pinned, Active, Snoozed, Settled }

/** `resolveSidebarThreadSection`: a snooze outranks settled, which outranks a pin. */
fun resolveSidebarThreadSection(
  snoozed: Boolean,
  settled: Boolean,
  pinned: Boolean,
): SidebarSection = when {
  snoozed -> SidebarSection.Snoozed
  settled -> SidebarSection.Settled
  pinned -> SidebarSection.Pinned
  else -> SidebarSection.Active
}

/** `SidebarListMarker`: the list's non-row items, by upstream's id. */
enum class SidebarListMarker(val id: String) {
  PinnedHeader("pinned-header"),
  ActivePlaceholder("active-placeholder"),
  SettledPlaceholder("settled-placeholder"),
  PinnedDivider("pinned-divider"),
  SnoozedHeader("snoozed-header"),
  SettledHeader("settled-header"),
}

/** `SidebarListItem`: a row, keyed by thread id, or a marker between sections. */
sealed interface SidebarListItem {
  data class Row(val key: String, val section: SidebarSection) : SidebarListItem

  data class Marker(val marker: SidebarListMarker) : SidebarListItem
}

private const val SIDEBAR_MARKER_PREFIX: String = "sidebar-marker-"

/** `sidebarMarkerId`. */
fun sidebarMarkerId(marker: SidebarListMarker): String = SIDEBAR_MARKER_PREFIX + marker.id

/** `sidebarListItemId`. */
fun sidebarListItemId(item: SidebarListItem): String = when (item) {
  is SidebarListItem.Row -> item.key
  is SidebarListItem.Marker -> sidebarMarkerId(item.marker)
}

/**
 * The list the drag works on, `sidebarListItemsFresh` (Sidebar.tsx): the pinned header, pinned
 * rows, the divider, the active placeholder and rows, the snoozed shelf when it has rows, then
 * the settled header, its placeholder and the settled rows on show. [snoozedShown] and
 * [settledShown] are the rows the shelves render; a closed shelf renders none.
 */
fun sidebarListItems(
  sections: ThreadSections,
  snoozedShown: List<Thread>,
  settledShown: List<Thread>,
): List<SidebarListItem> {
  val (pinned: List<Thread>, active: List<Thread>) = sections.inbox.partition { it.pinned }
  if (sections.inbox.isEmpty() && sections.snoozed.isEmpty() && sections.settled.isEmpty()) {
    return emptyList()
  }
  fun rows(threads: List<Thread>, section: SidebarSection): List<SidebarListItem> =
    threads.map { SidebarListItem.Row(it.id, section) }
  return buildList {
    add(SidebarListItem.Marker(SidebarListMarker.PinnedHeader))
    addAll(rows(pinned, SidebarSection.Pinned))
    add(SidebarListItem.Marker(SidebarListMarker.PinnedDivider))
    add(SidebarListItem.Marker(SidebarListMarker.ActivePlaceholder))
    addAll(rows(active, SidebarSection.Active))
    if (sections.snoozed.isNotEmpty()) {
      add(SidebarListItem.Marker(SidebarListMarker.SnoozedHeader))
      addAll(rows(snoozedShown, SidebarSection.Snoozed))
    }
    add(SidebarListItem.Marker(SidebarListMarker.SettledHeader))
    add(SidebarListItem.Marker(SidebarListMarker.SettledPlaceholder))
    addAll(rows(settledShown, SidebarSection.Settled))
  }
}

private fun sectionAtSidebarSlot(items: List<SidebarListItem>, index: Int): SidebarSection {
  var section: SidebarSection = SidebarSection.Pinned
  for (item in items.take(index)) {
    if (item !is SidebarListItem.Marker) continue
    when (item.marker) {
      SidebarListMarker.PinnedDivider -> section = SidebarSection.Active
      SidebarListMarker.SnoozedHeader -> section = SidebarSection.Snoozed
      SidebarListMarker.SettledHeader -> section = SidebarSection.Settled
      else -> Unit
    }
  }
  return section
}

/** `SidebarDropTarget`: where a drop lands, and the inbox orders it makes. */
data class SidebarDropTarget(
  val section: SidebarSection, // pinned, active or settled
  val pinnedOrder: List<String>,
  val activeOrder: List<String>,
)

/**
 * `resolveSidebarDropTarget`: the row [activeKey] moved to [overId]'s slot, as dnd-kit's
 * `arrayMove` does. Null when either isn't in [items], or the slot is on the snoozed shelf.
 */
fun resolveSidebarDropTarget(
  items: List<SidebarListItem>,
  activeKey: String,
  overId: String,
): SidebarDropTarget? {
  val activeIndex: Int = items.indexOfFirst { sidebarListItemId(it) == activeKey }
  val overIndex: Int = items.indexOfFirst { sidebarListItemId(it) == overId }
  if (activeIndex == -1 || overIndex == -1 || items[activeIndex] !is SidebarListItem.Row) {
    return null
  }
  val moved: MutableList<SidebarListItem> = items.toMutableList()
  val active: SidebarListItem = moved.removeAt(activeIndex)
  moved.add(overIndex, active)
  val section: SidebarSection = sectionAtSidebarSlot(moved, overIndex)
  if (section == SidebarSection.Snoozed) return null
  val pinnedOrder: MutableList<String> = mutableListOf()
  val activeOrder: MutableList<String> = mutableListOf()
  var currentSection: SidebarSection = SidebarSection.Pinned
  for (item in moved) {
    when (item) {
      is SidebarListItem.Marker -> when (item.marker) {
        SidebarListMarker.PinnedDivider -> currentSection = SidebarSection.Active
        SidebarListMarker.SnoozedHeader, SidebarListMarker.SettledHeader -> break
        else -> Unit
      }

      is SidebarListItem.Row ->
        if (currentSection == SidebarSection.Pinned) pinnedOrder += item.key
        else activeOrder += item.key
    }
  }
  return SidebarDropTarget(section, pinnedOrder, activeOrder)
}

/** `SidebarThreadDropPlan`: what a drop sends. */
sealed interface SidebarThreadDropPlan {
  data object None : SidebarThreadDropPlan

  data class ReorderPinned(
    val order: List<String>,
    val assignments: List<OrderKeyAssignment>,
  ) : SidebarThreadDropPlan

  data class Pin(
    val order: List<String>,
    val orderKey: String?,
    val extraAssignments: List<OrderKeyAssignment>,
  ) : SidebarThreadDropPlan

  data class MoveActive(
    val order: List<String>,
    val assignments: List<OrderKeyAssignment>,
    val unpin: Boolean,
    val unsettle: Boolean,
    val unsnooze: Boolean,
  ) : SidebarThreadDropPlan

  data object Settle : SidebarThreadDropPlan
}

/** `SidebarDropVerb`: the badge a dragged row wears over another section. */
enum class SidebarDropVerb(val label: String, val glyph: String) {
  Pin("Pin", "pin"),
  Unpin("Unpin", "pin-off"),
  Settle("Settle", "circle-check"),
  Unsettle("Un-settle", "undo-2"),
  Wake("Wake", "alarm-clock-off"),
}

/** `resolveSidebarDropVerb`. */
fun resolveSidebarDropVerb(from: SidebarSection, to: SidebarSection?): SidebarDropVerb? = when {
  to == null || to == from || to == SidebarSection.Snoozed -> null
  to == SidebarSection.Pinned -> SidebarDropVerb.Pin
  to == SidebarSection.Settled -> SidebarDropVerb.Settle
  from == SidebarSection.Pinned -> SidebarDropVerb.Unpin
  from == SidebarSection.Settled -> SidebarDropVerb.Unsettle
  else -> SidebarDropVerb.Wake
}

/**
 * `planSidebarThreadDrop`. [reorderableKeys] and [activeReorderableKeys] are the rows whose
 * server stores pinned and active orders; null means every row's does.
 */
fun planSidebarThreadDrop(
  activeKey: String,
  activeSection: SidebarSection,
  target: SidebarDropTarget,
  pinnedOrder: List<String>,
  pinnedKeysById: Map<String, String?>,
  activeOrder: List<String>,
  activeKeysById: Map<String, String?>,
  activePinned: Boolean = activeSection == SidebarSection.Pinned,
  activeSettled: Boolean = activeSection == SidebarSection.Settled,
  supportsSettlement: Boolean? = null,
  reorderableKeys: Set<String>? = null,
  activeReorderableKeys: Set<String>? = null,
): SidebarThreadDropPlan {
  if (supportsSettlement == false && (target.section == SidebarSection.Settled || activeSettled)) {
    return SidebarThreadDropPlan.None
  }
  fun arrange(
    order: List<String>,
    keysById: Map<String, String?>,
    writable: Set<String>?,
  ): List<OrderKeyAssignment>? {
    if (writable == null) return planPinnedReorder(order, keysById, activeKey)
    if (activeKey !in writable) return null
    val assignments: List<OrderKeyAssignment> = planPinnedReorder(
      orderedIds = order.filter { it in writable || keysById[it] != null },
      keysById = keysById,
      movedId = activeKey,
    )
    return if (assignments.all { it.id in writable }) assignments else null
  }
  return when (target.section) {
    SidebarSection.Active -> {
      val order: List<String> = target.activeOrder
      if (activeSection == SidebarSection.Active && order == activeOrder) {
        return SidebarThreadDropPlan.None
      }
      val assignments: List<OrderKeyAssignment> =
        arrange(order, activeKeysById, activeReorderableKeys) ?: return SidebarThreadDropPlan.None
      SidebarThreadDropPlan.MoveActive(
        order = order,
        assignments = assignments,
        unpin = activePinned,
        unsettle = activeSettled,
        unsnooze = activeSection == SidebarSection.Snoozed,
      )
    }

    SidebarSection.Settled ->
      if (activeSection == SidebarSection.Settled) {
        SidebarThreadDropPlan.None
      } else {
        SidebarThreadDropPlan.Settle
      }

    SidebarSection.Pinned -> {
      val order: List<String> = target.pinnedOrder
      if (activeSection == SidebarSection.Pinned && order == pinnedOrder) {
        return SidebarThreadDropPlan.None
      }
      val assignments: List<OrderKeyAssignment> =
        arrange(order, pinnedKeysById, reorderableKeys) ?: return SidebarThreadDropPlan.None
      when {
        activeSection != SidebarSection.Pinned -> SidebarThreadDropPlan.Pin(
          order = order,
          orderKey = assignments.find { it.id == activeKey }?.orderKey,
          extraAssignments =
            if (activePinned) assignments else assignments.filter { it.id != activeKey },
        )

        assignments.isEmpty() -> SidebarThreadDropPlan.None
        else -> SidebarThreadDropPlan.ReorderPinned(order, assignments)
      }
    }

    SidebarSection.Snoozed -> SidebarThreadDropPlan.None
  }
}

/**
 * Where the insertion line goes when the row at [activeKey] is over [overId]: under it when the
 * row comes from above (`arrayMove` puts it after), over it when from below. Over the pinned
 * header it always heads the pinned rows. Null when either isn't in [items].
 */
fun dropsBelow(items: List<SidebarListItem>, activeKey: String, overId: String): Boolean? {
  val activeIndex: Int = items.indexOfFirst { sidebarListItemId(it) == activeKey }
  val overIndex: Int = items.indexOfFirst { sidebarListItemId(it) == overId }
  if (activeIndex == -1 || overIndex == -1) return null
  if (overId == sidebarMarkerId(SidebarListMarker.PinnedHeader)) return true
  return activeIndex < overIndex
}

// Dragging a row out of the list, the context drag of Sidebar.tsx's pointer sensor options

/**
 * `pointerOutsideThreadList`: a drag past the list's left or right edge stops arranging rows and
 * carries the thread out as context. Only the sides count, so dragging past the top or bottom
 * still reorders. Asked on every move, so coming back in resumes the reorder.
 */
fun pointerOutsideThreadList(x: Float, left: Float, right: Float): Boolean = x < left || x > right

/**
 * `contextDragThreads`: the dragged row, or the whole multi-selection when the row is in it, in
 * the order the rows were picked.
 */
fun contextDragThreads(key: String?, selected: Set<String>): List<String> = when (key) {
  null -> emptyList()
  in selected -> selected.toList()
  else -> listOf(key)
}

/** The ghost's label, `moveThreadContextDrag`'s: the dragged row's trimmed title, or "Thread". */
fun threadContextDragTitle(title: String?): String = title?.trim().orEmpty().ifEmpty { "Thread" }
