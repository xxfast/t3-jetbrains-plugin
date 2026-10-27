package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.plugin.ui.stored
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ProjectRecord
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.generated.ProjectMutation

/**
 * `projects.mutate` over the fixture's [current] projects, as the server's
 * apps/server/src/orchestration-v2/ProjectCommands.ts decides: a create needs a new id and a
 * free workspace root, an update or delete an existing project. Answers the overlay with the
 * change and the stored project, as the RPC does.
 */
fun Overlay.mutateProject(
  current: List<Project>,
  mutation: ProjectMutation,
  now: String,
): Pair<Overlay, ProjectRecord> {
  val existing: Project? = current.find { it.id == mutation.projectId }
  fun missing(): Nothing = throw T3Exception.Rpc(
    "ProjectMutationError",
    "Project '${mutation.projectId}' does not exist for command '${mutation.type}'.",
  )
  return when (mutation) {
    is ProjectMutation.ProjectCreate -> {
      if (existing != null || mutation.projectId in deletedProjects) {
        throw T3Exception.Rpc(
          "ProjectMutationError",
          "Project '${mutation.projectId}' already exists and cannot be created twice.",
        )
      }
      val owner: Project? = current.find { it.workspaceRoot == mutation.workspaceRoot }
      if (owner != null) {
        throw T3Exception.Rpc(
          "ProjectMutationError",
          "Active project '${owner.id}' already exists for workspace root " +
            "'${mutation.workspaceRoot}'.",
        )
      }
      val created = Project(
        id = mutation.projectId,
        title = mutation.title,
        workspaceRoot = mutation.workspaceRoot,
        defaultModelSelection = null,
        scripts = mutation.scripts.orEmpty(),
        createdAt = now,
        updatedAt = now,
      )
      val next: Overlay = copy(
        projects = projects + (created.id to created),
        createdProjects = createdProjects + created.id,
      )
      next to created.record()
    }

    // Only what upstream's Project section sends: the title and the icon
    is ProjectMutation.ProjectUpdate -> {
      val project: Project = existing ?: missing()
      val updated: Project = project.copy(
        title = mutation.title ?: project.title,
        projectIcon = mutation.projectIcon?.stored() ?: project.projectIcon,
        updatedAt = now,
      )
      copy(projects = projects + (updated.id to updated)) to updated.record()
    }

    is ProjectMutation.ProjectDelete -> {
      val project: Project = existing ?: missing()
      copy(deletedProjects = deletedProjects + project.id) to project.record(deletedAt = now)
    }

    is ProjectMutation.Unknown -> throw T3Exception.Rpc(
      "ProjectMutationError",
      "Unknown project mutation '${mutation.type}'.",
    )
  }
}

/** The shell row as the stored project `projects.mutate` answers with. */
private fun Project.record(deletedAt: String? = null): ProjectRecord = ProjectRecord(
  id = id,
  title = title,
  workspaceRoot = workspaceRoot,
  repositoryIdentity = repositoryIdentity,
  faviconPath = faviconPath,
  projectIcon = projectIcon,
  defaultModelSelection = defaultModelSelection,
  defaultThreadEnvMode = defaultThreadEnvMode,
  autoPull = autoPull,
  scripts = scripts,
  createdAt = createdAt,
  updatedAt = updatedAt,
  deletedAt = deletedAt,
)
