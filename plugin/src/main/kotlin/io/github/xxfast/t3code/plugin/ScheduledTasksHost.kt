package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ide.runIdeAction
import io.github.xxfast.t3code.plugin.ui.ScheduledTaskActions
import io.github.xxfast.t3code.plugin.ui.ScheduledTasks
import io.github.xxfast.t3code.plugin.ui.ScheduledTasksLoad
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.ScheduledTask
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val LOG: Logger = Logger.getInstance("io.github.xxfast.t3code.plugin.ScheduledTasksHost")

/** Where editing a scheduled task lives, an action another part of the plugin may register. */
const val OPEN_WEB_VIEW_SCHEDULED_TASKS: String = "T3Code.OpenWebView.ScheduledTasks"

/**
 * `scheduledTasksLive`, only while [connected], as upstream only asks a connected environment.
 * A failure keeps its message for the section to show.
 */
@Composable
fun rememberScheduledTasks(client: T3Client, connected: Boolean): ScheduledTasksLoad {
  val load: State<ScheduledTasksLoad> = produceState(ScheduledTasksLoad(), client, connected) {
    value = ScheduledTasksLoad()
    if (!connected) return@produceState
    try {
      client.scheduledTasks().collect { tasks -> value = ScheduledTasksLoad(tasks) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LOG.info("scheduledTasks.subscribe failed", e)
      value = ScheduledTasksLoad(error = e.message ?: e.javaClass.simpleName)
    }
  }
  return load.value
}

/** Runs [OPEN_WEB_VIEW_SCHEDULED_TASKS] when it's registered, else null. Looked up by id only. */
fun scheduledTasksEditor(project: Project): (() -> Unit)? =
  ActionManager.getInstance().getAction(OPEN_WEB_VIEW_SCHEDULED_TASKS)
    ?.let { { runIdeAction(project, OPEN_WEB_VIEW_SCHEDULED_TASKS) } }

/**
 * Runs [block], reporting a failure in an error balloon titled as upstream's toast, the server's
 * `ScheduledTaskError` message as its body. [done] runs either way.
 */
fun CoroutineScope.launchTaskCall(
  project: Project,
  title: String,
  block: suspend () -> Unit,
  done: () -> Unit,
) {
  launch {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LOG.info(title, e)
      notify(project, e.message ?: e.javaClass.simpleName, NotificationType.ERROR, title)
    } finally {
      done()
    }
  }
}

/**
 * The "Scheduled" tab wired to the client, as upstream's `ScheduledTaskRow` acts: one call per
 * row at a time, a failure as "Could not update scheduled task".
 */
@Composable
fun ScheduledTasksContent(project: Project) {
  val client: T3Client = T3.get().client
  val connection: ConnectionState by client.connection.collectAsState()
  val connected: Boolean = connection is ConnectionState.Connected
  val load: ScheduledTasksLoad = rememberScheduledTasks(client, connected)
  val blocked: String? = operateBlockReason(connection)
  var busy: Set<String> by remember { mutableStateOf(emptySet()) }
  val scope: CoroutineScope = rememberCoroutineScope()
  val editor: (() -> Unit)? = remember(project) { scheduledTasksEditor(project) }
  fun act(task: ScheduledTask, call: suspend () -> Unit) {
    if (task.id in busy || blocked != null) return
    busy = busy + task.id
    scope.launchTaskCall(project, "Could not update scheduled task", call) {
      busy = busy - task.id
    }
  }
  ScheduledTasks(
    connected = connected,
    load = load,
    blocked = blocked,
    busy = busy,
    actions = ScheduledTaskActions(
      onRunNow = { task -> act(task) { client.runScheduledTask(task.id) } },
      onEnabledChange = { task, enabled ->
        act(task) { client.setScheduledTaskEnabled(task.id, enabled) }
      },
    ),
    onEdit = editor,
  )
}
