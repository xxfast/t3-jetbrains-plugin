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
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.github.xxfast.t3code.plugin.actions.threadActions
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.ThreadVisits
import io.github.xxfast.t3code.plugin.ui.Advance
import io.github.xxfast.t3code.plugin.ui.ChatView
import io.github.xxfast.t3code.plugin.ui.ComposerState
import io.github.xxfast.t3code.plugin.ui.PendingUserInputDraftAnswer
import io.github.xxfast.t3code.plugin.ui.Hint
import io.github.xxfast.t3code.plugin.ui.PendingActions
import io.github.xxfast.t3code.plugin.ui.PendingRequest
import io.github.xxfast.t3code.plugin.ui.PendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.activeRun
import io.github.xxfast.t3code.plugin.ui.advance
import io.github.xxfast.t3code.plugin.ui.answersResponse
import io.github.xxfast.t3code.plugin.ui.approvalResponse
import io.github.xxfast.t3code.plugin.ui.composerState
import io.github.xxfast.t3code.plugin.ui.setPendingUserInputCustomAnswer
import io.github.xxfast.t3code.plugin.ui.derivePendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.dismissal
import io.github.xxfast.t3code.plugin.ui.mergeOptimistic
import io.github.xxfast.t3code.plugin.ui.pendingRequest
import io.github.xxfast.t3code.plugin.ui.seedUserInputDraftAnswers
import io.github.xxfast.t3code.plugin.ui.togglePendingUserInputOptionSelection
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
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
  val pending: PendingMessages = outboxes[threadId] ?: PendingMessages()
  var queue: Boolean by remember(threadId) { mutableStateOf(false) }
  val draft: TextFieldState = remember(threadId) { controller.draft(threadId) }
  LaunchedEffect(projects) { ProjectIcons.get().ensure(projects) }

  val view: ThreadView? = load.view
  // Again when a dispatch answers: the view may already be past its sequence, or resolved
  val requests: PendingThreadRequests = remember(view?.runtimeRequests, view?.items) {
    derivePendingThreadRequests(view?.runtimeRequests.orEmpty(), view?.items.orEmpty())
  }
  LaunchedEffect(view, requests, pending.delivered, pending.responding) {
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
    queue = queue,
  )
  val (request: PendingRequest?, actions: PendingActions) =
    rememberPendingRequest(view, requests, pending.responding, controller)
  val projectId: String? = view?.thread?.projectId
  ChatView(
    view = shown,
    error = load.error,
    project = projects.find { it.id == projectId },
    favicon = favicons[projectId],
    composer = composer,
    draft = draft,
    pending = request,
    pendingActions = actions,
    onSend = { text, mode -> view?.let { controller.send(it, text, mode) } },
    onQueueChange = { queue = it },
    onRetry = onRetry,
    onOpenFile = { path -> view?.let { opener.openFile(it, path) } },
    onDiffFile = { change -> view?.let { opener.showDiff(it, change) } },
    onDiffRun = { run -> view?.let { opener.showRunDiff(it, run) } },
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
