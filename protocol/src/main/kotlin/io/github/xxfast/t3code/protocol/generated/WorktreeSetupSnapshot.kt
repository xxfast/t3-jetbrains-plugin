// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class WorktreeSetupSnapshot(
  val threadId: String,
  val phase: String,
  val startedAt: String,
  val endedAt: String?,
  val branch: String?,
  val baseRef: String?,
  val worktreePath: String?,
  val setupScript: WorktreeSetupSnapshot.SetupScript?,
  val stages: List<WorktreeSetupStage>,
  val error: String?,
  val sequence: Long,
) {
  @Serializable
  public data class SetupScript(
    val name: String,
    val command: String,
    val terminalId: String,
  )
}
