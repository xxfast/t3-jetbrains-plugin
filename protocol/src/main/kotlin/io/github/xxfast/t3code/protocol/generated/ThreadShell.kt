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

@Serializable
public data class ThreadShell(
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
  val lineage: AppThreadLineage,
  val forkedFrom: ThreadShell.ForkedFrom?,
  val activeProviderThreadId: String?,
  val historyOrigin: String? = null,
  val linkOrigin: LinkOrigin? = null,
  val delegatedFrom: DelegatedFrom? = null,
  val latestRunId: String?,
  val latestRunRequestedAt: String? = null,
  val latestRunStartedAt: String? = null,
  val latestRunCompletedAt: String? = null,
  val activeRunId: String?,
  val activityRunStartedAt: String? = null,
  val activityRunStatus: String? = null,
  val status: String,
  val lastError: String? = null,
  val lastErrorClass: String? = null,
  val usageLimitResetAt: String? = null,
  val pendingRuntimeRequest: PendingRuntimeRequestSummary?,
  val latestVisibleMessage: LatestVisibleMessageSummary?,
  val latestUserMessageAt: String?,
  val latestUserAuthoredMessageAt: String? = null,
  val hasActionableProposedPlan: Boolean,
  val pendingBackgroundTasks: List<PendingBackgroundTask>? = null,
  val providerInstanceHistory: List<String>? = null,
  val goal: ProviderGoal? = null,
  val itemCount: Long,
  val visibleItemCount: Long,
  val createdAt: String,
  val updatedAt: String,
  val archivedAt: String?,
  val settledOverride: String?,
  val settledAt: String?,
  val unsettledAt: String? = null,
  val snoozedUntil: String? = null,
  val snoozedAt: String? = null,
  val limitRecovery: LimitRecovery? = null,
  val pinnedAt: String? = null,
  val autoSettleDisabledAt: String? = null,
  val pinOrderKey: String? = null,
  val activeOrderKey: String? = null,
  val lastVisitedAt: String? = null,
  val titleRegeneration: ThreadTitleRegeneration? = null,
  val deletedAt: String?,
) {
  @Serializable(with = ForkedFrom.ForkedFromSerializer::class)
  public sealed interface ForkedFrom {
    public val type: String

    @Serializable
    public data class ForkedFromRun(
      @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "run",
      val threadId: String,
      val runId: String,
    ) : ThreadShell.ForkedFrom

    @Serializable
    public data class Node(
      @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "node",
      val nodeId: String,
    ) : ThreadShell.ForkedFrom

    @Serializable
    public data class ForkedFromProviderThread(
      @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "provider_thread",
      val providerThreadId: String,
      val providerTurnId: String? = null,
    ) : ThreadShell.ForkedFrom

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
}
