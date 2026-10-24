package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Throws, so the IDE's error dialog comes up with "Report to T3 Code" on it. Only visible in the
 * design harness (fixture mode); the code ships inert like the fixture client does.
 */
class ThrowTestErrorAction : AnAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = System.getProperty("t3code.fixtures") != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    throw IllegalStateException("T3 Code test error from ${e.place}")
  }
}
