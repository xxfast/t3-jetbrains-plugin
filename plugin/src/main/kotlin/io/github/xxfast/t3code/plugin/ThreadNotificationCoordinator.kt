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
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.BadgeIconSupplier
import io.github.xxfast.t3code.plugin.ide.NOTIFICATION_GROUP
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.settings.T3ProjectState
import io.github.xxfast.t3code.plugin.ui.ThreadStatus
import io.github.xxfast.t3code.plugin.ui.UNANSWERED_KINDS
import io.github.xxfast.t3code.plugin.ui.shownUnderParent
import io.github.xxfast.t3code.plugin.ui.waitingOnYou
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Upstream's `ThreadNotificationCoordinator` (apps/web/src/components/
 * ThreadNotificationCoordinator.tsx): a balloon in the "T3 Code" group when a thread needs you or
 * completes, and a dot and a count on T3 Sidebar's icon while threads wait on you. The balloon
 * and the Event Log stand in for both upstream's toast and its OS notification. Started by
 * [SidebarToolWindowFactory.init], so it works before the sidebar has ever been opened.
 *
 * Upstream's `notificationMode` and `inAppNotificationsEnabled` are client-local settings with
 * nothing on the wire, so they aren't read; the IDE's Settings > Notifications > "T3 Code" is the
 * switch. No sound either.
 */
@Service(Service.Level.PROJECT)
class ThreadNotificationCoordinator(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  private val started: AtomicBoolean = AtomicBoolean(false)

  /** One balloon per thread, as upstream's `pending` map by `threadNotificationTag`. */
  private val balloons: ConcurrentHashMap<String, Balloon> = ConcurrentHashMap()

  fun attach(toolWindow: ToolWindow) {
    if (!started.compareAndSet(false, true)) return
    val title: String = toolWindow.stripeTitle
    scope.launch {
      var notifications = ThreadNotifications()
      var shown = 0
      // What the IDE had before the badge went on, put back when it comes off
      var plain: Icon? = null
      // Live lists only: a cached one would baseline the IDE's last session, and every thread
      // waiting by now would look new once the server's list replaces it. Badge freezes too.
      T3.get().liveThreads.filterNotNull().collect { threads ->
        val (next: ThreadNotifications, changes: ThreadNotificationChanges) =
          notifications.next(threads)
        notifications = next
        // A request that's answered, or a failure that's retried, takes its balloon away
        changes.ended.forEach { id ->
          val balloon: Balloon = balloons[id]
            ?.takeIf { it.kind == ThreadNotificationKind.Input }
            ?: return@forEach
          if (balloons.remove(id, balloon)) balloon.notification.expire()
        }
        val count: Int = waitingThreads(threads).size
        if (changes.notify.isEmpty() && count == shown) return@collect
        withContext(Dispatchers.EDT) {
          if (project.isDisposed) return@withContext
          changes.notify.forEach(::notify)
          if (count == shown) return@withContext
          shown = count
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

  /** Shows [notification], on the EDT. */
  private fun notify(notification: ThreadNotification) {
    val thread: Thread = notification.thread
    // Plugin-only, upstream has no mute: a muted thread still badges, it just never balloons
    if (T3ProjectState.getInstance(project).isMuted(thread.id)) return
    // Already looking at it, as upstream skips the toast for the thread on screen
    val focused: Boolean = WindowManager.getInstance().getFrame(project)?.isActive == true
    if (focused && ThreadSelection.getInstance(project).selected.value == thread.id) return
    val balloon: Notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(
        notification.title,
        StringUtil.escapeXmlEntities(thread.title),
        notification.type,
      )
      .addAction(
        NotificationAction.createSimpleExpiring("Open thread") {
          T3Opener.getInstance(project).openThread(thread.id)
        },
      )
    balloons.put(thread.id, Balloon(notification.kind, balloon))?.notification?.expire()
    balloon.notify(project)
  }

  private class Balloon(val kind: ThreadNotificationKind, val notification: Notification)

  companion object {
    fun getInstance(project: Project): ThreadNotificationCoordinator = project.service()
  }
}

/** The toast's type upstream: success on completion, error on failure, a warning otherwise. */
private val ThreadNotification.type: NotificationType
  get() = when {
    kind == ThreadNotificationKind.Completion -> NotificationType.INFORMATION
    status == ThreadStatus.Failed -> NotificationType.ERROR
    else -> NotificationType.WARNING
  }

/**
 * Whether [thread] waits on something the composer answers. Auth refreshes and dynamic tool
 * calls are the provider's own business, like in `derivePendingThreadRequests`.
 */
private val Thread.waitsOnComposer: Boolean
  get() = waitingOnYou && pendingRuntimeRequest?.kind !in UNANSWERED_KINDS

/** The threads T3 Sidebar lists, not archived and not under a parent, that wait on you. */
fun waitingThreads(threads: List<Thread>): List<Thread> {
  return threads.filter { thread ->
    !thread.archived && !thread.shownUnderParent && thread.waitsOnComposer
  }
}
