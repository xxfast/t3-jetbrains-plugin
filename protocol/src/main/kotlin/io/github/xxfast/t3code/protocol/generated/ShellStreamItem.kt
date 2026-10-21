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
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val kind: String = "synchronized",
  ) : ShellStreamItem

  @Serializable
  public data class Snapshot(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val kind: String = "snapshot",
    val snapshot: ShellSnapshot,
    val resolvedRepositoryIdentityRoots: List<String> = emptyList(),
  ) : ShellStreamItem

  @Serializable
  public data class ProjectUpdated(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val kind: String = "project.updated",
    val sequence: Long,
    val project: ProjectShell,
  ) : ShellStreamItem

  @Serializable
  public data class ProjectRemoved(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val kind: String = "project.removed",
    val sequence: Long,
    val projectId: String,
  ) : ShellStreamItem

  @Serializable
  public data class ThreadUpdated(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val kind: String = "thread.updated",
    val sequence: Long,
    val location: String,
    val thread: ThreadShell,
  ) : ShellStreamItem

  @Serializable
  public data class ThreadRemoved(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val kind: String = "thread.removed",
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

public object ShellStreamItemSerializer : KSerializer<ShellStreamItem> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ShellStreamItem> =
    when (element["kind"]?.jsonPrimitive?.contentOrNull) {
      "synchronized" -> ShellStreamItem.Synchronized.serializer()
      "snapshot" -> ShellStreamItem.Snapshot.serializer()
      "project.updated" -> ShellStreamItem.ProjectUpdated.serializer()
      "project.removed" -> ShellStreamItem.ProjectRemoved.serializer()
      "thread.updated" -> ShellStreamItem.ThreadUpdated.serializer()
      "thread.removed" -> ShellStreamItem.ThreadRemoved.serializer()
      else -> ShellStreamItem.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ShellStreamItem {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ShellStreamItem.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ShellStreamItem) {
    when (value) {
      is ShellStreamItem.Synchronized ->
        ShellStreamItem.Synchronized.serializer().serialize(encoder, value)
      is ShellStreamItem.Snapshot -> ShellStreamItem.Snapshot.serializer().serialize(encoder, value)
      is ShellStreamItem.ProjectUpdated ->
        ShellStreamItem.ProjectUpdated.serializer().serialize(encoder, value)
      is ShellStreamItem.ProjectRemoved ->
        ShellStreamItem.ProjectRemoved.serializer().serialize(encoder, value)
      is ShellStreamItem.ThreadUpdated ->
        ShellStreamItem.ThreadUpdated.serializer().serialize(encoder, value)
      is ShellStreamItem.ThreadRemoved ->
        ShellStreamItem.ThreadRemoved.serializer().serialize(encoder, value)
      is ShellStreamItem.Unknown -> ShellStreamItem.Unknown.serializer().serialize(encoder, value)
    }
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
