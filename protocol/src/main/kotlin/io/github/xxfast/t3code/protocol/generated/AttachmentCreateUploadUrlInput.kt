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
import kotlinx.serialization.json.longOrNull

@Serializable(with = AttachmentCreateUploadUrlInputSerializer::class)
public sealed interface AttachmentCreateUploadUrlInput {
  public val type: String
  public val name: String
  public val mimeType: String
  public val sizeBytes: Long

  @Serializable
  public data class Image(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "image",
    override val name: String,
    override val mimeType: String,
    override val sizeBytes: Long,
  ) : AttachmentCreateUploadUrlInput

  @Serializable
  public data class File(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "file",
    override val name: String,
    override val mimeType: String,
    override val sizeBytes: Long,
  ) : AttachmentCreateUploadUrlInput

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownAttachmentCreateUploadUrlInputSerializer::class)
  public data class Unknown(
    override val type: String,
    override val name: String,
    override val mimeType: String,
    override val sizeBytes: Long,
    val raw: JsonObject,
  ) : AttachmentCreateUploadUrlInput
}

public object AttachmentCreateUploadUrlInputSerializer :
  KSerializer<AttachmentCreateUploadUrlInput> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<AttachmentCreateUploadUrlInput> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "image", null -> AttachmentCreateUploadUrlInput.Image.serializer()
      "file" -> AttachmentCreateUploadUrlInput.File.serializer()
      else -> AttachmentCreateUploadUrlInput.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): AttachmentCreateUploadUrlInput {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(AttachmentCreateUploadUrlInput.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: AttachmentCreateUploadUrlInput) {
    when (value) {
      is AttachmentCreateUploadUrlInput.Image ->
        AttachmentCreateUploadUrlInput.Image.serializer().serialize(encoder, value)
      is AttachmentCreateUploadUrlInput.File ->
        AttachmentCreateUploadUrlInput.File.serializer().serialize(encoder, value)
      is AttachmentCreateUploadUrlInput.Unknown ->
        AttachmentCreateUploadUrlInput.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownAttachmentCreateUploadUrlInputSerializer :
  KSerializer<AttachmentCreateUploadUrlInput.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): AttachmentCreateUploadUrlInput.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String): Long? = raw[key]?.jsonPrimitive?.longOrNull
    return AttachmentCreateUploadUrlInput.Unknown(
      type = string("type") ?: "unknown",
      name = string("name") ?: "",
      mimeType = string("mimeType") ?: "",
      sizeBytes = long("sizeBytes") ?: 0L,
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: AttachmentCreateUploadUrlInput.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
