package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.xxfast.t3code.plugin.actions.interruptCommand
import io.github.xxfast.t3code.plugin.actions.RENAME_ACTION
import io.github.xxfast.t3code.plugin.actions.performThreadAction
import io.github.xxfast.t3code.plugin.actions.showThreadMenu
import io.github.xxfast.t3code.plugin.ui.ChatHeaderActions
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.VcsStatuses
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ide.threadCwd
import io.github.xxfast.t3code.plugin.review.checkpointForRun
import io.github.xxfast.t3code.plugin.review.rollbackBlockReason
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.ui.Advance
import io.github.xxfast.t3code.plugin.ui.COMPACT_COMMAND
import io.github.xxfast.t3code.plugin.ui.ChatView
import io.github.xxfast.t3code.plugin.ui.ComposerContextWindow
import io.github.xxfast.t3code.plugin.ui.ComposerDispatchMode
import io.github.xxfast.t3code.plugin.ui.ComposerFooterActions
import io.github.xxfast.t3code.plugin.ui.ComposerFooterState
import io.github.xxfast.t3code.plugin.ui.ComposerState
import io.github.xxfast.t3code.plugin.ui.ContextWindowSnapshot
import io.github.xxfast.t3code.plugin.settings.ComposerDrafts
import io.github.xxfast.t3code.plugin.ui.DraftExtras
import io.github.xxfast.t3code.plugin.ui.PromptStashEntry
import io.github.xxfast.t3code.plugin.ui.parentThreadLink
import io.github.xxfast.t3code.plugin.ui.LocalMessageImages
import io.github.xxfast.t3code.plugin.ui.MessageImageState
import io.github.xxfast.t3code.plugin.ui.BranchSelectionTarget
import io.github.xxfast.t3code.plugin.ui.EnvMode
import io.github.xxfast.t3code.plugin.ui.canChangeThreadBranch
import io.github.xxfast.t3code.plugin.ui.hasCompactableConversation
import io.github.xxfast.t3code.plugin.ui.resolveBranchSelectionTarget
import io.github.xxfast.t3code.plugin.ui.SELECT_BASE_BRANCH_ERROR
import io.github.xxfast.t3code.plugin.ui.WorkspaceControlState
import io.github.xxfast.t3code.plugin.ui.WorktreeSetupProgress
import io.github.xxfast.t3code.plugin.ui.activeMessageCount
import io.github.xxfast.t3code.plugin.ui.canOverrideServerThreadEnvMode
import io.github.xxfast.t3code.plugin.ui.holdWorktreeSetup
import io.github.xxfast.t3code.plugin.ui.latestRun
import io.github.xxfast.t3code.plugin.ui.resolveSendEnvMode
import io.github.xxfast.t3code.plugin.ui.resolveVisibleWorktreeSetup
import io.github.xxfast.t3code.plugin.ui.resolveWorktreeSetupProgress
import io.github.xxfast.t3code.plugin.ui.shouldShowComposerContextStrip
import io.github.xxfast.t3code.plugin.ui.shouldCreateWorktree
import io.github.xxfast.t3code.plugin.ui.workspaceControlState
import io.github.xxfast.t3code.plugin.ui.worktreeSetupBlocksSend
import io.github.xxfast.t3code.plugin.terminal.ThreadTerminals
import io.github.xxfast.t3code.plugin.ui.ModelChangeBlock
import io.github.xxfast.t3code.plugin.ui.modelDisabledReason
import io.github.xxfast.t3code.plugin.ui.providerModelKey
import io.github.xxfast.t3code.plugin.ui.PendingActions
import io.github.xxfast.t3code.plugin.ui.PendingRequest
import io.github.xxfast.t3code.plugin.ui.PendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.PendingUserInputDraftAnswer
import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.plugin.ui.VcsSummary
import io.github.xxfast.t3code.plugin.ui.activeContextWindow
import io.github.xxfast.t3code.plugin.ui.activeRun
import io.github.xxfast.t3code.plugin.ui.advance
import io.github.xxfast.t3code.plugin.ui.answersResponse
import io.github.xxfast.t3code.plugin.ui.approvalResponse
import io.github.xxfast.t3code.plugin.ui.composerFooter
import io.github.xxfast.t3code.plugin.ui.composerState
import io.github.xxfast.t3code.plugin.ui.contextId
import io.github.xxfast.t3code.plugin.ui.derivePendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.deriveReportedModelSelection
import io.github.xxfast.t3code.plugin.ui.dismissal
import io.github.xxfast.t3code.plugin.ui.getStartedThreadModelChangeBlockReason
import io.github.xxfast.t3code.plugin.ui.instance
import io.github.xxfast.t3code.plugin.ui.mergeOptimistic
import io.github.xxfast.t3code.plugin.ui.nextModelSelection
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.plugin.ui.optionsJson
import io.github.xxfast.t3code.plugin.ui.pendingRequest
import io.github.xxfast.t3code.plugin.ui.providerSupportsManualCompaction
import io.github.xxfast.t3code.plugin.ui.rememberNow
import io.github.xxfast.t3code.plugin.ui.seedUserInputDraftAnswers
import io.github.xxfast.t3code.plugin.ui.setPendingUserInputCustomAnswer
import io.github.xxfast.t3code.plugin.ui.setThreadInteractionMode
import io.github.xxfast.t3code.plugin.ui.setThreadRuntimeMode
import io.github.xxfast.t3code.plugin.ui.shouldOfferResumeCompaction
import io.github.xxfast.t3code.plugin.ui.slug
import io.github.xxfast.t3code.plugin.ui.threadSupportsProviderHandoff
import io.github.xxfast.t3code.plugin.ui.togglePendingUserInputOptionSelection
import io.github.xxfast.t3code.plugin.ui.toggledInteractionMode
import io.github.xxfast.t3code.plugin.ui.updateThreadModelSelection
import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
import io.github.xxfast.t3code.protocol.generated.VcsListRefsInput
import io.github.xxfast.t3code.protocol.generated.VcsListRefsResult
import io.github.xxfast.t3code.protocol.generated.VcsRef
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import kotlin.coroutines.cancellation.CancellationException
import java.time.Instant
import io.github.xxfast.t3code.plugin.ui.AttachmentActions
import io.github.xxfast.t3code.plugin.ui.EditingQueuedRun
import io.github.xxfast.t3code.plugin.ui.QueuedRunsActions
import io.github.xxfast.t3code.plugin.ui.QueuedRunsState
import io.github.xxfast.t3code.plugin.ui.ThreadQueueWorkflowState
import io.github.xxfast.t3code.plugin.ui.deriveThreadQueueWorkflowState
import java.util.UUID

/** [ChatView] wired to the IDE, for the thread's editor tab. */
@Composable
fun ChatContent(project: Project, threadId: String, load: ThreadLoad, onRetry: () -> Unit) {
  val opener: T3Opener = T3Opener.getInstance(project)
  val controller: ChatController = ChatController.getInstance(project)
  val client: T3Client = T3.get().client
  val projects: List<T3Project> by client.projects.collectAsState()
  val threads: List<Thread> by client.threads.collectAsState()
  val favicons: Map<String, ImageBitmap> by ProjectIcons.get().favicons.collectAsState()
  val connection: ConnectionState by client.connection.collectAsState()
  val outboxes: Map<String, PendingMessages> by controller.outboxes.collectAsState()
  val extras: Map<String, DraftExtras> by controller.extras.collectAsState()
  val comments: List<ReviewCommentContext> = extras[threadId]?.comments.orEmpty()
  val pending: PendingMessages = outboxes[threadId] ?: PendingMessages()
  val draft: TextFieldState = remember(threadId) { controller.draft(threadId) }
  val stash: List<PromptStashEntry> by ComposerDrafts.get().stash.collectAsState()
  LaunchedEffect(projects) { ProjectIcons.get().ensure(projects) }
  val queuedRuns: QueuedRuns = QueuedRuns.getInstance(project)
  val editingAll: Map<String, EditingQueuedRun> by queuedRuns.editing.collectAsState()
  val queueBusy: Map<String, String> by queuedRuns.busy.collectAsState()
  val queueSaving: Set<String> by queuedRuns.saving.collectAsState()
  // `useQueuedRunEdit`: while a queued message is edited the composer holds its own draft
  val editing: EditingQueuedRun? = editingAll[threadId]
  val composerDraft: TextFieldState = if (editing != null) queuedRuns.editDraft(threadId) else draft

  val view: ThreadView? = load.view
  // For a check after a modal dialog, which outlives this composition's view
  val latest: ThreadView? by rememberUpdatedState(view)
  val operable: Boolean = operateBlockReason(connection) == null
  // Again when a dispatch answers: the view may already be past its sequence, or resolved
  val requests: PendingThreadRequests = remember(view?.runtimeRequests, view?.items) {
    derivePendingThreadRequests(view?.runtimeRequests.orEmpty(), view?.items.orEmpty())
  }
  LaunchedEffect(view, requests, pending.delivered, pending.responding, pending.settings) {
    view?.let { controller.reconcile(it, requests.requestIds) }
  }
  LaunchedEffect(view, editing) { view?.let { queuedRuns.reconcile(it, draft) } }
  val queue: ThreadQueueWorkflowState? = remember(view) {
    view?.let(::deriveThreadQueueWorkflowState)
  }
  val shown: ThreadView? = remember(view, pending.messages) {
    view?.let { current ->
      current.copy(items = mergeOptimistic(current.items, current.messages, pending.messages))
    }
  }
  val projectId: String? = view?.thread?.projectId
  // One subscription per checkout, shared by every tab showing it
  val cwd: String? = threadCwd(view?.thread, projects)
  val vcs: VcsSummary? by remember(cwd) { VcsStatuses.getInstance(project).summary(cwd) }
    .collectAsState()
  val setup: WorktreeSetupState = rememberWorktreeSetup(view, pending, client)
  val composer: ComposerState = composerState(
    connection = connection,
    running = view?.let { activeRun(it.runs) } != null,
    // A worktree being prepared holds the composer, upstream's `blocksSend`
    sending = pending.sending || setup.blocksSend || threadId in queueSaving,
    error = pending.error,
    // Upstream's `followUpBehavior` default; Ctrl/Cmd sends the other
    queue = false,
    reviewComments = comments.size,
  ).copy(editingQueuedMessage = editing != null)
  val (request: PendingRequest?, actions: PendingActions) =
    rememberPendingRequest(view, requests, pending.responding, controller)
  val (footer: ComposerFooterState?, footerActions: ComposerFooterActions) =
    rememberComposerFooter(project, view, pending, connection, controller, cwd, vcs, setup)
  val contextWindow: ComposerContextWindow =
    rememberContextWindow(view, footer, composer, connection, controller)
  val images: AttachmentImages = AttachmentImages.get()
  val previews: Map<String, ImageBitmap> by images.previews.collectAsState()
  val imageState: MessageImageState = remember(previews, project) {
    MessageImageState(
      previews = previews,
      onShown = images::ensure,
      onOpen = { attachment -> images.open(project, attachment) },
    )
  }
  // The timeline's sent images, read by the user message row only
  CompositionLocalProvider(LocalMessageImages provides imageState) {
    ChatView(
      view = shown,
      error = load.error,
      project = projects.find { it.id == projectId },
      favicon = favicons[projectId],
      composer = composer,
      draft = composerDraft,
      pending = request,
      pendingActions = actions,
      footer = footer,
      footerActions = footerActions,
      onClearReview = { comments.forEach { controller.removeContext(threadId, it.contextId) } },
      onRollback = { run ->
        view?.let { current -> confirmRollback(project, controller, current, run) { latest } }
      },
      onSend = onSend@{ text, mode ->
        val current: ThreadView = view ?: return@onSend
        // Edit mode repurposes the composer: a send saves the queued message in place
        if (editing != null) return@onSend queuedRuns.saveEdit(threadId, text)
        // `compactBeforeSend`, ChatView.tsx:6370: a `/compact` of its own doesn't compact twice
        val compactFirst: Boolean = contextWindow.compactBeforeSendTokens != null &&
          !contextWindow.keepFullHistory &&
          text.lowercase() != COMPACT_COMMAND
        // `shouldCreateWorktree`, ChatView.tsx:6324: the first message in New worktree mode
        // goes out as a launch, and refuses without a base branch
        val workspace: WorkspaceControlState? = footer?.workspace
        val sendEnvMode: EnvMode =
          resolveSendEnvMode(workspace?.envMode ?: EnvMode.LOCAL, isGitRepo = workspace != null)
        val worktree: ChatController.WorktreeLaunch? =
          if (workspace != null && footer != null && shouldCreateWorktree(current, sendEnvMode)) {
            val base: String = workspace.baseBranch
              ?: return@onSend controller.blocked(current.thread.id, SELECT_BASE_BRANCH_ERROR)
            ChatController.WorktreeLaunch(
              baseBranch = base,
              startFromOrigin = workspace.startFromOrigin,
              runtimeMode = footer.runtimeMode,
              interactionMode = footer.interactionMode,
            )
          } else {
            null
          }
        // In local mode a picked branch lands on the row with the send
        val nextBranch: String? =
          if (worktree == null && workspace != null) pending.workspace.branch else null
        controller.send(current, text, mode, compactFirst, worktree, nextBranch)
      },
      onInterrupt = {
        view?.let { current ->
          interruptCommand(current, UUID.randomUUID().toString())
            ?.let { command -> controller.dispatchForThread(current, command) }
        }
      },
      onRetry = onRetry,
      onOpenFile = { path -> view?.let { opener.openFile(it, path) } },
      onDiffFile = { change -> view?.let { opener.showDiff(it, change) } },
      onDiffRun = { run -> view?.let { opener.showRunDiff(it, run) } },
      vcs = vcs,
      // Upstream's `ChatHeader`: the thread action menu from the title, rename on double-click,
      // a new thread from the project. Each only with the operate scope, as `canOperateThread`
      header = ChatHeaderActions(
        onOpenMenu = { x, y -> showThreadMenu(project, threadId, x, y, view = latest) },
        onRename = if (operable) {
          { performThreadAction(project, threadId, RENAME_ACTION, fromSidebar = false) }
        } else {
          null
        },
        onNewThreadInProject = if (operable) {
          {
            view?.let { current ->
              // Upstream carries the composer's pick, else the row's (`carryModelSelection`)
              val carry: ModelSelection =
                pending.modelDraft ?: pending.settings.modelSelection(current.thread)
              NewThread.getInstance(project).start(current.thread.projectId, carry = carry)
            }
          }
        } else {
          null
        },
        parentThreadLink = parentThreadLink(view?.thread, threads),
        onOpenThread = { parentId -> opener.openThread(parentId) },
      ),
      // A queued message's edit is text only: the thread's draft attachments stay out of it
      extras = if (editing != null) DraftExtras() else extras[threadId] ?: DraftExtras(),
      attachmentActions = rememberAttachmentActions(project, threadId, controller)
        .takeIf { editing == null } ?: AttachmentActions(),
      stashCount = stash.size,
      contextWindow = contextWindow,
      mentions = rememberComposerMentions(threadId, client, controller),
      worktreeSetup = setup.visible,
      onCancelWorktreeSetup = { controller.cancelWorktreeSetup(threadId) },
      onOpenSetupTerminal = { terminalId ->
        ThreadTerminals.getInstance(project).open(threadId, terminalId)
      },
      queue = queue?.let { workflow ->
        QueuedRunsState(
          workflow = workflow,
          editingRunId = editing?.runId,
          busy = queueBusy.containsKey(threadId),
          enabled = operable,
        )
      },
      queueActions = QueuedRunsActions(
        onEdit = { queued -> view?.let { queuedRuns.beginEdit(it, queued) } },
        onCancelEdit = { queuedRuns.cancelEdit(threadId) },
        onSteer = { queuedRunId, targetRunId ->
          view?.let { queuedRuns.promote(it, queuedRunId, targetRunId) }
        },
        onRemove = { runId -> view?.let { queuedRuns.cancel(it, runId) } },
        onMove = { move -> view?.let { queuedRuns.reorder(it, move) } },
        onResume = { view?.let(queuedRuns::resume) },
      ),
    )
  }
}

/** What [rememberWorktreeSetup] works out for the thread on screen. */
class WorktreeSetupState(
  /** The snapshot the timeline shows, [resolveVisibleWorktreeSetup]. */
  val visible: WorktreeSetupSnapshot?,
  /** `isPreparingWorktree`: the worktree isn't ready for the agent yet. */
  val preparing: Boolean,
  /** `blocksSend`: the composer waits on the setup. */
  val blocksSend: Boolean,
)

/**
 * Upstream's `useWorktreeSetup` (`apps/web/src/components/chat/useWorktreeSetup.ts`):
 * `subscribeWorktreeSetup` for the thread while a launch is in flight, its run is preparing, or
 * it has a worktree, holding the last snapshot across the server's null after a finished
 * setup. A follow-up message hides a finished card.
 */
@Composable
internal fun rememberWorktreeSetup(
  view: ThreadView?,
  pending: PendingMessages,
  client: T3Client,
): WorktreeSetupState {
  val threadId: String? = view?.thread?.id
  val run: Run? = view?.let { latestRun(it.runs) }
  // `startedSetup`: a launch sent from here keeps the stream open after it answers, until the
  // thread's row or run says the setup is on, as `begin()` records it before the server knows
  var started: Boolean by remember(threadId) { mutableStateOf(false) }
  if (pending.launching) started = true
  val subscribe: Boolean = threadId != null &&
    (started || run?.status == "preparing" || view.thread.worktreePath != null)
  var held: WorktreeSetupSnapshot? by remember(threadId) { mutableStateOf(null) }
  var latest: WorktreeSetupSnapshot? by remember(threadId) { mutableStateOf(null) }
  LaunchedEffect(threadId, subscribe) {
    if (threadId == null || !subscribe) return@LaunchedEffect
    client.worktreeSetup(threadId).collect { snapshot ->
      latest = snapshot
      held = holdWorktreeSetup(held, snapshot)
    }
  }
  if (threadId == null) return WorktreeSetupState(null, preparing = false, blocksSend = false)
  val progress: WorktreeSetupProgress = resolveWorktreeSetupProgress(
    threadId = threadId,
    localPreparing = pending.launching,
    runStatus = run?.status,
    latest = latest,
    held = held,
  )
  val visible: WorktreeSetupSnapshot? = resolveVisibleWorktreeSetup(
    live = progress.snapshot,
    recorded = null,
    turnStarted = run?.startedAt != null,
    followUpSent = activeMessageCount(view!!) > 1,
  )
  return WorktreeSetupState(
    visible = visible,
    preparing = progress.preparing,
    blocksSend = worktreeSetupBlocksSend(visible, progress.preparing),
  )
}

/**
 * The footer's context window: [view]'s usage (`activeContextWindow`, ChatView.tsx:2547), its
 * "Compact context" when the provider has `/compact`, and upstream's `resumeCompactionTokens`
 * (ChatView.tsx:5307) with the per-thread "keep full history" choice. Upstream's "never" answer
 * to Claude's own resume question and held queued runs aren't checked.
 */
@Composable
private fun rememberContextWindow(
  view: ThreadView?,
  footer: ComposerFooterState?,
  composer: ComposerState,
  connection: ConnectionState,
  controller: ChatController,
): ComposerContextWindow {
  val threadId: String? = view?.thread?.id
  var keepFullHistory: Boolean by remember(threadId) { mutableStateOf(false) }
  val minute: Instant by rememberNow(60_000)
  val usage: ContextWindowSnapshot? = remember(view) { view?.let(::activeContextWindow) }
  val canCompact: Boolean = providerSupportsManualCompaction(footer?.provider)
  val disabledReason: String? = when {
    operateBlockReason(connection) != null -> "This connection cannot change threads."
    !canCompact -> "Compaction is unavailable for this provider"
    else -> null
  }
  val offer: Boolean = usage != null &&
    disabledReason == null &&
    shouldOfferResumeCompaction(
      driver = footer?.provider?.driver,
      usedTokens = usage.usedTokens,
      updatedAt = usage.updatedAt,
      promptCacheTtlMs = usage.promptCacheTtlMs,
      now = minute,
    )
  return ComposerContextWindow(
    usage = usage,
    modelDisplayName = footer?.modelLabel,
    // `compactThreadContext`: nothing while a run, a send or a request is in the way
    onCompact = if (canCompact) {
      {
        if (view != null && !composer.running && !composer.sending) {
          controller.send(view, COMPACT_COMMAND, ComposerDispatchMode.AUTO)
        }
      }
    } else {
      null
    },
    compactDisabledReason = disabledReason,
    hasCompactableConversation = view?.let { hasCompactableConversation(it.items) } ?: false,
    compactBeforeSendTokens = if (offer) usage?.usedTokens else null,
    keepFullHistory = keepFullHistory,
    onToggleKeepFullHistory = { keepFullHistory = !keepFullHistory },
  )
}

/**
 * The approval or question [view] waits on, and what answering it does. A question's drafts and
 * the question on screen live here, per request, like upstream's `usePendingThreadRequests.ts`.
 */
@Composable
private fun rememberPendingRequest(
  view: ThreadView?,
  requests: PendingThreadRequests,
  responding: Set<String>,
  controller: ChatController,
): Pair<PendingRequest?, PendingActions> {
  val input: TurnItem.UserInputRequest? = requests.userInputs.firstOrNull()?.item
  val questions: List<UserInputQuestion> = input?.questions.orEmpty()
  val drafts: MutableState<Map<String, PendingUserInputDraftAnswer>> =
    remember(input?.requestId) { mutableStateOf(seedUserInputDraftAnswers(questions)) }
  val index: MutableIntState = remember(input?.requestId) { mutableIntStateOf(0) }
  val request: PendingRequest? = pendingRequest(requests, responding, drafts.value, index.intValue)

  // Read the state when called, not when composed: an auto-advance fires 200 ms after a pick
  fun current(): PendingRequest? =
    pendingRequest(requests, responding, drafts.value, index.intValue)

  fun question(id: String): UserInputQuestion? = questions.find { it.id == id }

  val actions = PendingActions(
    onDecide = { decision ->
      val approval: PendingRequest.Approval? = current() as? PendingRequest.Approval
      if (view != null && approval != null) {
        val id: String = UUID.randomUUID().toString()
        val answer: Command = approvalResponse(view.thread.id, approval.requestId, decision, id)
        controller.respond(view, answer)
      }
    },
    onToggleOption = { questionId, value ->
      question(questionId)?.let { question ->
        val next: PendingUserInputDraftAnswer =
          togglePendingUserInputOptionSelection(question, drafts.value[questionId], value)
        drafts.value += questionId to next
      }
    },
    onCustomAnswer = { questionId, text ->
      val question: UserInputQuestion? = question(questionId)
      val next: PendingUserInputDraftAnswer? =
        question?.let { setPendingUserInputCustomAnswer(it, drafts.value[questionId], text) }
      if (next != null) drafts.value += questionId to next
    },
    onAdvance = {
      val asked: PendingRequest.UserInput? = current() as? PendingRequest.UserInput
      when (val step: Advance? = asked?.let(::advance)) {
        is Advance.Next -> index.intValue = step.index

        is Advance.Submit -> if (view != null && asked.responding.not()) {
          val id: String = UUID.randomUUID().toString()
          val answer: Command = answersResponse(view.thread.id, asked.requestId, step.answers, id)
          controller.respond(view, answer)
        }

        Advance.Stay, null -> Unit
      }
    },
    onBack = { index.intValue = (index.intValue - 1).coerceAtLeast(0) },
    onDismiss = {
      val asked: PendingRequest.UserInput? = current() as? PendingRequest.UserInput
      if (view != null && asked != null) {
        val id: String = UUID.randomUUID().toString()
        controller.respond(view, dismissal(view.thread.id, asked.requestId, id))
      }
    },
  )
  return request to actions
}


/**
 * The footer's model, runtime mode and Plan/Build for [view]'s thread: the server's values, or
 * what was just picked until the row confirms it, or a model drafted for the next message. The
 * catalog is `server.getConfig`'s providers, fetched (and retried) for each connection.
 */
@Composable
private fun rememberComposerFooter(
  project: Project,
  view: ThreadView?,
  pending: PendingMessages,
  connection: ConnectionState,
  controller: ChatController,
  cwd: String?,
  vcs: VcsSummary?,
  setup: WorktreeSetupState,
): Pair<ComposerFooterState?, ComposerFooterActions> {
  val connected: Boolean = connection is ConnectionState.Connected
  LaunchedEffect(connected) {
    if (connected) controller.loadCatalog() else controller.dropCatalog()
  }
  val info: ServerInfo? by controller.catalog.collectAsState()
  val thread: Thread? = view?.thread
  val selection: ModelSelection? =
    thread?.let { pending.modelDraft ?: pending.settings.modelSelection(it) }
  val instanceId: String? = selection?.instance ?: thread?.providerInstanceId
  // The settings aren't observable, so the picker's favourites are mirrored here per toggle
  var favorites: Set<String> by remember { mutableStateOf(T3Settings.get().modelFavorites) }
  var refsQuery: String by remember(thread?.id) { mutableStateOf("") }
  val workspaceRoot: String? = thread?.let { row ->
    T3.get().client.projects.value.find { it.id == row.projectId }?.workspaceRoot
  }
  val workspace: WorkspaceControlState? =
    rememberWorkspaceControl(view, pending, cwd, vcs, setup, refsQuery, connection, T3.get().client)
  val footer: ComposerFooterState? = info?.let { catalog ->
    if (thread == null) return@let null
    composerFooter(
      providers = catalog.providers,
      instanceId = instanceId,
      model = selection?.slug,
      runtimeMode = pending.settings.runtimeMode(thread),
      interactionMode = pending.settings.interactionMode(thread),
      selection = selection,
      reported = view?.let(::deriveReportedModelSelection),
      favorites = favorites,
    ).copy(
      workspace = workspace,
      cwd = cwd,
      showSkillsInSlashMenu = T3Settings.get().showSkillsInSlashMenu,
      composerRichTextEnabled = T3Settings.get().composerRichTextEnabled,
    )
  }

  fun commandId(): String = UUID.randomUUID().toString()

  fun blockReason(current: ThreadView, nextInstance: String, nextModel: String): ModelChangeBlock? =
    getStartedThreadModelChangeBlockReason(
      providers = info?.providers.orEmpty(),
      hasStartedSession = current.runs.isNotEmpty(),
      supportsProviderSwitchingViaHandoff = threadSupportsProviderHandoff(current),
      currentInstance = current.thread.providerInstanceId,
      currentModel = current.thread.modelSelection.slug,
      nextInstance = nextInstance,
      nextModel = nextModel,
    )

  fun pickModel(pickedInstance: String, model: String) {
    val current: ThreadView = view ?: return
    val row: Thread = current.thread
    val block: ModelChangeBlock? = blockReason(current, pickedInstance, model)
    val next: ModelSelection = nextModelSelection(selection, pickedInstance, model)
    when {
      block != null -> controller.blocked(row.id, block.toString())

      // Mid-run, the pick rides on the next message instead
      activeRun(current.runs) != null -> controller.draftModel(row.id, next)

      else -> {
        val command: Command = updateThreadModelSelection(
          threadId = row.id,
          selection = next,
          rowInstance = row.providerInstanceId,
          serverResolved = info?.serverResolvedCommandContext ?: false,
          commandId = commandId(),
        )
        controller.dispatchForThread(current, command)
      }
    }
    // The next new thread starts on it, `setStickyModelSelection` after `onProviderModelSelect`
    if (block == null) ComposerDrafts.get().rememberModel(next)
  }

  val actions = ComposerFooterActions(
    // `useProviderWorkspaceSnapshotRefresh` on the selected provider instance and `gitCwd`
    onRefreshWorkspaceSnapshot = {
      controller.refreshWorkspaceSnapshotIfStale(footer?.provider?.instanceId, cwd)
    },
    onOpenModelPicker = onOpenModelPicker@{
      val current: ThreadView = view ?: return@onOpenModelPicker
      val state: ComposerFooterState = footer ?: return@onOpenModelPicker
      val request = ModelPickerRequest(
        entries = state.pickerEntries,
        activeInstanceId = state.provider?.instanceId,
        activeModel = state.selection?.slug,
        favorites = favorites,
        disabledReason = { pickedInstance, model ->
          modelDisabledReason(blockReason(current, pickedInstance, model))
        },
        onToggleFavorite = { pickedInstance, model ->
          val key: String = providerModelKey(pickedInstance, model)
          favorites = if (key in favorites) favorites - key else favorites + key
          T3Settings.get().modelFavorites = favorites
        },
        onPick = { pickedInstance, model -> pickModel(pickedInstance, model) },
      )
      showModelPicker(project, request)
    },
    // Upstream keeps a trait as a composer draft, so it goes with the next message
    onModelOptions = onModelOptions@{ options ->
      val current: ThreadView = view ?: return@onModelOptions
      val base: ModelSelection = selection ?: return@onModelOptions
      val next: ModelSelection = base.copy(options = optionsJson(options))
      controller.draftModel(current.thread.id, next)
      ComposerDrafts.get().rememberModelOptions(next)
    },
    onRuntimeMode = { mode ->
      view?.let { current ->
        val command: Command = setThreadRuntimeMode(current.thread.id, mode, commandId())
        controller.dispatchForThread(current, command)
      }
    },
    onToggleInteractionMode = {
      if (view != null && footer != null) {
        val next: String = toggledInteractionMode(footer.interactionMode)
        val command: Command = setThreadInteractionMode(view.thread.id, next, commandId())
        controller.dispatchForThread(view, command)
      }
    },
    // `handleInteractionModeChange`, ChatView.tsx:3732: no plan without the toggle, nothing for
    // the mode it's already in
    onInteractionMode = onInteractionMode@{ mode ->
      if (view == null || footer == null) return@onInteractionMode
      if (mode == "plan" && !footer.showInteractionModeToggle) return@onInteractionMode
      if (mode != footer.interactionMode) {
        val command: Command = setThreadInteractionMode(view.thread.id, mode, commandId())
        controller.dispatchForThread(view, command)
      }
    },
    // `onEnvModeChange`, `setPendingServerThreadBranch` and the origin toggle, ChatView.tsx:4620:
    // composer-side until the first message, which launches with them
    onEnvMode = { mode ->
      thread?.let { controller.draftWorkspace(it.id) { draft -> draft.copy(envMode = mode) } }
    },
    onBaseBranch = { branch ->
      thread?.let { controller.draftWorkspace(it.id) { draft -> draft.copy(branch = branch) } }
    },
    onStartFromOrigin = { on ->
      thread?.let { controller.draftWorkspace(it.id) { draft -> draft.copy(startFromOrigin = on) } }
    },
    onRefsQuery = { query -> refsQuery = query },
    onPickRef = { ref ->
      val current: ThreadView? = view
      val root: String? = workspaceRoot
      if (current != null && root != null) {
        val target: BranchSelectionTarget =
          resolveBranchSelectionTarget(root, current.thread.worktreePath, ref)
        controller.pickRef(current.thread.id, cwd ?: root, ref, target)
      }
    },
    onCreateRef = { name ->
      val current: ThreadView? = view
      if (current != null && cwd != null) controller.createRef(current.thread.id, cwd, name)
    },
  )
  return footer to actions
}

/**
 * The Workspace control's state for [view]'s thread: the draft over the thread's row, the
 * checkout's branch from [vcs], and the refs `vcs.listRefs` lists for what's typed, asked
 * again per query while the control is a choice. Null off a git repo.
 */
@Composable
private fun rememberWorkspaceControl(
  view: ThreadView?,
  pending: PendingMessages,
  cwd: String?,
  vcs: VcsSummary?,
  setup: WorktreeSetupState,
  query: String, // what the branch picker is searching for
  connection: ConnectionState,
  client: T3Client,
): WorkspaceControlState? {
  if (view == null) return null
  val isGitRepo: Boolean = vcs != null
  // Upstream's strip is the draft hero state's; a started thread has none
  if (!shouldShowComposerContextStrip(view, isGitRepo) || setup.preparing) return null
  val (refs: List<VcsRef>, refsStatus: String?) = rememberRefs(cwd, query, client)
  return workspaceControlState(
    view = view,
    draft = pending.workspace,
    currentGitBranch = vcs?.branch,
    isGitRepo = isGitRepo,
    preparingWorktree = setup.preparing,
    refs = refs,
    refsQuery = query,
    refsStatus = refsStatus,
    canChangeBranch =
      canChangeThreadBranch((connection as? ConnectionState.Connected)?.session),
  )
}

/**
 * The refs `vcs.listRefs` lists in [cwd] for [query], asked again per query, with
 * "Loading refs...", a "Showing N of M" when the page isn't the whole list, or the error.
 */
@Composable
internal fun rememberRefs(
  cwd: String?,
  query: String,
  client: T3Client,
): Pair<List<VcsRef>, String?> {
  var refs: List<VcsRef> by remember(cwd) { mutableStateOf(emptyList()) }
  var refsStatus: String? by remember(cwd) { mutableStateOf(null) }
  LaunchedEffect(cwd, query) {
    if (cwd == null) return@LaunchedEffect
    refsStatus = "Loading refs..."
    try {
      val input = VcsListRefsInput(cwd = cwd, query = query.ifBlank { null }, limit = REFS_PAGE)
      val result: VcsListRefsResult = client.listRefs(input)
      refs = result.refs
      refsStatus = if (result.nextCursor != null) {
        "Showing ${result.refs.size} of ${result.totalCount} refs"
      } else {
        null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LOG.info("Couldn't list refs in $cwd", e)
      refsStatus = e.message ?: "Couldn't list refs"
    }
  }
  return refs to refsStatus
}

/** `usePaginatedBranches`'s page, enough for a picker without paging. */
private const val REFS_PAGE: Long = 100

private val LOG: Logger = Logger.getInstance("io.github.xxfast.t3code.plugin.ChatContent")

/**
 * Rolls [view]'s thread back to [run]'s checkpoint once you say so, with the desktop's copy for
 * its per-checkpoint "Roll back" (`ChatView.tsx:5808`). A turn can start while the dialog is up,
 * so [latest] is asked for the view again and the link's own rule ([rollbackBlockReason]) is
 * checked once more before anything goes out.
 */
private fun confirmRollback(
  project: Project,
  controller: ChatController,
  view: ThreadView,
  run: Run,
  latest: () -> ThreadView?,
) {
  val checkpoint: Checkpoint = checkpointForRun(view, run) ?: return
  val answer: Int = Messages.showYesNoDialog(
    project,
    "Roll back this thread to the selected checkpoint?\nThis action cannot be undone.",
    "T3 Code",
    "Roll back",
    "Cancel",
    Messages.getWarningIcon(),
  )
  if (answer != Messages.YES) return

  val current: ThreadView = latest() ?: return
  val connection: ConnectionState = T3.get().client.connection.value
  val reason: String? = rollbackBlockReason(operateBlockReason(connection), current)
  if (reason != null) {
    notify(project, reason, NotificationType.WARNING)
    return
  }

  controller.rollback(current, checkpoint)
}
