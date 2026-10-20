package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class CommandPaletteLogicTest {
  private fun items(
    projects: List<Project>,
    threads: List<Thread> = emptyList(),
    preferred: String? = null,
    query: String = "",
  ): List<CommandPaletteItem> =
    newThreadInGroups(projects, threads, preferred, "Local", query).flatMap { it.items }

  @Test
  fun `no project goes second and numbering skips it`() {
    val items: List<CommandPaletteItem> = items(listOf(project("a", "Alpha"), project("b", "Beta")))
    assertEquals(listOf("a", null, "b"), items.map { it.projectId })
    assertEquals(
      listOf("thread.jump.1", NEW_WITHOUT_PROJECT_COMMAND, "thread.jump.2"),
      items.map { it.shortcutCommand },
    )
  }

  @Test
  fun `no project shows disabled and the arrows skip it`() {
    val items: List<CommandPaletteItem> = items(listOf(project("a", "Alpha"), project("b", "Beta")))
    assertEquals(listOf(false, true, false), items.map { it.disabled })
    assertEquals(2, nextEnabledIndex(items, from = 0, step = 1))
    assertEquals(0, nextEnabledIndex(items, from = 2, step = 1))
    assertEquals(0, nextEnabledIndex(items, from = 2, step = -1))
    assertEquals(0, nextEnabledIndex(items, from = -1, step = 1))
    assertEquals(-1, nextEnabledIndex(items(emptyList()), from = -1, step = 1))
  }

  @Test
  fun `no project is alone without projects`() {
    val items: List<CommandPaletteItem> = items(emptyList())
    assertEquals(listOf(NO_PROJECT_VALUE), items.map { it.value })
  }

  @Test
  fun `subtitle is the location and workspace root`() {
    val items: List<CommandPaletteItem> = items(listOf(project("a", "Alpha")))
    assertEquals("Local · /code/a", items.first().description)
    assertNull(items.last().description)
  }

  @Test
  fun `preferred project moves first`() {
    val projects: List<Project> = listOf(project("a", "A"), project("b", "B"), project("c", "C"))
    val picked: List<Project> = buildSidebarProjectPickerEntries(projects, "c")
    assertEquals(listOf("c", "a", "b"), picked.map { it.id })
    val unknown: List<Project> = buildSidebarProjectPickerEntries(projects, "x")
    assertEquals(listOf("a", "b", "c"), unknown.map { it.id })
    assertEquals(listOf("c", null, "a", "b"), items(projects, preferred = "c").map { it.projectId })
  }

  @Test
  fun `projects sort by latest live thread activity then title`() {
    val projects: List<Project> = listOf(
      project("z", "Zulu"),
      project("b", "bravo"),
      project("a", "Alpha"),
      project("old", "Old"),
    )
    val threads: List<Thread> = listOf(
      thread("t1", "old", updatedAt = "2026-01-01T00:00:00Z"),
      thread("t2", "z", updatedAt = "2026-01-02T00:00:00Z"),
      thread("t3", "old", updatedAt = "2026-02-01T00:00:00Z", archivedAt = "2026-02-02T00:00:00Z"),
      thread(
        "t4", "z",
        updatedAt = "2025-01-01T00:00:00Z",
        latestUserMessage = "2026-03-01T00:00:00Z",
      ),
    )
    assertEquals(
      listOf("z", "old", "a", "b"),
      sortProjectsForSidebar(projects, threads).map { it.id },
    )
  }

  @Test
  fun `filter needs every word and ranks earlier terms higher`() {
    val items: List<CommandPaletteItem> =
      items(listOf(project("web", "Web app"), project("api", "API web")), query = "web")
    // "API web" holds the query in its title too, but "Web app" starts with it
    assertEquals(listOf("web", "api"), items.map { it.projectId })
    assertEquals(emptyList(), items(listOf(project("web", "Web app")), query = "web nope"))
    val none: List<CommandPaletteItem> = items(listOf(project("web", "Web app")), query = "none")
    assertEquals(listOf(null), none.map { it.projectId })
  }

  // Upstream: "ranks an order-independent setting title match above a split context match"
  @Test
  fun `order independent title match outranks a split context match`() {
    val groups: List<CommandPaletteGroup> = filterCommandPaletteGroups(
      activeGroups = listOf(
        CommandPaletteGroup(
          value = "settings-search",
          label = "Settings",
          items = listOf(
            item("setting:context-match", "Pairing settings", "remote backend"),
            item("setting:remote-pairing", "Remote pairing", "connections"),
          ),
        ),
      ),
      query = "pairing remote",
    )
    assertEquals(
      listOf("setting:remote-pairing", "setting:context-match"),
      groups.single().items.map { it.value },
    )
  }

  // Upstream: "keeps accent-insensitive setting results"
  @Test
  fun `accents don't matter`() {
    val groups: List<CommandPaletteGroup> = filterCommandPaletteGroups(
      activeGroups = listOf(CommandPaletteGroup("s", "S", listOf(item("themes", "Themes")))),
      query = "thè᪰mes",
    )
    assertEquals(listOf("themes"), groups.single().items.map { it.value })
  }

  // Upstream: "assigns positional jump shortcuts to the first nine displayed items"
  @Test
  fun `first nine get jump shortcuts`() {
    val items: List<CommandPaletteItem> = (1..10).map { index ->
      item("project-$index", "Project $index").copy(shortcutCommand = "chat.new")
    }
    assertEquals(
      THREAD_JUMP_KEYBINDING_COMMANDS + listOf(null),
      enumerateCommandPaletteItems(items).map { it.shortcutCommand },
    )
  }

  @Test
  fun `shortcut labels per platform`() {
    assertEquals("⌘1", shortcutLabelForCommand("thread.jump.1", isMac = true))
    assertEquals("⌥⌘N", shortcutLabelForCommand(NEW_WITHOUT_PROJECT_COMMAND, isMac = true))
    assertEquals("Ctrl+1", shortcutLabelForCommand("thread.jump.1", isMac = false))
    assertEquals("Ctrl+Alt+N", shortcutLabelForCommand(NEW_WITHOUT_PROJECT_COMMAND, isMac = false))
    assertNull(shortcutLabelForCommand(null, isMac = true))
  }

  @Test
  fun `key presses resolve per platform`() {
    fun resolve(key: String, meta: Boolean, ctrl: Boolean, alt: Boolean, isMac: Boolean): String? =
      resolveShortcutCommand(key, meta, ctrl, alt, shift = false, isMac = isMac)
    val jump: String? = resolve("3", meta = true, ctrl = false, alt = false, isMac = true)
    assertEquals("thread.jump.3", jump)
    assertNull(resolve("3", meta = true, ctrl = false, alt = false, isMac = false))
    assertEquals(
      NEW_WITHOUT_PROJECT_COMMAND,
      resolve("n", meta = false, ctrl = true, alt = true, isMac = false),
    )
  }
}

private fun item(value: String, vararg terms: String): CommandPaletteItem = CommandPaletteItem(
  value = value,
  label = terms.first(),
  description = null,
  searchTerms = terms.toList(),
  projectId = null,
)

private fun project(id: String, title: String): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "$id", "title": "$title", "workspaceRoot": "/code/$id", "scripts": [],
      "createdAt": "1970-01-01T00:00:00Z", "updatedAt": "1970-01-01T00:00:00Z"
    }
  """,
)

private fun thread(
  id: String,
  projectId: String,
  updatedAt: String,
  archivedAt: String? = null,
  latestUserMessage: String? = null,
): Thread = BASE_THREAD.copy(
  id = id,
  projectId = projectId,
  updatedAt = updatedAt,
  archivedAt = archivedAt,
  latestUserMessageAt = latestUserMessage,
)
