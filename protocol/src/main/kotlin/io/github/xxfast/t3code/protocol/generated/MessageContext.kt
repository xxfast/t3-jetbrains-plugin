// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class MessageContext(
  val version: Long,
  // TODO(contracts): array without an item schema
  val records: List<JsonElement>,
)
