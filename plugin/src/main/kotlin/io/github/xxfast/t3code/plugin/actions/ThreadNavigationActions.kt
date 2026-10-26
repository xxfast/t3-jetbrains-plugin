package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.JUMP_TO_THREAD_ACTION
import io.github.xxfast.t3code.plugin.SidebarNavigation
import io.github.xxfast.t3code.plugin.ui.ThreadTraversalDirection

/**
 * A Sidebar navigation key: works out a row to go to and opens it, as Sidebar.tsx's window
 * keydown does. Disabled when there's nowhere to go, so the key falls through to whatever else
 * the keymap has on it, as upstream only `preventDefault`s on a hit.
 */
abstract class ThreadNavigationAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  abstract fun target(e: AnActionEvent, navigation: SidebarNavigation): String?

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    e.presentation.isEnabled =
      project != null && target(e, SidebarNavigation.getInstance(project)) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val navigation: SidebarNavigation = SidebarNavigation.getInstance(project)
    target(e, navigation)?.let(navigation::navigate)
  }
}

/** Upstream's `thread.previous`. */
class PreviousThreadAction : ThreadNavigationAction() {
  override fun target(e: AnActionEvent, navigation: SidebarNavigation): String? =
    navigation.adjacent(ThreadTraversalDirection.Previous)
}

/** Upstream's `thread.next`. */
class NextThreadAction : ThreadNavigationAction() {
  override fun target(e: AnActionEvent, navigation: SidebarNavigation): String? =
    navigation.adjacent(ThreadTraversalDirection.Next)
}

/**
 * Upstream's `thread.jump.1` to `thread.jump.9`: one class, registered nine times, reading its
 * N off its own id, `threadJumpIndexFromCommand`.
 */
class JumpToThreadAction : ThreadNavigationAction() {
  override fun target(e: AnActionEvent, navigation: SidebarNavigation): String? {
    val n: Int = e.actionManager.getId(this)?.removePrefix(JUMP_TO_THREAD_ACTION)?.toIntOrNull()
      ?: return null
    return navigation.jump(n - 1)
  }
}
