// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.DeserializationStrategy
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

@Serializable(with = ThreadStreamItemSerializer::class)
public sealed interface ThreadStreamItem {
  public val kind: String

  @Serializable
  public data class Synchronized(
    override val kind: String = "synchronized",
  ) : ThreadStreamItem

  @Serializable
  public data class Snapshot(
    override val kind: String = "snapshot",
    val snapshotSequence: Long,
    val projection: ThreadProjection,
    val historyCursor: String? = null,
    val hasMoreHistory: Boolean? = null,
    val latestLocalTurnOrdinal: Long? = null,
    val payloadBudgetExceeded: Boolean? = null,
    val turnItemsOmitLocalVisible: Boolean? = null,
  ) : ThreadStreamItem

  @Serializable
  public data class Event(
    override val kind: String = "event",
    val sequence: Long,
    val event: DomainEvent,
  ) : ThreadStreamItem

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownThreadStreamItemSerializer::class)
  public data class Unknown(
    override val kind: String,
    val raw: JsonObject,
  ) : ThreadStreamItem
}

public object ThreadStreamItemSerializer : KSerializer<ThreadStreamItem> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ThreadStreamItem> =
    when (element["kind"]?.jsonPrimitive?.contentOrNull) {
      "synchronized" -> ThreadStreamItem.Synchronized.serializer()
      "snapshot" -> ThreadStreamItem.Snapshot.serializer()
      "event" -> ThreadStreamItem.Event.serializer()
      else -> ThreadStreamItem.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ThreadStreamItem {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ThreadStreamItem.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ThreadStreamItem) {
    when (value) {
      is ThreadStreamItem.Synchronized ->
        ThreadStreamItem.Synchronized.serializer().serialize(encoder, value)
      is ThreadStreamItem.Snapshot ->
        ThreadStreamItem.Snapshot.serializer().serialize(encoder, value)
      is ThreadStreamItem.Event -> ThreadStreamItem.Event.serializer().serialize(encoder, value)
      is ThreadStreamItem.Unknown -> ThreadStreamItem.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownThreadStreamItemSerializer : KSerializer<ThreadStreamItem.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ThreadStreamItem.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ThreadStreamItem.Unknown(
      kind = string("kind") ?: "unknown",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ThreadStreamItem.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
