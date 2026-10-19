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

@Serializable
public data class RuntimeRequest(
  val id: String,
  val nodeId: String,
  val providerTurnId: String?,
  val nativeRequestRef: ProviderRef?,
  val kind: String,
  val status: String,
  val responseCapability: RuntimeRequest.ResponseCapability,
  val createdAt: String,
  val resolvedAt: String?,
  val decision: String? = null,
  // TODO(contracts): object with no declared properties
  val answers: JsonObject? = null,
) {
  @Serializable(with = ResponseCapability.ResponseCapabilitySerializer::class)
  public sealed interface ResponseCapability {
    public val type: String

    @Serializable
    public data class Live(
      override val type: String = "live",
      val providerSessionId: String,
    ) : RuntimeRequest.ResponseCapability

    @Serializable
    public data class Message(
      override val type: String = "message",
    ) : RuntimeRequest.ResponseCapability

    @Serializable
    public data class NotResumable(
      override val type: String = "not_resumable",
      val reason: String,
    ) : RuntimeRequest.ResponseCapability

    /** A variant this build doesn't know. [raw] is the whole object as received. */
    @Serializable(with = ResponseCapability.UnknownResponseCapabilitySerializer::class)
    public data class Unknown(
      override val type: String,
      val raw: JsonObject,
    ) : ResponseCapability

    public object ResponseCapabilitySerializer :
      JsonContentPolymorphicSerializer<ResponseCapability>(ResponseCapability::class) {
      override fun selectDeserializer(
        element: JsonElement,
      ): DeserializationStrategy<ResponseCapability> =
        when (element.jsonObject["type"]?.jsonPrimitive?.contentOrNull) {
          "live" -> ResponseCapability.Live.serializer()
          "message" -> ResponseCapability.Message.serializer()
          "not_resumable" -> ResponseCapability.NotResumable.serializer()
          else -> ResponseCapability.Unknown.serializer()
        }
    }

    public object UnknownResponseCapabilitySerializer : KSerializer<ResponseCapability.Unknown> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      override fun deserialize(decoder: Decoder): ResponseCapability.Unknown {
        val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
        return ResponseCapability.Unknown(
          type = string("type") ?: "unknown",
          raw = raw,
        )
      }

      override fun serialize(encoder: Encoder, value: ResponseCapability.Unknown) {
        (encoder as JsonEncoder).encodeJsonElement(value.raw)
      }
    }
  }
}
