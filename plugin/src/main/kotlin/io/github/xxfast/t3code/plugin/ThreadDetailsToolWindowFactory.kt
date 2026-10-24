package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.OPEN_IN_IDE_ACTION
import io.github.xxfast.t3code.plugin.actions.belongsIn
import io.github.xxfast.t3code.plugin.actions.gitCwd
import io.github.xxfast.t3code.plugin.actions.interruptCommand
import io.github.xxfast.t3code.plugin.actions.openInIdeReason
import io.github.xxfast.t3code.plugin.actions.performThreadAction
import io.github.xxfast.t3code.plugin.actions.stopCommands
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.VcsStatuses
import io.github.xxfast.t3code.plugin.ide.ideRunConfigurations
import io.github.xxfast.t3code.plugin.ide.runIdeConfiguration
import io.github.xxfast.t3code.plugin.ide.localRoots
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ide.runIdeAction
import io.github.xxfast.t3code.plugin.ide.showIdeChanges
import io.github.xxfast.t3code.plugin.ide.threadCwd
import io.github.xxfast.t3code.plugin.terminal.TERMINAL_OPERATE
import io.github.xxfast.t3code.plugin.terminal.ThreadTerminals
import io.github.xxfast.t3code.plugin.ui.BranchSelectionTarget
import io.github.xxfast.t3code.plugin.ui.EnvMode
import io.github.xxfast.t3code.plugin.ui.Hint
import io.github.xxfast.t3code.plugin.ui.LineageBusyAction
import io.github.xxfast.t3code.plugin.ui.LineageSubagent
import io.github.xxfast.t3code.plugin.ui.OpenInState
import io.github.xxfast.t3code.plugin.ui.IdeRunConfiguration
import io.github.xxfast.t3code.plugin.ui.RefPickerActions
import io.github.xxfast.t3code.plugin.ui.RunItem
import io.github.xxfast.t3code.plugin.ui.THREAD_STOP
import io.github.xxfast.t3code.plugin.ui.ThreadDetails
import io.github.xxfast.t3code.plugin.ui.ThreadDetailsPanelActions
import io.github.xxfast.t3code.plugin.ui.ThreadDetailsPanelState
import io.github.xxfast.t3code.plugin.ui.ThreadLineage
import io.github.xxfast.t3code.plugin.ui.ThreadLineageActions
import io.github.xxfast.t3code.plugin.ui.VcsStatus
import io.github.xxfast.t3code.plugin.ui.WorkspaceControlState
import io.github.xxfast.t3code.plugin.ui.buildMenuItems
import io.github.xxfast.t3code.plugin.ui.canChangeThreadBranch
import io.github.xxfast.t3code.plugin.ui.changesTotals
import io.github.xxfast.t3code.plugin.ui.deriveThreadLineage
import io.github.xxfast.t3code.plugin.ui.ideActionForGitAction
import io.github.xxfast.t3code.plugin.ui.mergeBackCommand
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.plugin.ui.resolveBranchSelectionTarget
import io.github.xxfast.t3code.plugin.ui.resolvePrimaryRunItem
import io.github.xxfast.t3code.plugin.ui.resolveQuickAction
import io.github.xxfast.t3code.plugin.ui.subagentStopCommand
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.plugin.ui.workspaceControlState
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ProjectScript
import io.github.xxfast.t3code.protocol.generated.VcsRef
import io.github.xxfast.t3code.protocol.has
import io.github.xxfast.t3code.protocol.resolveProjectScripts
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.JewelComposePanel

private val LOG: Logger = Logger.getInstance(ThreadDetailsToolWindowFactory::class.java)

/** "T3 Thread Details": what's around the thread picked in T3 Sidebar. */
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
 * [ThreadDetails] wired to the IDE: the Workspace and Version Control sections from the shell
 * row, the thread view, the checkout's `subscribeVcsStatus` and this window's roots; the
 * Lineage from the shell list and the thread's projection, its actions dispatched as upstream's
 * `ThreadRelationshipsPanel` does, one at a time.
 */
@Composable
private fun ThreadDetailsContent(project: Project, threadId: String, view: ThreadView?) {
  val client: T3Client = T3.get().client
  val controller: ChatController = ChatController.getInstance(project)
  val threads: List<Thread> by client.threads.collectAsState()
  val projects: List<T3Project> by client.projects.collectAsState()
  val connection: ConnectionState by client.connection.collectAsState()
  val connected: Boolean = connection is ConnectionState.Connected
  // The catalog names the machine; the ChatView loads it too, which is fine, it answers once
  LaunchedEffect(connected) { if (connected) controller.loadCatalog() }
  val catalog: ServerInfo? by controller.catalog.collectAsState()
  val lineage: ThreadLineage? = remember(threadId, view, threads, catalog) {
    deriveThreadLineage(threadId, view, threads, catalog?.providers.orEmpty())
  }
  var busyAction: LineageBusyAction? by remember(threadId) { mutableStateOf(null) }
  var stoppingId: String? by remember(threadId) { mutableStateOf(null) }
  val scope: CoroutineScope = rememberCoroutineScope()
  val opener: T3Opener = T3Opener.getInstance(project)
  val (panel: ThreadDetailsPanelState?, panelActions: ThreadDetailsPanelActions) =
    rememberThreadDetailsPanel(project, threadId, view, connection, catalog, projects, threads)

  ThreadDetails(
    threadId = threadId,
    panel = panel,
    panelActions = panelActions,
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

/**
 * The Workspace and Version Control sections' state and actions for [threadId], as
 * `ThreadDetailsPanelProps` are resolved in ChatView.tsx: the project row's scripts, `gitCwd`,
 * the checkout's status, the composer's workspace draft (shared with the ChatView through
 * [ChatController]), and what this IDE window can do with the checkout. Null before the shell
 * row is known.
 */
@Composable
private fun rememberThreadDetailsPanel(
  project: Project,
  threadId: String,
  view: ThreadView?,
  connection: ConnectionState,
  catalog: ServerInfo?,
  projects: List<T3Project>,
  threads: List<Thread>,
): Pair<ThreadDetailsPanelState?, ThreadDetailsPanelActions> {
  val client: T3Client = T3.get().client
  val controller: ChatController = ChatController.getInstance(project)
  val row: Thread? = view?.thread ?: threads.find { it.id == threadId }
  val t3Project: T3Project? = row?.let { r -> projects.find { it.id == r.projectId } }
  val outboxes: Map<String, PendingMessages> by controller.outboxes.collectAsState()
  val pending: PendingMessages = outboxes[threadId] ?: PendingMessages()
  val cwd: String? = threadCwd(row, projects)
  val statuses: StateFlow<VcsStatus?> =
    remember(cwd) { VcsStatuses.getInstance(project).status(cwd) }
  val vcs: VcsStatus? by statuses.collectAsState()
  val setup: WorktreeSetupState = rememberWorktreeSetup(view, pending, client)
  var refsQuery: String by remember(threadId) { mutableStateOf("") }
  val (refs: List<VcsRef>, refsStatus: String?) = rememberRefs(cwd, refsQuery, client)
  // `lastInvokedScriptByProjectId`, per project while the tool window lives
  var preferredScriptId: String? by remember(row?.projectId) { mutableStateOf(null) }
  val session: ServerSession? = (connection as? ConnectionState.Connected)?.session
  // What this window has open, read once per thread: content roots rarely change under us
  val roots: List<Path> = remember(project, threadId) {
    ReadAction.compute<List<Path>, RuntimeException> { localRoots(project) }
  }
  val serverIsLocal: Boolean = remember { T3.serverIsLocal() }
  val ideName: String = remember { ApplicationNamesInfo.getInstance().fullProductName }

  val state: ThreadDetailsPanelState? = if (row == null || t3Project == null) {
    null
  } else {
    val workspace: WorkspaceControlState? = view?.let { current ->
      workspaceControlState(
        view = current,
        draft = pending.workspace,
        currentGitBranch = vcs?.local?.refName,
        isGitRepo = vcs?.local?.isRepo ?: true,
        preparingWorktree = setup.preparing,
        refs = refs,
        refsQuery = refsQuery,
        refsStatus = refsStatus,
        canChangeBranch = canChangeThreadBranch(session),
      )
    }
    // `resolveProjectScripts`: the settings decide, the row is one input; the row alone until
    // the catalog answers
    val scripts: List<ProjectScript> =
      catalog?.let { resolveProjectScripts(it.scriptSettings, t3Project) } ?: t3Project.scripts
    val cwdPath: Path? = gitCwd(row, projects)
    val inWindow: Boolean = cwdPath != null && serverIsLocal && belongsIn(cwdPath, roots)
    // This window's run configurations count as the checkout's scripts once it's open here
    val configurations: List<IdeRunConfiguration> =
      remember(threadId, inWindow) { if (inWindow) ideRunConfigurations(project) else emptyList() }
    val gitActionsReason: String? = when {
      !serverIsLocal -> "Server is on another machine"
      !inWindow -> "${cwd ?: "The checkout"} isn't open in this window"
      else -> null
    }
    ThreadDetailsPanelState(
      environmentLabel = catalog?.environmentLabel,
      machine = catalog?.machine ?: "server",
      // `workspacePath` in `RunContextSelector`: none while a new worktree is still a choice
      workspacePath =
        if (workspace?.envMode == EnvMode.WORKTREE && row.worktreePath == null) null else cwd,
      workspace = workspace,
      // `OpenInIdeAction` hides itself when the thread is already this window's
      openIn = if (inWindow) null else OpenInState(
        label = "Open in $ideName",
        reason = openInIdeReason(row, cwdPath, serverIsLocal, Path::isDirectory),
      ),
      scripts = scripts,
      runConfigurations = configurations,
      primaryRun = resolvePrimaryRunItem(scripts, preferredScriptId, configurations),
      runScriptsReason = if (session?.has(TERMINAL_OPERATE) == true) null else
        "Pair this client again with permission to run terminal commands.",
      gitCwd = cwd,
      isGitRepo = vcs?.local?.isRepo ?: true,
      vcs = vcs,
      quickAction = resolveQuickAction(
        status = vcs,
        isBusy = false,
        isDefaultRef = vcs?.local?.isDefaultRef ?: false,
        hasPrimaryRemote = vcs?.local?.hasPrimaryRemote ?: false,
      ),
      menuItems = buildMenuItems(vcs, isBusy = false, vcs?.local?.hasPrimaryRemote ?: false),
      gitActionsReason = gitActionsReason,
      changes = changesTotals(vcs),
      pullRequest = vcs?.remote?.pr,
    )
  }

  val actions = ThreadDetailsPanelActions(
    // Composer-side until the first message, as the ChatView's own strip keeps them
    onEnvMode = { mode -> controller.draftWorkspace(threadId) { it.copy(envMode = mode) } },
    refPicker = RefPickerActions(
      onRefsQuery = { query -> refsQuery = query },
      onBaseBranch = { branch -> controller.draftWorkspace(threadId) { it.copy(branch = branch) } },
      onPickRef = onPickRef@{ ref ->
        val current: ThreadView = view ?: return@onPickRef
        val root: String = t3Project?.workspaceRoot ?: return@onPickRef
        val target: BranchSelectionTarget =
          resolveBranchSelectionTarget(root, current.thread.worktreePath, ref)
        controller.pickRef(threadId, cwd ?: root, ref, target)
      },
      onCreateRef = { name -> if (cwd != null) controller.createRef(threadId, cwd, name) },
      onStartFromOrigin = { on ->
        controller.draftWorkspace(threadId) { it.copy(startFromOrigin = on) }
      },
    ),
    onOpenInIde = {
      performThreadAction(project, threadId, OPEN_IN_IDE_ACTION, fromSidebar = false)
    },
    onRun = { item ->
      when (item) {
        is RunItem.Script -> {
          preferredScriptId = item.script.id
          ThreadTerminals.getInstance(project).run(threadId, item.script)
        }
        is RunItem.Configuration -> if (!runIdeConfiguration(project, item.configuration.id)) {
          notify(project, "Run configuration ${item.name} is gone", NotificationType.WARNING)
        }
      }
    },
    onGitAction = { action ->
      val actionId: String? = ideActionForGitAction(action)
      if (actionId == null || !runIdeAction(project, actionId)) {
        notify(project, "This IDE has no action for \"$action\"", NotificationType.WARNING)
      }
    },
    onOpenChanges = {
      if (!showIdeChanges(project)) {
        notify(project, "No Commit tool window in this IDE", NotificationType.WARNING)
      }
    },
    onOpenUrl = BrowserUtil::browse,
  )
  return state to actions
}
