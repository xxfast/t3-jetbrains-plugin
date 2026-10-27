package io.github.xxfast.t3code.plugin.web

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.actions.ThreadActionScope
import io.github.xxfast.t3code.plugin.actions.threadRow
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PreviewSessionSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Each thread's open preview tabs as `preview.list` last answered, for "Open preview" to
 * enable on. A menu updates once as it opens and can't wait on the server, so this answers
 * from what it has and asks again when that's older than a few seconds.
 */
@Service(Service.Level.APP)
class PreviewSessions(private val scope: CoroutineScope) {
  private class Entry(val sessions: List<PreviewSessionSnapshot>, val at: Long)

  private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()
  private val asking: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** What `preview.list` last said for [threadId], null before it has. */
  fun cached(threadId: String): List<PreviewSessionSnapshot>? {
    val entry: Entry? = entries[threadId]
    if (entry == null || System.currentTimeMillis() - entry.at > STALE_MS) refresh(threadId)
    return entry?.sessions
  }

  /** `preview.list` for [threadId], now. Throws what the client throws. */
  suspend fun fetch(threadId: String): List<PreviewSessionSnapshot> {
    val sessions: List<PreviewSessionSnapshot> =
      previewSessionsFor(T3.get().client.previewSessions(threadId), threadId)
    entries[threadId] = Entry(sessions, System.currentTimeMillis())
    return sessions
  }

  private fun refresh(threadId: String) {
    if (!asking.add(threadId)) return
    scope.launch {
      try {
        fetch(threadId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.debug("preview.list for $threadId failed", e)
      } finally {
        asking.remove(threadId)
      }
    }
  }

  companion object {
    private const val STALE_MS: Long = 3_000
    private val LOG: Logger = Logger.getInstance(PreviewSessions::class.java)

    fun get(): PreviewSessions = service()
  }
}

/**
 * "Open preview" on a thread: its route in the T3 web view, where the preview is in the right
 * panel. Upstream's preview and device panes are right panel state (`rightPanelStore.ts`) with
 * no route of their own, and driving a preview needs `preview:operate`, which the plugin
 * doesn't ask for. So nothing native: the thread's page, when `preview.list` has a tab for it.
 */
class OpenPreviewAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project: Project? = e.project
    if (project == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val row: Thread? = e.threadRow(project, T3.get().client)
    val reason: String? = openPreviewDisabledReason(
      hasThread = row != null,
      environmentId = environmentId(project),
      sessions = row?.let { PreviewSessions.get().cached(it.id) },
    )
    e.presentation.isVisible = true
    e.presentation.isEnabled = reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val row: Thread = e.threadRow(project, T3.get().client) ?: return
    val route: String =
      webViewRoute(WebViewKind.Thread, environmentId(project), threadId = row.id) ?: return
    ThreadActionScope.getInstance(project).scope.launch {
      val sessions: List<PreviewSessionSnapshot> = try {
        PreviewSessions.get().fetch(row.id)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        val message: String = failure.message ?: "Couldn't list the thread's previews"
        notify(project, message, NotificationType.ERROR)
        return@launch
      }
      withContext(Dispatchers.EDT) {
        if (sessions.isEmpty()) {
          notify(project, NO_PREVIEW, NotificationType.INFORMATION)
        } else {
          openWebView(project, row.title, route)
        }
      }
    }
  }
}

/** The server's `environmentId`, once `server.getConfig` has answered. */
private fun environmentId(project: Project): String? =
  ChatController.getInstance(project).catalog.value?.environmentId
