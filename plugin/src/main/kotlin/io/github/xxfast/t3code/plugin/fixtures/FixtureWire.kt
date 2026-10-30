package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem

// Mirrors apps/server/src/orchestration-v2/WireProjection.ts, the parts the fixture client needs
// to strip from the thread stream like the real server does

private const val MAX_DETAIL_STRING_BYTES: Int = 32_768

/** [view] as the thread stream sends it: items whole only through `orchestration.getTurnItem`. */
internal fun projectForWire(view: ThreadView): ThreadView =
  view.copy(items = view.items.map(::projectTurnItemForWire))

/**
 * The `file_change` branch of `projectTurnItemForWire`: no `diffStr`, `oldStr` or `newStr`,
 * except a failed change keeps a truncated `diffStr`. Other items pass through.
 */
internal fun projectTurnItemForWire(item: TurnItem): TurnItem = when (item) {
  is TurnItem.FileChange -> {
    val diffStr: String? = item.diffStr
      ?.takeIf { diff -> item.status == "failed" && diff.isNotBlank() }
      ?.let(::truncateDetail)
    item.copy(diffStr = diffStr, oldStr = null, newStr = null)
  }

  else -> item
}

internal fun truncateDetail(value: String, maxBytes: Int = MAX_DETAIL_STRING_BYTES): String {
  val bytes: ByteArray = value.encodeToByteArray()
  if (bytes.size <= maxBytes) return value
  val prefix: String = bytes.copyOf(maxBytes).decodeToString().removeSuffix("�")
  return "$prefix\n… output truncated for transport"
}
