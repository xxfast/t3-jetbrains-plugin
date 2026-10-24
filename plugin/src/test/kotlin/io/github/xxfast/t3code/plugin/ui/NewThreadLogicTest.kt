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
    assertEquals(own, defaultModelSelection(own, listOf(ready)))
  }

  @Test
  fun `the first ready provider's default model`() {
    val providers: List<ServerProvider> = listOf(provider("off", enabled = false), ready)
    val selection: ModelSelection? = defaultModelSelection(null, providers)
    assertEquals(JsonPrimitive("claude"), selection?.instanceId)
    assertEquals(JsonPrimitive("opus"), selection?.model)
  }

  @Test
  fun `the first non custom model without a default`() {
    val models: List<ServerProviderModel> = listOf(model("custom", isCustom = true), model("haiku"))
    val selection: ModelSelection? = defaultModelSelection(null, listOf(provider("c", models)))
    assertEquals(JsonPrimitive("haiku"), selection?.model)
  }

  @Test
  fun `the last pick stands in for the server default`() {
    val sticky = selection("claude", "sonnet")
    assertEquals(sticky, newThreadModelSelection(null, listOf(ready), sticky, carry = null))
  }

  @Test
  fun `a carried selection beats the last pick and the project default beats both`() {
    val sticky = selection("claude", "sonnet")
    val carry = selection("claude", "opus")
    val own = selection("codex", "gpt")
    assertEquals(carry, newThreadModelSelection(null, listOf(ready), sticky, carry))
    assertEquals(own, newThreadModelSelection(own, listOf(ready), sticky, carry))
  }

  @Test
  fun `a pick on a provider that isn't ready is skipped`() {
    val stale = selection("off", "gpt")
    val providers: List<ServerProvider> = listOf(provider("off", enabled = false), ready)
    val selection: ModelSelection? = newThreadModelSelection(null, providers, stale, stale)
    assertEquals(JsonPrimitive("opus"), selection?.model)
  }

  @Test
  fun `nothing without a ready provider`() {
    val providers: List<ServerProvider> = listOf(
      provider("off", listOf(model("a")), enabled = false),
      provider("busy", listOf(model("b")), status = "error"),
    )
    assertNull(defaultModelSelection(null, providers))
    assertNull(newDraftThread(project(), providers, "t", "1970-01-01T00:00:00Z"))
  }

  @Test
  fun `a draft's fixed fields`() {
    val draft: DraftThread =
      assertNotNull(newDraftThread(project(), listOf(ready), "t", "1970-01-01T00:00:00Z"))
    assertEquals("t", draft.threadId)
    assertEquals("p", draft.projectId)
    assertEquals("full-access", draft.runtimeMode)
    assertEquals("default", draft.interactionMode)
    assertNull(draft.branch)
    assertNull(draft.worktreePath)
  }

  @Test
  fun `the local draft thread is an idle new thread with the draft's picks`() {
    val draft = DraftThread(
      threadId = "t",
      projectId = "p",
      createdAt = "1970-01-01T00:00:00Z",
      modelSelection = selection("claude", "opus"),
      interactionMode = "plan",
      branch = "main",
    )
    val thread = buildLocalDraftThread(draft)
    assertEquals("New thread", thread.title)
    assertEquals("claude", thread.providerInstanceId)
    assertEquals("plan", thread.interactionMode)
    assertEquals("main", thread.branch)
    assertEquals("idle", thread.status)
    assertEquals("t", thread.lineage.rootThreadId)
    assertEquals(0, draftThreadView(draft).items.size)
  }
}

private fun selection(instance: String, model: String): ModelSelection =
  ModelSelection(instanceId = JsonPrimitive(instance), model = JsonPrimitive(model))

private fun project(): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "p", "title": "P", "workspaceRoot": "/code/p", "scripts": [],
      "createdAt": "1970-01-01T00:00:00Z", "updatedAt": "1970-01-01T00:00:00Z"
    }
  """,
)

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
