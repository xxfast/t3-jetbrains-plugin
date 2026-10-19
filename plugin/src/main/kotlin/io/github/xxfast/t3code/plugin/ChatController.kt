package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ui.ComposerDispatchMode
import io.github.xxfast.t3code.plugin.ui.activeCapabilities
import io.github.xxfast.t3code.plugin.ui.activeRun
import io.github.xxfast.t3code.plugin.ui.messageDispatch
import io.github.xxfast.t3code.plugin.ui.optimisticMessage
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends messages and answers for this project's chat views. Owns each thread's draft and its
 * outbox of optimistic messages and answers in flight; the views render both, nothing in `ui`
 * sees the client.
 */
@Service(Service.Level.PROJECT)
class ChatController(private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  private val _outboxes: MutableStateFlow<Map<String, PendingMessages>> =
    MutableStateFlow(emptyMap())

  /**
   * Every thread's outbox, by thread id. One flow that never changes identity, so a view that
   * switches threads reads the new thread's outbox on its first frame.
   */
  val outboxes: StateFlow<Map<String, PendingMessages>> = _outboxes

  private val drafts: ConcurrentHashMap<String, TextFieldState> = ConcurrentHashMap()

  /** What's typed for [threadId], kept across hosts and thread switches like upstream's drafts. */
  fun draft(threadId: String): TextFieldState =
    drafts.computeIfAbsent(threadId) { TextFieldState() }

  /**
   * Retires the pending messages [view] acknowledges, or that it dropped after taking them, and
   * the answers to requests no longer in [waiting], the ids `derivePendingThreadRequests` found.
   */
  fun reconcile(view: ThreadView, waiting: Set<String>) {
    update(view.thread.id) { pending ->
      pending.reconciled(view.items, view.messages, view.sequence, waiting)
    }
  }

  /**
   * Answers an approval or a question on [view]'s thread: `runtime-request.respond` or
   * `thread.user-input.dismiss`. The panel shows it responding until the server resolves the
   * request; a refusal says why under the composer, like a failed send.
   */
  fun respond(view: ThreadView, command: OrchestrationV2Command) {
    val threadId: String = view.thread.id
    val requestId: String = when (command) {
      is Command.RuntimeRequestRespond -> command.requestId
      is Command.ThreadUserInputDismiss -> command.requestId
      else -> throw IllegalArgumentException("Not a response: ${command.type}")
    }
    update(threadId) { pending -> pending.respondingTo(requestId) }
    scope.launch {
      try {
        client.dispatch(command)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't respond to $requestId on $threadId", e)
        val reason: String = e.message ?: e.javaClass.simpleName
        update(threadId) { pending ->
          pending.respondFailed(requestId, "Couldn't respond: $reason")
        }
      }
    }
  }

  /**
   * Sends [text] as a follow-up on [view]'s thread. It shows at once until the server
   * acknowledges it; a refusal takes it back out and puts the text back in the draft.
   */
  fun send(view: ThreadView, text: String, mode: ComposerDispatchMode) {
    val threadId: String = view.thread.id
    val messageId: String = UUID.randomUUID().toString()
    val message: TurnItem.UserMessage =
      optimisticMessage(threadId, messageId, text, Instant.now(), view.items)
    update(threadId) { pending -> pending.sent(message) }
    draft(threadId).clearText()
    scope.launch {
      try {
        val info: ServerInfo = client.serverInfo()
        val run: Run? = activeRun(view.runs)
        val command: Command.MessageDispatch = messageDispatch(
          threadId = threadId,
          commandId = UUID.randomUUID().toString(),
          messageId = messageId,
          text = text,
          serverResolved = info.serverResolvedCommandContext,
          requested = mode,
          activeRun = run,
          capabilities = activeCapabilities(run, view.providerThreads, view.providerSessions),
        )
        val sequence: Long = client.dispatch(command)
        update(threadId) { pending -> pending.delivered(messageId, sequence) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't send to $threadId", e)
        val reason: String = e.message ?: e.javaClass.simpleName
        update(threadId) { pending -> pending.failed(messageId, "Couldn't send: $reason") }
        withContext(Dispatchers.EDT) {
          val draft: TextFieldState = draft(threadId)
          if (draft.text.isEmpty()) draft.setTextAndPlaceCursorAtEnd(text)
        }
      }
    }
  }

  private fun update(threadId: String, change: (PendingMessages) -> PendingMessages) {
    _outboxes.update { all ->
      val before: PendingMessages = all[threadId] ?: PendingMessages()
      val after: PendingMessages = change(before)
      if (after == before) all else all + (threadId to after)
    }
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(ChatController::class.java)

    fun getInstance(project: Project): ChatController = project.service()
  }
}
