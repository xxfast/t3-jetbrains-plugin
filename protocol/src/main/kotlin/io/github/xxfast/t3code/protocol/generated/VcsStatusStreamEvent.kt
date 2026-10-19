// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

@file:OptIn(ExperimentalSerializationApi::class)

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
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

@Serializable(with = VcsStatusStreamEventSerializer::class)
public sealed interface VcsStatusStreamEvent {
  public val tag: String

  @Serializable
  public data class Snapshot(
    @SerialName("_tag") @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val tag: String = "snapshot",
    val local: VcsStatusLocalResult,
    val remote: VcsStatusRemoteResult?,
  ) : VcsStatusStreamEvent

  @Serializable
  public data class LocalUpdated(
    @SerialName("_tag") @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val tag: String = "localUpdated",
    val local: VcsStatusLocalResult,
  ) : VcsStatusStreamEvent

  @Serializable
  public data class RemoteUpdated(
    @SerialName("_tag") @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val tag: String = "remoteUpdated",
    val remote: VcsStatusRemoteResult?,
  ) : VcsStatusStreamEvent

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownVcsStatusStreamEventSerializer::class)
  public data class Unknown(
    override val tag: String,
    val raw: JsonObject,
  ) : VcsStatusStreamEvent
}

public object VcsStatusStreamEventSerializer : KSerializer<VcsStatusStreamEvent> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<VcsStatusStreamEvent> =
    when (element["_tag"]?.jsonPrimitive?.contentOrNull) {
      "snapshot" -> VcsStatusStreamEvent.Snapshot.serializer()
      "localUpdated" -> VcsStatusStreamEvent.LocalUpdated.serializer()
      "remoteUpdated" -> VcsStatusStreamEvent.RemoteUpdated.serializer()
      else -> VcsStatusStreamEvent.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): VcsStatusStreamEvent {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(VcsStatusStreamEvent.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: VcsStatusStreamEvent) {
    when (value) {
      is VcsStatusStreamEvent.Snapshot ->
        VcsStatusStreamEvent.Snapshot.serializer().serialize(encoder, value)
      is VcsStatusStreamEvent.LocalUpdated ->
        VcsStatusStreamEvent.LocalUpdated.serializer().serialize(encoder, value)
      is VcsStatusStreamEvent.RemoteUpdated ->
        VcsStatusStreamEvent.RemoteUpdated.serializer().serialize(encoder, value)
      is VcsStatusStreamEvent.Unknown ->
        VcsStatusStreamEvent.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownVcsStatusStreamEventSerializer : KSerializer<VcsStatusStreamEvent.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): VcsStatusStreamEvent.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return VcsStatusStreamEvent.Unknown(
      tag = string("_tag") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: VcsStatusStreamEvent.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
