package io.github.xxfast.t3code.plugin.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The plugin's balloon group, registered in `plugin.xml`. */
const val NOTIFICATION_GROUP: String = "T3 Code"

private val LOG: Logger = Logger.getInstance("io.github.xxfast.t3code.plugin.ide.Notifications")

/** A balloon in the T3 Code group. */
fun notify(project: Project, message: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup(NOTIFICATION_GROUP)
    .createNotification(message, type)
    .notify(project)
}

/** Launches [block], reporting a failure as "[failure]: why" in an error balloon. */
fun CoroutineScope.launchReporting(
  project: Project,
  failure: String,
  block: suspend CoroutineScope.() -> Unit,
): Job = launch {
  try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    LOG.info(failure, e)
    notify(project, "$failure: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
  }
}
