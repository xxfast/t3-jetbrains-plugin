// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
public data class AssetImageDimensions(
  val width: Long,
  val height: Long,
)

@Serializable(with = ChatAttachmentSerializer::class)
public sealed interface ChatAttachment {
  public val type: String
  public val id: String
  public val name: String
  public val mimeType: String
  public val sizeBytes: Long

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownChatAttachmentSerializer::class)
  public data class Unknown(
    override val type: String,
    override val id: String,
    override val name: String,
    override val mimeType: String,
    override val sizeBytes: Long,
    val raw: JsonObject,
  ) : ChatAttachment
}

public object ChatAttachmentSerializer : KSerializer<ChatAttachment> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ChatAttachment> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "image" -> ChatImageAttachment.serializer()
      "file" -> ChatFileAttachment.serializer()
      else -> ChatAttachment.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ChatAttachment {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ChatAttachment.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ChatAttachment) {
    when (value) {
      is ChatImageAttachment -> ChatImageAttachment.serializer().serialize(encoder, value)
      is ChatFileAttachment -> ChatFileAttachment.serializer().serialize(encoder, value)
      is ChatAttachment.Unknown -> ChatAttachment.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownChatAttachmentSerializer : KSerializer<ChatAttachment.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ChatAttachment.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String): Long? = raw[key]?.jsonPrimitive?.longOrNull
    return ChatAttachment.Unknown(
      type = string("type") ?: "unknown",
      id = string("id") ?: "",
      name = string("name") ?: "",
      mimeType = string("mimeType") ?: "",
      sizeBytes = long("sizeBytes") ?: 0L,
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ChatAttachment.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class ChatFileAttachment(
  override val type: String = "file",
  override val id: String,
  override val name: String,
  override val mimeType: String,
  override val sizeBytes: Long,
  val source: PastedTextAttachmentSource? = null,
) : ChatAttachment, UserInputAttachmentAnswerPayload.AttachmentsByQuestionIdItem

@Serializable
public data class ChatImageAttachment(
  override val type: String = "image",
  override val id: String,
  override val name: String,
  override val mimeType: String,
  override val sizeBytes: Long,
  val source: SnapShotSource? = null,
) : ChatAttachment, UserInputAttachmentAnswerPayload.AttachmentsByQuestionIdItem

@Serializable
public data class ChatUnknownAttachment(
  val type: String,
  val id: String,
  val name: String,
  val mimeType: String,
  val sizeBytes: Long,
)

@Serializable
public data class ModelSelection(
  // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
  val provider: JsonElement? = null,
  // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
  val instanceId: JsonElement? = null,
  // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
  val model: JsonElement,
  // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
  val options: JsonElement? = null,
)

@Serializable
public data class MessageContext(
  val version: Long,
  // TODO(contracts): array without an item schema
  val records: List<JsonElement>,
)

@Serializable
public data class AppThread(
  val createdBy: String,
  val creationSource: String,
  val id: String,
  val projectId: String,
  val title: String,
  val providerInstanceId: String,
  val modelSelection: ModelSelection,
  val runtimeMode: String,
  val interactionMode: String,
  val branch: String?,
  val worktreePath: String?,
  val linkedPullRequest: ThreadLinkedPullRequest? = null,
  val pullRequests: List<ThreadPullRequestLink>? = null,
  val branchPullRequest: ThreadLinkedPullRequest? = null,
  val activeProviderThreadId: String?,
  val historyOrigin: String? = null,
  val linkOrigin: LinkOrigin? = null,
  val delegatedFrom: DelegatedFrom? = null,
  val lineage: AppThreadLineage,
  val forkedFrom: AppThread.ForkedFrom?,
  val createdAt: String,
  val updatedAt: String,
  val archivedAt: String?,
  val settledOverride: String? = null,
  val settledAt: String? = null,
  val unsettledAt: String? = null,
  val snoozedUntil: String? = null,
  val snoozedAt: String? = null,
  val lastSnoozeWakeAt: String? = null,
  val limitRecovery: LimitRecovery? = null,
  val pinnedAt: String? = null,
  val autoSettleDisabledAt: String? = null,
  val pinOrderKey: String? = null,
  val activeOrderKey: String? = null,
  val lastVisitedAt: String? = null,
  val titleRegeneration: ThreadTitleRegeneration? = null,
  val rollbackRequestId: String? = null,
  val rollbackFailure: AppThread.RollbackFailure? = null,
  val deletedAt: String?,
) {
  @Serializable(with = ForkedFrom.ForkedFromSerializer::class)
  public sealed interface ForkedFrom {
    public val type: String

    @Serializable
    public data class ForkedFromRun(
      override val type: String = "run",
      val threadId: String,
      val runId: String,
    ) : AppThread.ForkedFrom

    @Serializable
    public data class Node(
      override val type: String = "node",
      val nodeId: String,
    ) : AppThread.ForkedFrom

    @Serializable
    public data class ForkedFromProviderThread(
      override val type: String = "provider_thread",
      val providerThreadId: String,
      val providerTurnId: String? = null,
    ) : AppThread.ForkedFrom

    /** A variant this build doesn't know. [raw] is the whole object as received. */
    @Serializable(with = ForkedFrom.UnknownForkedFromSerializer::class)
    public data class Unknown(
      override val type: String,
      val raw: JsonObject,
    ) : ForkedFrom

    public object ForkedFromSerializer : KSerializer<ForkedFrom> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      private fun select(element: JsonObject): DeserializationStrategy<ForkedFrom> =
        when (element["type"]?.jsonPrimitive?.contentOrNull) {
          "run" -> ForkedFrom.ForkedFromRun.serializer()
          "node" -> ForkedFrom.Node.serializer()
          "provider_thread" -> ForkedFrom.ForkedFromProviderThread.serializer()
          else -> ForkedFrom.Unknown.serializer()
        }

      override fun deserialize(decoder: Decoder): ForkedFrom {
        val input: JsonDecoder = decoder as JsonDecoder
        val element: JsonObject = input.decodeJsonElement().jsonObject
        return try {
          input.json.decodeFromJsonElement(select(element), element)
        } catch (_: IllegalArgumentException) {
          input.json.decodeFromJsonElement(ForkedFrom.Unknown.serializer(), element)
        }
      }

      override fun serialize(encoder: Encoder, value: ForkedFrom) {
        when (value) {
          is ForkedFrom.ForkedFromRun ->
            ForkedFrom.ForkedFromRun.serializer().serialize(encoder, value)
          is ForkedFrom.Node -> ForkedFrom.Node.serializer().serialize(encoder, value)
          is ForkedFrom.ForkedFromProviderThread ->
            ForkedFrom.ForkedFromProviderThread.serializer().serialize(encoder, value)
          is ForkedFrom.Unknown -> ForkedFrom.Unknown.serializer().serialize(encoder, value)
        }
      }
    }

    public object UnknownForkedFromSerializer : KSerializer<ForkedFrom.Unknown> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      override fun deserialize(decoder: Decoder): ForkedFrom.Unknown {
        val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
        return ForkedFrom.Unknown(
          type = string("type") ?: "unknown",
          raw = raw,
        )
      }

      override fun serialize(encoder: Encoder, value: ForkedFrom.Unknown) {
        (encoder as JsonEncoder).encodeJsonElement(value.raw)
      }
    }
  }

  @Serializable
  public data class RollbackFailure(
    val requestId: String,
    val message: String,
  )
}

@Serializable
public data class AppThreadLineage(
  val parentThreadId: String?,
  val relationshipToParent: String?,
  val rootThreadId: String,
)

@Serializable
public data class ApprovalCapabilities(
  val supportsCommandApproval: Boolean,
  val supportsFileReadApproval: Boolean,
  val supportsFileChangeApproval: Boolean,
  val supportsApplyPatchApproval: Boolean,
  val approvalsHaveNativeRequestIds: Boolean,
  val approvalCallbacksAreLiveOnly: Boolean,
  val approvalsCanOriginateFromSubagents: Boolean,
)

@Serializable
public data class Checkpoint(
  val id: String,
  val threadId: String,
  val scopeId: String,
  val runId: String?,
  val nodeId: String,
  val parentCheckpointId: String?,
  val ordinalWithinScope: Long,
  val appRunOrdinal: Long?,
  val ref: String,
  val status: String,
  val files: List<CheckpointFileSummary>,
  val capturedAt: String,
)

@Serializable
public data class CheckpointCapabilities(
  val appCanCheckpointFilesystem: Boolean,
  val supportsNestedCheckpointScopes: Boolean,
  val providerCanRollbackConversation: Boolean,
  val providerRollbackReturnsSnapshot: Boolean,
  val providerCanReadConversationSnapshot: Boolean,
)

@Serializable
public data class CheckpointFileSummary(
  val path: String,
  val kind: String,
  val additions: Long,
  val deletions: Long,
)

@Serializable
public data class CheckpointRollbackRequest(
  val scopeId: String,
  val checkpointId: String,
  val requestedAt: String,
)

@Serializable
public data class CheckpointScope(
  val id: String,
  val threadId: String,
  val runId: String?,
  val nodeId: String,
  val parentScopeId: String?,
  val providerThreadId: String?,
  val kind: String,
  val ordinalWithinParent: Long,
  val advancesAppRunCount: Boolean,
  val cwd: String,
  val createdAt: String,
)

@Serializable
public data class ContextCapabilities(
  val acceptsSystemContext: Boolean,
  val acceptsDeveloperContext: Boolean,
  val acceptsSyntheticUserContext: Boolean,
  val canGenerateSummaries: Boolean,
  val canConsumeHandoffSummaries: Boolean,
  val supportsDeltaHandoff: Boolean,
  val supportsFullThreadHandoff: Boolean,
  val maxRecommendedHandoffChars: Long?,
)

@Serializable
public data class ContextHandoff(
  val id: String,
  val transferId: String? = null,
  val threadId: String,
  val targetRunId: String,
  val fromProviderThreadIds: List<String>,
  val toProviderThreadId: String,
  val coveredRunOrdinals: ContextHandoff.CoveredRunOrdinals,
  val strategy: String,
  val status: String,
  val summaryMessageId: String?,
  val summaryText: String,
  val history: ContextHandoff.History? = null,
  val delivery: ContextHandoff.Delivery? = null,
  val detailInTurnItem: Boolean? = null,
  val createdByProviderInstanceId: String?,
  val createdAt: String,
  val updatedAt: String,
) {
  @Serializable
  public data class CoveredRunOrdinals(
    val from: Long,
    val to: Long,
  )

  @Serializable
  public data class History(
    val messages: List<HistoricalMessage>,
    val coverage: String,
    val omittedItems: Long,
    val omittedItemIds: List<String>? = null,
  )

  @Serializable
  public data class Delivery(
    val nativeThreadId: String,
    val status: String,
    val itemIds: List<String>,
    val omittedItemIds: List<String>? = null,
  )
}

@Serializable
public data class ContextSourcePoint(
  val threadId: String,
  val runId: String? = null,
  val checkpointId: String? = null,
  val turnItemId: String? = null,
  val providerThreadRef: ProviderRef? = null,
  val providerTurnRef: ProviderRef? = null,
)

@Serializable
public data class ContextTransfer(
  val id: String,
  val type: String,
  val sourceThreadId: String,
  val targetThreadId: String,
  val sourcePoint: ContextSourcePoint,
  val basePoint: ContextSourcePoint?,
  val sourceProviderInstanceId: String?,
  val targetProviderInstanceId: String?,
  val targetRunId: String?,
  val status: String,
  val resolution: ContextTransferResolution?,
  val createdBy: String,
  val error: String?,
  val createdAt: String,
  val updatedAt: String,
  val consumedAt: String?,
)

@Serializable(with = ContextTransferResolutionSerializer::class)
public sealed interface ContextTransferResolution {
  public val strategy: String

  @Serializable
  public data class NativeFork(
    override val strategy: String = "native_fork",
    val providerThreadRef: ProviderRef,
  ) : ContextTransferResolution

  @Serializable
  public data class PortableContext(
    override val strategy: String = "portable_context",
    val contextHandoffId: String,
  ) : ContextTransferResolution

  @Serializable
  public data class DeltaContext(
    override val strategy: String = "delta_context",
    val contextHandoffId: String,
  ) : ContextTransferResolution

  @Serializable
  public data class ForkDeltaContext(
    override val strategy: String = "fork_delta_context",
    val contextHandoffId: String,
  ) : ContextTransferResolution

  @Serializable
  public data class CheckpointContext(
    override val strategy: String = "checkpoint_context",
    val contextHandoffId: String,
  ) : ContextTransferResolution

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownContextTransferResolutionSerializer::class)
  public data class Unknown(
    override val strategy: String,
    val raw: JsonObject,
  ) : ContextTransferResolution
}

public object ContextTransferResolutionSerializer : KSerializer<ContextTransferResolution> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ContextTransferResolution> =
    when (element["strategy"]?.jsonPrimitive?.contentOrNull) {
      "native_fork" -> ContextTransferResolution.NativeFork.serializer()
      "portable_context" -> ContextTransferResolution.PortableContext.serializer()
      "delta_context" -> ContextTransferResolution.DeltaContext.serializer()
      "fork_delta_context" -> ContextTransferResolution.ForkDeltaContext.serializer()
      "checkpoint_context" -> ContextTransferResolution.CheckpointContext.serializer()
      else -> ContextTransferResolution.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ContextTransferResolution {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ContextTransferResolution.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ContextTransferResolution) {
    when (value) {
      is ContextTransferResolution.NativeFork ->
        ContextTransferResolution.NativeFork.serializer().serialize(encoder, value)
      is ContextTransferResolution.PortableContext ->
        ContextTransferResolution.PortableContext.serializer().serialize(encoder, value)
      is ContextTransferResolution.DeltaContext ->
        ContextTransferResolution.DeltaContext.serializer().serialize(encoder, value)
      is ContextTransferResolution.ForkDeltaContext ->
        ContextTransferResolution.ForkDeltaContext.serializer().serialize(encoder, value)
      is ContextTransferResolution.CheckpointContext ->
        ContextTransferResolution.CheckpointContext.serializer().serialize(encoder, value)
      is ContextTransferResolution.Unknown ->
        ContextTransferResolution.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownContextTransferResolutionSerializer :
  KSerializer<ContextTransferResolution.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ContextTransferResolution.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ContextTransferResolution.Unknown(
      strategy = string("strategy") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ContextTransferResolution.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class ConversationMessage(
  val notification: Notification? = null,
  val createdBy: String,
  val creationSource: String,
  val scheduledTaskId: String? = null,
  val senderThreadId: String? = null,
  val id: String,
  val threadId: String,
  val runId: String?,
  val nodeId: String?,
  val role: String,
  val text: String,
  val context: MessageContext? = null,
  val attachments: List<ChatAttachment>,
  val streaming: Boolean,
  val createdAt: String,
  val updatedAt: String,
  val delegatedCompletion: ConversationMessage.DelegatedCompletion? = null,
) {
  @Serializable
  public data class DelegatedCompletion(
    val parentRunId: String,
    val generation: Long,
    val taskIds: List<String>,
  )
}

@Serializable
public data class DelegatedCompletionCohort(
  val disposition: String,
  val nextGeneration: Long,
  val delivery: DelegatedCompletionDelivery?,
)

@Serializable
public data class DelegatedCompletionDelivery(
  val generation: Long,
  val messageId: String,
  val taskIds: List<String>,
)

@Serializable
public data class DelegatedCompletionTaskDelivery(
  val state: String,
  val observedByRunId: String?,
)

@Serializable
public data class DelegatedFrom(
  val environmentId: String,
  val threadId: String,
  val title: String,
)

@Serializable
public data class ExecutionNode(
  val id: String,
  val threadId: String,
  val runId: String?,
  val parentNodeId: String?,
  val rootNodeId: String,
  val kind: String,
  val status: String,
  val countsForRun: Boolean,
  val providerThreadId: String?,
  val providerTurnId: String?,
  val nativeItemRef: ProviderRef?,
  val runtimeRequestId: String?,
  val checkpointScopeId: String?,
  val startedAt: String?,
  val completedAt: String?,
)

@Serializable
public data class FileChangeDetail(
  val operation: String,
  val path: String,
  val oldPath: String? = null,
  val fileType: String? = null,
  val mimeType: String? = null,
)

@Serializable
public data class FileSearchResult(
  val fileName: String,
  val line: Long? = null,
  val column: Long? = null,
  val preview: String? = null,
)

@Serializable
public data class HistoricalMessage(
  val role: String,
  val text: String,
  val runStatus: String? = null,
  val threadId: String,
  val runId: String?,
  val itemId: String,
  val providerThreadId: String?,
  val status: String,
  val kind: String,
)

@Serializable
public data class IdentityCapabilities(
  val nativeThreadIds: String,
  val nativeTurnIds: String,
  val nativeItemIds: String,
  val nativeRequestIds: String,
)

@Serializable
public data class LatestVisibleMessageSummary(
  val id: String,
  val role: String,
  val text: String,
  val updatedAt: String,
)

@Serializable
public data class LimitRecovery(
  val requestId: String? = null,
  val runId: String,
  val resetAt: String,
  val autoResume: Boolean,
  val snooze: Boolean? = null,
)

@Serializable
public data class LinkOrigin(
  val sessionId: String,
  val label: String,
)

@Serializable
public data class Notification(
  val source: NotificationSource,
  val outcome: String,
  val summary: String,
  val detail: String? = null,
)

@Serializable(with = NotificationSourceSerializer::class)
public sealed interface NotificationSource {
  public val kind: String

  @Serializable
  public data class DelegatedTask(
    override val kind: String = "delegated_task",
    val taskIds: List<String>,
    val childThreadId: String? = null,
  ) : NotificationSource

  @Serializable
  public data class BackgroundTask(
    override val kind: String = "background_task",
    val work: String,
    val childThreadId: String? = null,
  ) : NotificationSource

  @Serializable
  public data class BackgroundCommand(
    override val kind: String = "background_command",
  ) : NotificationSource

  @Serializable
  public data class NotificationSourceSubagent(
    override val kind: String = "subagent",
    val childThreadId: String? = null,
  ) : NotificationSource

  @Serializable
  public data class Command(
    override val kind: String = "command",
  ) : NotificationSource

  @Serializable
  public data class Monitor(
    override val kind: String = "monitor",
  ) : NotificationSource

  @Serializable
  public data class System(
    override val kind: String = "system",
  ) : NotificationSource

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownNotificationSourceSerializer::class)
  public data class Unknown(
    override val kind: String,
    val raw: JsonObject,
  ) : NotificationSource
}

public object NotificationSourceSerializer : KSerializer<NotificationSource> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<NotificationSource> =
    when (element["kind"]?.jsonPrimitive?.contentOrNull) {
      "delegated_task" -> NotificationSource.DelegatedTask.serializer()
      "background_task" -> NotificationSource.BackgroundTask.serializer()
      "background_command" -> NotificationSource.BackgroundCommand.serializer()
      "subagent" -> NotificationSource.NotificationSourceSubagent.serializer()
      "command" -> NotificationSource.Command.serializer()
      "monitor" -> NotificationSource.Monitor.serializer()
      "system" -> NotificationSource.System.serializer()
      else -> NotificationSource.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): NotificationSource {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(NotificationSource.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: NotificationSource) {
    when (value) {
      is NotificationSource.DelegatedTask ->
        NotificationSource.DelegatedTask.serializer().serialize(encoder, value)
      is NotificationSource.BackgroundTask ->
        NotificationSource.BackgroundTask.serializer().serialize(encoder, value)
      is NotificationSource.BackgroundCommand ->
        NotificationSource.BackgroundCommand.serializer().serialize(encoder, value)
      is NotificationSource.NotificationSourceSubagent ->
        NotificationSource.NotificationSourceSubagent.serializer().serialize(encoder, value)
      is NotificationSource.Command ->
        NotificationSource.Command.serializer().serialize(encoder, value)
      is NotificationSource.Monitor ->
        NotificationSource.Monitor.serializer().serialize(encoder, value)
      is NotificationSource.System ->
        NotificationSource.System.serializer().serialize(encoder, value)
      is NotificationSource.Unknown ->
        NotificationSource.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownNotificationSourceSerializer : KSerializer<NotificationSource.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): NotificationSource.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return NotificationSource.Unknown(
      kind = string("kind") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: NotificationSource.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable(with = PendingBackgroundTaskSerializer::class)
public sealed interface PendingBackgroundTask {
  public val taskId: String
  public val description: String?
  public val parentTaskId: String?
  public val kind: String

  @Serializable
  public data class PendingBackgroundTaskSubagent(
    override val taskId: String,
    override val description: String? = null,
    override val parentTaskId: String? = null,
    override val kind: String = "subagent",
    val providerInstanceId: String? = null,
    val childThreadId: String? = null,
  ) : PendingBackgroundTask

  @Serializable
  public data class Command(
    override val taskId: String,
    override val description: String? = null,
    override val parentTaskId: String? = null,
    override val kind: String = "command",
  ) : PendingBackgroundTask

  @Serializable
  public data class Monitor(
    override val taskId: String,
    override val description: String? = null,
    override val parentTaskId: String? = null,
    override val kind: String = "monitor",
  ) : PendingBackgroundTask

  @Serializable
  public data class BackgroundTask(
    override val taskId: String,
    override val description: String? = null,
    override val parentTaskId: String? = null,
    override val kind: String = "background_task",
  ) : PendingBackgroundTask

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownPendingBackgroundTaskSerializer::class)
  public data class Unknown(
    override val taskId: String,
    override val description: String?,
    override val parentTaskId: String?,
    override val kind: String,
    val raw: JsonObject,
  ) : PendingBackgroundTask
}

public object PendingBackgroundTaskSerializer : KSerializer<PendingBackgroundTask> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<PendingBackgroundTask> =
    when (element["kind"]?.jsonPrimitive?.contentOrNull) {
      "subagent" -> PendingBackgroundTask.PendingBackgroundTaskSubagent.serializer()
      "command" -> PendingBackgroundTask.Command.serializer()
      "monitor" -> PendingBackgroundTask.Monitor.serializer()
      "background_task" -> PendingBackgroundTask.BackgroundTask.serializer()
      else -> PendingBackgroundTask.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): PendingBackgroundTask {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(PendingBackgroundTask.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: PendingBackgroundTask) {
    when (value) {
      is PendingBackgroundTask.PendingBackgroundTaskSubagent ->
        PendingBackgroundTask.PendingBackgroundTaskSubagent.serializer().serialize(encoder, value)
      is PendingBackgroundTask.Command ->
        PendingBackgroundTask.Command.serializer().serialize(encoder, value)
      is PendingBackgroundTask.Monitor ->
        PendingBackgroundTask.Monitor.serializer().serialize(encoder, value)
      is PendingBackgroundTask.BackgroundTask ->
        PendingBackgroundTask.BackgroundTask.serializer().serialize(encoder, value)
      is PendingBackgroundTask.Unknown ->
        PendingBackgroundTask.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownPendingBackgroundTaskSerializer : KSerializer<PendingBackgroundTask.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): PendingBackgroundTask.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return PendingBackgroundTask.Unknown(
      taskId = string("taskId") ?: "",
      description = string("description"),
      parentTaskId = string("parentTaskId"),
      kind = string("kind") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: PendingBackgroundTask.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class PendingRuntimeRequestSummary(
  val id: String,
  val kind: String,
  val createdAt: String,
)

@Serializable(with = PlanArtifactSerializer::class)
public sealed interface PlanArtifact {
  public val id: String
  public val threadId: String
  public val runId: String?
  public val nodeId: String
  public val status: String
  public val detailInTurnItem: Boolean?
  public val kind: String

  @Serializable
  public data class ProposedPlan(
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String,
    override val status: String,
    override val detailInTurnItem: Boolean? = null,
    override val kind: String = "proposed_plan",
    val markdown: String,
  ) : PlanArtifact

  @Serializable
  public data class TodoList(
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String,
    override val status: String,
    override val detailInTurnItem: Boolean? = null,
    override val kind: String = "todo_list",
    val steps: List<PlanStep>,
    val explanation: String? = null,
  ) : PlanArtifact

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownPlanArtifactSerializer::class)
  public data class Unknown(
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String,
    override val status: String,
    override val detailInTurnItem: Boolean?,
    override val kind: String,
    val raw: JsonObject,
  ) : PlanArtifact
}

public object PlanArtifactSerializer : KSerializer<PlanArtifact> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<PlanArtifact> =
    when (element["kind"]?.jsonPrimitive?.contentOrNull) {
      "proposed_plan" -> PlanArtifact.ProposedPlan.serializer()
      "todo_list" -> PlanArtifact.TodoList.serializer()
      else -> PlanArtifact.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): PlanArtifact {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(PlanArtifact.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: PlanArtifact) {
    when (value) {
      is PlanArtifact.ProposedPlan ->
        PlanArtifact.ProposedPlan.serializer().serialize(encoder, value)
      is PlanArtifact.TodoList -> PlanArtifact.TodoList.serializer().serialize(encoder, value)
      is PlanArtifact.Unknown -> PlanArtifact.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownPlanArtifactSerializer : KSerializer<PlanArtifact.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): PlanArtifact.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    fun boolean(key: String): Boolean? = raw[key]?.jsonPrimitive?.booleanOrNull
    return PlanArtifact.Unknown(
      id = string("id") ?: "",
      threadId = string("threadId") ?: "",
      runId = string("runId"),
      nodeId = string("nodeId") ?: "",
      status = string("status") ?: "",
      detailInTurnItem = boolean("detailInTurnItem"),
      kind = string("kind") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: PlanArtifact.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class PlanStep(
  val id: String,
  val text: String,
  val status: String,
  val durationAnchorAt: String? = null,
  val durationMs: Long? = null,
)

@Serializable
public data class PlanningCapabilities(
  val emitsPlanUpdated: Boolean,
  val emitsTodoList: Boolean,
  val emitsProposedPlan: Boolean,
  val supportsStructuredQuestions: Boolean,
  val planDeltasHaveItemIds: Boolean,
)

@Serializable
public data class ProviderCapabilities(
  val sessions: SessionCapabilities,
  val threads: ThreadCapabilities,
  val turns: TurnCapabilities,
  val streaming: StreamingCapabilities,
  val tools: ToolCapabilities,
  val approvals: ApprovalCapabilities,
  val planning: PlanningCapabilities,
  val subagents: SubagentCapabilities,
  val context: ContextCapabilities,
  val checkpointing: CheckpointCapabilities,
  val identity: IdentityCapabilities,
  val runtimePolicy: RuntimePolicyCapabilities? = null,
)

@Serializable
public data class ProviderFailure(
  val `class`: String,
  val message: String,
  val code: String?,
  val retryable: Boolean?,
  val resetAt: String? = null,
)

@Serializable
public data class ProviderGoal(
  val objective: String,
  val status: String,
  val tokensUsed: Long? = null,
  val tokenBudget: Long? = null,
  val timeUsedSeconds: Long? = null,
  val checks: Long? = null,
  val lastCheck: String? = null,
)

@Serializable
public data class ProviderRef(
  val driver: String,
  val nativeId: String?,
  val strength: String,
  val fingerprint: String? = null,
  val ordinal: Long? = null,
)

@Serializable
public data class ProviderRetry(
  val attempt: Long,
  val maxAttempts: Long?,
  val retryDelayMs: Long?,
)

@Serializable
public data class ProviderSession(
  val id: String,
  val driver: String,
  val providerInstanceId: String,
  val status: String,
  val cwd: String,
  val model: String?,
  val capabilities: ProviderCapabilities,
  val createdAt: String,
  val updatedAt: String,
  val lastError: String?,
)

@Serializable
public data class ProviderSessionDetached(
  val providerSessionId: String,
  val detachedAt: String,
  val reason: String? = null,
)

@Serializable
public data class ProviderThread(
  val id: String,
  val driver: String,
  val providerInstanceId: String,
  val providerSessionId: String?,
  val appThreadId: String?,
  val ownerNodeId: String?,
  val nativeThreadRef: ProviderRef?,
  val nativeConversationHeadRef: ProviderRef?,
  val status: String,
  val firstRunOrdinal: Long?,
  val lastRunOrdinal: Long?,
  val handoffIds: List<String>,
  val forkedFrom: ProviderThread.ForkedFrom?,
  val pendingBackgroundTasks: List<PendingBackgroundTask>? = null,
  val contextUsage: ThreadTokenUsageSnapshot? = null,
  val nativeMetadata: ProviderThreadNativeMetadata? = null,
  val goal: ProviderGoal? = null,
  val createdAt: String,
  val updatedAt: String,
) {
  @Serializable
  public data class ForkedFrom(
    val providerThreadId: String,
    val providerTurnId: String? = null,
    val checkpointId: String? = null,
  )
}

@Serializable
public data class ProviderThreadNativeMetadata(
  val modelSelection: ModelSelection? = null,
  val title: String? = null,
  val updatedAt: String? = null,
  val itemIdentityVersion: Long? = null,
)

@Serializable
public data class ProviderTurn(
  val id: String,
  val providerThreadId: String,
  val nodeId: String,
  val runAttemptId: String?,
  val nativeTurnRef: ProviderRef?,
  val ordinal: Long,
  val status: String,
  val startedAt: String?,
  val completedAt: String?,
  val tokenUsage: ProviderTurnTokenUsage? = null,
  val turnTokenUsage: TurnTokenUsage? = null,
)

@Serializable
public data class ProviderTurnTokenUsage(
  val usedTokens: Long,
  val maxTokens: Long? = null,
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningOutputTokens: Long? = null,
  val promptCacheTtlMs: Long? = null,
  val updatedAt: String,
)

@Serializable
public data class RemoteTaskChild(
  val environmentId: String,
  val threadId: String,
  val label: String,
  val runId: String? = null,
  val stoppedThere: Boolean? = null,
  val stopCount: Long? = null,
)

@Serializable
public data class RestartCancelledBackgroundWork(
  val kind: String,
  val label: String,
  val id: String? = null,
)

@Serializable
public data class RunAttempt(
  val id: String,
  val nativeThreadId: String? = null,
  val runId: String,
  val attemptOrdinal: Long,
  val rootNodeId: String,
  val providerInstanceId: String,
  val providerThreadId: String,
  val providerTurnId: String?,
  val reason: String,
  val status: String,
  val startedAt: String?,
  val completedAt: String?,
)

@Serializable
public data class RunBackgroundWorkCancelled(
  val runId: String,
  val restartCancelledBackgroundWork: List<RestartCancelledBackgroundWork>,
)

@Serializable
public data class RuntimePolicyCapabilities(
  val enforcement: String,
)

@Serializable
public data class SessionCapabilities(
  val supportsMultipleProviderThreadsPerSession: Boolean,
  val supportsModelSwitchInSession: Boolean,
  val supportsProviderSwitchingViaHandoff: Boolean,
  val supportsRuntimeModeSwitchInSession: Boolean,
  val pendingRequestsSurviveRestart: Boolean,
)

@Serializable
public data class ShellSnapshot(
  val schemaVersion: Long,
  val snapshotSequence: Long,
  val threads: List<ThreadShell>,
  val archivedThreads: List<ThreadShell>,
  val projects: List<ProjectShell>,
)

@Serializable
public data class StreamingCapabilities(
  val streamsAssistantText: Boolean,
  val streamsReasoning: Boolean,
  val streamsToolOutput: Boolean,
  val streamsPlanText: Boolean,
  val emitsMessageCompleted: Boolean,
)

@Serializable
public data class Subagent(
  val id: String,
  val threadId: String,
  val runId: String?,
  val parentNodeId: String,
  val origin: String,
  val createdBy: String,
  val driver: String,
  val providerInstanceId: String,
  val providerThreadId: String?,
  val childThreadId: String?,
  val remoteChild: RemoteTaskChild? = null,
  val nativeTaskRef: ProviderRef?,
  val prompt: String,
  val title: String?,
  val model: String?,
  val modelSelection: ModelSelection? = null,
  val completionWake: String? = null,
  val completionDelivery: DelegatedCompletionTaskDelivery? = null,
  val status: String,
  val progress: String? = null,
  val workflow: SubagentWorkflow? = null,
  val result: String?,
  val startedAt: String?,
  val completedAt: String?,
  val updatedAt: String,
)

@Serializable
public data class SubagentCapabilities(
  val supportsSubagents: Boolean,
  val exposesSubagentThreadIds: Boolean,
  val emitsSubagentLifecycle: Boolean,
  val canWaitForSubagents: Boolean,
  val canCloseSubagents: Boolean,
  val canForkSubagentThread: Boolean,
)

@Serializable
public data class SubagentWorkflow(
  val name: String? = null,
  val runHandles: WorkflowRunHandles? = null,
  val phases: List<TaskWorkflowPhase>,
  val agents: List<WorkflowAgent>,
  val totalTokens: Long? = null,
  val toolCalls: Long? = null,
  val durationMs: Long? = null,
)

@Serializable
public data class ThreadCapabilities(
  val canCreateEmptyThread: Boolean,
  val canReadThreadSnapshot: Boolean,
  val canRollbackThread: Boolean,
  val canForkThread: Boolean,
  val canForkFromTurn: Boolean,
  val canForkFromSubagentThread: Boolean,
  val exposesNativeThreadId: Boolean,
)

@Serializable(with = ThreadLaunchWorkspaceStrategySerializer::class)
public sealed interface ThreadLaunchWorkspaceStrategy {
  public val type: String
  public val branch: String?

  @Serializable
  public data class Root(
    override val type: String = "root",
    override val branch: String? = null,
  ) : ThreadLaunchWorkspaceStrategy

  @Serializable
  public data class ExistingWorktree(
    override val type: String = "existing_worktree",
    val worktreePath: String,
    override val branch: String? = null,
  ) : ThreadLaunchWorkspaceStrategy

  @Serializable
  public data class Worktree(
    override val type: String = "worktree",
    val baseRef: String,
    override val branch: String? = null,
    val startFromOrigin: Boolean? = null,
  ) : ThreadLaunchWorkspaceStrategy

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownThreadLaunchWorkspaceStrategySerializer::class)
  public data class Unknown(
    override val type: String,
    override val branch: String?,
    val raw: JsonObject,
  ) : ThreadLaunchWorkspaceStrategy
}

public object ThreadLaunchWorkspaceStrategySerializer : KSerializer<ThreadLaunchWorkspaceStrategy> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ThreadLaunchWorkspaceStrategy> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "root" -> ThreadLaunchWorkspaceStrategy.Root.serializer()
      "existing_worktree" -> ThreadLaunchWorkspaceStrategy.ExistingWorktree.serializer()
      "worktree" -> ThreadLaunchWorkspaceStrategy.Worktree.serializer()
      else -> ThreadLaunchWorkspaceStrategy.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ThreadLaunchWorkspaceStrategy {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ThreadLaunchWorkspaceStrategy.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ThreadLaunchWorkspaceStrategy) {
    when (value) {
      is ThreadLaunchWorkspaceStrategy.Root ->
        ThreadLaunchWorkspaceStrategy.Root.serializer().serialize(encoder, value)
      is ThreadLaunchWorkspaceStrategy.ExistingWorktree ->
        ThreadLaunchWorkspaceStrategy.ExistingWorktree.serializer().serialize(encoder, value)
      is ThreadLaunchWorkspaceStrategy.Worktree ->
        ThreadLaunchWorkspaceStrategy.Worktree.serializer().serialize(encoder, value)
      is ThreadLaunchWorkspaceStrategy.Unknown ->
        ThreadLaunchWorkspaceStrategy.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownThreadLaunchWorkspaceStrategySerializer :
  KSerializer<ThreadLaunchWorkspaceStrategy.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ThreadLaunchWorkspaceStrategy.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ThreadLaunchWorkspaceStrategy.Unknown(
      type = string("type") ?: "unknown",
      branch = string("branch"),
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ThreadLaunchWorkspaceStrategy.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class ToolCapabilities(
  val exposesToolItemIds: Boolean,
  val emitsToolStarted: Boolean,
  val emitsToolCompleted: Boolean,
  val emitsToolOutput: Boolean,
  val supportsMcpTools: Boolean,
  val supportsDynamicToolCallbacks: Boolean,
)

@Serializable
public data class TurnCapabilities(
  val exposesNativeTurnId: Boolean,
  val emitsTurnStarted: Boolean,
  val emitsTurnCompleted: Boolean,
  val supportsInterrupt: Boolean,
  val supportsActiveSteering: Boolean,
  val activeSteeringInterruptsTools: Boolean? = null,
  val supportsSteeringByInterruptRestart: Boolean,
  val supportsQueuedMessages: Boolean,
  val terminalStatusQuality: String,
)

@Serializable
public data class UserInputQuestion(
  val id: String,
  val header: String,
  val question: String,
  val options: List<UserInputQuestion.Option>,
  val multiSelect: Boolean? = null,
  val allowCustomAnswer: Boolean? = null,
  val initialAnswer: String? = null,
  val required: Boolean? = null,
) {
  @Serializable
  public data class Option(
    val label: String,
    val description: String,
    val value: String? = null,
  )
}

@Serializable
public data class WebSearchResult(
  val title: String? = null,
  val url: String? = null,
  val snippet: String? = null,
)

@Serializable
public data class WorkflowAgent(
  val index: Long,
  val label: String,
  val agentId: String? = null,
  val childThreadId: String? = null,
  val state: String,
  val phaseIndex: Long? = null,
  val phaseTitle: String? = null,
  val model: String? = null,
  val attempt: Long? = null,
  val totalTokens: Long? = null,
  val toolCalls: Long? = null,
  val durationMs: Long? = null,
  val queuedAt: Long? = null,
  val startedAt: Long? = null,
  val prompt: String? = null,
  val result: String? = null,
)

@Serializable
public data class WorkflowRunHandles(
  val runId: String? = null,
  val transcriptDir: String? = null,
  val scriptPath: String? = null,
)

@Serializable
public data class PastedTextAttachmentSource(
  @SerialName("_tag") val tag: String,
)

@Serializable
public data class ProjectScript(
  val id: String,
  val name: String,
  val command: String,
  val icon: String,
  val runOnWorktreeCreate: Boolean,
  val runOnSettle: Boolean? = null,
  val async: Boolean? = null,
  val previewUrl: String? = null,
  val autoOpenPreview: Boolean? = null,
)

@Serializable
public data class ProviderApprovalOption(
  val decision: String,
  val label: String,
  val warning: String? = null,
)

@Serializable
public data class PullRequestActor(
  val isBot: Boolean? = null,
  val login: String,
  val name: String?,
  val avatarUrl: String?,
)

@Serializable
public data class RepositoryIdentity(
  val canonicalKey: String,
  val locator: RepositoryIdentityLocator,
  val webUrl: String? = null,
  val rootPath: String? = null,
  val displayName: String? = null,
  val provider: String? = null,
  val owner: String? = null,
  val name: String? = null,
  val origin: RepositoryOrigin? = null,
)

@Serializable
public data class RepositoryIdentityLocator(
  val source: String,
  val remoteName: String,
  val remoteUrl: String,
)

@Serializable
public data class RepositoryOrigin(
  val canonicalKey: String,
  val displayName: String? = null,
)

@Serializable
public data class ServerAuthDescriptor(
  val policy: String,
  val bootstrapMethods: List<String>,
  val sessionMethods: List<String>,
  val sessionCookieName: String,
  val serverUpdateScope: String? = null,
)

@Serializable(with = SnapShotAccessibilitySerializer::class)
public sealed interface SnapShotAccessibility {
  public val format: String
  public val truncated: Boolean

  @Serializable
  public data class FlatText(
    override val format: String = "flat-text",
    val text: String,
    override val truncated: Boolean,
  ) : SnapShotAccessibility

  @Serializable
  public data class ElementTree(
    override val format: String = "element-tree",
    val coordinateSpace: String,
    val imageSize: AssetImageDimensions,
    override val truncated: Boolean,
    val root: SnapShotAccessibilityNode,
  ) : SnapShotAccessibility

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownSnapShotAccessibilitySerializer::class)
  public data class Unknown(
    override val format: String,
    override val truncated: Boolean,
    val raw: JsonObject,
  ) : SnapShotAccessibility
}

public object SnapShotAccessibilitySerializer : KSerializer<SnapShotAccessibility> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<SnapShotAccessibility> =
    when (element["format"]?.jsonPrimitive?.contentOrNull) {
      "flat-text" -> SnapShotAccessibility.FlatText.serializer()
      "element-tree" -> SnapShotAccessibility.ElementTree.serializer()
      else -> SnapShotAccessibility.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): SnapShotAccessibility {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(SnapShotAccessibility.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: SnapShotAccessibility) {
    when (value) {
      is SnapShotAccessibility.FlatText ->
        SnapShotAccessibility.FlatText.serializer().serialize(encoder, value)
      is SnapShotAccessibility.ElementTree ->
        SnapShotAccessibility.ElementTree.serializer().serialize(encoder, value)
      is SnapShotAccessibility.Unknown ->
        SnapShotAccessibility.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownSnapShotAccessibilitySerializer : KSerializer<SnapShotAccessibility.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): SnapShotAccessibility.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    fun boolean(key: String): Boolean? = raw[key]?.jsonPrimitive?.booleanOrNull
    return SnapShotAccessibility.Unknown(
      format = string("format") ?: "unknown",
      truncated = boolean("truncated") ?: false,
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: SnapShotAccessibility.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class SnapShotAccessibilityNode(
  val role: String,
  val name: String? = null,
  val value: String? = null,
  val description: String? = null,
  val bounds: SnapShotAccessibilityNode.Bounds?,
  val state: SnapShotAccessibilityNode.State? = null,
  val actions: List<String> = emptyList(),
  val children: List<SnapShotAccessibilityNode>,
) {
  @Serializable
  public data class Bounds(
    val x: Long,
    val y: Long,
    val width: Long,
    val height: Long,
  )

  @Serializable
  public data class State(
    val active: Boolean? = null,
    val busy: Boolean? = null,
    val checked: String? = null,
    val editable: Boolean? = null,
    val enabled: Boolean? = null,
    val expanded: Boolean? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null,
    val visible: Boolean? = null,
  )
}

@Serializable
public data class SnapShotSource(
  val kind: String,
  val capturedAt: String,
  val appName: String,
  val windowTitle: String,
  val accessibleText: String? = null,
  val accessibility: SnapShotAccessibility? = null,
  val appIdentifier: String? = null,
  val appIconDataUrl: String? = null,
)

@Serializable(with = StoredProjectIconSerializer::class)
public sealed interface StoredProjectIcon {
  public val kind: String

  @Serializable
  public data class Lucide(
    override val kind: String = "lucide",
    val name: String,
    val color: String,
    val monogramText: String? = null,
    val monogram: String? = null,
  ) : StoredProjectIcon

  @Serializable
  public data class Emoji(
    override val kind: String = "emoji",
    val emoji: String,
  ) : StoredProjectIcon

  @Serializable
  public data class Monogram(
    override val kind: String = "monogram",
    val text: String,
    val color: String,
  ) : StoredProjectIcon

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownStoredProjectIconSerializer::class)
  public data class Unknown(
    override val kind: String,
    val raw: JsonObject,
  ) : StoredProjectIcon
}

public object StoredProjectIconSerializer : KSerializer<StoredProjectIcon> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<StoredProjectIcon> =
    when (element["kind"]?.jsonPrimitive?.contentOrNull) {
      "lucide" -> StoredProjectIcon.Lucide.serializer()
      "emoji" -> StoredProjectIcon.Emoji.serializer()
      "monogram" -> StoredProjectIcon.Monogram.serializer()
      else -> StoredProjectIcon.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): StoredProjectIcon {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(StoredProjectIcon.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: StoredProjectIcon) {
    when (value) {
      is StoredProjectIcon.Lucide -> StoredProjectIcon.Lucide.serializer().serialize(encoder, value)
      is StoredProjectIcon.Emoji -> StoredProjectIcon.Emoji.serializer().serialize(encoder, value)
      is StoredProjectIcon.Monogram ->
        StoredProjectIcon.Monogram.serializer().serialize(encoder, value)
      is StoredProjectIcon.Unknown ->
        StoredProjectIcon.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownStoredProjectIconSerializer : KSerializer<StoredProjectIcon.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): StoredProjectIcon.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return StoredProjectIcon.Unknown(
      kind = string("kind") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: StoredProjectIcon.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class TaskWorkflowPhase(
  val index: Long,
  val title: String,
)

@Serializable
public data class ThreadLinkedPullRequest(
  val projectId: String,
  val repository: String,
  val number: Long,
  val url: String,
)

@Serializable
public data class ThreadPullRequestLink(
  val host: String,
  val repository: String,
  val number: Long,
  val url: String,
  val source: String,
  val linkedAt: String,
  val snapshot: ThreadPullRequestSnapshot?,
  val stack: ThreadPullRequestStack?,
  val watch: ThreadPullRequestWatch? = null,
)

@Serializable
public data class ThreadPullRequestSnapshot(
  val state: String,
  val title: String,
  val headBranch: String,
  val baseBranch: String,
  val isDraft: Boolean,
  val updatedAt: String?,
  val syncedAt: String,
  val closedAt: String? = null,
  val mergedAt: String? = null,
  val author: PullRequestActor? = null,
  val additions: Long? = null,
  val deletions: Long? = null,
  val changedFiles: Long? = null,
  val reviewDecision: String? = null,
  val checksState: String? = null,
  val mergeability: String? = null,
)

@Serializable
public data class ThreadPullRequestStack(
  val kind: String,
  val id: String,
  val number: Long,
  val url: String,
  val base: String,
  val layers: List<ThreadPullRequestStackLayer>,
)

@Serializable
public data class ThreadPullRequestStackLayer(
  val number: Long,
  val headBranch: String,
  val state: String,
)

@Serializable
public data class ThreadPullRequestWatch(
  val startedAt: String,
  val headSha: String?,
  val failedChecks: List<String>,
  val passed: Boolean,
  val passedChecks: List<String>? = null,
  val remarksThrough: String,
  val remarkIds: List<String>,
  val conflicting: Boolean,
  val wakes: Long,
)

@Serializable
public data class ThreadTitleRegeneration(
  val requestId: String,
  val startedAt: String,
)

@Serializable
public data class ThreadTokenUsageSnapshot(
  val usedTokens: Long,
  val totalProcessedTokens: Long? = null,
  val maxTokens: Long? = null,
  val inputTokens: Long? = null,
  val cachedInputTokens: Long? = null,
  val outputTokens: Long? = null,
  val reasoningOutputTokens: Long? = null,
  val lastUsedTokens: Long? = null,
  val lastInputTokens: Long? = null,
  val lastCachedInputTokens: Long? = null,
  val lastOutputTokens: Long? = null,
  val lastReasoningOutputTokens: Long? = null,
  val toolUses: Long? = null,
  val durationMs: Long? = null,
  val compactsAutomatically: Boolean? = null,
  val autoCompactThreshold: Long? = null,
  val cost: ThreadTokenUsageSnapshot.Cost? = null,
) {
  @Serializable
  public data class Cost(
    val amount: Double,
    val currency: String,
  )
}

@Serializable
public data class ThreadTurnDiff(
  val fromTurnCount: Long,
  val toTurnCount: Long,
  val threadId: String,
  val diff: String,
)

@Serializable(with = ToolActivityIconSerializer::class)
public sealed interface ToolActivityIcon {
  public val tag: String

  @Serializable
  public data class Website(
    @SerialName("_tag") override val tag: String = "website",
    val pageUrl: String,
    val faviconUrl: String? = null,
    val faviconUrlDark: String? = null,
  ) : ToolActivityIcon

  @Serializable
  public data class NativeApp(
    @SerialName("_tag") override val tag: String = "native-app",
    val app: ToolActivityNativeAppReference,
  ) : ToolActivityIcon

  @Serializable
  public data class ThemedLogo(
    @SerialName("_tag") override val tag: String = "themed-logo",
    val logoUrl: String,
    val logoUrlDark: String? = null,
  ) : ToolActivityIcon

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownToolActivityIconSerializer::class)
  public data class Unknown(
    override val tag: String,
    val raw: JsonObject,
  ) : ToolActivityIcon
}

public object ToolActivityIconSerializer : KSerializer<ToolActivityIcon> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ToolActivityIcon> =
    when (element["_tag"]?.jsonPrimitive?.contentOrNull) {
      "website" -> ToolActivityIcon.Website.serializer()
      "native-app" -> ToolActivityIcon.NativeApp.serializer()
      "themed-logo" -> ToolActivityIcon.ThemedLogo.serializer()
      else -> ToolActivityIcon.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ToolActivityIcon {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ToolActivityIcon.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ToolActivityIcon) {
    when (value) {
      is ToolActivityIcon.Website -> ToolActivityIcon.Website.serializer().serialize(encoder, value)
      is ToolActivityIcon.NativeApp ->
        ToolActivityIcon.NativeApp.serializer().serialize(encoder, value)
      is ToolActivityIcon.ThemedLogo ->
        ToolActivityIcon.ThemedLogo.serializer().serialize(encoder, value)
      is ToolActivityIcon.Unknown -> ToolActivityIcon.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownToolActivityIconSerializer : KSerializer<ToolActivityIcon.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ToolActivityIcon.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ToolActivityIcon.Unknown(
      tag = string("_tag") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ToolActivityIcon.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable(with = ToolActivityNativeAppReferenceSerializer::class)
public sealed interface ToolActivityNativeAppReference {
  public val tag: String

  @Serializable
  public data class AppId(
    @SerialName("_tag") override val tag: String = "app-id",
    val appId: String,
  ) : ToolActivityNativeAppReference

  @Serializable
  public data class DisplayName(
    @SerialName("_tag") override val tag: String = "display-name",
    val displayName: String,
  ) : ToolActivityNativeAppReference

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownToolActivityNativeAppReferenceSerializer::class)
  public data class Unknown(
    override val tag: String,
    val raw: JsonObject,
  ) : ToolActivityNativeAppReference
}

public object ToolActivityNativeAppReferenceSerializer :
  KSerializer<ToolActivityNativeAppReference> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ToolActivityNativeAppReference> =
    when (element["_tag"]?.jsonPrimitive?.contentOrNull) {
      "app-id" -> ToolActivityNativeAppReference.AppId.serializer()
      "display-name" -> ToolActivityNativeAppReference.DisplayName.serializer()
      else -> ToolActivityNativeAppReference.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ToolActivityNativeAppReference {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ToolActivityNativeAppReference.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ToolActivityNativeAppReference) {
    when (value) {
      is ToolActivityNativeAppReference.AppId ->
        ToolActivityNativeAppReference.AppId.serializer().serialize(encoder, value)
      is ToolActivityNativeAppReference.DisplayName ->
        ToolActivityNativeAppReference.DisplayName.serializer().serialize(encoder, value)
      is ToolActivityNativeAppReference.Unknown ->
        ToolActivityNativeAppReference.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownToolActivityNativeAppReferenceSerializer :
  KSerializer<ToolActivityNativeAppReference.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ToolActivityNativeAppReference.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ToolActivityNativeAppReference.Unknown(
      tag = string("_tag") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ToolActivityNativeAppReference.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class ToolActivitySource(
  val key: String,
  val name: String,
  val kind: String,
  val icon: ToolActivityIcon? = null,
)

@Serializable(with = TurnTokenUsageSerializer::class)
public sealed interface TurnTokenUsage {
  public val usageScope: String
  public val cachedInputTokens: Long?
  public val cacheCreationTokens: Long?
  public val reasoningTokens: Long?
  public val hasSubagents: Boolean
  public val usageStatus: String
  public val inputTokens: Long
  public val outputTokens: Long

  @Serializable
  public data class MainAgent(
    override val usageScope: String = "main_agent",
    override val cachedInputTokens: Long? = null,
    override val cacheCreationTokens: Long? = null,
    override val reasoningTokens: Long? = null,
    override val hasSubagents: Boolean,
    override val usageStatus: String,
    override val inputTokens: Long,
    override val outputTokens: Long,
  ) : TurnTokenUsage

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownTurnTokenUsageSerializer::class)
  public data class Unknown(
    override val usageScope: String,
    override val cachedInputTokens: Long?,
    override val cacheCreationTokens: Long?,
    override val reasoningTokens: Long?,
    override val hasSubagents: Boolean,
    override val usageStatus: String,
    override val inputTokens: Long,
    override val outputTokens: Long,
    val raw: JsonObject,
  ) : TurnTokenUsage
}

public object TurnTokenUsageSerializer : KSerializer<TurnTokenUsage> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<TurnTokenUsage> =
    when (element["usageScope"]?.jsonPrimitive?.contentOrNull) {
      "main_agent" -> TurnTokenUsage.MainAgent.serializer()
      else -> TurnTokenUsage.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): TurnTokenUsage {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(TurnTokenUsage.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: TurnTokenUsage) {
    when (value) {
      is TurnTokenUsage.MainAgent -> TurnTokenUsage.MainAgent.serializer().serialize(encoder, value)
      is TurnTokenUsage.Unknown -> TurnTokenUsage.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownTurnTokenUsageSerializer : KSerializer<TurnTokenUsage.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): TurnTokenUsage.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String): Long? = raw[key]?.jsonPrimitive?.longOrNull
    fun boolean(key: String): Boolean? = raw[key]?.jsonPrimitive?.booleanOrNull
    return TurnTokenUsage.Unknown(
      usageScope = string("usageScope") ?: "unknown",
      cachedInputTokens = long("cachedInputTokens"),
      cacheCreationTokens = long("cacheCreationTokens"),
      reasoningTokens = long("reasoningTokens"),
      hasSubagents = boolean("hasSubagents") ?: false,
      usageStatus = string("usageStatus") ?: "",
      inputTokens = long("inputTokens") ?: 0L,
      outputTokens = long("outputTokens") ?: 0L,
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: TurnTokenUsage.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}

@Serializable
public data class UserInputAttachmentAnswerPayload(
  val requestId: String,
  val questionTextById: Map<String, String>? = null,
  // TODO(contracts): object with no declared properties
  val answers: JsonObject,
  val attachmentsByQuestionId:
      Map<String, List<UserInputAttachmentAnswerPayload.AttachmentsByQuestionIdItem>>,
) {
  @Serializable(with = AttachmentsByQuestionIdItem.AttachmentsByQuestionIdItemSerializer::class)
  public sealed interface AttachmentsByQuestionIdItem {
    public val type: String
    public val id: String
    public val name: String
    public val mimeType: String
    public val sizeBytes: Long

    /** A variant this build doesn't know. [raw] is the whole object as received. */
    @Serializable(
      with = AttachmentsByQuestionIdItem.UnknownAttachmentsByQuestionIdItemSerializer::class,
    )
    public data class Unknown(
      override val type: String,
      override val id: String,
      override val name: String,
      override val mimeType: String,
      override val sizeBytes: Long,
      val raw: JsonObject,
    ) : AttachmentsByQuestionIdItem

    public object AttachmentsByQuestionIdItemSerializer : KSerializer<AttachmentsByQuestionIdItem> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      private fun select(
        element: JsonObject,
      ): DeserializationStrategy<AttachmentsByQuestionIdItem> =
        when (element["type"]?.jsonPrimitive?.contentOrNull) {
          "image" -> ChatImageAttachment.serializer()
          "file" -> ChatFileAttachment.serializer()
          else -> AttachmentsByQuestionIdItem.Unknown.serializer()
        }

      override fun deserialize(decoder: Decoder): AttachmentsByQuestionIdItem {
        val input: JsonDecoder = decoder as JsonDecoder
        val element: JsonObject = input.decodeJsonElement().jsonObject
        return try {
          input.json.decodeFromJsonElement(select(element), element)
        } catch (_: IllegalArgumentException) {
          input.json.decodeFromJsonElement(
            AttachmentsByQuestionIdItem.Unknown.serializer(),
            element,
          )
        }
      }

      override fun serialize(encoder: Encoder, value: AttachmentsByQuestionIdItem) {
        when (value) {
          is ChatImageAttachment -> ChatImageAttachment.serializer().serialize(encoder, value)
          is ChatFileAttachment -> ChatFileAttachment.serializer().serialize(encoder, value)
          is AttachmentsByQuestionIdItem.Unknown ->
            AttachmentsByQuestionIdItem.Unknown.serializer().serialize(encoder, value)
        }
      }
    }

    public object UnknownAttachmentsByQuestionIdItemSerializer :
      KSerializer<AttachmentsByQuestionIdItem.Unknown> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      override fun deserialize(decoder: Decoder): AttachmentsByQuestionIdItem.Unknown {
        val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
        fun long(key: String): Long? = raw[key]?.jsonPrimitive?.longOrNull
        return AttachmentsByQuestionIdItem.Unknown(
          type = string("type") ?: "unknown",
          id = string("id") ?: "",
          name = string("name") ?: "",
          mimeType = string("mimeType") ?: "",
          sizeBytes = long("sizeBytes") ?: 0L,
          raw = raw,
        )
      }

      override fun serialize(encoder: Encoder, value: AttachmentsByQuestionIdItem.Unknown) {
        (encoder as JsonEncoder).encodeJsonElement(value.raw)
      }
    }
  }
}
