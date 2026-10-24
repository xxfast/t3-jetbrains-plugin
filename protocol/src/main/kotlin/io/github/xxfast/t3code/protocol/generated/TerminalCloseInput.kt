// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class TerminalCloseInput(
  val threadId: String,
  val terminalId: String? = null,
  val deleteHistory: Boolean? = null,
)
