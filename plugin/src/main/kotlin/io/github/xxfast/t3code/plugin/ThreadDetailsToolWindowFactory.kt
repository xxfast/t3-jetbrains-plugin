package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.interruptCommand
import io.github.xxfast.t3code.plugin.actions.stopCommands
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.Hint
import io.github.xxfast.t3code.plugin.ui.LineageBusyAction
import io.github.xxfast.t3code.plugin.ui.LineageSubagent
import io.github.xxfast.t3code.plugin.ui.THREAD_STOP
import io.github.xxfast.t3code.plugin.ui.ThreadDetails
import io.github.xxfast.t3code.plugin.ui.ThreadLineage
import io.github.xxfast.t3code.plugin.ui.ThreadLineageActions
import io.github.xxfast.t3code.plugin.ui.deriveThreadLineage
import io.github.xxfast.t3code.plugin.ui.mergeBackCommand
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.plugin.ui.subagentStopCommand
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import java.util.UUID
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel

private val LOG: Logger = Logger.getInstance(ThreadDetailsToolWindowFactory::class.java)

/** "T3 Thread Details": what's around the thread picked in T3 Sidebar. Its Lineage, so far. */
class ThreadDetailsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val client: T3Client = T3.get().client
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      when (val id: String? = selection.selected.collectAsState().value) {
        null -> Hint("Select a thread to see its details")
        else -> {
          // Keyed, so a new thread starts from a fresh state instead of the last one's view
          val load: ThreadLoad = key(id) {
            remember { client.threadLoad(id) }.collectAsState(initial = ThreadLoad()).value
          }
          ThreadDetailsContent(project, id, load.view)
        }
      }
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
  }
}

/**
 * [ThreadDetails] wired to the IDE: the Lineage from the shell list and the thread's projection,
 * and its actions dispatched as upstream's `ThreadRelationshipsPanel` does, one at a time.
 */
@Composable
private fun ThreadDetailsContent(project: Project, threadId: String, view: ThreadView?) {
  val client: T3Client = T3.get().client
  val threads: List<Thread> by client.threads.collectAsState()
  val connection: ConnectionState by client.connection.collectAsState()
  val catalog: ServerInfo? by ChatController.getInstance(project).catalog.collectAsState()
  val lineage: ThreadLineage? = remember(threadId, view, threads, catalog) {
    deriveThreadLineage(threadId, view, threads, catalog?.providers.orEmpty())
  }
  var busyAction: LineageBusyAction? by remember(threadId) { mutableStateOf(null) }
  var stoppingId: String? by remember(threadId) { mutableStateOf(null) }
  val scope: CoroutineScope = rememberCoroutineScope()
  val opener: T3Opener = T3Opener.getInstance(project)

  ThreadDetails(
    threadId = threadId,
    lineage = lineage,
    busyAction = busyAction,
    stoppingId = stoppingId,
    enabled = operateBlockReason(connection) == null,
    actions = ThreadLineageActions(
      onOpenThread = opener::openThread,
      // `merge`: on success the merge target opens
      onMerge = onMerge@{
        val target: String = lineage?.mergeTargetThreadId ?: return@onMerge
        val runId: String = lineage.mergeBackRunId ?: return@onMerge
        if (busyAction != null) return@onMerge
        busyAction = LineageBusyAction.Merge
        scope.launch {
          val merged: Boolean = reporting(project, "Could not merge back") {
            client.dispatch(mergeBackCommand(threadId, target, runId, commandId()))
          }
          busyAction = null
          if (merged) opener.openThread(target)
        }
      },
      // `detach`: upstream's `stopThreadSession`
      onDetach = onDetach@{
        val current: ThreadView = view ?: return@onDetach
        if (lineage?.canDetach != true || busyAction != null) return@onDetach
        busyAction = LineageBusyAction.Detach
        scope.launch {
          reporting(project, "Could not disconnect agent session") {
            stopCommands(current, commandId()).forEach { command -> client.dispatch(command) }
          }
          busyAction = null
        }
      },
      // `stopSubagent`
      onStop = onStop@{ row ->
        val agent: LineageSubagent = row.agent ?: return@onStop
        if (stoppingId != null) return@onStop
        stoppingId = row.threadId
        scope.launch {
          reporting(project, "Could not stop subagent") {
            stopSubagentCommand(client, agent, row.threadId, catalog)
              ?.let { command -> client.dispatch(command) }
          }
          stoppingId = null
        }
      },
    ),
  )
}

/**
 * What `stopSubagent` hands `interruptThreadTurn` (commands.ts:769): a native subagent stops on
 * its parent's run with `subagent.stop`; an app-owned one interrupts its child thread, with
 * `thread.stop` where the server takes it, else the child's active run. Null with nothing to stop.
 */
private suspend fun stopSubagentCommand(
  client: T3Client,
  agent: LineageSubagent,
  childThreadId: String,
  info: ServerInfo?,
): OrchestrationV2Command? = when {
  agent.origin == "provider_native" -> subagentStopCommand(agent, commandId())
  info.supports(THREAD_STOP) ->
    Command.ThreadStop(commandId = commandId(), threadId = childThreadId)
  else -> interruptCommand(client.thread(childThreadId).first(), commandId())
}

/** Runs [block], reporting a failure in an error balloon titled as upstream's toast. */
private suspend fun reporting(
  project: Project,
  title: String,
  block: suspend () -> Unit,
): Boolean = try {
  block()
  true
} catch (e: CancellationException) {
  throw e
} catch (e: Exception) {
  LOG.info(title, e)
  notify(project, e.message ?: e.javaClass.simpleName, NotificationType.ERROR, title)
  false
}

private fun commandId(): String = UUID.randomUUID().toString()
