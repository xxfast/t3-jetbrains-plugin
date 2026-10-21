// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public data class TerminalAttachInput(
  val threadId: String,
  val terminalId: String,
  val cwd: String? = null,
  val worktreePath: String? = null,
  val cols: Long? = null,
  val rows: Long? = null,
  // TODO(contracts): object with no declared properties
  val env: JsonObject? = null,
  val providerInstanceId: String? = null,
  val restartIfNotRunning: Boolean? = null,
  val resume: Boolean? = null,
)
