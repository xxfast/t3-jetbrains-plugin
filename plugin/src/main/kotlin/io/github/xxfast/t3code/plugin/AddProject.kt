package io.github.xxfast.t3code.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.github.xxfast.t3code.plugin.actions.ThreadActionScope
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.AddProjectPlan
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.plugin.ui.planAddProject
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.ProjectMutation
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Upstream's Add project (Sidebar.tsx's `openAddProjectCommandPalette`, then CommandPalette.tsx's
 * `handleAddProjectForEnvironment`): a folder, then its project's latest unsettled thread when
 * the server already has it, else a `project.create` and a new thread in it. The folder comes
 * from the IDE's chooser on a local server, typed as a server path otherwise, where upstream
 * browses the environment's filesystem.
 */
fun addProject(project: Project) {
  val path: String = pickProjectFolder(project) ?: return
  val client: T3Client = T3.get().client
  val plan: AddProjectPlan = planAddProject(path, client.projects.value, client.threads.value)
  ThreadActionScope.getInstance(project).scope.launchReporting(project, "Failed to add project") {
    when (plan) {
      AddProjectPlan.None -> Unit
      is AddProjectPlan.Existing -> {
        val latest: String? = plan.latest?.id
        if (latest != null) T3Opener.getInstance(project).openThread(latest)
        else NewThread.getInstance(project).start(plan.project.id)
      }

      is AddProjectPlan.Create -> {
        if (operateBlockReason(client.connection.value) != null) {
          notify(project, CANNOT_ADD, NotificationType.ERROR, "Cannot add project")
          return@launchReporting
        }
        // Ids are the client's to mint, as upstream's `newProjectId()`
        val projectId: String = UUID.randomUUID().toString()
        client.mutateProject(
          ProjectMutation.ProjectCreate(
            commandId = UUID.randomUUID().toString(),
            projectId = projectId,
            title = plan.title,
            workspaceRoot = plan.workspaceRoot,
            createWorkspaceRootIfMissing = true,
          ),
        )
        // The draft needs the project's row, which the shell stream brings a moment later
        withTimeout(SHELL_TIMEOUT_MS) {
          client.projects.first { projects -> projects.any { it.id == projectId } }
        }
        NewThread.getInstance(project).start(projectId)
      }
    }
  }
}

private fun pickProjectFolder(project: Project): String? {
  if (!T3.serverIsLocal()) {
    return Messages.showInputDialog(project, "Folder on the server", "Add Project", null)
  }
  val descriptor: FileChooserDescriptor = FileChooserDescriptorFactory
    .createSingleFolderDescriptor()
    .withTitle("Add Project")
  val folder: VirtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return null
  return folder.toNioPath().toString()
}

/** Why Add project can't create one, upstream's toast. */
const val CANNOT_ADD: String = "This connection cannot add projects."

private const val SHELL_TIMEOUT_MS: Long = 10_000
