// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ThreadProjection(
  val thread: AppThread,
  val runs: List<Run>,
  val attempts: List<RunAttempt>,
  val nodes: List<ExecutionNode>,
  val subagents: List<Subagent>,
  val providerSessions: List<ProviderSession>,
  val providerThreads: List<ProviderThread>,
  val providerTurns: List<ProviderTurn>,
  val runtimeRequests: List<RuntimeRequest>,
  val messages: List<ConversationMessage>,
  val plans: List<PlanArtifact>,
  val turnItems: List<TurnItem>,
  val checkpointScopes: List<CheckpointScope>,
  val checkpoints: List<Checkpoint>,
  val contextHandoffs: List<ContextHandoff>,
  val contextTransfers: List<ContextTransfer>,
  val visibleTurnItems: List<ThreadProjection.VisibleTurnItem>,
  val updatedAt: String,
) {
  @Serializable
  public data class VisibleTurnItem(
    val position: Long,
    val visibility: String,
    val sourceThreadId: String,
    val sourceItemId: String,
    val item: TurnItem,
  )
}
