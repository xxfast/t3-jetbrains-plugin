// Generated from v0.1.5 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class MessageContext(
  val version: Long,
  val guest: GuestMessageAttribution? = null,
  // TODO(contracts): array without an item schema
  val records: List<JsonElement>,
)
