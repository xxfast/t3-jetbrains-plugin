package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.interruptCommand
import io.github.xxfast.t3code.plugin.actions.threadActions
import io.github.xxfast.t3code.plugin.ide.ProjectAwareness
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.ide.VcsStatuses
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ide.threadCwd
import io.github.xxfast.t3code.plugin.review.checkpointForRun
import io.github.xxfast.t3code.plugin.review.rollbackBlockReason
import io.github.xxfast.t3code.plugin.ui.Advance
import io.github.xxfast.t3code.plugin.ui.COMPACT_COMMAND
import io.github.xxfast.t3code.plugin.ui.ChatView
import io.github.xxfast.t3code.plugin.ui.ComposerContextWindow
import io.github.xxfast.t3code.plugin.ui.ComposerDispatchMode
import io.github.xxfast.t3code.plugin.ui.ComposerFooterActions
import io.github.xxfast.t3code.plugin.ui.ComposerFooterState
import io.github.xxfast.t3code.plugin.ui.ComposerState
import io.github.xxfast.t3code.plugin.ui.ContextWindowSnapshot
import io.github.xxfast.t3code.plugin.ui.DraftExtras
import io.github.xxfast.t3code.plugin.ui.Hint
import io.github.xxfast.t3code.plugin.ui.ModelChangeBlock
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
import java.time.Instant
import java.util.UUID
import javax.swing.JComponent
import org.jetbrains.jewel.bridge.JewelComposePanel

/** "T3 Chat View": whatever thread is selected in T3 Sidebar, for those who'd rather dock it. */
class ChatViewToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val client: T3Client = T3.get().client
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    val panel: JComponent = JewelComposePanel {
      when (val id: String? = selection.selected.collectAsState().value) {
        null -> Hint("Pick a thread in T3 Sidebar")
        else -> {
          val attempt: MutableIntState = remember(id) { mutableIntStateOf(0) }
          // Keyed, so a new thread or a retry starts from a fresh state instead of the last value
          val load: ThreadLoad = key(id, attempt.intValue) {
            remember { client.threadLoad(id) }.collectAsState(initial = ThreadLoad()).value
          }
          ChatContent(project, id, load, onRetry = { attempt.intValue++ })
        }
      }
    }
    val content: Content = ContentFactory.getInstance().createContent(panel, null, false)
    toolWindow.contentManager.addContent(content)
    // Thread actions act on the selection, which is what this window shows
    toolWindow.setTitleActions(listOf(threadActions()))
    ThreadVisits.getInstance(project).follow(toolWindow)
    ProjectAwareness.getInstance(project) // selects this window's worktree thread, if it has one
  }
}

/** [ChatView] wired to the IDE, shared by the editor tab and the tool window. */
@Composable
fun ChatContent(project: Project, threadId: String, load: ThreadLoad, onRetry: () -> Unit) {
  val opener: T3Opener = T3Opener.getInstance(project)
  val controller: ChatController = ChatController.getInstance(project)
  val client: T3Client = T3.get().client
  val projects: List<T3Project> by client.projects.collectAsState()
  val favicons: Map<String, ImageBitmap> by ProjectIcons.get().favicons.collectAsState()
  val connection: ConnectionState by client.connection.collectAsState()
  val outboxes: Map<String, PendingMessages> by controller.outboxes.collectAsState()
  val extras: Map<String, DraftExtras> by controller.extras.collectAsState()
  val comments: List<ReviewCommentContext> = extras[threadId]?.comments.orEmpty()
  val pending: PendingMessages = outboxes[threadId] ?: PendingMessages()
  val draft: TextFieldState = remember(threadId) { controller.draft(threadId) }
  LaunchedEffect(projects) { ProjectIcons.get().ensure(projects) }

  val view: ThreadView? = load.view
  // For a check after a modal dialog, which outlives this composition's view
  val latest: ThreadView? by rememberUpdatedState(view)
  // Again when a dispatch answers: the view may already be past its sequence, or resolved
  val requests: PendingThreadRequests = remember(view?.runtimeRequests, view?.items) {
    derivePendingThreadRequests(view?.runtimeRequests.orEmpty(), view?.items.orEmpty())
  }
  LaunchedEffect(view, requests, pending.delivered, pending.responding, pending.settings) {
    view?.let { controller.reconcile(it, requests.requestIds) }
  }
  val shown: ThreadView? = remember(view, pending.messages) {
    view?.let { current ->
      current.copy(items = mergeOptimistic(current.items, current.messages, pending.messages))
    }
  }
  val composer: ComposerState = composerState(
    connection = connection,
    running = view?.let { activeRun(it.runs) } != null,
    sending = pending.sending,
    error = pending.error,
    // Upstream's `followUpBehavior` default; Ctrl/Cmd sends the other
    queue = false,
    reviewComments = comments.size,
  )
  val (request: PendingRequest?, actions: PendingActions) =
    rememberPendingRequest(view, requests, pending.responding, controller)
  val (footer: ComposerFooterState?, footerActions: ComposerFooterActions) =
    rememberComposerFooter(view, pending, connection, controller)
  val contextWindow: ComposerContextWindow =
    rememberContextWindow(view, footer, composer, connection, controller)
  val projectId: String? = view?.thread?.projectId
  // One subscription per checkout, shared by every tab showing it
  val cwd: String? = threadCwd(view?.thread, projects)
  val vcs: VcsSummary? by remember(cwd) { VcsStatuses.getInstance(project).summary(cwd) }
    .collectAsState()
  ChatView(
    view = shown,
    error = load.error,
    project = projects.find { it.id == projectId },
    favicon = favicons[projectId],
    composer = composer,
    draft = draft,
    pending = request,
    pendingActions = actions,
    footer = footer,
    footerActions = footerActions,
    onClearReview = { comments.forEach { controller.removeContext(threadId, it.contextId) } },
    onRollback = { run ->
      view?.let { current -> confirmRollback(project, controller, current, run) { latest } }
    },
    onSend = { text, mode ->
      // `compactBeforeSend`, ChatView.tsx:6370: a `/compact` of its own doesn't compact twice
      val compactFirst: Boolean = contextWindow.compactBeforeSendTokens != null &&
        !contextWindow.keepFullHistory &&
        text.lowercase() != COMPACT_COMMAND
      view?.let { controller.send(it, text, mode, compactFirst) }
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
    extras = extras[threadId] ?: DraftExtras(),
    attachmentActions = rememberAttachmentActions(project, threadId, controller),
    contextWindow = contextWindow,
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
  view: ThreadView?,
  pending: PendingMessages,
  connection: ConnectionState,
  controller: ChatController,
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
    )
  }

  fun commandId(): String = UUID.randomUUID().toString()

  val actions = ComposerFooterActions(
    onModel = onModel@{ pickedInstance, model ->
      val current: ThreadView = view ?: return@onModel
      val row: Thread = current.thread
      val block: ModelChangeBlock? = getStartedThreadModelChangeBlockReason(
        providers = info?.providers.orEmpty(),
        hasStartedSession = current.runs.isNotEmpty(),
        supportsProviderSwitchingViaHandoff = threadSupportsProviderHandoff(current),
        currentInstance = row.providerInstanceId,
        currentModel = row.modelSelection.slug,
        nextInstance = pickedInstance,
        nextModel = model,
      )
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
    },
    // Upstream keeps a trait as a composer draft, so it goes with the next message
    onModelOptions = onModelOptions@{ options ->
      val current: ThreadView = view ?: return@onModelOptions
      val base: ModelSelection = selection ?: return@onModelOptions
      controller.draftModel(current.thread.id, base.copy(options = optionsJson(options)))
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
  )
  return footer to actions
}

private val LOG: Logger = Logger.getInstance(ChatViewToolWindowFactory::class.java)

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
