package io.github.xxfast.t3code.plugin.actions

import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ide.IGNORE_CASE
import io.github.xxfast.t3code.plugin.ide.isUnder
import io.github.xxfast.t3code.plugin.ide.localRoots
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ide.pathOf
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * "Open in IDE", upstream's `OpenInPicker` (ThreadDetailsPanel.tsx:164) with the IDE as the only
 * editor: focuses the window that has the thread's [gitCwd] open, else opens it in a new one.
 * Hidden when it belongs in this window already. Doesn't need the server, only the path, and only
 * when the server's paths are this machine's.
 */
class OpenInIdeAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val client: T3Client = T3.get().client
    val row: Thread? = e.threadRow(project, client)
    val cwd: Path? = row?.let { gitCwd(it, client.projects.value) }
    val local: Boolean = T3.serverIsLocal()
    // A BGT update already holds a read action, which localRoots needs
    if (cwd != null && local && belongsIn(cwd, localRoots(project))) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val reason: String? = openInIdeReason(row, cwd, local, Path::isDirectory)
    e.presentation.isVisible = true
    e.presentation.isEnabled = reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val client: T3Client = T3.get().client
    val row: Thread = e.threadRow(project, client) ?: return
    val cwd: Path? = gitCwd(row, client.projects.value)
    val reason: String? = openInIdeReason(row, cwd, T3.serverIsLocal(), Path::isDirectory)
    if (cwd == null || reason != null) {
      notify(project, "Couldn't open in IDE: ${reason ?: NO_CWD}", NotificationType.ERROR)
      return
    }
    val windows: List<Project> = ProjectManager.getInstance().openProjects.toList()
    val window: Project? = ReadAction.compute<Project?, RuntimeException> {
      windowFor(cwd, windows, ::localRoots)
    }
    if (window != null) {
      ProjectUtil.focusProjectWindow(window, true)
    } else {
      ProjectUtil.openOrImport(cwd, null, true)
    }
  }
}

/**
 * `gitCwd` (ChatView.tsx:2922, Sidebar.tsx:1635): [row]'s worktree, else its project's
 * `workspaceRoot`. Null when neither is a path.
 */
fun gitCwd(row: Thread, projects: List<T3Project>): Path? =
  (row.worktreePath ?: projects.find { it.id == row.projectId }?.workspaceRoot)?.let(::pathOf)

/**
 * Whether [cwd] belongs in a window with [roots]: it's one of them, or inside one, so a monorepo
 * window whose content roots hold the project's root counts.
 */
fun belongsIn(cwd: Path, roots: List<Path>, ignoreCase: Boolean = IGNORE_CASE): Boolean =
  roots.any { root -> isUnder(root, cwd, ignoreCase) }

/** The first of [windows] [cwd] [belongsIn], to focus, or null to open a new one. */
fun <W> windowFor(
  cwd: Path,
  windows: List<W>,
  roots: (W) -> List<Path>,
  ignoreCase: Boolean = IGNORE_CASE,
): W? = windows.firstOrNull { window -> belongsIn(cwd, roots(window), ignoreCase) }

/**
 * Why [row] can't open in the IDE at [cwd], its [gitCwd], or null when it can. [exists] is only
 * asked once the server is local: it touches the disk.
 */
fun openInIdeReason(
  row: Thread?,
  cwd: Path?,
  serverIsLocal: Boolean,
  exists: (Path) -> Boolean,
): String? {
  if (row == null) return NO_THREAD
  if (cwd == null) return NO_CWD
  if (!serverIsLocal) return "Server is on another machine"
  return if (exists(cwd)) null else "$cwd isn't on this disk"
}

private const val NO_CWD: String = "Thread has no workspace path"
