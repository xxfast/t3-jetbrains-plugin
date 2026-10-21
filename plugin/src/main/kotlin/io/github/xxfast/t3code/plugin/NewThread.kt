package io.github.xxfast.t3code.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.createThread
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.Command
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import io.github.xxfast.t3code.protocol.Project as T3Project

/** Starts a thread in a T3 project and opens it once the server lists it. */
@Service(Service.Level.PROJECT)
class NewThread(private val project: Project, private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  /** Opens the "New thread in..." picker; a pick starts the thread. */
  fun open() {
    showNewThreadIn(project) { projectId -> if (projectId != null) start(projectId) }
  }

  /** Starts a thread in [projectId], on [branch] and [worktreePath] when given. */
  fun start(projectId: String, branch: String? = null, worktreePath: String? = null) {
    scope.launchReporting(project, "Couldn't start a thread") {
      val controller: ChatController = ChatController.getInstance(project)
      if (controller.catalog.value == null) controller.loadCatalog()
      val catalog: ServerInfo? = controller.catalog.value
      val target: T3Project = client.projects.value.find { it.id == projectId }
        ?: return@launchReporting
      // Ids are the client's to mint, as upstream's `randomUUID()`
      val threadId: String = UUID.randomUUID().toString()
      val commandId: String = UUID.randomUUID().toString()
      val command: Command.ThreadCreate? =
        createThread(
          target,
          catalog?.providers.orEmpty(),
          threadId,
          commandId,
          branch,
          worktreePath,
        )
      if (command == null) {
        val hint = "No provider is ready. Set one up in the T3 Code desktop app."
        notify(project, hint, NotificationType.WARNING)
        return@launchReporting
      }
      client.dispatch(command)
      val listed: List<Thread>? = withTimeoutOrNull(10.seconds) {
        client.threads.first { threads -> threads.any { it.id == threadId } }
      }
      if (listed == null) {
        val late = "The new thread didn't show up. It may still appear in the T3 Sidebar."
        notify(project, late, NotificationType.WARNING)
        return@launchReporting
      }
      T3Opener.getInstance(project).openThread(threadId)
    }
  }

  companion object {
    fun getInstance(project: Project): NewThread = project.service()
  }
}
