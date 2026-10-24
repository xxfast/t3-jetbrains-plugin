package io.github.xxfast.t3code.plugin.ide

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.util.IconUtil
import io.github.xxfast.t3code.plugin.ui.IdeRunConfiguration
import javax.swing.Icon

/**
 * [project]'s run configurations as T3 Thread Details lists them, temporary ones left out, the
 * run widget's selection marked. Icons are rasterised once here, off the Swing icon.
 */
fun ideRunConfigurations(project: Project): List<IdeRunConfiguration> {
  val manager: RunManager = RunManager.getInstance(project)
  val selected: String? = manager.selectedConfiguration?.uniqueID
  return manager.allSettings
    .filter { !it.isTemporary }
    .map { settings ->
      IdeRunConfiguration(
        id = settings.uniqueID,
        name = settings.name,
        icon = (settings.configuration.icon ?: settings.type.icon)?.let(::bitmapOf),
        selected = settings.uniqueID == selected,
      )
    }
}

/** Runs the configuration with [id] under the Run executor, as the run widget's play would. */
fun runIdeConfiguration(project: Project, id: String): Boolean {
  val settings: RunnerAndConfigurationSettings =
    RunManager.getInstance(project).allSettings.find { it.uniqueID == id } ?: return false
  ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
  return true
}

private fun bitmapOf(icon: Icon): ImageBitmap? =
  runCatching { IconUtil.toBufferedImage(icon).toComposeImageBitmap() }.getOrNull()
