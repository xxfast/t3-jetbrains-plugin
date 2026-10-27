// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ScheduledTask(
  val id: String,
  val title: String,
  val prompt: String,
  val enabled: Boolean,
  val schedule: ScheduledTaskSchedule,
  val projectId: String,
  val threadId: String?,
  val workspaceStrategy: ThreadLaunchWorkspaceStrategy,
  val modelSelection: ModelSelection,
  val runtimeMode: String,
  val interactionMode: String,
  val createdBy: String,
  val creationSource: String,
  val createdAt: String,
  val updatedAt: String,
  val nextRunAt: String?,
  val lastRunAt: String?,
  val lastRunStatus: String,
  val lastRunError: String?,
  val runCount: Long,
  val webhook: ScheduledTaskWebhookEndpoint? = null,
)
