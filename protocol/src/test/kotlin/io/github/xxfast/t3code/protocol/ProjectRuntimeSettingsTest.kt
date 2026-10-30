package io.github.xxfast.t3code.protocol

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ProjectRuntimeSettingsTest {
  private val settings = ProjectRuntimeSettings(
    defaultRuntimeMode = "approval-required",
    projectSettingsRuntimeModes = mapOf("a" to "auto"),
  )

  @Test
  fun `a project settings override wins`() {
    assertEquals("auto", resolveProjectDefaultRuntimeMode(settings, "a"))
  }

  @Test
  fun `otherwise the environment's default`() {
    assertEquals("approval-required", resolveProjectDefaultRuntimeMode(settings, "b"))
    assertEquals("approval-required", resolveProjectDefaultRuntimeMode(settings, null))
  }

  @Test
  fun `absent settings fall back to full access`() {
    assertEquals(
      DEFAULT_RUNTIME_MODE,
      resolveProjectDefaultRuntimeMode(ProjectRuntimeSettings(), "a"),
    )
    assertEquals("full-access", DEFAULT_RUNTIME_MODE)
  }
}
