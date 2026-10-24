package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.ProjectScript
import io.github.xxfast.t3code.protocol.generated.ProjectShell
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ProjectScriptsTest {
  private val default: ProjectScript = script("default")
  private val own: ProjectScript = script("own")
  private val row: ProjectScript = script("row")

  @Test
  fun `a project settings override wins outright`() {
    val settings = ProjectScriptSettings(
      defaultProjectScripts = listOf(default),
      projectScriptOverrides = mapOf("p" to listOf(script("legacy"))),
      projectSettingsScripts = mapOf("p" to listOf(own)),
    )
    assertEquals(listOf(own), resolveProjectScripts(settings, project("p", row)))
  }

  @Test
  fun `folded settings answer the defaults for everyone else`() {
    val settings = ProjectScriptSettings(
      defaultProjectScripts = listOf(default),
      projectScriptOverrides = mapOf("p" to listOf(script("legacy"))),
      projectSettingsFolded = true,
    )
    assertEquals(listOf(default), resolveProjectScripts(settings, project("p", row)))
  }

  @Test
  fun `a legacy override list is the answer, a legacy null means the defaults`() {
    val settings = ProjectScriptSettings(
      defaultProjectScripts = listOf(default),
      projectScriptOverrides = mapOf("a" to listOf(own), "b" to null),
    )
    assertEquals(listOf(own), resolveProjectScripts(settings, project("a", row)))
    assertEquals(listOf(default), resolveProjectScripts(settings, project("b", row)))
  }

  @Test
  fun `otherwise the row, and the defaults when the row is empty`() {
    val settings = ProjectScriptSettings(defaultProjectScripts = listOf(default))
    assertEquals(listOf(row), resolveProjectScripts(settings, project("p", row)))
    assertEquals(listOf(default), resolveProjectScripts(settings, project("p")))
    assertEquals(emptyList(), resolveProjectScripts(ProjectScriptSettings(), project("p")))
  }

  private fun script(id: String): ProjectScript =
    ProjectScript(id = id, name = id, command = id, icon = "run", runOnWorktreeCreate = false)

  private fun project(id: String, vararg scripts: ProjectScript): Project = ProjectShell(
    id = id,
    title = id,
    workspaceRoot = "/repo/$id",
    defaultModelSelection = null,
    scripts = scripts.toList(),
    createdAt = "2026-10-01T00:00:00.000Z",
    updatedAt = "2026-10-01T00:00:00.000Z",
  )
}
