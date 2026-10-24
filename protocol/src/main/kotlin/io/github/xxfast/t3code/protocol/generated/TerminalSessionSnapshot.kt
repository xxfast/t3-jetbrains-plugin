// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class TerminalSessionSnapshot(
  val threadId: String,
  val terminalId: String,
  val cwd: String,
  val worktreePath: String?,
  val status: String,
  val pid: Long?,
  val history: String,
  val exitCode: Long?,
  val exitSignal: Long?,
  val label: String,
  val updatedAt: String,
  val sequence: Long? = null,
)
