package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.TurnItem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// Mirrors packages/client-runtime/src/work-log/itemDetail.ts

private val LIVE_TURN_ITEM_STATUSES: Set<String> = setOf("idle", "pending", "running", "waiting")

// `DateTime.formatIso`, which is `Date.toISOString`: always milliseconds, always `Z`
private val ISO: DateTimeFormatter =
  DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * A running item keeps one key, so an open row fetches once while it streams and again when it
 * finishes, not on every update.
 */
fun turnItemDetailRevision(item: TurnItem): String =
  if (item.status in LIVE_TURN_ITEM_STATUSES) "live" else formatIso(item.updatedAt)

private fun formatIso(timestamp: String): String = try {
  ISO.format(Instant.parse(timestamp))
} catch (_: DateTimeParseException) {
  timestamp
}
