package io.github.xxfast.t3code.plugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.BadgeIconSupplier
import io.github.xxfast.t3code.plugin.ide.NOTIFICATION_GROUP
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ui.UNANSWERED_KINDS
import io.github.xxfast.t3code.plugin.ui.approvalLabel
import io.github.xxfast.t3code.plugin.ui.shownUnderParent
import io.github.xxfast.t3code.plugin.ui.waitingOnYou
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells you when a thread starts waiting on you: a balloon with "Open", and a dot and a count on
 * T3 Sidebar's icon. Started by [SidebarToolWindowFactory.init], which runs when the tool window
 * registers, so it works before the sidebar has ever been opened.
 */
@Service(Service.Level.PROJECT)
class WaitingWatcher(private val project: Project, private val scope: CoroutineScope) {
  private val started: AtomicBoolean = AtomicBoolean(false)

  /** Balloons still showing, by [waitingKey], so a resolved request takes its balloon away. */
  private val balloons: ConcurrentHashMap<String, Notification> = ConcurrentHashMap()

  fun attach(toolWindow: ToolWindow) {
    if (!started.compareAndSet(false, true)) return
    val title: String = toolWindow.stripeTitle
    scope.launch {
      var tracker = WaitingTracker()
      var shown = 0
      // What the IDE had before the badge went on, put back when it comes off
      var plain: Icon? = null
      T3.get().client.threads.collect { threads ->
        val waiting: List<Thread> = waitingThreads(threads)
        val (next: WaitingTracker, changes: WaitingChanges) =
          tracker.next(waiting, loaded = threads.isNotEmpty())
        tracker = next
        changes.stopped.forEach { key -> balloons.remove(key)?.expire() }
        changes.started.forEach(::notify)
        val count: Int = waiting.size
        if (count == shown) return@collect
        shown = count
        withContext(Dispatchers.EDT) {
          if (project.isDisposed) return@withContext
          if (count > 0) {
            val base: Icon? = plain ?: toolWindow.icon.also { plain = it }
            if (base != null) toolWindow.setIcon(BadgeIconSupplier(base).infoIcon)
            // No public numeric badge for tool window icons; the count goes in the title
            toolWindow.stripeTitle = "$title ($count waiting)"
          } else {
            plain?.let(toolWindow::setIcon)
            plain = null
            toolWindow.stripeTitle = title
          }
        }
      }
    }
  }

  private fun notify(thread: Thread) {
    // Already looking at it
    if (ThreadSelection.getInstance(project).selected.value == thread.id) return
    val key: String = waitingKey(thread) ?: return
    val notification: Notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(
        StringUtil.escapeXmlEntities(thread.title),
        "Waiting on you: ${waitingLabel(thread.pendingRuntimeRequest?.kind)}",
        NotificationType.INFORMATION,
      )
      .addAction(
        NotificationAction.createSimpleExpiring("Open") {
          T3Opener.getInstance(project).openThread(thread.id)
        },
      )
    balloons.put(key, notification)?.expire()
    notification.notify(project)
  }

  companion object {
    fun getInstance(project: Project): WaitingWatcher = project.service()
  }
}

/**
 * Whether [thread] waits on something the composer answers. Auth refreshes and dynamic tool
 * calls are the provider's own business, like in `derivePendingThreadRequests`.
 */
private val Thread.waitsOnComposer: Boolean
  get() = waitingOnYou && pendingRuntimeRequest?.kind !in UNANSWERED_KINDS

/** One wait: the thread and the request it waits on, so a new request is a new wait. */
fun waitingKey(thread: Thread): String? {
  if (!thread.waitsOnComposer) return null
  return "${thread.id}:${thread.pendingRuntimeRequest?.id ?: "waiting"}"
}

/** What a thread waits on, for the balloon: a question, or the approval's kind. */
fun waitingLabel(kind: String?): String = when (kind) {
  null -> "approval"
  "user_input" -> "question"
  else -> approvalLabel(kind).lowercase()
}

/** The threads T3 Sidebar lists, not archived and not under a parent, that wait on you. */
fun waitingThreads(threads: List<Thread>): List<Thread> {
  return threads.filter { thread ->
    !thread.archived && !thread.shownUnderParent && thread.waitsOnComposer
  }
}

data class WaitingChanges(val started: List<Thread>, val stopped: Set<String>)

/**
 * Which waits started and which ended since the last look. The first [loaded] list is the
 * baseline: threads already waiting when the IDE connects don't each get a balloon.
 */
data class WaitingTracker(val keys: Set<String> = emptySet(), val seeded: Boolean = false) {
  /** [waiting] is [waitingThreads] of a thread list; [loaded] is whether that list had any. */
  fun next(waiting: List<Thread>, loaded: Boolean): Pair<WaitingTracker, WaitingChanges> {
    val byKey: Map<String, Thread> = waiting
      .mapNotNull { thread -> waitingKey(thread)?.let { key -> key to thread } }
      .toMap()
    val none = WaitingChanges(emptyList(), emptySet())
    if (!seeded) {
      if (!loaded) return this to none
      return WaitingTracker(byKey.keys, seeded = true) to none
    }

    val started: List<Thread> = byKey.filterKeys { it !in keys }.values.toList()
    val stopped: Set<String> = keys - byKey.keys
    return WaitingTracker(byKey.keys, seeded = true) to WaitingChanges(started, stopped)
  }
}
