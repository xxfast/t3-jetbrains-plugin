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

@Serializable(with = TerminalAttachStreamEventSerializer::class)
public sealed interface TerminalAttachStreamEvent {
  public val type: String

  @Serializable
  public data class Snapshot(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "snapshot",
    val snapshot: TerminalSessionSnapshot,
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Output(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "output",
    val data: String,
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Exited(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "exited",
    val exitCode: Long?,
    val exitSignal: Long?,
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Closed(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "closed",
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Error(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "error",
    val message: String,
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Cleared(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "cleared",
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Restarted(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "restarted",
    val snapshot: TerminalSessionSnapshot,
  ) : TerminalAttachStreamEvent

  @Serializable
  public data class Activity(
    val threadId: String,
    val terminalId: String,
    val sequence: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "activity",
    val hasRunningSubprocess: Boolean,
    val label: String,
  ) : TerminalAttachStreamEvent

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownTerminalAttachStreamEventSerializer::class)
  public data class Unknown(
    override val type: String,
    val raw: JsonObject,
  ) : TerminalAttachStreamEvent
}

public object TerminalAttachStreamEventSerializer : KSerializer<TerminalAttachStreamEvent> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<TerminalAttachStreamEvent> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "snapshot" -> TerminalAttachStreamEvent.Snapshot.serializer()
      "output" -> TerminalAttachStreamEvent.Output.serializer()
      "exited" -> TerminalAttachStreamEvent.Exited.serializer()
      "closed" -> TerminalAttachStreamEvent.Closed.serializer()
      "error" -> TerminalAttachStreamEvent.Error.serializer()
      "cleared" -> TerminalAttachStreamEvent.Cleared.serializer()
      "restarted" -> TerminalAttachStreamEvent.Restarted.serializer()
      "activity" -> TerminalAttachStreamEvent.Activity.serializer()
      else -> TerminalAttachStreamEvent.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): TerminalAttachStreamEvent {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(TerminalAttachStreamEvent.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: TerminalAttachStreamEvent) {
    when (value) {
      is TerminalAttachStreamEvent.Snapshot ->
        TerminalAttachStreamEvent.Snapshot.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Output ->
        TerminalAttachStreamEvent.Output.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Exited ->
        TerminalAttachStreamEvent.Exited.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Closed ->
        TerminalAttachStreamEvent.Closed.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Error ->
        TerminalAttachStreamEvent.Error.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Cleared ->
        TerminalAttachStreamEvent.Cleared.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Restarted ->
        TerminalAttachStreamEvent.Restarted.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Activity ->
        TerminalAttachStreamEvent.Activity.serializer().serialize(encoder, value)
      is TerminalAttachStreamEvent.Unknown ->
        TerminalAttachStreamEvent.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownTerminalAttachStreamEventSerializer :
  KSerializer<TerminalAttachStreamEvent.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): TerminalAttachStreamEvent.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return TerminalAttachStreamEvent.Unknown(
      type = string("type") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: TerminalAttachStreamEvent.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
