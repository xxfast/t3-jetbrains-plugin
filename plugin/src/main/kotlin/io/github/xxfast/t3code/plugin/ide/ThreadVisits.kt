package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.actions.blockedBy
import io.github.xxfast.t3code.plugin.ui.shouldVisit
import io.github.xxfast.t3code.plugin.ui.visitDelay
import io.github.xxfast.t3code.plugin.ui.visitKey
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.Command
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tells the server this window looked at a thread, what upstream's `ThreadVisitWatermark`
 * (apps/web/src/components/ChatView.tsx:8667 to 8725) does for the thread on screen. While a
 * thread is on screen, every time its row moves past the last visit a `thread.visit` goes out at
 * the row's `updatedAt`: at once for an unseen completion, else at most every 10 seconds.
 * Failures are logged, never shown, as upstream's `reportFailure: false`.
 *
 * Each host mounts and unmounts it once: an editor tab on select and deselect, "T3 Chat View"
 * through [follow].
 */
@Service(Service.Level.PROJECT)
class ThreadVisits(private val project: Project, private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  /** The thread on screen, if any. A new [Watermark] per mount, so reopening visits again. */
  private val open: MutableStateFlow<Watermark?> = MutableStateFlow(null)

  init {
    scope.launch {
      open.collectLatest { watermark -> watermark?.let { watch(it) } }
    }
  }

  /** [threadId] came on screen: upstream's watermark mounting. Already on screen, no change. */
  fun opened(threadId: String) {
    open.update { current -> current?.takeIf { it.threadId == threadId } ?: Watermark(threadId) }
  }

  /** [threadId] left the screen: unmounting. Another thread on screen stays. */
  fun closed(threadId: String) {
    open.update { current -> current?.takeIf { it.threadId != threadId } }
  }

  /** Mounts the selected thread while [toolWindow] is visible, and unmounts it when it hides. */
  fun follow(toolWindow: ToolWindow) {
    val visible: MutableStateFlow<Boolean> = MutableStateFlow(toolWindow.isVisible)
    project.messageBus.connect(toolWindow.disposable).subscribe(
      ToolWindowManagerListener.TOPIC,
      object : ToolWindowManagerListener {
        override fun stateChanged(toolWindowManager: ToolWindowManager) {
          visible.value = toolWindow.isVisible
        }
      },
    )
    val selected: StateFlow<String?> = ThreadSelection.getInstance(project).selected
    val job: Job = scope.launch {
      var shown: String? = null
      combine(visible, selected) { isVisible, id -> id.takeIf { isVisible } }
        .distinctUntilChanged()
        .collect { id ->
          shown?.let(::closed)
          id?.let(::opened)
          shown = id
        }
    }
    Disposer.register(toolWindow.disposable) { job.cancel() }
  }

  /** Keeps a mark-unread on the open [row] from being visited away straight after. */
  fun markedUnread(row: Thread) {
    open.value?.takeIf { it.threadId == row.id }?.lastDispatched = row.visitKey
  }

  private suspend fun watch(watermark: Watermark) {
    combine(client.threads, client.connection) { threads, connection ->
      threads.find { it.id == watermark.threadId }?.takeIf { blockedBy(connection) == null }
    }.collectLatest { row ->
      if (row == null || !shouldVisit(row, watermark.lastDispatched)) return@collectLatest
      val since: Duration = Duration.between(watermark.lastDispatchAt, Instant.now())
      delay(visitDelay(row, since).toMillis())
      watermark.lastDispatched = row.visitKey
      watermark.lastDispatchAt = Instant.now()
      // Off the collector, so the echo of this visit can't cancel it
      scope.launch { visit(row) }
    }
  }

  private suspend fun visit(row: Thread) {
    try {
      client.dispatch(
        Command.ThreadVisit(
          commandId = UUID.randomUUID().toString(),
          threadId = row.id,
          visitedAt = row.updatedAt,
        ),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LOG.info("Couldn't record a visit to ${row.id}", e)
    }
  }

  /** Upstream's `lastDispatchedVisitRef` and `lastVisitDispatchAtRef`, one per open. */
  private class Watermark(val threadId: String) {
    @Volatile var lastDispatched: String? = null

    @Volatile var lastDispatchAt: Instant = Instant.EPOCH
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(ThreadVisits::class.java)

    fun getInstance(project: Project): ThreadVisits = project.service()
  }
}
