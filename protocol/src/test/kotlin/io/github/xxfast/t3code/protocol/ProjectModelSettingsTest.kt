package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProjectShell
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ProjectModelSettingsTest {
  private val environment: ModelSelection = selection("environment")
  private val override: ModelSelection = selection("override")
  private val row: ModelSelection = selection("row")

  @Test
  fun `a project settings override wins, a null one means none`() {
    val settings = ProjectModelSettings(
      defaultModelSelection = environment,
      projectSettingsModels = mapOf("a" to override, "b" to null),
    )
    assertEquals(override, resolveProjectDefaultModelSelection(settings, project("a", row)))
    assertNull(resolveProjectDefaultModelSelection(settings, project("b", row)))
  }

  @Test
  fun `the row counts until the settings are folded`() {
    val legacy = ProjectModelSettings(defaultModelSelection = environment)
    assertEquals(row, resolveProjectDefaultModelSelection(legacy, project("p", row)))
    val folded = legacy.copy(projectSettingsFolded = true)
    assertEquals(environment, resolveProjectDefaultModelSelection(folded, project("p", row)))
  }

  @Test
  fun `otherwise the environment's default`() {
    val settings = ProjectModelSettings(defaultModelSelection = environment)
    assertEquals(environment, resolveProjectDefaultModelSelection(settings, project("p")))
    assertNull(resolveProjectDefaultModelSelection(ProjectModelSettings(), project("p")))
  }

  private fun selection(model: String): ModelSelection =
    ModelSelection(instanceId = JsonPrimitive("claude"), model = JsonPrimitive(model))

  private fun project(id: String, default: ModelSelection? = null): Project = ProjectShell(
    id = id,
    title = id,
    workspaceRoot = "/repo/$id",
    defaultModelSelection = default,
    scripts = emptyList(),
    createdAt = "2026-10-01T00:00:00.000Z",
    updatedAt = "2026-10-01T00:00:00.000Z",
  )
}
