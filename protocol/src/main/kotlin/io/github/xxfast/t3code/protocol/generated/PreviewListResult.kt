// Generated from v0.1.5 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class PreviewListResult(
  val sessions: List<PreviewSessionSnapshot>,
  val serverEpoch: String,
  val revision: Long,
)
