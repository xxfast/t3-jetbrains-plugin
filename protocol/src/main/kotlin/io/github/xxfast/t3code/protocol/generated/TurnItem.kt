// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable(with = TurnItemSerializer::class)
public sealed interface TurnItem {
  public val toolNonExecutionKind: String?
  public val toolSurface: String?
  public val id: String
  public val threadId: String
  public val runId: String?
  public val nodeId: String?
  public val providerThreadId: String?
  public val providerTurnId: String?
  public val parentItemId: String?
  public val ordinal: Long
  public val status: String
  public val title: String?
  public val startedAt: String?
  public val completedAt: String?
  public val updatedAt: String
  public val type: String

  @Serializable
  public data class TurnItemNotification(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "notification",
    val source: NotificationSource,
    val outcome: String,
    val summary: String,
    val detail: String? = null,
  ) : TurnItem

  @Serializable
  public data class UserMessage(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    val createdBy: String,
    val creationSource: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "user_message",
    val messageId: String,
    val scheduledTaskId: String? = null,
    val senderThreadId: String? = null,
    val workflowScript: Boolean? = null,
    val inputIntent: String,
    val text: String,
    val context: MessageContext? = null,
    val attachments: List<ChatAttachment>,
  ) : TurnItem

  @Serializable
  public data class AssistantMessage(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "assistant_message",
    val messageId: String,
    val text: String,
    val attachments: List<ChatAttachment>? = null,
    val streaming: Boolean,
  ) : TurnItem

  @Serializable
  public data class Reasoning(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "reasoning",
    val text: String,
    val streaming: Boolean,
  ) : TurnItem

  @Serializable
  public data class ProposedPlan(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "proposed_plan",
    val planId: String,
    val markdown: String,
    val streaming: Boolean,
  ) : TurnItem

  @Serializable
  public data class TodoList(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "todo_list",
    val planId: String,
    val steps: List<PlanStep>,
    val explanation: String? = null,
  ) : TurnItem

  @Serializable
  public data class UserInputRequest(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "user_input_request",
    val requestId: String,
    val questions: List<UserInputQuestion>,
    val questionAnswer: UserInputAttachmentAnswerPayload? = null,
    val responseMode: String? = null,
  ) : TurnItem

  @Serializable
  public data class FileChange(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "file_change",
    val fileName: String,
    val additions: Long? = null,
    val deletions: Long? = null,
    val diffStr: String? = null,
    val oldStr: String? = null,
    val newStr: String? = null,
    val changes: List<FileChangeDetail>? = null,
  ) : TurnItem

  @Serializable
  public data class CommandExecution(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "command_execution",
    val input: String,
    val output: String? = null,
    val outputOmitted: Boolean? = null,
    val outputIndicatesFailure: Boolean? = null,
    val exitCode: Long? = null,
  ) : TurnItem

  @Serializable
  public data class FileSearch(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "file_search",
    val pattern: String? = null,
    val results: List<FileSearchResult>? = null,
  ) : TurnItem

  @Serializable
  public data class WebSearch(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "web_search",
    val patterns: List<String>? = null,
    val results: List<WebSearchResult>? = null,
  ) : TurnItem

  @Serializable
  public data class ApprovalRequest(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "approval_request",
    val requestId: String,
    val requestKind: String,
    val prompt: String? = null,
    val appName: String? = null,
    val options: List<ProviderApprovalOption>? = null,
  ) : TurnItem

  @Serializable
  public data class TurnItemCheckpoint(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "checkpoint",
    val checkpointId: String,
    val scopeId: String,
    val files: List<CheckpointFileSummary>,
  ) : TurnItem

  @Serializable
  public data class RunInterruptRequest(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run_interrupt_request",
    val message: String,
  ) : TurnItem

  @Serializable
  public data class RunInterruptResult(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run_interrupt_result",
    val message: String,
  ) : TurnItem

  @Serializable
  public data class SystemNotice(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "system_notice",
    val message: String,
  ) : TurnItem

  @Serializable
  public data class Error(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "error",
    val failure: ProviderFailure,
    val retry: ProviderRetry? = null,
  ) : TurnItem

  @Serializable
  public data class Compaction(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "compaction",
    val driver: String?,
    val summary: String? = null,
    val beforeTokenCount: Long? = null,
    val afterTokenCount: Long? = null,
  ) : TurnItem

  @Serializable
  public data class Handoff(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "handoff",
    val contextHandoffId: String,
    val fromProviderThreadIds: List<String>,
    val toProviderThreadId: String,
    val fromProviderInstanceIds: List<String>,
    val toProviderInstanceId: String,
    val fromModelSelections: List<ModelSelection>? = null,
    val toModel: String? = null,
    val strategy: String,
    val summary: String? = null,
  ) : TurnItem

  @Serializable
  public data class Fork(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String? = null,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "fork",
    val source: TurnItem.Fork.Source,
    val targetThreadId: String,
  ) : TurnItem {
    @Serializable(with = Source.SourceSerializer::class)
    public sealed interface Source {
      public val type: String

      @Serializable
      public data class SourceRun(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run",
        val threadId: String,
        val runId: String,
      ) : TurnItem.Fork.Source

      @Serializable
      public data class Node(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "node",
        val nodeId: String,
      ) : TurnItem.Fork.Source

      @Serializable
      public data class SourceProviderThread(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "provider_thread",
        val providerThreadId: String,
        val providerTurnId: String? = null,
      ) : TurnItem.Fork.Source

      /** A variant this build doesn't know. [raw] is the whole object as received. */
      @Serializable(with = Source.UnknownSourceSerializer::class)
      public data class Unknown(
        override val type: String,
        val raw: JsonObject,
      ) : Source

      public object SourceSerializer : KSerializer<Source> {
        override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

        private fun select(element: JsonObject): DeserializationStrategy<Source> =
          when (element["type"]?.jsonPrimitive?.contentOrNull) {
            "run" -> Source.SourceRun.serializer()
            "node" -> Source.Node.serializer()
            "provider_thread" -> Source.SourceProviderThread.serializer()
            else -> Source.Unknown.serializer()
          }

        override fun deserialize(decoder: Decoder): Source {
          val input: JsonDecoder = decoder as JsonDecoder
          val element: JsonObject = input.decodeJsonElement().jsonObject
          return try {
            input.json.decodeFromJsonElement(select(element), element)
          } catch (_: IllegalArgumentException) {
            input.json.decodeFromJsonElement(Source.Unknown.serializer(), element)
          }
        }

        override fun serialize(encoder: Encoder, value: Source) {
          when (value) {
            is Source.SourceRun -> Source.SourceRun.serializer().serialize(encoder, value)
            is Source.Node -> Source.Node.serializer().serialize(encoder, value)
            is Source.SourceProviderThread ->
              Source.SourceProviderThread.serializer().serialize(encoder, value)
            is Source.Unknown -> Source.Unknown.serializer().serialize(encoder, value)
          }
        }
      }

      public object UnknownSourceSerializer : KSerializer<Source.Unknown> {
        override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

        override fun deserialize(decoder: Decoder): Source.Unknown {
          val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
          fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
          return Source.Unknown(
            type = string("type") ?: "unknown",
            raw = raw,
          )
        }

        override fun serialize(encoder: Encoder, value: Source.Unknown) {
          (encoder as JsonEncoder).encodeJsonElement(value.raw)
        }
      }
    }
  }

  @Serializable
  public data class ThreadCreated(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "thread_created",
    val targetThreadId: String,
    val targetRunId: String?,
    val targetProviderInstanceId: String,
    val targetModel: String,
  ) : TurnItem

  @Serializable
  public data class SecretRequest(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "secret_request",
    val label: String,
    val reason: String,
    val placeholder: String? = null,
    val secretStatus: String,
  ) : TurnItem

  @Serializable
  public data class TurnItemSubagent(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "subagent",
    val subagentId: String,
    val origin: String,
    val driver: String,
    val providerInstanceId: String,
    val childThreadId: String?,
    val prompt: String,
    val progress: String? = null,
    val result: String?,
  ) : TurnItem

  @Serializable
  public data class DynamicTool(
    override val toolNonExecutionKind: String? = null,
    override val toolSurface: String? = null,
    val toolIcon: ToolActivityIcon? = null,
    val toolSource: ToolActivitySource? = null,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    val nativeItemRef: ProviderRef?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "dynamic_tool",
    val toolName: String?,
    val viewedImagePath: String? = null,
    // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
    val input: JsonElement,
    // TODO(contracts): unconstrained upstream (Schema.Unknown or an opaque type)
    val output: JsonElement? = null,
    val outputOmitted: Boolean? = null,
  ) : TurnItem

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownTurnItemSerializer::class)
  public data class Unknown(
    override val toolNonExecutionKind: String?,
    override val toolSurface: String?,
    override val id: String,
    override val threadId: String,
    override val runId: String?,
    override val nodeId: String?,
    override val providerThreadId: String?,
    override val providerTurnId: String?,
    override val parentItemId: String?,
    override val ordinal: Long,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    override val type: String,
    val raw: JsonObject,
  ) : TurnItem
}

public object TurnItemSerializer : KSerializer<TurnItem> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<TurnItem> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "notification" -> TurnItem.TurnItemNotification.serializer()
      "user_message" -> TurnItem.UserMessage.serializer()
      "assistant_message" -> TurnItem.AssistantMessage.serializer()
      "reasoning" -> TurnItem.Reasoning.serializer()
      "proposed_plan" -> TurnItem.ProposedPlan.serializer()
      "todo_list" -> TurnItem.TodoList.serializer()
      "user_input_request" -> TurnItem.UserInputRequest.serializer()
      "file_change" -> TurnItem.FileChange.serializer()
      "command_execution" -> TurnItem.CommandExecution.serializer()
      "file_search" -> TurnItem.FileSearch.serializer()
      "web_search" -> TurnItem.WebSearch.serializer()
      "approval_request" -> TurnItem.ApprovalRequest.serializer()
      "checkpoint" -> TurnItem.TurnItemCheckpoint.serializer()
      "run_interrupt_request" -> TurnItem.RunInterruptRequest.serializer()
      "run_interrupt_result" -> TurnItem.RunInterruptResult.serializer()
      "system_notice" -> TurnItem.SystemNotice.serializer()
      "error" -> TurnItem.Error.serializer()
      "compaction" -> TurnItem.Compaction.serializer()
      "handoff" -> TurnItem.Handoff.serializer()
      "fork" -> TurnItem.Fork.serializer()
      "thread_created" -> TurnItem.ThreadCreated.serializer()
      "secret_request" -> TurnItem.SecretRequest.serializer()
      "subagent" -> TurnItem.TurnItemSubagent.serializer()
      "dynamic_tool" -> TurnItem.DynamicTool.serializer()
      else -> TurnItem.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): TurnItem {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(TurnItem.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: TurnItem) {
    when (value) {
      is TurnItem.TurnItemNotification ->
        TurnItem.TurnItemNotification.serializer().serialize(encoder, value)
      is TurnItem.UserMessage -> TurnItem.UserMessage.serializer().serialize(encoder, value)
      is TurnItem.AssistantMessage ->
        TurnItem.AssistantMessage.serializer().serialize(encoder, value)
      is TurnItem.Reasoning -> TurnItem.Reasoning.serializer().serialize(encoder, value)
      is TurnItem.ProposedPlan -> TurnItem.ProposedPlan.serializer().serialize(encoder, value)
      is TurnItem.TodoList -> TurnItem.TodoList.serializer().serialize(encoder, value)
      is TurnItem.UserInputRequest ->
        TurnItem.UserInputRequest.serializer().serialize(encoder, value)
      is TurnItem.FileChange -> TurnItem.FileChange.serializer().serialize(encoder, value)
      is TurnItem.CommandExecution ->
        TurnItem.CommandExecution.serializer().serialize(encoder, value)
      is TurnItem.FileSearch -> TurnItem.FileSearch.serializer().serialize(encoder, value)
      is TurnItem.WebSearch -> TurnItem.WebSearch.serializer().serialize(encoder, value)
      is TurnItem.ApprovalRequest -> TurnItem.ApprovalRequest.serializer().serialize(encoder, value)
      is TurnItem.TurnItemCheckpoint ->
        TurnItem.TurnItemCheckpoint.serializer().serialize(encoder, value)
      is TurnItem.RunInterruptRequest ->
        TurnItem.RunInterruptRequest.serializer().serialize(encoder, value)
      is TurnItem.RunInterruptResult ->
        TurnItem.RunInterruptResult.serializer().serialize(encoder, value)
      is TurnItem.SystemNotice -> TurnItem.SystemNotice.serializer().serialize(encoder, value)
      is TurnItem.Error -> TurnItem.Error.serializer().serialize(encoder, value)
      is TurnItem.Compaction -> TurnItem.Compaction.serializer().serialize(encoder, value)
      is TurnItem.Handoff -> TurnItem.Handoff.serializer().serialize(encoder, value)
      is TurnItem.Fork -> TurnItem.Fork.serializer().serialize(encoder, value)
      is TurnItem.ThreadCreated -> TurnItem.ThreadCreated.serializer().serialize(encoder, value)
      is TurnItem.SecretRequest -> TurnItem.SecretRequest.serializer().serialize(encoder, value)
      is TurnItem.TurnItemSubagent ->
        TurnItem.TurnItemSubagent.serializer().serialize(encoder, value)
      is TurnItem.DynamicTool -> TurnItem.DynamicTool.serializer().serialize(encoder, value)
      is TurnItem.Unknown -> TurnItem.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownTurnItemSerializer : KSerializer<TurnItem.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): TurnItem.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String): Long? = raw[key]?.jsonPrimitive?.longOrNull
    return TurnItem.Unknown(
      toolNonExecutionKind = string("toolNonExecutionKind"),
      toolSurface = string("toolSurface"),
      id = string("id") ?: "",
      threadId = string("threadId") ?: "",
      runId = string("runId"),
      nodeId = string("nodeId"),
      providerThreadId = string("providerThreadId"),
      providerTurnId = string("providerTurnId"),
      parentItemId = string("parentItemId"),
      ordinal = long("ordinal") ?: 0L,
      status = string("status") ?: "",
      title = string("title"),
      startedAt = string("startedAt"),
      completedAt = string("completedAt"),
      updatedAt = string("updatedAt") ?: "",
      type = string("type") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: TurnItem.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
