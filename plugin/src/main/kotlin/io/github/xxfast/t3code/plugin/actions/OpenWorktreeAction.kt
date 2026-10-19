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
import io.github.xxfast.t3code.plugin.ide.isOpen
import io.github.xxfast.t3code.plugin.ide.localRoots
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ide.pathOf
import io.github.xxfast.t3code.protocol.Thread
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Opens a thread's worktree as its own IDE window, when no window has it open yet. Ours: the
 * desktop app has no IDE windows to open. Doesn't need the server, only the path, and only when
 * the server's paths are this machine's.
 */
class OpenWorktreeAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val row: Thread? = e.threadRow(project, T3.get().client)
    val reason: String? = openWorktreeReason(row, T3.serverIsLocal(), ::openRoots)
    e.presentation.isEnabled = reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val row: Thread = e.threadRow(project, T3.get().client) ?: return
    val path: Path = row.worktreePath?.let(::pathOf) ?: return
    if (!T3.serverIsLocal() || !path.isDirectory()) {
      notify(project, "Couldn't open $path: it isn't on this machine", NotificationType.ERROR)
      return
    }
    ProjectUtil.openOrImport(path, null, true)
  }

  /** Every open IDE window's roots. A read action, which BGT updates already hold. */
  private fun openRoots(): List<Path> = ReadAction.compute<List<Path>, RuntimeException> {
    ProjectManager.getInstance().openProjects.flatMap(::localRoots)
  }
}

/**
 * Why [row]'s worktree can't open in a new window, or null when it can. [openRoots] is only
 * asked once there's a worktree to look for: it reads every open window's roots.
 */
fun openWorktreeReason(row: Thread?, serverIsLocal: Boolean, openRoots: () -> List<Path>): String? {
  if (row == null) return NO_THREAD
  val path: Path = row.worktreePath?.let(::pathOf) ?: return "Thread has no worktree"
  if (!serverIsLocal) return "Server is on another machine"
  return if (isOpen(path, openRoots())) "Worktree is already open" else null
}
