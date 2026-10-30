package io.github.xxfast.t3code.plugin.ui

// The Sidebar's multi-select: apps/web/src/threadSelectionStore.ts as pure transitions, the
// bulk menu builders from apps/web/src/components/Sidebar.logic.ts, and `handleThreadClick`'s
// branches from Sidebar.tsx, at the pinned tag.

/**
 * `ThreadSelectionState`: the rows picked for a bulk action, in the order they were picked, and
 * the row a Shift range starts from. A plain click sets the anchor without selecting anything.
 */
data class ThreadSelectionState(
  val selectedThreadKeys: Set<String> = emptySet(),
  val anchorThreadKey: String? = null,
) {
  /** `hasSelection`. */
  val hasSelection: Boolean get() = selectedThreadKeys.isNotEmpty()

  /** `toggleThread`: in or out, and the anchor moves to it when it's in. */
  fun toggleThread(threadKey: String): ThreadSelectionState {
    val next: Set<String> = if (threadKey in selectedThreadKeys) {
      selectedThreadKeys - threadKey
    } else {
      selectedThreadKeys + threadKey
    }
    val anchor: String? = if (threadKey in next) threadKey else anchorThreadKey
    return ThreadSelectionState(next, anchor)
  }

  /**
   * `rangeSelectTo`: everything from the anchor to [threadKey] in [orderedThreadKeys], on top of
   * what's picked already. The anchor stays. Without an anchor, or one the order doesn't have,
   * it adds [threadKey] alone and anchors there.
   */
  fun rangeSelectTo(threadKey: String, orderedThreadKeys: List<String>): ThreadSelectionState {
    val anchor: String = anchorThreadKey
      ?: return ThreadSelectionState(selectedThreadKeys + threadKey, threadKey)
    val anchorIndex: Int = orderedThreadKeys.indexOf(anchor)
    val targetIndex: Int = orderedThreadKeys.indexOf(threadKey)
    if (anchorIndex == -1 || targetIndex == -1) {
      return ThreadSelectionState(selectedThreadKeys + threadKey, threadKey)
    }
    val start: Int = minOf(anchorIndex, targetIndex)
    val end: Int = maxOf(anchorIndex, targetIndex)
    val range: List<String> = orderedThreadKeys.subList(start, end + 1)
    return ThreadSelectionState(selectedThreadKeys + range, anchor)
  }

  /** `clearSelection`: nothing picked, no anchor. */
  fun clearSelection(): ThreadSelectionState = EMPTY_SELECTION

  /** `setAnchor`: where the next Shift range starts. */
  fun setAnchor(threadKey: String): ThreadSelectionState = copy(anchorThreadKey = threadKey)

  /** `removeFromSelection`: drops [threadKeys], and the anchor with them if it's one. */
  fun removeFromSelection(threadKeys: Collection<String>): ThreadSelectionState {
    val remove: Set<String> = threadKeys.toSet()
    if (selectedThreadKeys.none { it in remove }) return this
    val anchor: String? = anchorThreadKey?.takeUnless { it in remove }
    return ThreadSelectionState(selectedThreadKeys - remove, anchor)
  }
}

val EMPTY_SELECTION: ThreadSelectionState = ThreadSelectionState()

/**
 * `getThreadKeysToDeselectAfterDelete`: of what was [selectedThreadKeys] when a bulk delete
 * began, the ones it deleted and the ones that are gone anyway. A failed delete stays picked.
 */
fun getThreadKeysToDeselectAfterDelete(
  selectedThreadKeys: List<String>,
  deletedThreadKeys: Set<String>,
  hasThread: (String) -> Boolean,
): List<String> = selectedThreadKeys.filter { it in deletedThreadKeys || !hasThread(it) }

/** What a click on a row does, `handleThreadClick`. */
enum class SidebarRowClick { Open, Toggle, Range }

/**
 * `handleThreadClick`'s branches: Cmd on macOS, Ctrl elsewhere, toggles the row; Shift selects
 * a range; anything else opens it.
 */
fun sidebarRowClick(
  isMac: Boolean,
  meta: Boolean,
  ctrl: Boolean,
  shift: Boolean,
): SidebarRowClick = when {
  if (isMac) meta else ctrl -> SidebarRowClick.Toggle
  shift -> SidebarRowClick.Range
  else -> SidebarRowClick.Open
}

/** A bulk menu entry, upstream's `ContextMenuItem`: its label and whether it's greyed. */
data class BulkMenuItem(val label: String, val disabled: Boolean = false)

/**
 * `buildBulkTitleRegenerationContextMenuItem`: none when no row's server regenerates titles,
 * "Regenerating… (N)" greyed when every one of them is at it already.
 */
fun buildBulkTitleRegenerationContextMenuItem(
  supportedCount: Int,
  actionableCount: Int,
): BulkMenuItem? = when {
  supportedCount == 0 -> null
  actionableCount == 0 -> BulkMenuItem("Regenerating… ($supportedCount)", disabled = true)
  else -> BulkMenuItem("Regenerate titles ($actionableCount)")
}

/** `buildBulkUnpinContextMenuItem`: only with a pinned row in the selection. */
fun buildBulkUnpinContextMenuItem(pinnedCount: Int): BulkMenuItem? =
  if (pinnedCount == 0) null else BulkMenuItem("Unpin ($pinnedCount)")
