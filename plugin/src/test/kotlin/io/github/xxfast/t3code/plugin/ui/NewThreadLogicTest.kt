package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class NewThreadLogicTest {
  private val ready: ServerProvider = provider(
    "claude",
    models = listOf(model("custom", isCustom = true), model("sonnet"), model("opus", true)),
  )

  @Test
  fun `the project's default wins`() {
    val own = ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("gpt"))
    assertEquals(own, defaultModelSelection(project(default = own), listOf(ready)))
  }

  @Test
  fun `the first ready provider's default model`() {
    val providers: List<ServerProvider> = listOf(provider("off", enabled = false), ready)
    val selection: ModelSelection? = defaultModelSelection(project(), providers)
    assertEquals(JsonPrimitive("claude"), selection?.instanceId)
    assertEquals(JsonPrimitive("opus"), selection?.model)
  }

  @Test
  fun `the first non custom model without a default`() {
    val models: List<ServerProviderModel> = listOf(model("custom", isCustom = true), model("haiku"))
    val selection: ModelSelection? = defaultModelSelection(project(), listOf(provider("c", models)))
    assertEquals(JsonPrimitive("haiku"), selection?.model)
  }

  @Test
  fun `the last pick stands in for the server default`() {
    val sticky = selection("claude", "sonnet")
    assertEquals(sticky, newThreadModelSelection(project(), listOf(ready), sticky, carry = null))
  }

  @Test
  fun `a carried selection beats the last pick and the project default beats both`() {
    val sticky = selection("claude", "sonnet")
    val carry = selection("claude", "opus")
    val own = selection("codex", "gpt")
    assertEquals(carry, newThreadModelSelection(project(), listOf(ready), sticky, carry))
    assertEquals(own, newThreadModelSelection(project(default = own), listOf(ready), sticky, carry))
  }

  @Test
  fun `a pick on a provider that isn't ready is skipped`() {
    val stale = selection("off", "gpt")
    val providers: List<ServerProvider> = listOf(provider("off", enabled = false), ready)
    val selection: ModelSelection? = newThreadModelSelection(project(), providers, stale, stale)
    assertEquals(JsonPrimitive("opus"), selection?.model)
  }

  @Test
  fun `nothing without a ready provider`() {
    val providers: List<ServerProvider> = listOf(
      provider("off", listOf(model("a")), enabled = false),
      provider("busy", listOf(model("b")), status = "error"),
    )
    assertNull(defaultModelSelection(project(), providers))
    assertNull(createThread(project(), providers, "t", "c"))
  }

  @Test
  fun `the command's fixed fields`() {
    val command = assertNotNull(createThread(project(), listOf(ready), "t", "c"))
    assertEquals("thread.create", command.type)
    assertEquals("user", command.createdBy)
    assertEquals("web", command.creationSource)
    assertEquals("t", command.threadId)
    assertEquals("c", command.commandId)
    assertEquals("p", command.projectId)
    assertEquals("New thread", command.title)
    assertEquals("full-access", command.runtimeMode)
    assertEquals("default", command.interactionMode)
    assertNull(command.branch)
    assertNull(command.worktreePath)
  }
}

private fun selection(instance: String, model: String): ModelSelection =
  ModelSelection(instanceId = JsonPrimitive(instance), model = JsonPrimitive(model))

private fun project(default: ModelSelection? = null): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "p", "title": "P", "workspaceRoot": "/code/p", "scripts": [],
      "createdAt": "1970-01-01T00:00:00Z", "updatedAt": "1970-01-01T00:00:00Z"
    }
  """,
).copy(defaultModelSelection = default)

private fun provider(
  instanceId: String,
  models: List<ServerProviderModel> = emptyList(),
  enabled: Boolean = true,
  status: String = "ready",
): ServerProvider = ServerProvider(
  instanceId = instanceId,
  driver = instanceId,
  enabled = enabled,
  installed = true,
  version = null,
  status = status,
  auth = ServerProviderAuth(status = "authenticated"),
  checkedAt = "2026-10-20T10:00:00Z",
  models = models,
)

private fun model(
  slug: String,
  isDefault: Boolean? = null,
  isCustom: Boolean = false,
): ServerProviderModel = ServerProviderModel(
  slug = slug,
  name = slug,
  isCustom = isCustom,
  isDefault = isDefault,
  capabilities = null,
)
