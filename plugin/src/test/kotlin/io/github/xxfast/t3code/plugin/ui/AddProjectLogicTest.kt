package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class AddProjectLogicTest {
  @Test
  fun `paths lose trailing separators, roots keep theirs`() {
    assertEquals("/code/kstore", normalizeProjectPathForDispatch(" /code/kstore// "))
    assertEquals("C:\\code\\kstore", normalizeProjectPathForDispatch("C:\\code\\kstore\\"))
    assertEquals("/", normalizeProjectPathForDispatch("/"))
    assertEquals("C:\\", normalizeProjectPathForDispatch("C:\\"))
    assertEquals("C:/", normalizeProjectPathForDispatch("C:/"))
    assertEquals("C:\\", normalizeProjectPathForDispatch("C:"), "A bare drive isn't its root")
  }

  @Test
  fun `windows paths compare backslashed and lower case, posix ones as they are`() {
    assertEquals("c:\\code\\kstore", normalizeProjectPathForComparison("C:/Code/KStore/"))
    assertEquals("/Code/KStore", normalizeProjectPathForComparison("/Code/KStore/"))
    assertEquals("c:\\", normalizeProjectPathForComparison("C:/"))
  }

  @Test
  fun `the title is the last segment`() {
    assertEquals("kstore", inferProjectTitleFromPath("/code/kstore/"))
    assertEquals("KStore", inferProjectTitleFromPath("C:\\Developer\\KStore"))
  }

  @Test
  fun `a folder that's already a project opens its latest unsettled thread`() {
    val kstore: Project = project("kstore", "C:\\Developer\\KStore")
    val older: Thread = BASE_THREAD.copy(id = "a", projectId = "kstore", updatedAt = EARLY)
    val newer: Thread = BASE_THREAD.copy(id = "b", projectId = "kstore", updatedAt = LATE)
    val other: Thread = BASE_THREAD.copy(id = "c", projectId = "router", updatedAt = LATE)

    val plan: AddProjectPlan =
      planAddProject("c:/developer/kstore/", listOf(kstore), listOf(older, newer, other))

    assertEquals(AddProjectPlan.Existing(kstore, newer), plan)
    val settled: Thread = newer.copy(settledOverride = "settled")
    val parked: AddProjectPlan =
      planAddProject("C:\\Developer\\KStore", listOf(kstore), listOf(older, settled))
    val existing: AddProjectPlan.Existing = assertIs(parked)
    assertNull(existing.latest, "A settled latest starts a new thread")
  }

  @Test
  fun `a new folder is created under its last segment`() {
    val known: List<Project> = listOf(project("k", "/code/k"))
    val plan: AddProjectPlan = planAddProject("/code/fresh/", known, emptyList())

    assertEquals(AddProjectPlan.Create(title = "fresh", workspaceRoot = "/code/fresh"), plan)
    assertEquals(AddProjectPlan.None, planAddProject("  ", emptyList(), emptyList()))
  }
}

private const val EARLY: String = "2026-10-19T10:00:00Z"
private const val LATE: String = "2026-10-19T12:00:00Z"

private fun project(id: String, root: String): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "$id", "title": "$id", "workspaceRoot": "${root.replace("\\", "\\\\")}", "scripts": [],
      "createdAt": "$EARLY", "updatedAt": "$EARLY"
    }
  """,
)
