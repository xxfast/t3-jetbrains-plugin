package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.T3JsonOut
import io.github.xxfast.t3code.protocol.generated.ProjectIconOverride
import io.github.xxfast.t3code.protocol.generated.ProjectMutation
import io.github.xxfast.t3code.protocol.generated.StoredProjectIcon
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProjectSettingsLogicTest {
  @Test
  fun `the picker opens on the automatic identity without an override`() {
    val draft: ProjectIconDraft = projectIconDraft(project(title = "KStore"))

    assertEquals(ProjectIconKind.Lucide, draft.kind)
    assertEquals("folder-code", draft.lucide)
    assertEquals("💻", draft.emoji)
    assertEquals(projectMonogram("KStore"), draft.letters)
    assertEquals(projectColorName("KStore"), draft.color)
  }

  @Test
  fun `the picker opens on the current override`() {
    val monogram = StoredProjectIcon.Monogram(text = "KS", color = "rose")
    val draft: ProjectIconDraft = projectIconDraft(project(icon = monogram))

    assertEquals(ProjectIconKind.Monogram, draft.kind)
    assertEquals("KS", draft.letters)
    assertEquals("rose", draft.color)
    val emoji: ProjectIconDraft =
      projectIconDraft(project(icon = StoredProjectIcon.Emoji(emoji = "🦀")))
    assertEquals(ProjectIconKind.Emoji, emoji.kind)
    assertEquals("🦀", emoji.emoji)
  }

  @Test
  fun `monogram letters are normalised and checked as ProjectMonogramText`() {
    assertEquals("KS", normalizeMonogram("  ks "))
    assertTrue(isMonogramText("KS"))
    assertTrue(isMonogramText("T3"))
    assertFalse(isMonogramText(""))
    assertFalse(isMonogramText("-K"))
    assertFalse(isMonogramText("A".repeat(33)))
  }

  @Test
  fun `lucide names are kebab case, at most 64`() {
    assertTrue(isLucideIconName("folder-code"))
    assertTrue(isLucideIconName("git-pull-request-2"))
    assertFalse(isLucideIconName("Folder"))
    assertFalse(isLucideIconName("folder--code"))
    assertFalse(isLucideIconName("a".repeat(65)))
  }

  @Test
  fun `a bad icon says why, a good one doesn't`() {
    val draft: ProjectIconDraft = projectIconDraft(project())

    assertNull(projectIconError(draft))
    val monogram: ProjectIconDraft = draft.copy(kind = ProjectIconKind.Monogram, letters = " ")
    assertEquals("One or two letters or numbers.", projectIconError(monogram))
    assertNotNull(projectIconError(draft.copy(lucide = "Not An Icon")))
    assertNotNull(projectIconError(draft.copy(kind = ProjectIconKind.Emoji, emoji = "")))
  }

  @Test
  fun `the override is the picked kind only`() {
    val draft: ProjectIconDraft = projectIconDraft(project()).copy(color = "teal")

    assertEquals(
      ProjectIconOverride.Lucide(name = "folder-code", color = "teal"),
      projectIconOverride(draft),
    )
    assertEquals(
      ProjectIconOverride.Monogram(text = "AB", color = "teal"),
      projectIconOverride(draft.copy(kind = ProjectIconKind.Monogram, letters = "ab")),
    )
    assertEquals(
      ProjectIconOverride.Emoji(emoji = "💻"),
      projectIconOverride(draft.copy(kind = ProjectIconKind.Emoji)),
    )
  }

  @Test
  fun `the description is upstream's`() {
    assertEquals("Automatic", projectIconDescription(project()))
    val lucide = StoredProjectIcon.Lucide(name = "folder-code", color = "blue")
    assertEquals("folder-code · blue", projectIconDescription(project(icon = lucide)))
    assertEquals("KS · rose", projectIconDescription(project(icon = monogram())))
    assertEquals("public/icon.svg", projectIconDescription(project(favicon = "public/icon.svg")))
  }

  @Test
  fun `title needs an update only when edited and different`() {
    assertTrue(projectGroupTitleNeedsUpdate(listOf("KStore"), "Store", wasEdited = true))
    assertFalse(projectGroupTitleNeedsUpdate(listOf("KStore"), "Store", wasEdited = false))
    assertFalse(projectGroupTitleNeedsUpdate(listOf("KStore"), "KStore", wasEdited = true))
  }

  @Test
  fun `nothing changed sends nothing`() {
    val target: Project = project(icon = monogram())

    assertNull(projectSettingsUpdate(target, "KStore", titleEdited = false, icon = null, "c"))
    assertNull(projectSettingsUpdate(target, " KStore ", titleEdited = true, icon = null, "c"))
    val same = ProjectIconOverride.Monogram(text = "KS", color = "rose")
    assertNull(projectSettingsUpdate(target, "KStore", titleEdited = false, icon = same, "c"))
  }

  @Test
  fun `a rename and a new icon go in one update, trimmed`() {
    val icon = ProjectIconOverride.Emoji(emoji = "🦀")
    val update: ProjectMutation.ProjectUpdate =
      assertNotNull(projectSettingsUpdate(project(), " Store ", titleEdited = true, icon, "c"))

    assertEquals("p", update.projectId)
    assertEquals("Store", update.title)
    assertEquals(icon, update.projectIcon)
  }

  @Test
  fun `the update goes out with its type and without the fields it leaves alone`() {
    val update: ProjectMutation =
      assertNotNull(projectSettingsUpdate(project(), "Store", titleEdited = true, null, "c"))
    val json: String = T3JsonOut.encodeToString(ProjectMutation.serializer(), update)

    val expected = """{"type":"project.update","commandId":"c","projectId":"p","title":"Store"}"""
    assertEquals(expected, json)
  }
}

private fun monogram(): StoredProjectIcon = StoredProjectIcon.Monogram(text = "KS", color = "rose")

private fun project(
  title: String = "KStore",
  icon: StoredProjectIcon? = null,
  favicon: String? = null,
): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "p", "title": "$title", "workspaceRoot": "/code/p", "scripts": [],
      "createdAt": "1970-01-01T00:00:00Z", "updatedAt": "1970-01-01T00:00:00Z"
    }
  """,
).copy(projectIcon = icon, faviconPath = favicon)
