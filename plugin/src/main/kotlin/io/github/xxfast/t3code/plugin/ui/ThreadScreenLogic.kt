package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One row of the thread screen: a run's "Worked for" line or an item, keyed uniquely. */
sealed interface ThreadRow {
  val key: String

  data class RunLine(val run: Run) : ThreadRow {
    override val key: String = "run:${run.id}"
  }

  data class Item(val item: TurnItem, override val key: String) : ThreadRow
}

/**
 * Items under their run, in order. Items don't carry a run id in our model, so an item belongs to
 * the last run requested at or before it started. Items with no usable time stay in the run of the
 * item before them, so lines never repeat. Each run's line goes after the user message that opened
 * it, like T3 does, or first when the run doesn't open with one. Without runs it's just the items.
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
    val bucket: List<ThreadRow.Item> = buckets[index]
    val opener: Int = if (bucket.firstOrNull()?.item is TurnItem.UserMessage) 1 else 0
    bucket.take(opener) + ThreadRow.RunLine(run) + bucket.drop(opener)
  }
}

val Run.running: Boolean get() = completedAt == null && status in RUNNING

/** `Worked for 1m 12s` once done, `Working for 12s` while running, else the status. */
fun runLabel(run: Run, now: Instant): String {
  val start: Instant? = (run.startedAt ?: run.requestedAt).instantOrNull()
  val end: Instant? = run.completedAt?.instantOrNull()
  return when {
    start != null && end != null -> "Worked for ${duration(Duration.between(start, end))}"
    start != null && run.running -> "Working for ${duration(Duration.between(start, now))}"
    else -> run.status.replaceFirstChar { it.uppercase() }
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

private val FullJson: Json = Json(from = T3Json) { encodeDefaults = true }

/** The item as JSON: as received for [TurnItem.Unknown], re-encoded for the branches we know. */
fun TurnItem.json(): JsonObject = when (this) {
  is TurnItem.Unknown -> raw
  else -> FullJson.encodeToJsonElement(TurnItem.serializer(), this).jsonObject
}

/**
 * Whether to keep following the tail after rows changed. The user was at the bottom if the last
 * row before the change was on screen.
 */
fun wasAtBottom(lastVisibleIndex: Int?, previousCount: Int): Boolean =
  previousCount == 0 || (lastVisibleIndex != null && lastVisibleIndex >= previousCount - 1)

/** `+3 -1`, either side left out when absent. */
fun diffStat(additions: Long?, deletions: Long?): String =
  listOfNotNull(additions?.let { "+$it" }, deletions?.let { "-$it" }).joinToString(" ")

/** What "Open" on a file change opens: the first changed path, else the file name. */
fun TurnItem.FileChange.openPath(): String = changes?.firstOrNull()?.path ?: fileName
