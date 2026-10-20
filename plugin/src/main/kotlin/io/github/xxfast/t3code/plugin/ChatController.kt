package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ui.AttachmentClass
import io.github.xxfast.t3code.plugin.ui.COMPACT_COMMAND
import io.github.xxfast.t3code.plugin.ui.ComposerDispatchMode
import io.github.xxfast.t3code.plugin.ui.DraftAttachment
import io.github.xxfast.t3code.plugin.ui.DraftExtras
import io.github.xxfast.t3code.plugin.ui.PASTED_IMAGE_NAME
import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.plugin.ui.encodePng
import io.github.xxfast.t3code.plugin.ui.attachmentLimitError
import io.github.xxfast.t3code.plugin.ui.classifyAttachment
import io.github.xxfast.t3code.plugin.ui.messageContext
import io.github.xxfast.t3code.plugin.ui.outgoingText
import io.github.xxfast.t3code.plugin.ui.titleSeed
import io.github.xxfast.t3code.plugin.ui.toRecord
import io.github.xxfast.t3code.plugin.ui.uploadUrlInput
import io.github.xxfast.t3code.plugin.ui.uploadedAttachment
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlResult
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.MessageContext
import java.awt.Image
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import io.github.xxfast.t3code.plugin.ui.activeCapabilities
import io.github.xxfast.t3code.plugin.ui.activeRun
import io.github.xxfast.t3code.plugin.ui.messageDispatch
import io.github.xxfast.t3code.plugin.ui.optimisticMessage
import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.Command.MessageDispatch.DispatchMode
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProviderCapabilities
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
class ChatController(private val project: Project, private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  private val _outboxes: MutableStateFlow<Map<String, PendingMessages>> =
    MutableStateFlow(emptyMap())

  /**
   * Every thread's outbox, by thread id. One flow that never changes identity, so a view that
   * switches threads reads the new thread's outbox on its first frame.
   */
  val outboxes: StateFlow<Map<String, PendingMessages>> = _outboxes

  private val drafts: ConcurrentHashMap<String, TextFieldState> = ConcurrentHashMap()

  /**
   * Rolls [view]'s thread back to [checkpoint], `checkpoint.rollback` the way
   * `revertThreadCheckpoint` sends it (`commands.ts:873`): scope and checkpoint, and no
   * `restoreFiles`, like the desktop's per-checkpoint "Roll back". The thread stream shows the
   * `checkpoint.rollback-requested` that follows; a refusal is a balloon. One at a time per
   * thread, upstream's `rewindingThreadKeys` (`ChatView.tsx:5793`): a second click while the
   * first is still on its way does nothing.
   */
  fun rollback(view: ThreadView, checkpoint: Checkpoint) {
    val threadId: String = view.thread.id
    if (!rollingBack.add(threadId)) return
    val command = Command.CheckpointRollback(
      commandId = UUID.randomUUID().toString(),
      threadId = threadId,
      scopeId = checkpoint.scopeId,
      checkpointId = checkpoint.id,
    )
    scope.launchReporting(project, "Couldn't roll back") {
      try {
        client.dispatch(command)
      } finally {
        rollingBack.remove(threadId)
      }
    }
  }

  /** Threads with a `checkpoint.rollback` still being dispatched. */
  private val rollingBack: MutableSet<String> = ConcurrentHashMap.newKeySet()

  private val _catalog: MutableStateFlow<ServerInfo?> = MutableStateFlow(null)

  /** `server.getConfig` for this connection: providers and their models. Null until it loads. */
  val catalog: StateFlow<ServerInfo?> = _catalog

  /** What's typed for [threadId], kept across hosts and thread switches like upstream's drafts. */
  fun draft(threadId: String): TextFieldState =
    drafts.computeIfAbsent(threadId) { TextFieldState() }

  private val _extras: MutableStateFlow<Map<String, DraftExtras>> = MutableStateFlow(emptyMap())

  /** Every thread's draft attachments and context, by thread id. */
  val extras: StateFlow<Map<String, DraftExtras>> = _extras

  /**
   * One attachment's upload, by local id: the bytes until they're up, the job moving them, and
   * the id the server minted, so removing the chip can stop the job and release the id.
   */
  private class Transfer(@Volatile var bytes: ByteArray?) {
    @Volatile var job: Job? = null
    @Volatile var minted: String? = null
  }

  private val transfers: ConcurrentHashMap<String, Transfer> = ConcurrentHashMap()

  /** Adds [comment], an editor selection or a diff review comment, to [threadId]'s draft. */
  fun addContext(threadId: String, comment: ReviewCommentContext) {
    updateExtras(threadId) { extras -> extras.withComment(comment) }
  }

  fun removeContext(threadId: String, contextId: String) {
    updateExtras(threadId) { extras -> extras.withoutComment(contextId) }
  }

  /** Why an attach never started, shown under the chips. */
  fun refuse(threadId: String, reason: String) {
    updateExtras(threadId) { extras -> extras.refused(reason) }
  }

  /** Checks then reads [paths] off the EDT and attaches each, see [attach]. */
  fun attachFiles(threadId: String, paths: List<Path>) {
    scope.launch(Dispatchers.IO) {
      for (path in paths) attachFile(threadId, path)
    }
  }

  // Refused on its size before a byte is read, so a huge drop never lands in memory
  private fun attachFile(threadId: String, path: Path) {
    val name: String = path.fileName?.toString() ?: path.toString()
    val unreadable = "'$name' is empty or could not be read."
    if (!Files.isRegularFile(path)) return refuse(threadId, unreadable)
    val mimeType: String? = try {
      Files.probeContentType(path)
    } catch (_: Exception) {
      null
    }
    val accepted: AttachmentClass.Accepted = when (val kind = classifyAttachment(name, mimeType)) {
      is AttachmentClass.Refused -> return refuse(threadId, kind.reason)
      is AttachmentClass.Accepted -> kind
    }
    val bytes: ByteArray = try {
      val size: Long = Files.size(path)
      val existing: List<DraftAttachment> = extrasOf(threadId).attachments
      attachmentLimitError(name, accepted.kind, accepted.mimeType, size, existing)
        ?.let { reason -> return refuse(threadId, reason) }
      Files.readAllBytes(path)
    } catch (e: IOException) {
      LOG.info("Couldn't read $path", e)
      return refuse(threadId, unreadable)
    }
    attach(threadId, name, mimeType, bytes)
  }

  /**
   * Attaches [bytes] to [threadId]'s draft. The desktop app's path, not the issue's: mint with
   * `attachments.createUploadUrl`, POST the bytes, and the minted id goes straight into
   * `message.dispatch.attachments` (`apps/web/src/lib/attachmentUploadQueue.ts`).
   * `assets.persistChatAttachments` is only for data-URL images, which the plugin never makes.
   */
  fun attach(threadId: String, name: String, mimeType: String?, bytes: ByteArray) {
    val accepted: AttachmentClass.Accepted = when (val kind = classifyAttachment(name, mimeType)) {
      is AttachmentClass.Refused -> return refuse(threadId, kind.reason)
      is AttachmentClass.Accepted -> kind
    }
    val attachment = DraftAttachment(
      id = UUID.randomUUID().toString(),
      name = name,
      kind = accepted.kind,
      mimeType = accepted.mimeType,
      sizeBytes = bytes.size.toLong(),
    )
    // Before the chip shows, so a remove as fast as the eye can't miss it
    transfers[attachment.id] = Transfer(bytes)
    var started = false
    updateExtras(threadId) { extras ->
      val error: String? = attachmentLimitError(
        name = name,
        kind = attachment.kind,
        mimeType = attachment.mimeType,
        sizeBytes = attachment.sizeBytes,
        existing = extras.attachments,
      )
      started = error == null
      if (error != null) extras.refused(error) else extras.attaching(attachment)
    }
    if (!started) {
      transfers.remove(attachment.id)
      return
    }
    upload(threadId, attachment)
  }

  /** A pasted image, encoded as PNG off the EDT, see [attach]. */
  fun attachImage(threadId: String, image: Image) {
    scope.launch(Dispatchers.IO) {
      val bytes: ByteArray = try {
        encodePng(image)
      } catch (e: Exception) {
        LOG.info("Couldn't encode a pasted image", e)
        return@launch refuse(threadId, "The pasted image could not be read.")
      }
      attach(threadId, PASTED_IMAGE_NAME, "image/png", bytes)
    }
  }

  /** Upstream's manual retry: release the failed upload's id and start again. */
  fun retryAttachment(threadId: String, id: String) {
    val attachment: DraftAttachment =
      extrasOf(threadId).attachments.find { it.id == id } ?: return
    val transfer: Transfer = transfers[id] ?: return
    if (transfer.bytes == null) return
    transfer.job?.cancel()
    transfer.minted?.let(::release)
    transfer.minted = null
    updateExtras(threadId) { extras -> extras.retrying(id) }
    upload(threadId, attachment)
  }

  /** Drops the chip, stops its upload and releases what the server minted for it. */
  fun removeAttachment(threadId: String, id: String) {
    val transfer: Transfer? = transfers.remove(id)
    transfer?.job?.cancel()
    transfer?.minted?.let(::release)
    updateExtras(threadId) { extras -> extras.withoutAttachment(id) }
  }

  // `deletePendingAttachmentUpload`: best effort, failures aren't the user's problem
  private fun release(attachmentId: String) {
    scope.launch {
      try {
        client.deleteAttachment(attachmentId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't release attachment $attachmentId", e)
      }
    }
  }

  // `runAttachmentUploadCycle` in packages/client-runtime/src/state/attachments.ts: mint, then
  // transfer. A failed mint says it couldn't start; a failed transfer says why.
  private fun upload(threadId: String, attachment: DraftAttachment) {
    val transfer: Transfer = transfers[attachment.id] ?: return
    val bytes: ByteArray = transfer.bytes ?: return
    transfer.job = scope.launch {
      val minted: AttachmentCreateUploadUrlResult = try {
        client.createUploadUrl(uploadUrlInput(attachment))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't start uploading ${attachment.name}", e)
        updateExtras(threadId) { it.uploadFailed(attachment.id, "Upload could not start") }
        return@launch
      }
      transfer.minted = minted.attachmentId
      // Removed while minting: nobody will release this id but us
      if (transfers[attachment.id] !== transfer) return@launch release(minted.attachmentId)
      try {
        client.upload(minted.relativeUrl, attachment.mimeType, bytes)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't upload ${attachment.name}", e)
        val reason: String = e.message ?: "Upload failed"
        updateExtras(threadId) { it.uploadFailed(attachment.id, reason) }
        return@launch
      }
      transfer.bytes = null
      val uploaded: ChatAttachment = uploadedAttachment(attachment, minted.attachmentId)
      updateExtras(threadId) { it.uploaded(attachment.id, uploaded) }
    }
  }

  private fun extrasOf(threadId: String): DraftExtras = _extras.value[threadId] ?: DraftExtras()

  private fun updateExtras(threadId: String, change: (DraftExtras) -> DraftExtras) {
    _extras.update { all ->
      val before: DraftExtras = all[threadId] ?: DraftExtras()
      val after: DraftExtras = change(before)
      if (after == before) all else all + (threadId to after)
    }
  }

  /**
   * Retires the pending messages [view] acknowledges, or that it dropped after taking them, and
   * the answers to requests no longer in [waiting], the ids `derivePendingThreadRequests` found.
   */
  fun reconcile(view: ThreadView, waiting: Set<String>) {
    update(view.thread.id) { pending ->
      val reconciled: PendingMessages =
        pending.reconciled(view.items, view.messages, view.sequence, waiting)
      reconciled.copy(settings = reconciled.settings.reconciled(view.thread, view.sequence))
    }
  }

  /** [selection] waits for [threadId]'s next message: upstream doesn't switch a running thread. */
  fun draftModel(threadId: String, selection: ModelSelection) {
    update(threadId) { pending -> pending.draftedModel(selection) }
  }

  /** Says why a pick did nothing, under [threadId]'s composer. */
  fun blocked(threadId: String, reason: String) {
    update(threadId) { pending -> pending.copy(error = reason) }
  }

  /**
   * Fetches `server.getConfig` until it answers, backing off, for as long as the caller runs; a
   * send that fetches it refreshes it too. Call while connected, cancel when the connection goes.
   */
  suspend fun loadCatalog() {
    var wait: Duration = 1.seconds
    while (true) {
      try {
        _catalog.value = client.serverInfo()
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("No provider catalog yet, trying again in $wait", e)
        delay(wait)
        wait = minOf(wait * 2, 30.seconds)
      }
    }
  }

  /** The connection went: the next one gets its own catalog. */
  fun dropCatalog() {
    _catalog.value = null
  }

  /**
   * Dispatches a thread command for [view]'s thread, like a pick in the composer's footer. A
   * thread setting shows at once and gives way to the thread row; a refusal puts the row's value
   * back and says why under the composer, like a failed send.
   */
  fun dispatchForThread(view: ThreadView, command: OrchestrationV2Command) {
    val threadId: String = view.thread.id
    update(threadId) { pending -> pending.picked(command) }
    scope.launch {
      try {
        val sequence: Long = client.dispatch(command)
        update(threadId) { pending ->
          pending.copy(settings = pending.settings.delivered(command, sequence))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't ${command.type} on $threadId", e)
        val reason: String = e.message ?: e.javaClass.simpleName
        update(threadId) { pending ->
          val error = "Couldn't change it: $reason"
          pending.copy(settings = pending.settings.failed(command), error = error)
        }
      }
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
   * acknowledges it; a refusal takes it back out and puts the text back in the draft. With
   * [compactFirst] a `/compact` goes out first and the message queues behind it, as upstream's
   * `compactBeforeSend` (`ChatView.tsx:6370`): steering into a compaction is refused.
   */
  fun send(
    view: ThreadView,
    text: String,
    mode: ComposerDispatchMode,
    compactFirst: Boolean = false,
  ) {
    val threadId: String = view.thread.id
    val messageId: String = UUID.randomUUID().toString()
    // Attachments and context ride along; the pinned server always advertises
    // `inlineMessageContext` (apps/server/src/environment/ServerEnvironment.ts:226), so the
    // records go as `context`, never the legacy text serialisation
    val extras: DraftExtras = extrasOf(threadId)
    val outgoing: String = outgoingText(text, extras)
    val context: MessageContext? = messageContext(extras.comments.map { it.toRecord() })
    val attachments: List<ChatAttachment> = extras.ready
    val message: TurnItem.UserMessage =
      optimisticMessage(threadId, messageId, outgoing, Instant.now(), view.items)
        .copy(context = context, attachments = attachments)
    // A model picked mid-run rides on this message
    val model: ModelSelection? = _outboxes.value[threadId]?.modelDraft
    update(threadId) { pending -> pending.sent(message, model) }
    draft(threadId).clearText()
    updateExtras(threadId) { DraftExtras() }
    scope.launch {
      try {
        val info: ServerInfo = client.serverInfo()
        _catalog.value = info
        val run: Run? = activeRun(view.runs)
        val capabilities: ProviderCapabilities? =
          activeCapabilities(run, view.providerThreads, view.providerSessions)
        if (compactFirst) {
          val compact: Command.MessageDispatch = messageDispatch(
            threadId = threadId,
            commandId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            text = COMPACT_COMMAND,
            serverResolved = info.serverResolvedCommandContext,
            requested = mode,
            activeRun = run,
            capabilities = capabilities,
            modelSelection = model,
          )
          client.dispatch(compact)
        }
        val command: Command.MessageDispatch = messageDispatch(
          threadId = threadId,
          commandId = UUID.randomUUID().toString(),
          messageId = messageId,
          text = outgoing,
          serverResolved = info.serverResolvedCommandContext,
          requested = if (compactFirst) ComposerDispatchMode.QUEUE else mode,
          activeRun = run,
          capabilities = capabilities,
          modelSelection = model,
          // What the Sidebar shows until the server names the thread; the server ignores it
          // past the first message
          titleSeed = titleSeed(text, extras),
        ).let { dispatch ->
          // The compaction is the active run now, whatever this view last saw
          if (compactFirst) {
            dispatch.copy(dispatchMode = DispatchMode.QueueAfterActive())
          } else {
            dispatch
          }
        }.copy(attachments = attachments, context = context)
        val sequence: Long = client.dispatch(command)
        update(threadId) { pending -> pending.delivered(messageId, sequence) }
        // The message holds them now; they're no longer the draft's to release
        extras.attachments.forEach { transfers.remove(it.id) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't send to $threadId", e)
        val reason: String = e.message ?: e.javaClass.simpleName
        update(threadId) { pending -> pending.failed(messageId, "Couldn't send: $reason") }
        updateExtras(threadId) { current -> current.restored(extras) }
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
