// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
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

@Serializable(with = ShellStreamItemSerializer::class)
public sealed interface ShellStreamItem {
  public val kind: String

  @Serializable
  public data class Synchronized(
    override val kind: String = "synchronized",
  ) : ShellStreamItem

  @Serializable
  public data class Snapshot(
    override val kind: String = "snapshot",
    val snapshot: ShellSnapshot,
    val resolvedRepositoryIdentityRoots: List<String> = emptyList(),
  ) : ShellStreamItem

  @Serializable
  public data class ProjectUpdated(
    override val kind: String = "project.updated",
    val sequence: Long,
    val project: ProjectShell,
  ) : ShellStreamItem

  @Serializable
  public data class ProjectRemoved(
    override val kind: String = "project.removed",
    val sequence: Long,
    val projectId: String,
  ) : ShellStreamItem

  @Serializable
  public data class ThreadUpdated(
    override val kind: String = "thread.updated",
    val sequence: Long,
    val location: String,
    val thread: ThreadShell,
  ) : ShellStreamItem

  @Serializable
  public data class ThreadRemoved(
    override val kind: String = "thread.removed",
    val sequence: Long,
    val location: String,
    val threadId: String,
  ) : ShellStreamItem

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownShellStreamItemSerializer::class)
  public data class Unknown(
    override val kind: String,
    val raw: JsonObject,
  ) : ShellStreamItem
}

public object ShellStreamItemSerializer :
  JsonContentPolymorphicSerializer<ShellStreamItem>(ShellStreamItem::class) {
  override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ShellStreamItem> =
    when (element.jsonObject["kind"]?.jsonPrimitive?.contentOrNull) {
      "synchronized" -> ShellStreamItem.Synchronized.serializer()
      "snapshot" -> ShellStreamItem.Snapshot.serializer()
      "project.updated" -> ShellStreamItem.ProjectUpdated.serializer()
      "project.removed" -> ShellStreamItem.ProjectRemoved.serializer()
      "thread.updated" -> ShellStreamItem.ThreadUpdated.serializer()
      "thread.removed" -> ShellStreamItem.ThreadRemoved.serializer()
      else -> ShellStreamItem.Unknown.serializer()
    }
}

public object UnknownShellStreamItemSerializer : KSerializer<ShellStreamItem.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ShellStreamItem.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ShellStreamItem.Unknown(
      kind = string("kind") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ShellStreamItem.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
