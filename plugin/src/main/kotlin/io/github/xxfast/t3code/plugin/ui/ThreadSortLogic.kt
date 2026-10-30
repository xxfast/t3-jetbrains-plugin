package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import kotlin.math.floor

// Order keys, one to one with packages/client-runtime/src/state/threadSort.ts at the pinned tag.
// Pinned and active rows share the same fractional keys: lowercase digits read as a fraction in
// (0, 1), so a key always fits between two others.

private const val PIN_ORDER_DIGITS: String = "abcdefghijklmnopqrstuvwxyz"

/** One write of a reorder: [orderKey] for thread [id]. */
data class OrderKeyAssignment(val id: String, val orderKey: String)

/** JS's `Math.round`, half up. Kotlin's `round` is half even. */
private fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

private fun isValidPinOrderKey(key: String): Boolean {
  if (key.isEmpty()) return false
  if (key.any { it !in PIN_ORDER_DIGITS }) return false
  // A trailing minimum digit leaves no room for a key just before this one; generators never
  // produce it, so it's corrupt
  return key.last() != PIN_ORDER_DIGITS[0]
}

/** Midpoint of two digit strings as fractions in (0, 1). "" is the open bound. Needs a < b. */
private fun pinOrderMidpoint(a: String, b: String): String {
  require(b == "" || a < b) { "pinOrderMidpoint: bounds out of order" }
  if (b != "") {
    var n = 0
    while ((a.getOrNull(n) ?: PIN_ORDER_DIGITS[0]) == b.getOrNull(n)) n += 1
    if (n > 0) return b.take(n) + pinOrderMidpoint(a.drop(n), b.drop(n))
  }
  val digitA: Int = if (a == "") 0 else PIN_ORDER_DIGITS.indexOf(a[0])
  val digitB: Int = if (b == "") PIN_ORDER_DIGITS.length else PIN_ORDER_DIGITS.indexOf(b[0])
  if (digitB - digitA > 1) {
    return PIN_ORDER_DIGITS[jsRound((digitA + digitB) / 2.0).toInt()].toString()
  }
  if (b.length > 1) return b[0].toString()
  return PIN_ORDER_DIGITS[digitA] + pinOrderMidpoint(a.drop(1), "")
}

/**
 * A key strictly between two neighbours; null bounds are the top of the pinned block and the
 * bottom of the keyed run. Null instead of throwing when the keys are corrupt or out of order,
 * so the caller rewrites the section.
 */
fun pinOrderKeyBetween(before: String?, after: String?): String? {
  val a: String = before ?: ""
  val b: String = after ?: ""
  if (a != "" && !isValidPinOrderKey(a)) return null
  if (b != "" && !isValidPinOrderKey(b)) return null
  if (b != "" && a >= b) return null
  return pinOrderMidpoint(a, b)
}

/** [count] keys spread evenly, each leaving room on both sides. */
fun generateSpreadPinOrderKeys(count: Int): List<String> {
  var width = 2
  var space: Double = PIN_ORDER_DIGITS.length.toDouble() * PIN_ORDER_DIGITS.length
  while (space <= (count + 1) * 2.0) {
    width += 1
    space *= PIN_ORDER_DIGITS.length
  }
  val step: Double = space / (count + 1)
  return List(count) { index ->
    var value: Long = jsRound(step * (index + 1))
    if (value % PIN_ORDER_DIGITS.length == 0L) value += 1
    var key = ""
    repeat(width) {
      key = PIN_ORDER_DIGITS[(value % PIN_ORDER_DIGITS.length).toInt()] + key
      value /= PIN_ORDER_DIGITS.length
    }
    key
  }
}

/**
 * The writes that put [movedId] where it sits in [orderedIds]: one key between its neighbours
 * when they have keys, else the whole run spread again. [keysById] includes rows hidden from
 * [orderedIds], whose keys stay reserved; only [orderedIds] get writes.
 */
fun planPinnedReorder(
  orderedIds: List<String>,
  keysById: Map<String, String?>,
  movedId: String,
): List<OrderKeyAssignment> {
  val visibleIds: Set<String> = orderedIds.toSet()
  val reservedKeys: Set<String> = keysById
    .filter { (id, key) -> id !in visibleIds && key != null }
    .values.filterNotNull().toSet()
  val movedIndex: Int = orderedIds.indexOf(movedId)
  if (movedIndex == -1) return emptyList()
  val beforeId: String? = orderedIds.getOrNull(movedIndex - 1)
  val afterId: String? = orderedIds.getOrNull(movedIndex + 1)
  val beforeKey: String? = beforeId?.let { keysById[it] }
  val afterKey: String? = afterId?.let { keysById[it] }
  val beforeUsable: Boolean = beforeId == null || beforeKey != null
  val afterUsable: Boolean = afterId == null || afterKey != null
  if (beforeUsable && afterUsable) {
    var key: String? = pinOrderKeyBetween(beforeKey, afterKey)
    while (key != null && key in reservedKeys) key = pinOrderKeyBetween(key, afterKey)
    if (key != null) return listOf(OrderKeyAssignment(movedId, key))
  }
  val keys: List<String> = generateSpreadPinOrderKeys(orderedIds.size + reservedKeys.size)
    .filter { it !in reservedKeys }
    .take(orderedIds.size)
  return orderedIds.mapIndexedNotNull { index, id ->
    val key: String = keys[index]
    if (keysById[id] == key) null else OrderKeyAssignment(id, key)
  }
}

enum class MoveDirection { Up, Down }

/** One step of [movedId] up or down [orderedIds], as [planPinnedReorder]'s writes. */
fun planPinnedMove(
  orderedIds: List<String>,
  keysById: Map<String, String?>,
  movedId: String,
  direction: MoveDirection,
): List<OrderKeyAssignment>? {
  val from: Int = orderedIds.indexOf(movedId)
  if (from == -1) return null
  val to: Int = if (direction == MoveDirection.Up) from - 1 else from + 1
  if (to < 0 || to >= orderedIds.size) return null
  val newOrder: MutableList<String> = orderedIds.toMutableList()
  newOrder.removeAt(from)
  newOrder.add(to, movedId)
  return planPinnedReorder(newOrder, keysById, movedId)
}

/**
 * Where a fresh pin goes: before the smallest key across every pinned row, hidden ones included,
 * so it can't land under a hidden head. `topOfPinnedRunOrderKey`,
 * apps/web/src/hooks/useThreadActions.ts.
 */
fun topOfPinnedRunOrderKey(threads: List<Thread>): String? {
  val firstKey: String? = threads
    .filter { it.pinnedAt != null }
    .mapNotNull { it.pinOrderKey }
    .minOrNull()
  return pinOrderKeyBetween(null, firstKey)
}
