package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.generated.ProjectIconOverride
import io.github.xxfast.t3code.protocol.generated.ProjectMutation
import io.github.xxfast.t3code.protocol.generated.StoredProjectIcon
import java.text.Normalizer
import java.util.Locale

// The rules of upstream's Project section, apps/web/src/components/settings/
// ProjectSettingsPanel.tsx, and its icon picker, ProjectIconPickerDialog.tsx, at the pinned tag.

/** The picker's three kinds, its "Icon type" toggle: Icons, Emoji, Monogram. */
enum class ProjectIconKind(val label: String) {
  Lucide("Icons"),
  Emoji("Emoji"),
  Monogram("Monogram"),
}

/** `DEFAULT_ICON` and the emoji the picker starts on when the project has neither. */
const val DEFAULT_LUCIDE_ICON: String = "folder-code"
const val DEFAULT_PROJECT_EMOJI: String = "💻"

const val PROJECT_TITLE_EMPTY: String = "Project title cannot be empty"
/** Upstream's note over the section when the pairing can't operate, and the Save tooltip. */
const val PROJECT_READ_ONLY: String = "This connection cannot change this project."
const val NEEDS_OPERATE: String = "Needs the operate scope"

/** What the picker opens on for [project]: its override, else the automatic identity. */
data class ProjectIconDraft(
  val kind: ProjectIconKind,
  val lucide: String,
  val emoji: String,
  val letters: String,
  val color: String,
)

/**
 * The picker's initial state (`ProjectIconPickerDialog`'s `useState`s): the current override's
 * kind and values, else Icons on `folder-code` with `deriveProjectIdentity`'s monogram and
 * colour.
 */
fun projectIconDraft(project: Project): ProjectIconDraft {
  val current: StoredProjectIcon? = project.projectIcon
  val automaticColor: String = projectColorName(project.title)
  return ProjectIconDraft(
    kind = when (current) {
      is StoredProjectIcon.Emoji -> ProjectIconKind.Emoji
      is StoredProjectIcon.Monogram -> ProjectIconKind.Monogram
      else -> ProjectIconKind.Lucide
    },
    lucide = (current as? StoredProjectIcon.Lucide)?.name ?: DEFAULT_LUCIDE_ICON,
    emoji = (current as? StoredProjectIcon.Emoji)?.emoji ?: DEFAULT_PROJECT_EMOJI,
    letters = (current as? StoredProjectIcon.Monogram)?.text ?: projectMonogram(project.title),
    color = when (current) {
      is StoredProjectIcon.Lucide -> current.color
      is StoredProjectIcon.Monogram -> current.color
      else -> automaticColor
    },
  )
}

/** The picker's `monogram`: letters NFKC-normalised, trimmed and upper-cased. */
fun normalizeMonogram(letters: String): String =
  Normalizer.normalize(letters, Normalizer.Form.NFKC).trim().uppercase(Locale.ROOT)

private val MONOGRAM: Regex = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}\\u200c\\u200d]*$")
private val LUCIDE_NAME: Regex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

/** `ProjectMonogramText` in packages/contracts/src/project.ts. */
fun isMonogramText(text: String): Boolean =
  text.isNotEmpty() && text.length <= 32 && MONOGRAM.matches(text)

/** `ProjectLucideIconName`, project.ts. */
fun isLucideIconName(name: String): Boolean = name.length <= 64 && LUCIDE_NAME.matches(name)

/** `ProjectEmoji`, project.ts: trimmed, not empty, at most 32. */
fun isProjectEmoji(emoji: String): Boolean = emoji.isNotEmpty() && emoji.length <= 32

/** Why [draft] can't be saved as an icon, or null. Upstream disables Save on a bad monogram. */
fun projectIconError(draft: ProjectIconDraft): String? = when (draft.kind) {
  ProjectIconKind.Monogram ->
    if (isMonogramText(normalizeMonogram(draft.letters))) null
    else "One or two letters or numbers."
  ProjectIconKind.Lucide ->
    if (isLucideIconName(draft.lucide.trim())) null
    else "A Lucide icon name, like folder-code."
  ProjectIconKind.Emoji -> if (isProjectEmoji(draft.emoji.trim())) null else "Paste an emoji."
}

/** The picker's `save`: [draft] as the override `project.update` sends. */
fun projectIconOverride(draft: ProjectIconDraft): ProjectIconOverride = when (draft.kind) {
  ProjectIconKind.Monogram ->
    ProjectIconOverride.Monogram(text = normalizeMonogram(draft.letters), color = draft.color)
  ProjectIconKind.Lucide ->
    ProjectIconOverride.Lucide(name = draft.lucide.trim(), color = draft.color)
  ProjectIconKind.Emoji -> ProjectIconOverride.Emoji(emoji = draft.emoji.trim())
}

/** The same override as the shell carries it, so it compares with [Project.projectIcon]. */
fun ProjectIconOverride.stored(): StoredProjectIcon? = when (this) {
  is ProjectIconOverride.Lucide -> StoredProjectIcon.Lucide(name = name, color = color)
  is ProjectIconOverride.Emoji -> StoredProjectIcon.Emoji(emoji = emoji)
  is ProjectIconOverride.Monogram -> StoredProjectIcon.Monogram(text = text, color = color)
  is ProjectIconOverride.Unknown -> null
}

/**
 * The Project icon row's description: `name · color`, `text · color`, the emoji, else the
 * favicon path, else "Automatic".
 */
fun projectIconDescription(project: Project): String = when (val icon = project.projectIcon) {
  is StoredProjectIcon.Lucide -> "${icon.name} · ${icon.color}"
  is StoredProjectIcon.Monogram -> "${icon.text} · ${icon.color}"
  is StoredProjectIcon.Emoji -> icon.emoji
  else -> project.faviconPath ?: "Automatic"
}

/** `projectGroupTitleNeedsUpdate`, ProjectSettingsPanel.logic.ts. */
fun projectGroupTitleNeedsUpdate(
  memberTitles: List<String>,
  nextTitle: String,
  wasEdited: Boolean,
): Boolean = wasEdited && memberTitles.any { it != nextTitle }

/**
 * The one `project.update` the dialog's OK sends: the trimmed title when it was edited and
 * differs (`renameGroup`), the icon when it differs from the stored one (`setProjectIcon`).
 * Null when nothing changed. Upstream sends the two separately, as each field commits.
 */
fun projectSettingsUpdate(
  project: Project,
  typedTitle: String,
  titleEdited: Boolean,
  icon: ProjectIconOverride?,
  commandId: String,
): ProjectMutation.ProjectUpdate? {
  val title: String = typedTitle.trim()
  val retitled: String? =
    title.takeIf { projectGroupTitleNeedsUpdate(listOf(project.title), it, titleEdited) }
  val reiconed: ProjectIconOverride? = icon?.takeIf { it.stored() != project.projectIcon }
  if (retitled == null && reiconed == null) return null
  return ProjectMutation.ProjectUpdate(
    commandId = commandId,
    projectId = project.id,
    title = retitled,
    projectIcon = reiconed,
  )
}
