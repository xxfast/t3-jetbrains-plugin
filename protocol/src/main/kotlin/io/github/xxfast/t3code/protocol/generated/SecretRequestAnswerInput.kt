// Generated from v0.1.5 by contracts/generate.ts, do not edit.

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
public data class SecretRequestAnswerInput(
  val threadId: String,
  val turnItemId: String,
  val answer: SecretRequestAnswerInput.Answer,
) {
  @Serializable(with = Answer.AnswerSerializer::class)
  public sealed interface Answer {
    public val type: String

    @Serializable
    public data class Save(
      @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "save",
      val secret: String,
    ) : SecretRequestAnswerInput.Answer

    @Serializable
    public data class Decline(
      @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "decline",
    ) : SecretRequestAnswerInput.Answer

    /** A variant this build doesn't know. [raw] is the whole object as received. */
    @Serializable(with = Answer.UnknownAnswerSerializer::class)
    public data class Unknown(
      override val type: String,
      val raw: JsonObject,
    ) : Answer

    public object AnswerSerializer : KSerializer<Answer> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      private fun select(element: JsonObject): DeserializationStrategy<Answer> =
        when (element["type"]?.jsonPrimitive?.contentOrNull) {
          "save" -> Answer.Save.serializer()
          "decline" -> Answer.Decline.serializer()
          else -> Answer.Unknown.serializer()
        }

      override fun deserialize(decoder: Decoder): Answer {
        val input: JsonDecoder = decoder as JsonDecoder
        val element: JsonObject = input.decodeJsonElement().jsonObject
        return try {
          input.json.decodeFromJsonElement(select(element), element)
        } catch (_: IllegalArgumentException) {
          input.json.decodeFromJsonElement(Answer.Unknown.serializer(), element)
        }
      }

      override fun serialize(encoder: Encoder, value: Answer) {
        when (value) {
          is Answer.Save -> Answer.Save.serializer().serialize(encoder, value)
          is Answer.Decline -> Answer.Decline.serializer().serialize(encoder, value)
          is Answer.Unknown -> Answer.Unknown.serializer().serialize(encoder, value)
        }
      }
    }

    public object UnknownAnswerSerializer : KSerializer<Answer.Unknown> {
      override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

      override fun deserialize(decoder: Decoder): Answer.Unknown {
        val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
        return Answer.Unknown(
          type = string("type") ?: "unknown",
          raw = raw,
        )
      }

      override fun serialize(encoder: Encoder, value: Answer.Unknown) {
        (encoder as JsonEncoder).encodeJsonElement(value.raw)
      }
    }
  }
}
