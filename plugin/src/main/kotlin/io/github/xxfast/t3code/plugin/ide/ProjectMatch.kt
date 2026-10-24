package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.project.Project as IdeProject
import com.intellij.openapi.roots.ProjectRootManager
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

// Which T3 projects and threads an IDE window has open. Pure, so the rules are tested without an
// IDE: the caller hands in the window's content roots (base path included), from [localRoots].

/**
 * [project]'s content roots on this disk plus its base path: what the matching compares. IDEA's
 * attached projects are content roots too. Call it inside a read action.
 */
fun localRoots(project: IdeProject): List<Path> {
  if (project.isDisposed) return emptyList()
  val content: List<Path> = ProjectRootManager.getInstance(project).contentRoots
    .filter { it.isInLocalFileSystem }
    .mapNotNull { pathOf(it.path) }
  return (content + listOfNotNull(project.basePath?.let(::pathOf))).distinct()
}

/**
 * The repository's shared git directory for a checkout at [path], or `null` when it isn't one. A
 * main checkout has a `.git` directory, which is it. A worktree has a `.git` file saying
 * `gitdir: X`, and `X/commondir` names the shared directory relative to `X`.
 */
fun gitCommonDir(path: Path): Path? {
  val dotGit: Path = path.resolve(".git")
  if (dotGit.isDirectory()) return dotGit.toAbsolutePath().normalize()
  if (!dotGit.isRegularFile()) return null
  return try {
    val gitDir: Path = dotGit.readText().lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith(GITDIR) }
      ?.removePrefix(GITDIR)
      ?.trim()
      ?.let { path.resolve(it) }
      ?: return null
    val commonDir: Path = gitDir.resolve("commondir")
    val shared: Path = if (Files.isRegularFile(commonDir)) {
      gitDir.resolve(commonDir.readText().trim())
    } else {
      gitDir
    }
    shared.toAbsolutePath().normalize()
  } catch (_: IOException) {
    null
  } catch (_: InvalidPathException) {
    null
  }
}

private const val GITDIR: String = "gitdir:"

/** [path] as written on the server, made comparable here; `null` when it isn't a path. */
fun pathOf(path: String): Path? =
  try {
    Path.of(path).toAbsolutePath().normalize()
  } catch (_: InvalidPathException) {
    null
  }

/**
 * The Sidebar's filter for a window that has [open] open, from [openProjects]: a [persisted]
 * choice (`null` when the user hasn't made one, empty for an explicit "All projects"), else the
 * open projects, else all. A persisted choice whose projects are all gone falls through.
 */
fun matchProjects(
  persisted: List<String>?,
  projects: List<Project>,
  open: Set<String>,
): ProjectFilter {
  val known: Set<String> = projects.mapTo(HashSet()) { it.id }
  when {
    persisted == null -> Unit
    persisted.isEmpty() -> return ProjectFilter.All
    projects.isEmpty() -> return ProjectFilter.Only(persisted.toSet())
    else -> persisted.filter { it in known }.takeIf { it.isNotEmpty() }
      ?.let { return ProjectFilter.Only(it.toSet()) }
  }
  return if (open.isNotEmpty()) ProjectFilter.Only(open) else ProjectFilter.All
}

/**
 * The T3 projects a window with [roots] has open, in server order. Every project whose root
 * contains a content root or sits inside one, so a monorepo gets all of its projects; failing
 * that, every project sharing a git common dir with a content root, for a worktree on one side
 * and the main checkout on the other.
 */
fun openProjects(
  roots: List<Path>,
  projects: List<Project>,
  commonDir: (Path) -> Path? = ::gitCommonDir,
  ignoreCase: Boolean = IGNORE_CASE,
): Set<String> {
  val located: List<Pair<Project, Path>> = projects.mapNotNull { project ->
    pathOf(project.workspaceRoot)?.let { project to it }
  }
  val byPath: Set<String> = located
    .filter { (_, root) ->
      roots.any { isUnder(root, it, ignoreCase) || isUnder(it, root, ignoreCase) }
    }
    .mapTo(LinkedHashSet()) { (project, _) -> project.id }
  if (byPath.isNotEmpty()) return byPath
  val ours: List<Path> = roots.mapNotNull(commonDir)
  return located
    .filter { (_, root) ->
      val theirs: Path = commonDir(root) ?: return@filter false
      ours.any { samePath(it, theirs, ignoreCase) }
    }
    .mapTo(LinkedHashSet()) { (project, _) -> project.id }
}

/** The thread whose worktree is one of [roots]: what a window opened on a T3 worktree is for. */
fun threadForWorkspace(
  roots: List<Path>,
  threads: List<Thread>,
  ignoreCase: Boolean = IGNORE_CASE,
): Thread? = threads
  .filter { it.archivedAt == null }
  .firstOrNull { thread ->
    val worktree: Path = thread.worktreePath?.let(::pathOf) ?: return@firstOrNull false
    roots.any { samePath(it, worktree, ignoreCase) }
  }

/** Whether [path] is already one of the [openRoots] of some IDE window. */
fun isOpen(path: Path, openRoots: List<Path>, ignoreCase: Boolean = IGNORE_CASE): Boolean =
  openRoots.any { samePath(it, path, ignoreCase) }
