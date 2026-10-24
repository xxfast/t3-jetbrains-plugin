package io.github.xxfast.t3code.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.github.xxfast.t3code.plugin.ide.T3Opener

/**
 * Opens T3 Sidebar, T3 Thread Details and a Chat View tab per thread id in `-Dt3code.open`, a
 * comma-separated list that may be empty. `runIde` and `runHarness` set it from `t3.open`, so
 * the sandbox comes up ready to look at. Without the property, as installed, it does nothing.
 */
class LaunchOpen : ProjectActivity {
  override suspend fun execute(project: Project) {
    val threadIds: List<String> = System.getProperty("t3code.open")
      ?.split(',')
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?: return
    val manager: ToolWindowManager = ToolWindowManager.getInstance(project)
    manager.invokeLater {
      if (project.isDisposed) return@invokeLater
      manager.getToolWindow("T3 Sidebar")?.show()
      manager.getToolWindow("T3 Thread Details")?.show()
      threadIds.forEach(T3Opener.getInstance(project)::openThread)
    }
  }
}
