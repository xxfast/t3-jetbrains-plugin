package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.text.Collator
import java.text.Normalizer

/**
 * A palette row, upstream's `CommandPaletteActionItem` (apps/web/src/components/
 * CommandPalette.logic.ts) without the icon and `run`: the UI picks those from [projectId], which
 * is null for "No project". A [disabled] row shows but never runs, as `executeItem` skips it.
 */
data class CommandPaletteItem(
  val value: String,
  val label: String,
  val description: String?,
  val searchTerms: List<String>,
  val shortcutCommand: String? = null,
  val projectId: String?,
  val disabled: Boolean = false,
)

/** Upstream's `CommandPaletteGroup`. */
data class CommandPaletteGroup(
  val value: String,
  val label: String,
  val items: List<CommandPaletteItem>,
)

/** `THREAD_JUMP_KEYBINDING_COMMANDS`, packages/contracts/src/keybindings.ts. */
val THREAD_JUMP_KEYBINDING_COMMANDS: List<String> = (1..9).map { index -> "thread.jump.$index" }

const val NEW_WITHOUT_PROJECT_COMMAND: String = "chat.newWithoutProject"

/** "No project"'s value, as CommandPalette.tsx builds it. */
const val NO_PROJECT_VALUE: String = "new-thread-in:no-project"

/**
 * The "New thread in..." submenu's groups, as CommandPalette.tsx builds `projectThreadItems`
 * (1162 to 1235) and `filterCommandPaletteGroups` filters them for [query]. Projects come in
 * Sidebar order with [preferredProjectId] first, numbered ⌘1 to ⌘9, then "No project" goes in.
 */
fun newThreadInGroups(
  projects: List<Project>,
  threads: List<Thread>,
  preferredProjectId: String?,
  locationLabel: String,
  query: String,
): List<CommandPaletteGroup> {
  val pickerProjects: List<Project> = buildSidebarProjectPickerEntries(
    projects = sortProjectsForSidebar(projects, threads),
    preferredProjectId = preferredProjectId,
  )
  val projectItems: List<CommandPaletteItem> = enumerateCommandPaletteItems(
    buildProjectActionItems(
      projects = pickerProjects,
      valuePrefix = "new-thread-in",
      searchTerms = { listOf(locationLabel) },
      renderDescription = { project -> "$locationLabel · ${project.workspaceRoot}" },
    ),
  )
  // The plugin has no scratch workspace root to match, so the first project is never scratch
  val noProjectIndex = 1
  val noProject = CommandPaletteItem(
    value = NO_PROJECT_VALUE,
    label = "No project",
    description = null,
    searchTerms = listOf("no project", "without project", "none"),
    shortcutCommand = NEW_WITHOUT_PROJECT_COMMAND,
    projectId = null,
    // Upstream opens the scratch project (useScratchProject.ts:56); the protocol can't yet
    disabled = true,
  )
  val items: List<CommandPaletteItem> =
    projectItems.take(noProjectIndex) + noProject + projectItems.drop(noProjectIndex)
  return filterCommandPaletteGroups(
    activeGroups = listOf(CommandPaletteGroup(value = "projects", label = "Projects", items)),
    query = query,
  )
}

/**
 * Where the arrows land from [from], moving [step] and looping as Base UI's list does, past
 * disabled rows. -1 when nothing is enabled.
 */
fun nextEnabledIndex(items: List<CommandPaletteItem>, from: Int, step: Int): Int {
  if (items.none { !it.disabled }) return -1
  var index: Int = if (from < 0 && step < 0) items.size else from
  do {
    index = (index + step).mod(items.size)
  } while (items[index].disabled)
  return index
}

/**
 * `buildProjectActionItems`, CommandPalette.logic.ts:249. A project's display name is its title.
 */
fun buildProjectActionItems(
  projects: List<Project>,
  valuePrefix: String,
  searchTerms: (Project) -> List<String> = { emptyList() },
  renderDescription: ((Project) -> String)? = null,
  shortcutCommand: String? = null,
): List<CommandPaletteItem> = projects.map { project ->
  CommandPaletteItem(
    value = "$valuePrefix:${project.id}",
    label = project.title,
    description = renderDescription?.invoke(project) ?: project.workspaceRoot,
    searchTerms = listOf(project.title, project.title, project.workspaceRoot) +
      searchTerms(project),
    shortcutCommand = shortcutCommand,
    projectId = project.id,
  )
}

/** `enumerateCommandPaletteItems`, CommandPalette.logic.ts:217: the first nine get ⌘1 to ⌘9. */
fun enumerateCommandPaletteItems(items: List<CommandPaletteItem>): List<CommandPaletteItem> =
  items.mapIndexed { index, item ->
    item.copy(shortcutCommand = THREAD_JUMP_KEYBINDING_COMMANDS.getOrNull(index))
  }

/**
 * `filterCommandPaletteGroups`, CommandPalette.logic.ts:415, inside a submenu: every query word
 * must appear somewhere in an item's terms, and matches sort by [rankCommandPaletteItemMatch],
 * ties keeping their order. Items carry no `secondary` or `searchRecency` here.
 */
fun filterCommandPaletteGroups(
  activeGroups: List<CommandPaletteGroup>,
  query: String,
): List<CommandPaletteGroup> {
  val isActionsFilter: Boolean = query.startsWith(">")
  val searchQuery: String = if (isActionsFilter) query.drop(1) else query
  val normalizedQuery: String = normalizeSearchText(searchQuery)
  val baseGroups: List<CommandPaletteGroup> =
    if (isActionsFilter) activeGroups.filter { it.value == "actions" } else activeGroups
  if (normalizedQuery.isEmpty()) return baseGroups
  val queryTokens: List<String> = normalizedQuery.split(" ")

  return baseGroups.mapNotNull { group ->
    val items: List<CommandPaletteItem> = group.items
      .filter { item ->
        val haystack: String = normalizeSearchText(item.searchTerms.joinToString(" "))
        queryTokens.all { token -> haystack.contains(token) }
      }
      .sortedByDescending { item ->
        rankCommandPaletteItemMatch(item, normalizedQuery, queryTokens)
      }
    if (items.isEmpty()) null else group.copy(items = items)
  }
}

/**
 * `rankCommandPaletteItemMatch`, CommandPalette.logic.ts: the first term that holds every token
 * decides, earlier terms ranking higher, then exact over prefix over substring.
 */
fun rankCommandPaletteItemMatch(
  item: CommandPaletteItem,
  normalizedQuery: String,
  queryTokens: List<String>,
): Int {
  val terms: List<String> = item.searchTerms.filter { it.isNotEmpty() }
  terms.forEachIndexed { index, field ->
    val fieldRank: Int? = rankSearchFieldMatch(field, normalizedQuery, queryTokens)
    if (fieldRank != null) return 1_000 - index * 100 + fieldRank
  }
  return 0
}

/** `rankSearchFieldMatch`, CommandPalette.logic.ts. Null is upstream's negative infinity. */
private fun rankSearchFieldMatch(
  field: String,
  normalizedQuery: String,
  queryTokens: List<String>,
): Int? {
  val normalizedField: String = normalizeSearchText(field)
  if (normalizedField.isEmpty() || !queryTokens.all { normalizedField.contains(it) }) return null
  return when {
    normalizedField == normalizedQuery -> 3
    normalizedField.startsWith(normalizedQuery) -> 2
    normalizedField.contains(normalizedQuery) -> 1
    else -> 0
  }
}

private val MARKS: Regex = Regex("\\p{M}")
private val WHITESPACE: Regex = Regex("\\s+")

/** `normalizeSearchText`, apps/web/src/lib/utils.ts: accents off, lowercase, spaces collapsed. */
fun normalizeSearchText(value: String): String =
  Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(MARKS, "")
    .lowercase()
    .replace(WHITESPACE, " ")
    .trim()

/**
 * `buildSidebarProjectPickerEntries`, apps/web/src/sidebarProjectGrouping.ts:173: the preferred
 * project moves to the top, the rest keep their order. The plugin has no logical project groups,
 * so each project is its own entry and its own target.
 */
fun buildSidebarProjectPickerEntries(
  projects: List<Project>,
  preferredProjectId: String?,
): List<Project> {
  val preferredIndex: Int = projects.indexOfFirst { it.id == preferredProjectId }
  if (preferredIndex <= 0) return projects
  return listOf(projects[preferredIndex]) +
    projects.subList(0, preferredIndex) +
    projects.subList(preferredIndex + 1, projects.size)
}

private val TITLE_ORDER: Collator = Collator.getInstance()

/**
 * `sortLogicalProjectsForSidebar`, apps/web/src/components/Sidebar.logic.ts:1278, on the default
 * "updated_at" order (packages/contracts/src/settings.ts:59): the project with the latest
 * non-archived thread activity first, ties by title then id.
 */
fun sortProjectsForSidebar(projects: List<Project>, threads: List<Thread>): List<Project> {
  val threadsByProjectId: Map<String, List<Thread>> = threads
    .filter { it.archivedAt == null }
    .groupBy { it.projectId }
  return projects
    .map { project -> project to getProjectSortTimestamp(project, threadsByProjectId[project.id]) }
    .sortedWith { (left, leftAt), (right, rightAt) ->
      rightAt.compareTo(leftAt).takeIf { it != 0 }
        ?: TITLE_ORDER.compare(left.title, right.title).takeIf { it != 0 }
        ?: left.id.compareTo(right.id)
    }
    .map { (project, _) -> project }
}

/** `getProjectSortTimestamp`, Sidebar.logic.ts:1211, for "updated_at". */
fun getProjectSortTimestamp(project: Project, projectThreads: List<Thread>?): Long {
  if (!projectThreads.isNullOrEmpty()) return projectThreads.maxOf { getThreadSortTimestamp(it) }
  return toSortableTimestamp(project.updatedAt)
    ?: toSortableTimestamp(project.createdAt)
    ?: Long.MIN_VALUE
}

/**
 * `getThreadSortTimestamp`, packages/client-runtime/src/state/threadSort.ts:104, for
 * "updated_at": the latest user message, else last update, else creation. The shell row has no
 * messages to scan.
 */
fun getThreadSortTimestamp(thread: Thread): Long =
  toSortableTimestamp(thread.latestUserMessageAt)
    ?: toSortableTimestamp(thread.updatedAt)
    ?: toSortableTimestamp(thread.createdAt)
    ?: Long.MIN_VALUE

/** `toSortableTimestamp`, threadSort.ts:17. */
fun toSortableTimestamp(iso: String?): Long? = iso?.toInstantOrNull()?.toEpochMilli()

/** Upstream's `KeybindingShortcut`. [modKey] is ⌘ on macOS and Ctrl elsewhere. */
data class KeybindingShortcut(
  val key: String,
  val metaKey: Boolean = false,
  val ctrlKey: Boolean = false,
  val altKey: Boolean = false,
  val shiftKey: Boolean = false,
  val modKey: Boolean = false,
)

/**
 * The defaults the palette shows, from packages/shared/src/keybindings.ts: `mod+alt+n` (174) and
 * `mod+1` to `mod+9` (193). Upstream lets users rebind these; the plugin doesn't.
 */
val DEFAULT_KEYBINDINGS: Map<String, KeybindingShortcut> =
  THREAD_JUMP_KEYBINDING_COMMANDS.mapIndexed { index, command ->
    command to KeybindingShortcut(key = "${index + 1}", modKey = true)
  }.toMap() +
    (NEW_WITHOUT_PROJECT_COMMAND to KeybindingShortcut(key = "n", modKey = true, altKey = true))

/**
 * Which of [DEFAULT_KEYBINDINGS] a key press is, upstream's `resolveShortcutCommand` without
 * `when` clauses. [key] is lowercase, as a browser's `event.key` compares.
 */
fun resolveShortcutCommand(
  key: String,
  meta: Boolean,
  ctrl: Boolean,
  alt: Boolean,
  shift: Boolean,
  isMac: Boolean,
): String? = DEFAULT_KEYBINDINGS.entries.firstOrNull { (_, shortcut) ->
  val wantsMeta: Boolean = shortcut.metaKey || (shortcut.modKey && isMac)
  val wantsCtrl: Boolean = shortcut.ctrlKey || (shortcut.modKey && !isMac)
  shortcut.key == key && wantsMeta == meta && wantsCtrl == ctrl &&
    shortcut.altKey == alt && shortcut.shiftKey == shift
}?.key

/** `formatShortcutKeyLabel`, apps/web/src/keybindings.ts:188. */
fun formatShortcutKeyLabel(key: String): String = when {
  key == " " -> "Space"
  key.length == 1 -> key.uppercase()
  key == "escape" -> "Esc"
  key == "arrowup" -> "Up"
  key == "arrowdown" -> "Down"
  key == "arrowleft" -> "Left"
  key == "arrowright" -> "Right"
  else -> key.take(1).uppercase() + key.drop(1)
}

/** `formatShortcutLabel`, keybindings.ts:199: "⌥⌘N" on macOS, "Ctrl+Alt+N" elsewhere. */
fun formatShortcutLabel(shortcut: KeybindingShortcut, isMac: Boolean): String {
  val keyLabel: String = formatShortcutKeyLabel(shortcut.key)
  val showMeta: Boolean = shortcut.metaKey || (shortcut.modKey && isMac)
  val showCtrl: Boolean = shortcut.ctrlKey || (shortcut.modKey && !isMac)
  val showAlt: Boolean = shortcut.altKey
  val showShift: Boolean = shortcut.shiftKey

  if (isMac) {
    return buildString {
      if (showCtrl) append("⌃")
      if (showAlt) append("⌥")
      if (showShift) append("⇧")
      if (showMeta) append("⌘")
      append(keyLabel)
    }
  }

  return listOfNotNull(
    "Ctrl".takeIf { showCtrl },
    "Alt".takeIf { showAlt },
    "Shift".takeIf { showShift },
    "Meta".takeIf { showMeta },
    keyLabel,
  ).joinToString("+")
}

/** `shortcutLabelForCommand`, keybindings.ts:226, against [DEFAULT_KEYBINDINGS]. */
fun shortcutLabelForCommand(command: String?, isMac: Boolean): String? {
  if (command == null) return null
  val shortcut: KeybindingShortcut = DEFAULT_KEYBINDINGS[command] ?: return null
  return formatShortcutLabel(shortcut, isMac)
}
