// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class Run(
  val id: String,
  val threadId: String,
  val ordinal: Long,
  val providerInstanceId: String,
  val modelSelection: ModelSelection,
  val providerThreadId: String?,
  val userMessageId: String,
  val rootNodeId: String?,
  val activeAttemptId: String?,
  val status: String,
  val queuePosition: Long? = null,
  val queueHeld: Boolean? = null,
  val requestedAt: String,
  val startedAt: String?,
  val completedAt: String?,
  val checkpointId: String?,
  val contextHandoffId: String?,
  val restartContinuationOfRunId: String? = null,
  val delegatedTaskId: String? = null,
  val workStartedAt: String? = null,
  val restartCancelledBackgroundWork: List<RestartCancelledBackgroundWork>? = null,
  val sourcePlanRef: Run.SourcePlanRef? = null,
  val delegatedCompletion: DelegatedCompletionCohort? = null,
  val workspacePreparation: ThreadLaunchWorkspaceStrategy? = null,
) {
  @Serializable
  public data class SourcePlanRef(
    val threadId: String,
    val planId: String,
  )
}
