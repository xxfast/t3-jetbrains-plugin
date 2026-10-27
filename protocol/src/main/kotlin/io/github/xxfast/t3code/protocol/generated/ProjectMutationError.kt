// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class ProjectMutationError(
  @SerialName("_tag") val tag: String,
  val commandId: String,
  val message: String,
  // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
  val cause: JsonElement? = null,
)
