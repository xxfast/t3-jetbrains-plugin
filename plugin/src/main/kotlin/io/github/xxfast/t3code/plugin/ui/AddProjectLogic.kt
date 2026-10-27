package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.util.Locale

// Add project, as upstream's CommandPalette.tsx `handleAddProjectForEnvironment` decides it, with
// the path rules of packages/shared/src/path.ts and packages/client-runtime/src/state/projects.ts.

private val WINDOWS_DRIVE: Regex = Regex("^[a-zA-Z]:([/\\\\]|$)")
private val DRIVE_ROOT: Regex = Regex("^[a-zA-Z]:[/\\\\]$")
private val BARE_DRIVE: Regex = Regex("^[a-zA-Z]:$")

fun isWindowsDrivePath(value: String): Boolean = WINDOWS_DRIVE.containsMatchIn(value)

fun isUncPath(value: String): Boolean = value.startsWith("\\\\")

/** `isRootPath`: `/`, `\`, or a drive with its separator. A bare `C:` isn't a root. */
private fun isRootPath(value: String): Boolean =
  value == "/" || value == "\\" || DRIVE_ROOT.matches(value)

/** `trimTrailingPathSeparators`: a root stays, `C:` gets its backslash back. */
private fun trimTrailingPathSeparators(value: String): String {
  if (value.isEmpty() || isRootPath(value)) return value
  val trimmed: String =
    if (value.startsWith("/")) value.trimEnd('/') else value.trimEnd('/', '\\')
  if (trimmed.isEmpty()) return value
  return if (BARE_DRIVE.matches(trimmed)) "$trimmed\\" else trimmed
}

/** `normalizeProjectPathForDispatch`: trimmed, no trailing separators. */
fun normalizeProjectPathForDispatch(value: String): String =
  trimTrailingPathSeparators(value.trim())

/** `normalizeProjectPathForComparison`: Windows paths compare backslashed and lower case. */
fun normalizeProjectPathForComparison(value: String): String {
  val normalized: String = normalizeProjectPathForDispatch(value)
  return if (isWindowsDrivePath(normalized) || isUncPath(normalized)) {
    normalized.replace('/', '\\').lowercase(Locale.ROOT)
  } else {
    normalized
  }
}

/** `findProjectByPath`: the project whose workspace root is [candidatePath]. */
fun findProjectByPath(projects: List<Project>, candidatePath: String): Project? {
  val candidate: String = normalizeProjectPathForComparison(candidatePath)
  if (candidate.isEmpty()) return null
  return projects.find { normalizeProjectPathForComparison(it.workspaceRoot) == candidate }
}

/** `inferProjectTitleFromPath`: the last non-empty segment, else the path. */
fun inferProjectTitleFromPath(value: String): String {
  val normalized: String = normalizeProjectPathForDispatch(value)
  return normalized.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: normalized
}

/**
 * `getLatestThreadForProject` (client-runtime/src/state/threadSort.ts) on upstream's default
 * `updated_at` order: the project's unarchived thread with the latest user message, else the
 * latest update, ties to the larger id.
 */
fun getLatestThreadForProject(threads: List<Thread>, projectId: String): Thread? = threads
  .filter { it.projectId == projectId && it.archivedAt == null }
  .maxWithOrNull(compareBy<Thread> { it.latestUserMessageAt ?: it.updatedAt }.thenBy { it.id })

/** What Add project does with a picked folder, upstream's `handleAddProjectForEnvironment`. */
sealed interface AddProjectPlan {
  /** Already a project: open its latest unsettled thread, else a new thread in it. */
  data class Existing(val project: Project, val latest: Thread?) : AddProjectPlan

  /** A `project.create` for [workspaceRoot] titled [title], then a new thread in it. */
  data class Create(val title: String, val workspaceRoot: String) : AddProjectPlan

  /** Nothing to add. */
  data object None : AddProjectPlan
}

fun planAddProject(
  rawCwd: String,
  projects: List<Project>,
  threads: List<Thread>,
): AddProjectPlan {
  val cwd: String = normalizeProjectPathForDispatch(rawCwd)
  if (cwd.isEmpty()) return AddProjectPlan.None
  val existing: Project? = findProjectByPath(projects, cwd)
  if (existing != null) {
    val latest: Thread? = getLatestThreadForProject(threads, existing.id)
    return AddProjectPlan.Existing(existing, latest?.takeIf { it.settledOverride != "settled" })
  }
  return AddProjectPlan.Create(inferProjectTitleFromPath(cwd), cwd)
}
