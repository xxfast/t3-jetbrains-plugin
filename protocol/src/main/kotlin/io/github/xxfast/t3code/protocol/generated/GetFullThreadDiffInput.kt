// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class GetFullThreadDiffInput(
  val threadId: String,
  val toTurnCount: Long,
  val ignoreWhitespace: Boolean? = null,
)
