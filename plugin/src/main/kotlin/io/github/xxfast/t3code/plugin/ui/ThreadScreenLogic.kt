package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.TurnItem
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One row of the thread screen: a run header or an item, with a key unique in the list. */
sealed interface ThreadRow {
  val key: String

  data class RunHeader(val run: Run) : ThreadRow {
    override val key: String = "run:${run.id}"
  }

  data class Item(val item: TurnItem, override val key: String) : ThreadRow
}

/**
 * Items under their run, in order. Items don't carry a run id in our model, so an item belongs to
 * the last run requested at or before it started. Items with no usable time stay in the run of the
 * item before them, so headers never repeat. Without runs it's just the items.
 */
fun threadRows(runs: List<Run>, items: List<TurnItem>): List<ThreadRow> {
  val keyed: List<ThreadRow.Item> = keyed(items)
  val timed: List<Pair<Run, Instant>> = runs
    .sortedBy { it.ordinal }
    .mapNotNull { run -> (run.requestedAt ?: run.startedAt)?.instantOrNull()?.let { run to it } }
  if (timed.isEmpty()) return keyed

  val buckets: List<MutableList<ThreadRow.Item>> = timed.map { mutableListOf() }
  var current = 0
  keyed.forEach { row ->
    val at: Instant? = row.item.time()
    if (at != null) {
      val index: Int = timed.indexOfLast { (_, requested) -> requested <= at }
      current = maxOf(current, index)
    }
    buckets[current] += row
  }
  return timed.flatMapIndexed { index, (run, _) ->
    listOf(ThreadRow.RunHeader(run)) + buckets[index]
  }
}

/** Item ids are the key; a blank or repeated id falls back to its position so keys stay unique. */
private fun keyed(items: List<TurnItem>): List<ThreadRow.Item> {
  val seen: MutableSet<String> = mutableSetOf()
  return items.mapIndexed { index, item ->
    val unique: Boolean = item.id.isNotBlank() && seen.add(item.id)
    val key: String = if (unique) "item:${item.id}" else "at:$index"
    ThreadRow.Item(item, key)
  }
}

/** When the item happened: when it started, else when it last changed. `null` if neither parses. */
fun TurnItem.time(): Instant? = startedAt?.instantOrNull() ?: updatedAt.instantOrNull()

private fun String.instantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private val PrettyJson: Json = Json(from = T3Json) { prettyPrint = true }

fun prettyJson(raw: JsonObject): String = PrettyJson.encodeToString(JsonObject.serializer(), raw)

/**
 * Whether to keep following the tail after rows changed. The user was at the bottom if the last
 * row before the change was on screen.
 */
fun wasAtBottom(lastVisibleIndex: Int?, previousCount: Int): Boolean =
  previousCount == 0 || (lastVisibleIndex != null && lastVisibleIndex >= previousCount - 1)

/** `+3 -1`, either side left out when absent. */
fun diffStat(additions: Int?, deletions: Int?): String =
  listOfNotNull(additions?.let { "+$it" }, deletions?.let { "-$it" }).joinToString(" ")

/** What "Open" on a file change opens: the first changed path, else the file name. */
fun TurnItem.FileChange.openPath(): String = changes.firstOrNull()?.path ?: fileName
