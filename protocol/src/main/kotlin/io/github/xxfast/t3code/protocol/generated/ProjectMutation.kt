// Generated from v0.1.2 by contracts/generate.ts, do not edit.

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

@Serializable(with = ProjectMutationSerializer::class)
public sealed interface ProjectMutation {
  public val type: String
  public val commandId: String
  public val projectId: String

  @Serializable
  public data class ProjectCreate(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "project.create",
    override val commandId: String,
    override val projectId: String,
    val title: String,
    val workspaceRoot: String,
    val createWorkspaceRootIfMissing: Boolean? = null,
    val defaultModelSelection: ModelSelection? = null,
    val scripts: List<ProjectScript>? = null,
  ) : ProjectMutation

  @Serializable
  public data class ProjectUpdate(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "project.update",
    override val commandId: String,
    override val projectId: String,
    val title: String? = null,
    val workspaceRoot: String? = null,
    val defaultModelSelection: ModelSelection? = null,
    val autoPull: Boolean? = null,
    val projectIcon: ProjectIconOverride? = null,
    val faviconPath: String? = null,
    val defaultThreadEnvMode: String? = null,
    val scripts: List<ProjectScript>? = null,
  ) : ProjectMutation

  @Serializable
  public data class ProjectDelete(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val type: String = "project.delete",
    override val commandId: String,
    override val projectId: String,
    val force: Boolean? = null,
  ) : ProjectMutation

  /** A variant this build doesn't know. [raw] is the whole object as received. */
  @Serializable(with = UnknownProjectMutationSerializer::class)
  public data class Unknown(
    override val type: String,
    override val commandId: String,
    override val projectId: String,
    val raw: JsonObject,
  ) : ProjectMutation
}

public object ProjectMutationSerializer : KSerializer<ProjectMutation> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  private fun select(element: JsonObject): DeserializationStrategy<ProjectMutation> =
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "project.create" -> ProjectMutation.ProjectCreate.serializer()
      "project.update" -> ProjectMutation.ProjectUpdate.serializer()
      "project.delete" -> ProjectMutation.ProjectDelete.serializer()
      else -> ProjectMutation.Unknown.serializer()
    }

  override fun deserialize(decoder: Decoder): ProjectMutation {
    val input: JsonDecoder = decoder as JsonDecoder
    val element: JsonObject = input.decodeJsonElement().jsonObject
    return try {
      input.json.decodeFromJsonElement(select(element), element)
    } catch (_: IllegalArgumentException) {
      input.json.decodeFromJsonElement(ProjectMutation.Unknown.serializer(), element)
    }
  }

  override fun serialize(encoder: Encoder, value: ProjectMutation) {
    when (value) {
      is ProjectMutation.ProjectCreate ->
        ProjectMutation.ProjectCreate.serializer().serialize(encoder, value)
      is ProjectMutation.ProjectUpdate ->
        ProjectMutation.ProjectUpdate.serializer().serialize(encoder, value)
      is ProjectMutation.ProjectDelete ->
        ProjectMutation.ProjectDelete.serializer().serialize(encoder, value)
      is ProjectMutation.Unknown -> ProjectMutation.Unknown.serializer().serialize(encoder, value)
    }
  }
}

public object UnknownProjectMutationSerializer : KSerializer<ProjectMutation.Unknown> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun deserialize(decoder: Decoder): ProjectMutation.Unknown {
    val raw: JsonObject = (decoder as JsonDecoder).decodeJsonElement().jsonObject
    fun string(key: String): String? = raw[key]?.jsonPrimitive?.contentOrNull
    return ProjectMutation.Unknown(
      type = string("type") ?: "unknown",
      commandId = string("commandId") ?: "",
      projectId = string("projectId") ?: "",
      raw = raw,
    )
  }

  override fun serialize(encoder: Encoder, value: ProjectMutation.Unknown) {
    (encoder as JsonEncoder).encodeJsonElement(value.raw)
  }
}
