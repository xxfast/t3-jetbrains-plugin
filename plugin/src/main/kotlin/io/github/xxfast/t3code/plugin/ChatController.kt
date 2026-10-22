package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import io.github.xxfast.t3code.plugin.settings.ComposerDrafts
import io.github.xxfast.t3code.plugin.ui.PersistedComposerDraft
import io.github.xxfast.t3code.plugin.ui.PromptStashEntry
import io.github.xxfast.t3code.plugin.ui.STASH_EVICTED
import io.github.xxfast.t3code.plugin.ui.StashKey
import io.github.xxfast.t3code.plugin.ui.Stashed
import io.github.xxfast.t3code.plugin.ui.composerDraftKey
import io.github.xxfast.t3code.plugin.ui.hydratedExtras
import io.github.xxfast.t3code.plugin.ui.persistedDraft
import io.github.xxfast.t3code.plugin.ui.restoredExtras
import io.github.xxfast.t3code.plugin.ui.restoredPrompt
import io.github.xxfast.t3code.plugin.ui.stashEntry
import io.github.xxfast.t3code.plugin.ui.stashKey
import io.github.xxfast.t3code.plugin.ui.stashRestoreBlock
import io.github.xxfast.t3code.plugin.ui.stashed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ui.AttachmentClass
import io.github.xxfast.t3code.plugin.ui.AttachmentKind
import io.github.xxfast.t3code.plugin.ui.COMPACT_COMMAND
import io.github.xxfast.t3code.plugin.ui.ComposerDispatchMode
import io.github.xxfast.t3code.plugin.ui.DraftAttachment
import io.github.xxfast.t3code.plugin.ui.DraftExtras
import io.github.xxfast.t3code.plugin.ui.PASTED_IMAGE_NAME
import io.github.xxfast.t3code.plugin.ui.ProviderWorkspaceSnapshotRefresh
import io.github.xxfast.t3code.plugin.ui.hasCompleteProviderWorkspaceSnapshot
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.plugin.ui.BranchSelectionTarget
import io.github.xxfast.t3code.plugin.ui.WorkspaceDraft
import io.github.xxfast.t3code.plugin.ui.deriveLocalBranchNameFromRemoteRef
import io.github.xxfast.t3code.plugin.ui.threadBranchUpdateForNextTurn
import io.github.xxfast.t3code.plugin.ui.launchThreadInput
import io.github.xxfast.t3code.plugin.ui.encodePng
import io.github.xxfast.t3code.plugin.ui.attachmentLimitError
import io.github.xxfast.t3code.plugin.ui.classifyAttachment
import io.github.xxfast.t3code.plugin.ui.messageContext
import io.github.xxfast.t3code.plugin.ui.outgoingText
import io.github.xxfast.t3code.plugin.ui.ThreadContextRecord
import io.github.xxfast.t3code.plugin.ui.Upload
import io.github.xxfast.t3code.plugin.ui.titleSeed
import io.github.xxfast.t3code.plugin.ui.uploadUrlInput
import io.github.xxfast.t3code.plugin.ui.uploadedAttachment
import io.github.xxfast.t3code.plugin.actions.PASTE_AS_TEXT_ACTION_ID
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.PASTED_TEXT_MIME_TYPE
import io.github.xxfast.t3code.plugin.ui.PasteFold
import io.github.xxfast.t3code.plugin.ui.REMOTE_FOLDER_REFUSAL
import io.github.xxfast.t3code.plugin.ui.fileNameForMimeType
import io.github.xxfast.t3code.plugin.ui.foldPastedText
import io.github.xxfast.t3code.plugin.ui.folderLinkText
import io.github.xxfast.t3code.plugin.ui.formatAttachmentSize
import io.github.xxfast.t3code.plugin.ui.isHeicImageFile
import io.github.xxfast.t3code.plugin.ui.isVideoAttachment
import io.github.xxfast.t3code.plugin.ui.nextPastedTextFileName
import io.github.xxfast.t3code.plugin.ui.textForEndInsertion
import com.intellij.notification.NotificationType
import com.intellij.openapi.keymap.KeymapUtil
import java.awt.Desktop
import java.io.File
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
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchInput
import io.github.xxfast.t3code.protocol.generated.VcsCreateRefInput
import io.github.xxfast.t3code.protocol.generated.VcsCreateRefResult
import io.github.xxfast.t3code.protocol.generated.VcsRef
import io.github.xxfast.t3code.protocol.generated.VcsSwitchRefInput
import io.github.xxfast.t3code.protocol.generated.VcsSwitchRefResult
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

  /**
   * What's typed for [threadId], kept across hosts, thread switches and restarts like upstream's
   * drafts.
   */
  fun draft(threadId: String): TextFieldState =
    drafts.computeIfAbsent(threadId) { restoreDraft(threadId) }

  // `composerDraftStore`'s persist: the saved draft comes back, and every change to the text,
  // attachments or context is saved under the server the draft was opened on
  private fun restoreDraft(threadId: String): TextFieldState {
    val store: ComposerDrafts = ComposerDrafts.get()
    val key: String = composerDraftKey(ComposerDrafts.server(), threadId)
    val saved: PersistedComposerDraft? = store.draft(key)
    val state = TextFieldState(saved?.prompt.orEmpty())
    if (saved != null) updateExtras(threadId) { it.restored(hydratedExtras(saved)) }
    scope.launch {
      combine(snapshotFlow { state.text.toString() }, _extras) { text, all ->
        persistedDraft(text, all[threadId] ?: DraftExtras())
      }
        .distinctUntilChanged()
        .collect { draft -> store.put(key, draft) }
    }
    return state
  }

  /**
   * The stash shortcut on [threadId]'s composer, upstream's `stashCurrentPrompt`. True when the
   * stash menu should open instead.
   */
  fun onStashKey(threadId: String): Boolean {
    val text: String = draft(threadId).text.toString()
    val entries: List<PromptStashEntry> = ComposerDrafts.get().stash.value
    when (val key: StashKey = stashKey(text, extrasOf(threadId), entries)) {
      StashKey.Stash -> stash(threadId, text)
      is StashKey.Restore -> restoreStash(threadId, key.entry.id)
      StashKey.ToggleMenu -> return true
      is StashKey.Refused -> refuse(threadId, key.reason)
    }
    return false
  }

  private fun stash(threadId: String, text: String) {
    val store: ComposerDrafts = ComposerDrafts.get()
    val server: String = ComposerDrafts.server()
    val entry: PromptStashEntry = stashEntry(
      id = UUID.randomUUID().toString(),
      createdAt = System.currentTimeMillis(),
      server = server,
      text = text,
      extras = extrasOf(threadId),
    )
    val result: Stashed = stashed(store.stash.value, entry)
    store.putStash(result.entries)
    // The entry holds the uploads now: the chips go without releasing them
    for (attachment in entry.attachments) transfers.remove(attachment.id)
    draft(threadId).clearText()
    val notice: String? = result.evicted?.let { STASH_EVICTED }
    updateExtras(threadId) { DraftExtras(notice = notice) }
    result.evicted?.let(::releaseStashed)
  }

  /** `restoreStashEntry`: [entryId] leaves the stash and joins [threadId]'s draft. */
  fun restoreStash(threadId: String, entryId: String) {
    val store: ComposerDrafts = ComposerDrafts.get()
    val entry: PromptStashEntry = store.stash.value.find { it.id == entryId } ?: return
    stashRestoreBlock(entry, ComposerDrafts.server())?.let { return refuse(threadId, it) }
    store.putStash(store.stash.value - entry)
    val state: TextFieldState = draft(threadId)
    val current: String = state.text.toString()
    val next: String = restoredPrompt(current, entry.prompt)
    if (next != current) state.setTextAndPlaceCursorAtEnd(next)
    updateExtras(threadId) { extras -> restoredExtras(extras, entry) }
  }

  /** `deleteStashEntry`: gone, and its uploads released. */
  fun deleteStash(entryId: String) {
    val store: ComposerDrafts = ComposerDrafts.get()
    val entry: PromptStashEntry = store.stash.value.find { it.id == entryId } ?: return
    store.putStash(store.stash.value - entry)
    releaseStashed(entry)
  }

  // `releasePersistedAttachmentUpload`, only on the server that holds them
  private fun releaseStashed(entry: PromptStashEntry) {
    if (entry.server != ComposerDrafts.server()) return
    entry.attachments.mapNotNull { it.attachmentId }.forEach(::release)
  }

  private val _extras: MutableStateFlow<Map<String, DraftExtras>> = MutableStateFlow(emptyMap())

  /** Every thread's draft attachments and context, by thread id. */
  val extras: StateFlow<Map<String, DraftExtras>> = _extras

  /**
   * One attachment's upload, by local id: the bytes until they're up (an image's for as long as
   * it's in the draft), the job moving them, and
   * the id the server minted, so removing the chip can stop the job and release the id.
   * [source] is the file it came from, so a video tile can play it.
   */
  private class Transfer(@Volatile var bytes: ByteArray?, val source: Path? = null) {
    @Volatile var job: Job? = null
    @Volatile var minted: String? = null
  }

  private val transfers: ConcurrentHashMap<String, Transfer> = ConcurrentHashMap()

  /**
   * The names folded pastes took per thread, upstream's `pastedTextFileNamesRef`, so a removed
   * `pasted-text.txt` isn't handed out again.
   */
  private val pastedTextFileNames: ConcurrentHashMap<String, MutableSet<String>> =
    ConcurrentHashMap()

  /** Adds [comment], an editor selection or a diff review comment, to [threadId]'s draft. */
  fun addContext(threadId: String, comment: ReviewCommentContext) {
    draft(threadId) // the saved draft first, so this joins it rather than replacing it
    updateExtras(threadId) { extras -> extras.withComment(comment) }
  }

  /** Adds [record], a thread mentioned with `@`, to [threadId]'s draft. */
  fun addThreadContext(threadId: String, record: ThreadContextRecord) {
    updateExtras(threadId) { extras -> extras.withThread(record) }
  }

  fun removeContext(threadId: String, contextId: String) {
    updateExtras(threadId) { extras -> extras.withoutComment(contextId) }
  }

  /** Why an attach never started, shown under the chips. */
  fun refuse(threadId: String, reason: String) {
    updateExtras(threadId) { extras -> extras.refused(reason) }
  }

  /**
   * Checks then reads [paths] off the EDT and attaches each, see [attach]. Folders among them
   * become file links in the prompt instead, see [addFolders], the split upstream's
   * `splitTransferredFiles` makes for a drop or a paste.
   */
  fun attachFiles(threadId: String, paths: List<Path>) {
    scope.launch(Dispatchers.IO) {
      val (folders: List<Path>, files: List<Path>) = paths.partition { Files.isDirectory(it) }
      if (folders.isNotEmpty()) addFolders(threadId, folders)
      for (path in files) attachFile(threadId, path)
    }
  }

  /**
   * `addComposerFolders`, `ChatComposer.tsx:5801`: each folder as `serializeComposerFileLink` and
   * a space at the end of the prompt, when the server shares this machine's paths.
   */
  private suspend fun addFolders(threadId: String, folders: List<Path>) {
    if (!T3.serverIsLocal()) return refuse(threadId, REMOTE_FOLDER_REFUSAL)
    withContext(Dispatchers.EDT) {
      val draft: TextFieldState = draft(threadId)
      for (folder in folders) {
        val link: String = folderLinkText(folder.toAbsolutePath().toString())
        draft.edit {
          val text: String = textForEndInsertion(toString(), link)
          append(text)
          placeCursorAtEnd()
        }
      }
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
    // `prepareImageForAttachment`: HEIC goes out as JPEG, or not at all
    if (isHeicImageFile(name, accepted.mimeType)) {
      val jpeg: ByteArray = convertHeicToJpeg(path)
        ?: return refuse(threadId, "'$name' could not be read as an image.")
      return attach(threadId, fileNameForMimeType(name, "image/jpeg"), "image/jpeg", jpeg, path)
    }
    attach(threadId, name, mimeType, bytes, path)
  }

  /**
   * Attaches [bytes] to [threadId]'s draft. The desktop app's path, not the issue's: mint with
   * `attachments.createUploadUrl`, POST the bytes, and the minted id goes straight into
   * `message.dispatch.attachments` (`apps/web/src/lib/attachmentUploadQueue.ts`).
   * `assets.persistChatAttachments` is only for data-URL images, which the plugin never makes.
   * [source] is the file the bytes came from, when there is one.
   */
  fun attach(
    threadId: String,
    name: String,
    mimeType: String?,
    bytes: ByteArray,
    source: Path? = null,
  ) {
    val accepted: AttachmentClass.Accepted = when (val kind = classifyAttachment(name, mimeType)) {
      is AttachmentClass.Refused -> return refuse(threadId, kind.reason)
      is AttachmentClass.Accepted -> kind
    }
    // Only [attachFile] converts HEIC; one that reaches here unconverted can't be sent
    if (isHeicImageFile(name, accepted.mimeType)) {
      return refuse(threadId, "'$name' could not be read as an image.")
    }
    val attachment = DraftAttachment(
      id = UUID.randomUUID().toString(),
      name = name,
      kind = accepted.kind,
      mimeType = accepted.mimeType,
      sizeBytes = bytes.size.toLong(),
      // Upstream's `previewUrl`, an object URL over the local file (`ChatComposer.tsx:5558`)
      preview = if (accepted.kind == AttachmentKind.IMAGE) decodePreview(bytes) else null,
    )
    // Before the chip shows, so a remove as fast as the eye can't miss it
    transfers[attachment.id] = Transfer(bytes, source)
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

  /**
   * What `foldPastedText` (`ChatComposer.tsx:5659`) would do with [text] pasted over the
   * selection of [threadId]'s prompt. [bypassAutoAttachment] is the paste-as-text shortcut.
   */
  fun pasteFold(threadId: String, text: String, bypassAutoAttachment: Boolean = false): PasteFold {
    val draft: TextFieldState = draft(threadId)
    return foldPastedText(
      text = text,
      bypassAutoAttachment = bypassAutoAttachment,
      valueLength = draft.text.length,
      selectionStart = draft.selection.min,
      selectionEnd = draft.selection.max,
      attachmentCount = extrasOf(threadId).attachments.size,
    )
  }

  /**
   * `foldPastedText`'s action for a paste [pasteFold] didn't leave inline: a text file named by
   * `nextPastedTextFileName` with upstream's toast, or the too-large refusal.
   */
  fun foldPaste(threadId: String, text: String) {
    when (val fold: PasteFold = pasteFold(threadId, text)) {
      PasteFold.Inline -> return
      is PasteFold.TooLarge -> return refuse(threadId, "${fold.title}. ${fold.description}")
      PasteFold.Attach -> Unit
    }
    val reserved: MutableSet<String> =
      pastedTextFileNames.computeIfAbsent(threadId) { ConcurrentHashMap.newKeySet() }
    extrasOf(threadId).attachments.mapTo(reserved) { it.name }
    val name: String = nextPastedTextFileName(reserved)
    reserved += name
    val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
    attach(threadId, name, PASTED_TEXT_MIME_TYPE, bytes)
    if (extrasOf(threadId).attachments.none { it.name == name }) return
    val shortcut: String = KeymapUtil.getFirstKeyboardShortcutText(PASTE_AS_TEXT_ACTION_ID)
      .ifEmpty { "paste as text" }
    notify(
      project = project,
      title = "Large paste attached as $name",
      message = "${formatAttachmentSize(bytes.size.toLong())} · Use $shortcut to keep a large " +
        "paste inline.",
      type = NotificationType.INFORMATION,
    )
  }

  /** The paste-as-text shortcut's paste: [text] over the prompt's selection, never folded. */
  fun pasteInline(threadId: String, text: String) {
    draft(threadId).edit {
      val start: Int = selection.min
      replace(start, selection.max, text)
      placeCursorBeforeCharAt(start + text.length)
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

  /**
   * A draft image's full bytes in the IDE's image viewer, the composer tile's click
   * (`buildExpandedImagePreview`, `ChatComposer.tsx:6810`). A video tile's click plays it
   * (`ChatComposer.tsx:6953`); the IDE has no player, so the file opens in the system's.
   */
  fun openAttachment(threadId: String, id: String) {
    val attachment: DraftAttachment =
      extrasOf(threadId).attachments.find { it.id == id } ?: return
    if (isVideoAttachment(attachment)) {
      val source: File = transfers[id]?.source?.toFile() ?: return
      scope.launchReporting(project, "Couldn't play ${attachment.name}") {
        withContext(Dispatchers.IO) { Desktop.getDesktop().open(source) }
      }
      return
    }
    val local: ByteArray? = transfers[id]?.bytes
    val uploaded: ChatAttachment? = (attachment.upload as? Upload.Ready)?.attachment
    if (local == null && uploaded == null) return
    scope.launchReporting(project, "Couldn't open ${attachment.name}") {
      // A restored draft has no local bytes: the server still has the upload
      val bytes: ByteArray = local ?: T3.get().client.attachment(uploaded!!.id)
      openImage(project, id, attachment.name, attachment.mimeType, bytes)
    }
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
      // Only when the shown percentage moves, `formatAttachmentUploadProgress` floors it
      var shown = 0
      val total: Long = maxOf(1L, bytes.size.toLong())
      try {
        client.upload(minted.relativeUrl, attachment.mimeType, bytes) { sent ->
          val progress: Double = sent.toDouble() / total
          val percent: Int = (progress * 100).toInt()
          if (percent != shown) {
            shown = percent
            updateExtras(threadId) { it.uploadProgress(attachment.id, progress) }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't upload ${attachment.name}", e)
        val reason: String = e.message ?: "Upload failed"
        updateExtras(threadId) { it.uploadFailed(attachment.id, reason) }
        return@launch
      }
      // An image keeps its bytes for the tile's click, see [openAttachment]
      if (attachment.kind != AttachmentKind.IMAGE) transfer.bytes = null
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

  /** The Workspace control changed for [threadId]: where its first turn runs. */
  fun draftWorkspace(threadId: String, change: (WorkspaceDraft) -> WorkspaceDraft) {
    update(threadId) { pending -> pending.draftedWorkspace(change) }
  }

  /**
   * A ref picked in the composer's branch control, `selectRef` in
   * `BranchToolbarBranchSelector.tsx:384`: a ref that has a worktree only becomes the thread's
   * branch (upstream's server-thread path records the branch and keeps the checkout); any other
   * is checked out in [cwd] with `vcs.switchRef`, a remote one as its local name, and the
   * answer becomes the branch for the next turn. A refusal says so under the composer.
   */
  fun pickRef(threadId: String, cwd: String, ref: VcsRef, target: BranchSelectionTarget) {
    if (target.reuseExistingWorktree) {
      draftWorkspace(threadId) { draft -> draft.copy(branch = ref.name) }
      return
    }
    val local: String =
      if (ref.isRemote == true) deriveLocalBranchNameFromRemoteRef(ref.name) else ref.name
    draftWorkspace(threadId) { draft -> draft.copy(branch = local) }
    scope.launch {
      try {
        val result: VcsSwitchRefResult =
          client.switchRef(VcsSwitchRefInput(cwd = target.checkoutCwd, refName = ref.name))
        val name: String = result.refName ?: local
        draftWorkspace(threadId) { draft -> draft.copy(branch = name) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't switch to ${ref.name} in $cwd", e)
        update(threadId) { pending ->
          pending.copy(
            workspace = pending.workspace.copy(branch = null),
            error = "Failed to switch ref: ${e.message ?: e.javaClass.simpleName}",
          )
        }
      }
    }
  }

  /** `createRef` in `BranchToolbarBranchSelector.tsx:441`: a new branch, checked out at once. */
  fun createRef(threadId: String, cwd: String, name: String) {
    draftWorkspace(threadId) { draft -> draft.copy(branch = name) }
    scope.launch {
      try {
        val result: VcsCreateRefResult =
          client.createRef(VcsCreateRefInput(cwd = cwd, refName = name, switchRef = true))
        draftWorkspace(threadId) { draft -> draft.copy(branch = result.refName) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't create $name in $cwd", e)
        update(threadId) { pending ->
          pending.copy(
            workspace = pending.workspace.copy(branch = null),
            error = "Failed to create branch: ${e.message ?: e.javaClass.simpleName}",
          )
        }
      }
    }
  }

  /** The setup card's Cancel, `cancelWorktreeSetup` in `useWorktreeSetup.ts:156`. */
  fun cancelWorktreeSetup(threadId: String) {
    scope.launchReporting(project, "Couldn't cancel the worktree setup") {
      client.worktreeSetupCancel(threadId)
    }
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

  private val snapshotRefresh = ProviderWorkspaceSnapshotRefresh()

  /**
   * `refreshWorkspaceSnapshotIfStale`, ChatComposer.tsx:1716: asks `server.refreshProviders`
   * for [instanceId]'s workspace snapshot at [cwd] when [ProviderWorkspaceSnapshotRefresh] says
   * one is due, and takes the answer as the catalog's providers. No snapshot back, or a
   * failure, waits out the cooldown before the next try. Failures stay quiet, as upstream's
   * `reportFailure: false`.
   */
  fun refreshWorkspaceSnapshotIfStale(instanceId: String?, cwd: String?) {
    val provider: ServerProvider? =
      _catalog.value?.providers?.find { it.instanceId == instanceId }
    val request: ProviderWorkspaceSnapshotRefresh.Request =
      snapshotRefresh.begin(provider, instanceId, cwd, System.currentTimeMillis()) ?: return
    scope.launch {
      val providers: List<ServerProvider>? = try {
        client.refreshProviders(instanceId, cwd)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Couldn't refresh $instanceId's workspace snapshot at $cwd", e)
        null
      }
      if (providers != null) _catalog.update { info -> info?.copy(providers = providers) }
      val refreshed: ServerProvider? = providers?.find { it.instanceId == instanceId }
      if (!hasCompleteProviderWorkspaceSnapshot(refreshed, cwd)) {
        snapshotRefresh.retryLater(request, System.currentTimeMillis())
      }
    }
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
    worktree: WorktreeLaunch? = null,
    nextBranch: String? = null, // the branch picked for this turn, `persistThreadSettingsForNextTurn`
  ) {
    val threadId: String = view.thread.id
    val messageId: String = UUID.randomUUID().toString()
    // Attachments and context ride along; the pinned server always advertises
    // `inlineMessageContext` (apps/server/src/environment/ServerEnvironment.ts:226), so the
    // records go as `context`, never the legacy text serialisation
    val extras: DraftExtras = extrasOf(threadId)
    val outgoing: String = outgoingText(text, extras)
    val context: MessageContext? = messageContext(extras.records(outgoing))
    val attachments: List<ChatAttachment> = extras.ready
    val message: TurnItem.UserMessage =
      optimisticMessage(threadId, messageId, outgoing, Instant.now(), view.items)
        .copy(context = context, attachments = attachments)
    // A model picked mid-run rides on this message
    val model: ModelSelection? = _outboxes.value[threadId]?.modelDraft
    update(threadId) { pending -> pending.sent(message, model).copy(launching = worktree != null) }
    draft(threadId).clearText()
    updateExtras(threadId) { DraftExtras() }
    scope.launch {
      try {
        val info: ServerInfo = client.serverInfo()
        _catalog.value = info
        if (worktree != null) {
          launch(view, worktree, messageId, outgoing, context, attachments, model, text, extras)
          return@launch
        }
        // `resolveThreadMetadataUpdateForNextTurn`: the picked branch lands on the row first
        threadBranchUpdateForNextTurn(view.thread, nextBranch, UUID.randomUUID().toString())
          ?.let { command ->
            client.dispatch(command)
            update(threadId) { pending -> pending.copy(workspace = WorkspaceDraft()) }
          }
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
        update(threadId) { pending ->
          pending.failed(messageId, "Couldn't send: $reason").copy(launching = false)
        }
        updateExtras(threadId) { current -> current.restored(extras) }
        withContext(Dispatchers.EDT) {
          val draft: TextFieldState = draft(threadId)
          if (draft.text.isEmpty()) draft.setTextAndPlaceCursorAtEnd(text)
        }
      }
    }
  }

  /**
   * The first message of a thread that wants a new worktree: `orchestration.launchThread`
   * with `reuseExistingThread`, the way upstream's `startThreadTurn` goes when the send
   * carries `bootstrap.prepareWorktree` (`commands.ts:634`). The worktree's progress streams
   * on `subscribeWorktreeSetup`; the hosts show it. The answer carries no command sequence,
   * so the optimistic message retires only once the thread stream acknowledges it.
   */
  private suspend fun launch(
    view: ThreadView,
    worktree: WorktreeLaunch,
    messageId: String,
    outgoing: String,
    context: MessageContext?,
    attachments: List<ChatAttachment>,
    model: ModelSelection?,
    text: String,
    extras: DraftExtras,
  ) {
    val threadId: String = view.thread.id
    val input: ThreadLaunchInput = launchThreadInput(
      view = view,
      commandId = UUID.randomUUID().toString(),
      messageId = messageId,
      text = outgoing,
      context = context,
      attachments = attachments,
      modelSelection = model,
      runtimeMode = worktree.runtimeMode,
      interactionMode = worktree.interactionMode,
      baseBranch = worktree.baseBranch,
      startFromOrigin = worktree.startFromOrigin,
      titleSeed = titleSeed(text, extras),
    )
    try {
      client.launchThread(input)
    } finally {
      update(threadId) { pending -> pending.copy(launching = false) }
    }
    update(threadId) { pending ->
      pending.delivered(messageId, Long.MAX_VALUE).copy(workspace = WorkspaceDraft())
    }
    extras.attachments.forEach { transfers.remove(it.id) }
  }

  /**
   * What a first send in worktree mode prepares: `prepareWorktree` plus the thread settings
   * `launchThread` takes along (`ChatView.tsx:6942`).
   */
  class WorktreeLaunch(
    val baseBranch: String,
    val startFromOrigin: Boolean,
    val runtimeMode: String,
    val interactionMode: String,
  )

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
