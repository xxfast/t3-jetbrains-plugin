package io.github.xxfast.t3code.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Hand-written shapes for what 0.1 needs. Names and fields match upstream's
// packages/contracts at the tag in contracts/T3CODE_VERSION. Everything the plugin
// doesn't read stays out, and T3Json ignores it on the wire. #7 replaces these.

/** What `GET /api/auth/session` answers. */
@Serializable
public data class ServerSession(
  val authenticated: Boolean,
  val auth: ServerAuthDescriptor,
  val permissions: List<String> = emptyList(),
  val scopes: List<String> = emptyList(),
  val sessionMethod: String? = null,
  val expiresAt: String? = null,
)

@Serializable
public data class ServerAuthDescriptor(
  val policy: String,
  val bootstrapMethods: List<String> = emptyList(),
  val sessionMethods: List<String> = emptyList(),
  val sessionCookieName: String? = null,
)

/** A bearer session minted by `POST /oauth/token`. Stored by the plugin in PasswordSafe. */
@Serializable
public data class Credential(
  val accessToken: String,
  val tokenType: String = "Bearer",
  val scopes: List<String> = emptyList(),
  val expiresInSeconds: Long? = null,
)

/** `OrchestrationProjectShell`. */
@Serializable
public data class Project(
  val id: String,
  val title: String,
  val workspaceRoot: String,
  val createdAt: String,
  val updatedAt: String,
)

/** `OrchestrationV2ThreadShell`, the list row. Archived and pinned derive from the timestamps. */
@Serializable
public data class Thread(
  val id: String,
  val projectId: String,
  val title: String,
  /** `idle` or a run status. Unknown values render as a neutral badge with the raw string. */
  val status: String = "idle",
  val branch: String? = null,
  val worktreePath: String? = null,
  val archivedAt: String? = null,
  val pinnedAt: String? = null,
  val latestRunId: String? = null,
  val activeRunId: String? = null,
  val pendingRuntimeRequest: PendingRuntimeRequest? = null,
  val latestVisibleMessage: LatestVisibleMessage? = null,
  val itemCount: Int = 0,
  val createdAt: String,
  val updatedAt: String,
) {
  public val archived: Boolean get() = archivedAt != null
  public val pinned: Boolean get() = pinnedAt != null
}

@Serializable
public data class PendingRuntimeRequest(val id: String, val kind: String, val createdAt: String)

@Serializable
public data class LatestVisibleMessage(
  val id: String,
  val role: String,
  val text: String,
  val updatedAt: String,
)

/** A `Run` is upstream's "turn". `ordinal` is what the diff RPCs call a turn count. */
@Serializable
public data class Run(
  val id: String,
  val ordinal: Int,
  val status: String,
  val requestedAt: String? = null,
  val startedAt: String? = null,
  val completedAt: String? = null,
)

/** One open thread: the row, its runs and the items to render, in order. */
public data class ThreadView(
  val thread: Thread,
  val runs: List<Run>,
  val items: List<TurnItem>,
)

@Serializable
public data class FileContents(
  val relativePath: String,
  val contents: String,
  val byteLength: Long = 0,
  val truncated: Boolean = false,
)

@Serializable
public data class FileChangeEntry(
  val operation: String,
  val path: String,
  val oldPath: String? = null,
)

@Serializable
public data class ApprovalOption(
  val decision: String,
  val label: String,
  val warning: String? = null,
)

@Serializable
public data class Failure(
  @SerialName("class") val failureClass: String = "unknown",
  val message: String,
  val code: String? = null,
  val retryable: Boolean? = null,
)

/**
 * A turn item. Discriminated on `type`; anything this build doesn't know decodes as [Unknown]
 * with the raw JSON so the UI can still show it. The common fields are the ones every item
 * carries upstream; the per-type ones are only what 0.1 renders.
 */
@Serializable(with = TurnItemSerializer::class)
public sealed interface TurnItem {
  public val type: String
  public val id: String
  public val status: String
  public val title: String?
  public val startedAt: String?
  public val completedAt: String?
  public val updatedAt: String

  @Serializable
  public data class UserMessage(
    override val type: String = "user_message",
    override val id: String,
    override val status: String = "completed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val text: String,
  ) : TurnItem

  @Serializable
  public data class AssistantMessage(
    override val type: String = "assistant_message",
    override val id: String,
    override val status: String = "completed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val text: String,
    val streaming: Boolean = false,
  ) : TurnItem

  @Serializable
  public data class Reasoning(
    override val type: String = "reasoning",
    override val id: String,
    override val status: String = "completed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val text: String,
    val streaming: Boolean = false,
  ) : TurnItem

  @Serializable
  public data class CommandExecution(
    override val type: String = "command_execution",
    override val id: String,
    override val status: String = "completed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val input: String,
    val output: String? = null,
    val outputOmitted: Boolean = false,
    val exitCode: Int? = null,
  ) : TurnItem

  @Serializable
  public data class FileChange(
    override val type: String = "file_change",
    override val id: String,
    override val status: String = "completed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val fileName: String,
    val additions: Int? = null,
    val deletions: Int? = null,
    val diffStr: String? = null,
    val oldStr: String? = null,
    val newStr: String? = null,
    val changes: List<FileChangeEntry> = emptyList(),
  ) : TurnItem

  @Serializable
  public data class ApprovalRequest(
    override val type: String = "approval_request",
    override val id: String,
    override val status: String = "waiting",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val requestId: String,
    val requestKind: String,
    val prompt: String? = null,
    val options: List<ApprovalOption> = emptyList(),
  ) : TurnItem

  @Serializable
  public data class Error(
    override val type: String = "error",
    override val id: String,
    override val status: String = "failed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val failure: Failure,
  ) : TurnItem

  @Serializable
  public data class SystemNotice(
    override val type: String = "system_notice",
    override val id: String,
    override val status: String = "completed",
    override val title: String? = null,
    override val startedAt: String? = null,
    override val completedAt: String? = null,
    override val updatedAt: String,
    val message: String,
  ) : TurnItem

  /** Any type this build doesn't know. [raw] is the whole item as received. */
  @Serializable(with = UnknownTurnItemSerializer::class)
  public data class Unknown(
    override val type: String,
    override val id: String,
    override val status: String,
    override val title: String?,
    override val startedAt: String?,
    override val completedAt: String?,
    override val updatedAt: String,
    val raw: JsonObject,
  ) : TurnItem
}

public object TurnItemSerializer : JsonContentPolymorphicSerializer<TurnItem>(TurnItem::class) {
  override fun selectDeserializer(element: JsonElement): DeserializationStrategy<TurnItem> =
    when (element.jsonObject["type"]?.jsonPrimitive?.contentOrNull) {
      "user_message" -> TurnItem.UserMessage.serializer()
      "assistant_message" -> TurnItem.AssistantMessage.serializer()
      "reasoning" -> TurnItem.Reasoning.serializer()
      "command_execution" -> TurnItem.CommandExecution.serializer()
      "file_change" -> TurnItem.FileChange.serializer()
      "approval_request" -> TurnItem.ApprovalRequest.serializer()
      "error" -> TurnItem.Error.serializer()
      "system_notice" -> TurnItem.SystemNotice.serializer()
      else -> TurnItem.Unknown.serializer()
    }
}

public object UnknownTurnItemSerializer : KSerializer<TurnItem.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): TurnItem.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return TurnItem.Unknown(
      type = string("type") ?: "unknown",
      id = string("id") ?: "",
      status = string("status") ?: "completed",
      title = string("title"),
      startedAt = string("startedAt"),
      completedAt = string("completedAt"),
      updatedAt = string("updatedAt") ?: "",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: TurnItem.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
