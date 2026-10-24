package io.github.xxfast.t3code.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.settings.ComposerDrafts
import io.github.xxfast.t3code.plugin.ui.DraftThread
import io.github.xxfast.t3code.plugin.ui.newDraftThread
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.resolveProjectDefaultModelSelection
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import io.github.xxfast.t3code.protocol.Project as T3Project

/**
 * Opens a new thread in a T3 project as a draft, upstream's `useNewThreadHandler`
 * (useHandleNewThread.ts). The server only hears of it when its first message is sent.
 */
@Service(Service.Level.PROJECT)
class NewThread(private val project: Project, private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  /** Opens the "New thread in..." picker; a pick opens the draft. */
  fun open() {
    showNewThreadIn(project) { projectId -> if (projectId != null) start(projectId) }
  }

  /**
   * Opens [projectId]'s draft, on [branch] and [worktreePath] when given. [carry] is the model
   * of the thread it starts from, upstream's `carryModelSelection`; the last pick anywhere
   * stands in without one.
   */
  fun start(
    projectId: String,
    branch: String? = null,
    worktreePath: String? = null,
    carry: ModelSelection? = null,
  ) {
    scope.launchReporting(project, "Couldn't start a thread") {
      val drafts: DraftThreads = DraftThreads.get()
      // Upstream reuses the project's draft while the server doesn't list it. It starts a
      // fresh one when that draft has text, leaving the old one in the Sidebar's draft block;
      // the plugin has no draft block, so any unsent draft comes back
      val stored: DraftThread? = drafts.forProject(projectId)?.takeIf { draft ->
        val listed: Boolean = client.threads.value.any { it.id == draft.threadId }
        if (listed) drafts.promoted(draft.threadId)
        !listed
      }
      if (stored != null) {
        if (branch != null || worktreePath != null) {
          drafts.update(stored.threadId) { it.copy(branch = branch, worktreePath = worktreePath) }
        }
        T3Opener.getInstance(project).openThread(stored.threadId)
        return@launchReporting
      }
      val controller: ChatController = ChatController.getInstance(project)
      if (controller.catalog.value == null) controller.loadCatalog()
      val catalog: ServerInfo? = controller.catalog.value
      val target: T3Project = client.projects.value.find { it.id == projectId }
        ?: return@launchReporting
      // `resolveProjectSettings`: the settings decide, the row only until they're folded
      val projectDefault: ModelSelection? = catalog
        ?.let { resolveProjectDefaultModelSelection(it.modelSettings, target) }
        ?: target.defaultModelSelection
      val draft: DraftThread? = newDraftThread(
        target,
        catalog?.providers.orEmpty(),
        // Ids are the client's to mint, as upstream's `newThreadId()`
        threadId = UUID.randomUUID().toString(),
        createdAt = Instant.now().toString(),
        branch = branch,
        worktreePath = worktreePath,
        projectDefault = projectDefault,
        sticky = ComposerDrafts.get().stickyModel().selection,
        carry = carry,
      )
      if (draft == null) {
        val hint = "No provider is ready. Set one up in the T3 Code desktop app."
        notify(project, hint, NotificationType.WARNING)
        return@launchReporting
      }
      drafts.put(draft)
      T3Opener.getInstance(project).openThread(draft.threadId)
    }
  }

  companion object {
    fun getInstance(project: Project): NewThread = project.service()
  }
}
