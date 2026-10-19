package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
