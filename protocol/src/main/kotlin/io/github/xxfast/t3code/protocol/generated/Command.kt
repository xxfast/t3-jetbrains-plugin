// Generated from v0.1.2 by contracts/generate.ts, do not edit.

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
import kotlinx.serialization.json.longOrNull

@Serializable(with = CommandSerializer::class)
public sealed interface Command {
  public val type: String
  public val commandId: String

  @Serializable
  public data class ThreadStop(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.stop",
    override val commandId: String,
    val threadId: String,
    val reason: String? = null,
  ) : Command

  @Serializable
  public data class ThreadCreate(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.create",
    val createdBy: String,
    val creationSource: String,
    override val commandId: String,
    val threadId: String,
    val projectId: String,
    val title: String,
    val modelSelection: ModelSelection,
    val runtimeMode: String,
    val interactionMode: String,
    val branch: String?,
    val worktreePath: String?,
    val importedNativeThread: Command.ThreadCreate.ImportedNativeThread? = null,
  ) : Command {
    @Serializable
    public data class ImportedNativeThread(
      val ref: Command.ThreadCreate.ImportedNativeThread.Ref,
      val metadata: ProviderThreadNativeMetadata? = null,
    ) {
      @Serializable
      public data class Ref(
        val driver: String,
        val nativeId: String,
        val strength: String,
      )
    }
  }

  @Serializable
  public data class ThreadArchive(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.archive",
    override val commandId: String,
    val threadId: String,
  ) : Command

  @Serializable
  public data class ThreadUnarchive(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.unarchive",
    override val commandId: String,
    val threadId: String,
  ) : Command

  @Serializable
  public data class ThreadDelete(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.delete",
    override val commandId: String,
    val threadId: String,
  ) : Command

  @Serializable
  public data class ThreadSettle(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.settle",
    override val commandId: String,
    val threadId: String,
    val settledAt: String? = null,
  ) : Command

  @Serializable
  public data class ThreadAutoSettle(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.auto-settle",
    override val commandId: String,
    val threadId: String,
    val snapshotAt: String,
    val settledAt: String? = null,
  ) : Command

  @Serializable
  public data class ThreadUnsettle(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.unsettle",
    override val commandId: String,
    val threadId: String,
    val reason: String,
  ) : Command

  @Serializable
  public data class ThreadSnooze(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.snooze",
    override val commandId: String,
    val threadId: String,
    val snoozedUntil: String,
  ) : Command

  @Serializable
  public data class ThreadUnsnooze(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.unsnooze",
    override val commandId: String,
    val threadId: String,
    val reason: String,
  ) : Command

  @Serializable
  public data class ThreadAutoSettleSet(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.auto-settle.set",
    override val commandId: String,
    val threadId: String,
    val enabled: Boolean,
  ) : Command

  @Serializable
  public data class ThreadPin(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.pin",
    override val commandId: String,
    val threadId: String,
    val orderKey: String? = null,
  ) : Command

  @Serializable
  public data class ThreadUnpin(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.unpin",
    override val commandId: String,
    val threadId: String,
  ) : Command

  @Serializable
  public data class ThreadPinReorder(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.pin.reorder",
    override val commandId: String,
    val threadId: String,
    val orderKey: String,
  ) : Command

  @Serializable
  public data class ThreadActiveReorder(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.active.reorder",
    override val commandId: String,
    val threadId: String,
    val orderKey: String,
  ) : Command

  @Serializable
  public data class ThreadVisit(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.visit",
    override val commandId: String,
    val threadId: String,
    val visitedAt: String,
  ) : Command

  @Serializable
  public data class ThreadMarkUnread(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.mark-unread",
    override val commandId: String,
    val threadId: String,
  ) : Command

  @Serializable
  public data class ThreadMetadataUpdate(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.metadata.update",
    override val commandId: String,
    val threadId: String,
    val title: String? = null,
    val regenerateTitle: Boolean? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
    val expectedWorktreePath: String? = null,
    val expectedBranch: String? = null,
    val expectedEmpty: Boolean? = null,
    val limitRecovery: LimitRecoveryUpdate? = null,
    val linkedPullRequest: ThreadLinkedPullRequest? = null,
  ) : Command

  @Serializable
  public data class CommandThreadPullRequestLink(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.pull-request.link",
    override val commandId: String,
    val threadId: String,
    val host: String,
    val repository: String,
    val number: Long,
    val url: String,
    val source: String,
    val repositoryWebUrl: String? = null,
  ) : Command

  @Serializable
  public data class ThreadPullRequestUnlink(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.pull-request.unlink",
    override val commandId: String,
    val threadId: String,
    val host: String,
    val repository: String,
    val number: Long,
  ) : Command

  @Serializable
  public data class ThreadPullRequestLinkSync(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.pull-request-link.sync",
    override val commandId: String,
    val threadId: String,
    val host: String,
    val repository: String,
    val number: Long,
    val snapshot: ThreadPullRequestSnapshot,
    val stack: ThreadPullRequestStack?,
  ) : Command

  @Serializable
  public data class CommandThreadPullRequestWatch(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.pull-request.watch",
    override val commandId: String,
    val threadId: String,
    val host: String,
    val repository: String,
    val number: Long,
    val watching: Boolean,
    val link: Command.CommandThreadPullRequestWatch.Link? = null,
  ) : Command {
    @Serializable
    public data class Link(
      val url: String,
      val source: String,
      val repositoryWebUrl: String? = null,
    )
  }

  @Serializable
  public data class ThreadPullRequestSync(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.pull-request.sync",
    override val commandId: String,
    val threadId: String,
    val projectId: String,
    val snapshotSequence: Long,
    val expected: Command.ThreadPullRequestSync.Expected,
    val branchPullRequest: ThreadLinkedPullRequest?,
    val linkedPullRequest: ThreadLinkedPullRequest? = null,
  ) : Command {
    @Serializable
    public data class Expected(
      val workspaceRoot: String,
      val branch: String?,
      val worktreePath: String?,
      val linkedPullRequest: ThreadLinkedPullRequest?,
      val branchPullRequest: ThreadLinkedPullRequest?,
    )
  }

  @Serializable
  public data class ThreadTitleRegenerationComplete(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.title.regeneration.complete",
    override val commandId: String,
    val threadId: String,
    val requestId: String,
    val title: String? = null,
  ) : Command

  @Serializable
  public data class ThreadRuntimeModeSet(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.runtime-mode.set",
    override val commandId: String,
    val threadId: String,
    val runtimeMode: String,
  ) : Command

  @Serializable
  public data class ThreadInteractionModeSet(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.interaction-mode.set",
    override val commandId: String,
    val threadId: String,
    val interactionMode: String,
  ) : Command

  @Serializable
  public data class ThreadModelSelectionSet(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.model-selection.set",
    override val commandId: String,
    val threadId: String,
    val modelSelection: ModelSelection,
  ) : Command

  @Serializable
  public data class ProviderSessionDetach(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "provider-session.detach",
    override val commandId: String,
    val threadId: String,
    val providerSessionId: String,
    val reason: String? = null,
  ) : Command

  @Serializable
  public data class MessageDispatch(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "message.dispatch",
    val notification: Notification? = null,
    val createdBy: String,
    val creationSource: String,
    val scheduledTaskId: String? = null,
    val senderThreadId: String? = null,
    override val commandId: String,
    val threadId: String,
    val messageId: String,
    val text: String,
    val context: MessageContext? = null,
    val attachments: List<ChatAttachment>,
    val titleSeed: String? = null,
    val modelSelection: ModelSelection? = null,
    val sourcePlanRef: Command.MessageDispatch.SourcePlanRef? = null,
    val restartContinuationOfRunId: String? = null,
    val usageLimitContinuationOfRunId: String? = null,
    val manualContinuationOfRunId: String? = null,
    val usageLimitRecoveryRequestId: String? = null,
    val deliveryIntent: String? = null,
    val delegatedCompletion: Command.MessageDispatch.DelegatedCompletion? = null,
    val dispatchMode: Command.MessageDispatch.DispatchMode,
  ) : Command {
    @Serializable
    public data class SourcePlanRef(
      val threadId: String,
      val planId: String,
    )

    @Serializable
    public data class DelegatedCompletion(
      val parentRunId: String,
      val generation: Long,
      val taskIds: List<String>,
    )

    @Serializable(with = DispatchMode.DispatchModeSerializer::class)
    public sealed interface DispatchMode {
      public val type: String

      @Serializable
      public data class DeferStart(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "defer_start",
        val workspaceStrategy: ThreadLaunchWorkspaceStrategy? = null,
      ) : Command.MessageDispatch.DispatchMode

      @Serializable
      public data class SteerActive(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "steer_active",
        val targetRunId: String,
      ) : Command.MessageDispatch.DispatchMode

      @Serializable
      public data class RestartActive(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "restart_active",
        val targetRunId: String,
      ) : Command.MessageDispatch.DispatchMode

      @Serializable
      public data class QueueAfterActive(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "queue_after_active",
      ) : Command.MessageDispatch.DispatchMode

      @Serializable
      public data class StartImmediately(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "start_immediately",
      ) : Command.MessageDispatch.DispatchMode

      /** A variant this build doesn't know. [raw] is the whole object as received. */
      @Serializable(with = DispatchMode.UnknownDispatchModeSerializer::class)
      public data class Unknown(
        override val type: String,
        val raw: JsonObject,
      ) : DispatchMode

      public object DispatchModeSerializer : KSerializer<DispatchMode> {
        override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

        private fun select(element: JsonObject): DeserializationStrategy<DispatchMode> =
          when (element["type"]?.jsonPrimitive?.contentOrNull) {
            "defer_start" -> DispatchMode.DeferStart.serializer()
            "steer_active" -> DispatchMode.SteerActive.serializer()
            "restart_active" -> DispatchMode.RestartActive.serializer()
            "queue_after_active" -> DispatchMode.QueueAfterActive.serializer()
            "start_immediately" -> DispatchMode.StartImmediately.serializer()
            else -> DispatchMode.Unknown.serializer()
          }

        override fun deserialize(decoder: Decoder): DispatchMode {
          val input: JsonDecoder = decoder as JsonDecoder
          val element: JsonObject = input.decodeJsonElement().jsonObject
          return try {
            input.json.decodeFromJsonElement(select(element), element)
          } catch (_: IllegalArgumentException) {
            input.json.decodeFromJsonElement(DispatchMode.Unknown.serializer(), element)
          }
        }

        override fun serialize(encoder: Encoder, value: DispatchMode) {
          when (value) {
            is DispatchMode.DeferStart ->
              DispatchMode.DeferStart.serializer().serialize(encoder, value)
            is DispatchMode.SteerActive ->
              DispatchMode.SteerActive.serializer().serialize(encoder, value)
            is DispatchMode.RestartActive ->
              DispatchMode.RestartActive.serializer().serialize(encoder, value)
            is DispatchMode.QueueAfterActive ->
              DispatchMode.QueueAfterActive.serializer().serialize(encoder, value)
            is DispatchMode.StartImmediately ->
              DispatchMode.StartImmediately.serializer().serialize(encoder, value)
            is DispatchMode.Unknown -> DispatchMode.Unknown.serializer().serialize(encoder, value)
          }
        }
      }

      public object UnknownDispatchModeSerializer : KSerializer<DispatchMode.Unknown> {
        override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

        override fun deserialize(decoder: Decoder): DispatchMode.Unknown {
          val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
          fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
          return DispatchMode.Unknown(
            type = string("type") ?: "unknown",
            raw = raw,
          )
        }

        override fun serialize(encoder: Encoder, value: DispatchMode.Unknown) {
          (encoder as JsonEncoder).encodeJsonElement(value.raw)
        }
      }
    }
  }

  @Serializable
  public data class PreparedRunRelease(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "prepared-run.release",
    override val commandId: String,
    val threadId: String,
    val runId: String,
  ) : Command

  @Serializable
  public data class NotificationDeliveryAccept(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "notification.delivery.accept",
    override val commandId: String,
    val threadId: String,
    val messageId: String,
  ) : Command

  @Serializable
  public data class PreparedRunProgress(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "prepared-run.progress",
    override val commandId: String,
    val threadId: String,
    val runId: String,
    val phase: String,
  ) : Command

  @Serializable
  public data class PreparedRunFail(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "prepared-run.fail",
    override val commandId: String,
    val threadId: String,
    val runId: String,
    val failure: ProviderFailure,
  ) : Command

  @Serializable
  public data class PreparedRunRetry(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "prepared-run.retry",
    override val commandId: String,
    val threadId: String,
    val runId: String,
  ) : Command

  @Serializable
  public data class SubagentStop(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "subagent.stop",
    override val commandId: String,
    val threadId: String,
    val runId: String,
    val subagentId: String,
  ) : Command

  @Serializable
  public data class RunInterrupt(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run.interrupt",
    override val commandId: String,
    val threadId: String,
    val runId: String,
    val reason: String? = null,
    val holdQueue: Boolean? = null,
  ) : Command

  @Serializable
  public data class QueuedMessagePromoteToSteer(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "queued-message.promote-to-steer",
    override val commandId: String,
    val threadId: String,
    val queuedRunId: String,
    val targetRunId: String,
  ) : Command

  @Serializable
  public data class QueueResume(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "queue.resume",
    override val commandId: String,
    val threadId: String,
  ) : Command

  @Serializable
  public data class QueuedRunStart(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "queued-run.start",
    override val commandId: String,
    val threadId: String,
    val runId: String,
  ) : Command

  @Serializable
  public data class QueuedRunReorder(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "queued-run.reorder",
    override val commandId: String,
    val threadId: String,
    val runId: String,
    val beforeRunId: String?,
  ) : Command

  @Serializable
  public data class QueuedRunCancel(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "queued-run.cancel",
    override val commandId: String,
    val threadId: String,
    val runId: String,
  ) : Command

  @Serializable
  public data class QueuedRunEdit(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "queued-run.edit",
    val context: MessageContext? = null,
    override val commandId: String,
    val threadId: String,
    val runId: String,
    val text: String,
    val attachments: List<ChatAttachment>? = null,
  ) : Command

  @Serializable
  public data class RuntimeRequestRespond(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "runtime-request.respond",
    override val commandId: String,
    val threadId: String,
    val requestId: String,
    val decision: String? = null,
    // TODO(contracts): object with no declared properties
    val answers: JsonObject? = null,
    val attachmentsByQuestionId:
        Map<String, List<Command.RuntimeRequestRespond.AttachmentsByQuestionIdItem>>? = null,
  ) : Command {
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

      public object AttachmentsByQuestionIdItemSerializer :
        KSerializer<AttachmentsByQuestionIdItem> {
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

  @Serializable
  public data class ThreadUserInputDismiss(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "thread.user-input.dismiss",
    override val commandId: String,
    val threadId: String,
    val requestId: String,
  ) : Command

  @Serializable
  public data class CheckpointRollback(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "checkpoint.rollback",
    val restoreFiles: Boolean? = null,
    override val commandId: String,
    val threadId: String,
    val scopeId: String,
    val checkpointId: String,
  ) : Command

  @Serializable
  public data class ThreadFork(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.fork",
    val createdBy: String,
    val creationSource: String,
    override val commandId: String,
    val sourceThreadId: String,
    val targetThreadId: String,
    val sourcePoint: ThreadForkSourcePoint,
    val title: String? = null,
    val createdAt: String? = null,
  ) : Command

  @Serializable
  public data class ThreadMergeBack(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.merge_back",
    val createdBy: String,
    val creationSource: String,
    override val commandId: String,
    val sourceThreadId: String,
    val targetThreadId: String,
    val sourcePoint: ThreadForkSourcePoint,
    val createdAt: String? = null,
  ) : Command

  @Serializable
  public data class DelegatedTaskRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "delegated_task.request",
    val createdBy: String,
    val creationSource: String,
    override val commandId: String,
    val parentThreadId: String,
    val parentRunId: String,
    val parentNodeId: String,
    val task: String,
    val title: String? = null,
    val modelSelection: ModelSelection,
    val runtimeMode: String,
    val interactionMode: String,
    val workspaceStrategy: ThreadLaunchWorkspaceStrategy? = null,
    val completionWake: String? = null,
    val createdAt: String? = null,
  ) : Command

  @Serializable
  public data class DelegatedTaskWakePolicy(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "delegated_task.wake-policy",
    override val commandId: String,
    val parentThreadId: String,
    val taskId: String,
    val completionWake: String,
  ) : Command

  @Serializable
  public data class DelegatedTaskCompletionDeliveryAcknowledge(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "delegated_task.completion-delivery.acknowledge",
    override val commandId: String,
    val parentThreadId: String,
    val taskId: String,
    val observedByRunId: String?,
  ) : Command

  @Serializable
  public data class DelegatedTaskCompletionDeliveryDispose(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val type: String = "delegated_task.completion-delivery.dispose",
    override val commandId: String,
    val parentThreadId: String,
    val taskId: String,
  ) : Command

  @Serializable
  public data class ThreadCreatedRecord(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread.created.record",
    override val commandId: String,
    val parentThreadId: String,
    val parentRunId: String,
    val parentNodeId: String,
    val targetThreadId: String,
    val targetRunId: String?,
  ) : Command

  @Serializable
  public data class ProviderSwitch(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "provider.switch",
    override val commandId: String,
    val threadId: String,
    val modelSelection: ModelSelection,
  ) : Command

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownCommandSerializer::class)
  public data class Unknown(
    override val type: String,
    override val commandId: String,
    val raw: JsonObject,
  ) : Command
}

public object CommandSerializer : KSerializer<Command> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<Command> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "thread.stop" -> Command.ThreadStop.serializer()
      "thread.create" -> Command.ThreadCreate.serializer()
      "thread.archive" -> Command.ThreadArchive.serializer()
      "thread.unarchive" -> Command.ThreadUnarchive.serializer()
      "thread.delete" -> Command.ThreadDelete.serializer()
      "thread.settle" -> Command.ThreadSettle.serializer()
      "thread.auto-settle" -> Command.ThreadAutoSettle.serializer()
      "thread.unsettle" -> Command.ThreadUnsettle.serializer()
      "thread.snooze" -> Command.ThreadSnooze.serializer()
      "thread.unsnooze" -> Command.ThreadUnsnooze.serializer()
      "thread.auto-settle.set" -> Command.ThreadAutoSettleSet.serializer()
      "thread.pin" -> Command.ThreadPin.serializer()
      "thread.unpin" -> Command.ThreadUnpin.serializer()
      "thread.pin.reorder" -> Command.ThreadPinReorder.serializer()
      "thread.active.reorder" -> Command.ThreadActiveReorder.serializer()
      "thread.visit" -> Command.ThreadVisit.serializer()
      "thread.mark-unread" -> Command.ThreadMarkUnread.serializer()
      "thread.metadata.update" -> Command.ThreadMetadataUpdate.serializer()
      "thread.pull-request.link" -> Command.CommandThreadPullRequestLink.serializer()
      "thread.pull-request.unlink" -> Command.ThreadPullRequestUnlink.serializer()
      "thread.pull-request-link.sync" -> Command.ThreadPullRequestLinkSync.serializer()
      "thread.pull-request.watch" -> Command.CommandThreadPullRequestWatch.serializer()
      "thread.pull-request.sync" -> Command.ThreadPullRequestSync.serializer()
      "thread.title.regeneration.complete" -> Command.ThreadTitleRegenerationComplete.serializer()
      "thread.runtime-mode.set" -> Command.ThreadRuntimeModeSet.serializer()
      "thread.interaction-mode.set" -> Command.ThreadInteractionModeSet.serializer()
      "thread.model-selection.set" -> Command.ThreadModelSelectionSet.serializer()
      "provider-session.detach" -> Command.ProviderSessionDetach.serializer()
      "message.dispatch" -> Command.MessageDispatch.serializer()
      "prepared-run.release" -> Command.PreparedRunRelease.serializer()
      "notification.delivery.accept" -> Command.NotificationDeliveryAccept.serializer()
      "prepared-run.progress" -> Command.PreparedRunProgress.serializer()
      "prepared-run.fail" -> Command.PreparedRunFail.serializer()
      "prepared-run.retry" -> Command.PreparedRunRetry.serializer()
      "subagent.stop" -> Command.SubagentStop.serializer()
      "run.interrupt" -> Command.RunInterrupt.serializer()
      "queued-message.promote-to-steer" -> Command.QueuedMessagePromoteToSteer.serializer()
      "queue.resume" -> Command.QueueResume.serializer()
      "queued-run.start" -> Command.QueuedRunStart.serializer()
      "queued-run.reorder" -> Command.QueuedRunReorder.serializer()
      "queued-run.cancel" -> Command.QueuedRunCancel.serializer()
      "queued-run.edit" -> Command.QueuedRunEdit.serializer()
      "runtime-request.respond" -> Command.RuntimeRequestRespond.serializer()
      "thread.user-input.dismiss" -> Command.ThreadUserInputDismiss.serializer()
      "checkpoint.rollback" -> Command.CheckpointRollback.serializer()
      "thread.fork" -> Command.ThreadFork.serializer()
      "thread.merge_back" -> Command.ThreadMergeBack.serializer()
      "delegated_task.request" -> Command.DelegatedTaskRequest.serializer()
      "delegated_task.wake-policy" -> Command.DelegatedTaskWakePolicy.serializer()
      "delegated_task.completion-delivery.acknowledge" ->
        Command.DelegatedTaskCompletionDeliveryAcknowledge.serializer()
      "delegated_task.completion-delivery.dispose" ->
        Command.DelegatedTaskCompletionDeliveryDispose.serializer()
      "thread.created.record" -> Command.ThreadCreatedRecord.serializer()
      "provider.switch" -> Command.ProviderSwitch.serializer()
      else -> Command.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): Command {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(Command.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: Command) {
    when (value) {
      is Command.ThreadStop -> Command.ThreadStop.serializer().serialize(encoder, value)
      is Command.ThreadCreate -> Command.ThreadCreate.serializer().serialize(encoder, value)
      is Command.ThreadArchive -> Command.ThreadArchive.serializer().serialize(encoder, value)
      is Command.ThreadUnarchive -> Command.ThreadUnarchive.serializer().serialize(encoder, value)
      is Command.ThreadDelete -> Command.ThreadDelete.serializer().serialize(encoder, value)
      is Command.ThreadSettle -> Command.ThreadSettle.serializer().serialize(encoder, value)
      is Command.ThreadAutoSettle -> Command.ThreadAutoSettle.serializer().serialize(encoder, value)
      is Command.ThreadUnsettle -> Command.ThreadUnsettle.serializer().serialize(encoder, value)
      is Command.ThreadSnooze -> Command.ThreadSnooze.serializer().serialize(encoder, value)
      is Command.ThreadUnsnooze -> Command.ThreadUnsnooze.serializer().serialize(encoder, value)
      is Command.ThreadAutoSettleSet ->
        Command.ThreadAutoSettleSet.serializer().serialize(encoder, value)
      is Command.ThreadPin -> Command.ThreadPin.serializer().serialize(encoder, value)
      is Command.ThreadUnpin -> Command.ThreadUnpin.serializer().serialize(encoder, value)
      is Command.ThreadPinReorder -> Command.ThreadPinReorder.serializer().serialize(encoder, value)
      is Command.ThreadActiveReorder ->
        Command.ThreadActiveReorder.serializer().serialize(encoder, value)
      is Command.ThreadVisit -> Command.ThreadVisit.serializer().serialize(encoder, value)
      is Command.ThreadMarkUnread -> Command.ThreadMarkUnread.serializer().serialize(encoder, value)
      is Command.ThreadMetadataUpdate ->
        Command.ThreadMetadataUpdate.serializer().serialize(encoder, value)
      is Command.CommandThreadPullRequestLink ->
        Command.CommandThreadPullRequestLink.serializer().serialize(encoder, value)
      is Command.ThreadPullRequestUnlink ->
        Command.ThreadPullRequestUnlink.serializer().serialize(encoder, value)
      is Command.ThreadPullRequestLinkSync ->
        Command.ThreadPullRequestLinkSync.serializer().serialize(encoder, value)
      is Command.CommandThreadPullRequestWatch ->
        Command.CommandThreadPullRequestWatch.serializer().serialize(encoder, value)
      is Command.ThreadPullRequestSync ->
        Command.ThreadPullRequestSync.serializer().serialize(encoder, value)
      is Command.ThreadTitleRegenerationComplete ->
        Command.ThreadTitleRegenerationComplete.serializer().serialize(encoder, value)
      is Command.ThreadRuntimeModeSet ->
        Command.ThreadRuntimeModeSet.serializer().serialize(encoder, value)
      is Command.ThreadInteractionModeSet ->
        Command.ThreadInteractionModeSet.serializer().serialize(encoder, value)
      is Command.ThreadModelSelectionSet ->
        Command.ThreadModelSelectionSet.serializer().serialize(encoder, value)
      is Command.ProviderSessionDetach ->
        Command.ProviderSessionDetach.serializer().serialize(encoder, value)
      is Command.MessageDispatch -> Command.MessageDispatch.serializer().serialize(encoder, value)
      is Command.PreparedRunRelease ->
        Command.PreparedRunRelease.serializer().serialize(encoder, value)
      is Command.NotificationDeliveryAccept ->
        Command.NotificationDeliveryAccept.serializer().serialize(encoder, value)
      is Command.PreparedRunProgress ->
        Command.PreparedRunProgress.serializer().serialize(encoder, value)
      is Command.PreparedRunFail -> Command.PreparedRunFail.serializer().serialize(encoder, value)
      is Command.PreparedRunRetry -> Command.PreparedRunRetry.serializer().serialize(encoder, value)
      is Command.SubagentStop -> Command.SubagentStop.serializer().serialize(encoder, value)
      is Command.RunInterrupt -> Command.RunInterrupt.serializer().serialize(encoder, value)
      is Command.QueuedMessagePromoteToSteer ->
        Command.QueuedMessagePromoteToSteer.serializer().serialize(encoder, value)
      is Command.QueueResume -> Command.QueueResume.serializer().serialize(encoder, value)
      is Command.QueuedRunStart -> Command.QueuedRunStart.serializer().serialize(encoder, value)
      is Command.QueuedRunReorder -> Command.QueuedRunReorder.serializer().serialize(encoder, value)
      is Command.QueuedRunCancel -> Command.QueuedRunCancel.serializer().serialize(encoder, value)
      is Command.QueuedRunEdit -> Command.QueuedRunEdit.serializer().serialize(encoder, value)
      is Command.RuntimeRequestRespond ->
        Command.RuntimeRequestRespond.serializer().serialize(encoder, value)
      is Command.ThreadUserInputDismiss ->
        Command.ThreadUserInputDismiss.serializer().serialize(encoder, value)
      is Command.CheckpointRollback ->
        Command.CheckpointRollback.serializer().serialize(encoder, value)
      is Command.ThreadFork -> Command.ThreadFork.serializer().serialize(encoder, value)
      is Command.ThreadMergeBack -> Command.ThreadMergeBack.serializer().serialize(encoder, value)
      is Command.DelegatedTaskRequest ->
        Command.DelegatedTaskRequest.serializer().serialize(encoder, value)
      is Command.DelegatedTaskWakePolicy ->
        Command.DelegatedTaskWakePolicy.serializer().serialize(encoder, value)
      is Command.DelegatedTaskCompletionDeliveryAcknowledge ->
        Command.DelegatedTaskCompletionDeliveryAcknowledge.serializer().serialize(encoder, value)
      is Command.DelegatedTaskCompletionDeliveryDispose ->
        Command.DelegatedTaskCompletionDeliveryDispose.serializer().serialize(encoder, value)
      is Command.ThreadCreatedRecord ->
        Command.ThreadCreatedRecord.serializer().serialize(encoder, value)
      is Command.ProviderSwitch -> Command.ProviderSwitch.serializer().serialize(encoder, value)
      is Command.Unknown -> Command.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownCommandSerializer : KSerializer<Command.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): Command.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return Command.Unknown(
      type = string("type") ?: "unknown",
      commandId = string("commandId") ?: "",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: Command.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
