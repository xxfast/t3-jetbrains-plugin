// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

@file:OptIn(ExperimentalSerializationApi::class)

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = DomainEventSerializer::class)
public sealed interface DomainEvent {
  public val id: String
  public val threadId: String
  public val runId: String?
  public val nodeId: String?
  public val driver: String?
  public val providerInstanceId: String?
  public val rawEventId: String?
  public val occurredAt: String
  public val type: String

  @Serializable
  public data class ThreadCreated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.created",
    val payload: AppThread,
  ) : DomainEvent

  @Serializable
  public data class Thread(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    override val type: String,
    val payload: AppThread,
  ) : DomainEvent

  @Serializable
  public data class ThreadPullRequestLinkUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.pull-request-link-updated",
    val payload: ThreadPullRequestLink,
  ) : DomainEvent

  @Serializable
  public data class RunCreated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run.created",
    val payload: Run,
  ) : DomainEvent

  @Serializable
  public data class RunUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run.updated",
    val payload: Run,
  ) : DomainEvent

  @Serializable
  public data class DomainEventRunBackgroundWorkCancelled(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "run.background-work-cancelled",
    val payload: RunBackgroundWorkCancelled,
  ) : DomainEvent

  @Serializable
  public data class RunAttemptCreated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run-attempt.created",
    val payload: RunAttempt,
  ) : DomainEvent

  @Serializable
  public data class RunAttemptUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run-attempt.updated",
    val payload: RunAttempt,
  ) : DomainEvent

  @Serializable
  public data class NodeUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "node.updated",
    val payload: ExecutionNode,
  ) : DomainEvent

  @Serializable
  public data class SubagentUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "subagent.updated",
    val payload: Subagent,
  ) : DomainEvent

  @Serializable
  public data class DomainEventProviderSession(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    override val type: String,
    val payload: ProviderSession,
  ) : DomainEvent

  @Serializable
  public data class DomainEventProviderSessionDetached(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "provider-session.detached",
    val payload: ProviderSessionDetached,
  ) : DomainEvent

  @Serializable
  public data class ProviderThreadUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "provider-thread.updated",
    val payload: ProviderThread,
  ) : DomainEvent

  @Serializable
  public data class ProviderTurnUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "provider-turn.updated",
    val payload: ProviderTurn,
  ) : DomainEvent

  @Serializable
  public data class RuntimeRequestUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "runtime-request.updated",
    val payload: RuntimeRequest,
  ) : DomainEvent

  @Serializable
  public data class MessageUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "message.updated",
    val payload: ConversationMessage,
  ) : DomainEvent

  @Serializable
  public data class TurnItemUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "turn-item.updated",
    val payload: TurnItem,
  ) : DomainEvent

  @Serializable
  public data class PlanUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "plan.updated",
    val payload: PlanArtifact,
  ) : DomainEvent

  @Serializable
  public data class CheckpointScopeCreated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "checkpoint-scope.created",
    val payload: CheckpointScope,
  ) : DomainEvent

  @Serializable
  public data class CheckpointCaptured(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "checkpoint.captured",
    val payload: Checkpoint,
  ) : DomainEvent

  @Serializable
  public data class CheckpointRollbackRequested(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "checkpoint.rollback-requested",
    val payload: CheckpointRollbackRequest,
  ) : DomainEvent

  @Serializable
  public data class ContextHandoffUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "context-handoff.updated",
    val payload: ContextHandoff,
  ) : DomainEvent

  @Serializable
  public data class ContextTransferCreated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "context-transfer.created",
    val payload: ContextTransfer,
  ) : DomainEvent

  @Serializable
  public data class ContextTransferUpdated(
    override val id: String,
    override val threadId: String,
    override val runId: String? = null,
    override val nodeId: String? = null,
    override val driver: String? = null,
    override val providerInstanceId: String? = null,
    override val rawEventId: String? = null,
    override val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "context-transfer.updated",
    val payload: ContextTransfer,
  ) : DomainEvent

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownDomainEventSerializer::class)
  public data class Unknown(
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val driver: String?,
    override val providerInstanceId: String?,
    override val rawEventId: String?,
    override val occurredAt: String,
    override val type: String,
    val raw: JsonObject,
  ) : DomainEvent
}

public object DomainEventSerializer : KSerializer<DomainEvent> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<DomainEvent> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "thread.created" -> DomainEvent.ThreadCreated.serializer()
      "thread.archived",
      "thread.unarchived",
      "thread.deleted",
      "thread.settled",
      "thread.unsettled",
      "thread.snoozed",
      "thread.unsnoozed",
      "thread.pinned",
      "thread.auto-settle-set",
      "thread.unpinned",
      "thread.pin-reordered",
      "thread.active-reordered",
      "thread.visited",
      "thread.marked-unread",
      "thread.metadata-updated",
      "thread.pull-request-synced",
      "thread.runtime-mode-updated",
      "thread.interaction-mode-updated",
      "thread.model-selection-updated",
      "thread.provider-switched" ->
        DomainEvent.Thread.serializer()
      "thread.pull-request-link-updated" -> DomainEvent.ThreadPullRequestLinkUpdated.serializer()
      "run.created" -> DomainEvent.RunCreated.serializer()
      "run.updated" -> DomainEvent.RunUpdated.serializer()
      "run.background-work-cancelled" ->
        DomainEvent.DomainEventRunBackgroundWorkCancelled.serializer()
      "run-attempt.created" -> DomainEvent.RunAttemptCreated.serializer()
      "run-attempt.updated" -> DomainEvent.RunAttemptUpdated.serializer()
      "node.updated" -> DomainEvent.NodeUpdated.serializer()
      "subagent.updated" -> DomainEvent.SubagentUpdated.serializer()
      "provider-session.attached",
      "provider-session.updated" ->
        DomainEvent.DomainEventProviderSession.serializer()
      "provider-session.detached" -> DomainEvent.DomainEventProviderSessionDetached.serializer()
      "provider-thread.updated" -> DomainEvent.ProviderThreadUpdated.serializer()
      "provider-turn.updated" -> DomainEvent.ProviderTurnUpdated.serializer()
      "runtime-request.updated" -> DomainEvent.RuntimeRequestUpdated.serializer()
      "message.updated" -> DomainEvent.MessageUpdated.serializer()
      "turn-item.updated" -> DomainEvent.TurnItemUpdated.serializer()
      "plan.updated" -> DomainEvent.PlanUpdated.serializer()
      "checkpoint-scope.created" -> DomainEvent.CheckpointScopeCreated.serializer()
      "checkpoint.captured" -> DomainEvent.CheckpointCaptured.serializer()
      "checkpoint.rollback-requested" -> DomainEvent.CheckpointRollbackRequested.serializer()
      "context-handoff.updated" -> DomainEvent.ContextHandoffUpdated.serializer()
      "context-transfer.created" -> DomainEvent.ContextTransferCreated.serializer()
      "context-transfer.updated" -> DomainEvent.ContextTransferUpdated.serializer()
      else -> DomainEvent.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): DomainEvent {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(DomainEvent.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: DomainEvent) {
    when (value) {
      is DomainEvent.ThreadCreated ->
        DomainEvent.ThreadCreated.serializer().serialize(encoder, value)
      is DomainEvent.Thread -> DomainEvent.Thread.serializer().serialize(encoder, value)
      is DomainEvent.ThreadPullRequestLinkUpdated ->
        DomainEvent.ThreadPullRequestLinkUpdated.serializer().serialize(encoder, value)
      is DomainEvent.RunCreated -> DomainEvent.RunCreated.serializer().serialize(encoder, value)
      is DomainEvent.RunUpdated -> DomainEvent.RunUpdated.serializer().serialize(encoder, value)
      is DomainEvent.DomainEventRunBackgroundWorkCancelled ->
        DomainEvent.DomainEventRunBackgroundWorkCancelled.serializer().serialize(encoder, value)
      is DomainEvent.RunAttemptCreated ->
        DomainEvent.RunAttemptCreated.serializer().serialize(encoder, value)
      is DomainEvent.RunAttemptUpdated ->
        DomainEvent.RunAttemptUpdated.serializer().serialize(encoder, value)
      is DomainEvent.NodeUpdated -> DomainEvent.NodeUpdated.serializer().serialize(encoder, value)
      is DomainEvent.SubagentUpdated ->
        DomainEvent.SubagentUpdated.serializer().serialize(encoder, value)
      is DomainEvent.DomainEventProviderSession ->
        DomainEvent.DomainEventProviderSession.serializer().serialize(encoder, value)
      is DomainEvent.DomainEventProviderSessionDetached ->
        DomainEvent.DomainEventProviderSessionDetached.serializer().serialize(encoder, value)
      is DomainEvent.ProviderThreadUpdated ->
        DomainEvent.ProviderThreadUpdated.serializer().serialize(encoder, value)
      is DomainEvent.ProviderTurnUpdated ->
        DomainEvent.ProviderTurnUpdated.serializer().serialize(encoder, value)
      is DomainEvent.RuntimeRequestUpdated ->
        DomainEvent.RuntimeRequestUpdated.serializer().serialize(encoder, value)
      is DomainEvent.MessageUpdated ->
        DomainEvent.MessageUpdated.serializer().serialize(encoder, value)
      is DomainEvent.TurnItemUpdated ->
        DomainEvent.TurnItemUpdated.serializer().serialize(encoder, value)
      is DomainEvent.PlanUpdated -> DomainEvent.PlanUpdated.serializer().serialize(encoder, value)
      is DomainEvent.CheckpointScopeCreated ->
        DomainEvent.CheckpointScopeCreated.serializer().serialize(encoder, value)
      is DomainEvent.CheckpointCaptured ->
        DomainEvent.CheckpointCaptured.serializer().serialize(encoder, value)
      is DomainEvent.CheckpointRollbackRequested ->
        DomainEvent.CheckpointRollbackRequested.serializer().serialize(encoder, value)
      is DomainEvent.ContextHandoffUpdated ->
        DomainEvent.ContextHandoffUpdated.serializer().serialize(encoder, value)
      is DomainEvent.ContextTransferCreated ->
        DomainEvent.ContextTransferCreated.serializer().serialize(encoder, value)
      is DomainEvent.ContextTransferUpdated ->
        DomainEvent.ContextTransferUpdated.serializer().serialize(encoder, value)
      is DomainEvent.Unknown -> DomainEvent.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownDomainEventSerializer : KSerializer<DomainEvent.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): DomainEvent.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return DomainEvent.Unknown(
      id = string("id") ?: "",
      threadId = string("threadId") ?: "",
      runId = string("runId"),
      nodeId = string("nodeId"),
      driver = string("driver"),
      providerInstanceId = string("providerInstanceId"),
      rawEventId = string("rawEventId"),
      occurredAt = string("occurredAt") ?: "",
      type = string("type") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: DomainEvent.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
