package io.github.xxfast.t3code.plugin.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * A thread lifecycle action. Acts on [T3DataKeys.THREAD_ID] from the context (the thread view, a
 * Sidebar row) and falls back to the selected thread, so Find Action and keymaps work anywhere.
 * Enablement is [disabledReason], and the reason is the description while disabled. Hidden when
 * the server doesn't advertise [capability], and on the thread view's toolbar when [menuOnly].
 */
abstract class ThreadAction(
  private val kind: ThreadActionKind,
  private val capability: String? = null,
  private val menuOnly: Boolean = false,
) : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null || !e.shows(project, capability, menuOnly)) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val client: T3Client = T3.get().client
    val row: Thread? = e.threadRow(project, client)
    val reason: String? = disabledReason(kind, client.connection.value, row)
    present(e.presentation, row)
    e.presentation.isEnabled = reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val client: T3Client = T3.get().client
    val row: Thread = e.threadRow(project, client) ?: return
    val live: ThreadView? = e.getData(T3DataKeys.THREAD_VIEW)?.takeIf { it.thread.id == row.id }
    val target = ThreadTarget(project, client, row, live)
    val text: String = e.presentation.text ?: templateText ?: "do that"
    ThreadActionScope.getInstance(project).scope
      .launchReporting(project, "Couldn't ${text.lowercase()}") { perform(target) }
  }

  /** Text and icon for [row], on the background thread. */
  protected open fun present(presentation: Presentation, row: Thread?) = Unit

  protected abstract suspend fun perform(target: ThreadTarget)
}

/**
 * A submenu of thread actions, hidden like a [ThreadAction] when the server doesn't do
 * [capability] or this is the thread view's toolbar.
 */
abstract class ThreadActionGroup(private val capability: String?) : DefaultActionGroup(),
  DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    e.presentation.isEnabledAndVisible =
      project != null && e.shows(project, capability, menuOnly = true)
  }
}

/** Whether an action needing [capability] shows here at all. */
private fun AnActionEvent.shows(project: Project, capability: String?, menuOnly: Boolean): Boolean {
  if (menuOnly && isFromActionToolbar) return false
  if (capability == null) return true
  return ChatController.getInstance(project).catalog.value.supports(capability)
}

/** The thread an action acts on: the context's, else the selected one. */
fun AnActionEvent.threadId(project: Project): String? =
  getData(T3DataKeys.THREAD_ID) ?: ThreadSelection.getInstance(project).selected.value

/** The row for [threadId] in the client's thread list. */
fun AnActionEvent.threadRow(project: Project, client: T3Client): Thread? =
  threadId(project)?.let { id -> client.threads.value.find { it.id == id } }

/** What an action acts on: [row], and the host's live view of it when there is one. */
class ThreadTarget(
  val project: Project,
  val client: T3Client,
  val row: Thread,
  private val live: ThreadView?,
) {
  /** The host's live view, or one look at the thread: subscribe, take the snapshot, unsubscribe. */
  suspend fun view(): ThreadView =
    live ?: withTimeout(SNAPSHOT_TIMEOUT_MS) { client.thread(row.id).first() }

  fun commandId(): String = UUID.randomUUID().toString()

  fun inform(message: String) = notify(project, message, NotificationType.INFORMATION)

  private companion object {
    const val SNAPSHOT_TIMEOUT_MS: Long = 10_000
  }
}

/** Where thread actions run their commands, off the EDT. */
@Service(Service.Level.PROJECT)
class ThreadActionScope(val scope: CoroutineScope) {
  companion object {
    fun getInstance(project: Project): ThreadActionScope = project.service()
  }
}
