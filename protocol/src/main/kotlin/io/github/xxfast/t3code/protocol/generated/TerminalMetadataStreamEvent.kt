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

@Serializable(with = TerminalMetadataStreamEventSerializer::class)
public sealed interface TerminalMetadataStreamEvent {
  public val type: String

  @Serializable
  public data class Snapshot(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "snapshot",
    val terminals: List<TerminalSummary>,
  ) : TerminalMetadataStreamEvent

  @Serializable
  public data class Upsert(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "upsert",
    val terminal: TerminalSummary,
  ) : TerminalMetadataStreamEvent

  @Serializable
  public data class Remove(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "remove",
    val threadId: String,
    val terminalId: String,
  ) : TerminalMetadataStreamEvent

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownTerminalMetadataStreamEventSerializer::class)
  public data class Unknown(
    override val type: String,
    val raw: JsonObject,
  ) : TerminalMetadataStreamEvent
}

public object TerminalMetadataStreamEventSerializer : KSerializer<TerminalMetadataStreamEvent> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<TerminalMetadataStreamEvent> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "snapshot" -> TerminalMetadataStreamEvent.Snapshot.serializer()
      "upsert" -> TerminalMetadataStreamEvent.Upsert.serializer()
      "remove" -> TerminalMetadataStreamEvent.Remove.serializer()
      else -> TerminalMetadataStreamEvent.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): TerminalMetadataStreamEvent {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(TerminalMetadataStreamEvent.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: TerminalMetadataStreamEvent) {
    when (value) {
      is TerminalMetadataStreamEvent.Snapshot ->
        TerminalMetadataStreamEvent.Snapshot.serializer().serialize(encoder, value)
      is TerminalMetadataStreamEvent.Upsert ->
        TerminalMetadataStreamEvent.Upsert.serializer().serialize(encoder, value)
      is TerminalMetadataStreamEvent.Remove ->
        TerminalMetadataStreamEvent.Remove.serializer().serialize(encoder, value)
      is TerminalMetadataStreamEvent.Unknown ->
        TerminalMetadataStreamEvent.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownTerminalMetadataStreamEventSerializer :
  KSerializer<TerminalMetadataStreamEvent.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): TerminalMetadataStreamEvent.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return TerminalMetadataStreamEvent.Unknown(
      type = string("type") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: TerminalMetadataStreamEvent.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
