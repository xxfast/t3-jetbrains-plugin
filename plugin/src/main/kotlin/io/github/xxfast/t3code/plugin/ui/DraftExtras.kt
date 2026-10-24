package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import kotlinx.serialization.json.JsonObject

/**
 * What one thread's draft carries besides its text: attachments and their uploads, review
 * comments from the editor, and why the last attach was refused. Pure, so the controller stays
 * a thin shell. Upstream keeps the same per thread in `composerDraftStore.ts`.
 */
data class DraftExtras(
  val attachments: List<DraftAttachment> = emptyList(),
  val comments: List<ReviewCommentContext> = emptyList(),
  val notice: String? = null,
  /** Threads mentioned with `@`, upstream's `threadContexts`. */
  val threads: List<ThreadContextRecord> = emptyList(),
  /**
   * The editor's selection, shown as a chip until the send makes it a comment. Only the view's
   * copy has it; the controller's drafts never do, so it's never saved or restored.
   */
  val selection: ReviewCommentContext? = null,
) {
  /** The attachments that go out with the message, as the server knows them. */
  val ready: List<ChatAttachment>
    get() = attachments.mapNotNull { (it.upload as? Upload.Ready)?.attachment }

  /** Why Send waits on the attachments, or null. */
  val blockReason: String? get() = attachmentBlockReason(attachments)

  /** Something to send even with no text: attachments, never comments alone, see [sendable]. */
  val hasContent: Boolean get() = attachments.isNotEmpty()

  /** [attachment] starts uploading. A new attach clears the last refusal. */
  fun attaching(attachment: DraftAttachment): DraftExtras =
    copy(attachments = attachments + attachment, notice = null)

  /** An attach that never started, and why. */
  fun refused(reason: String): DraftExtras = copy(notice = reason)

  fun uploaded(id: String, attachment: ChatAttachment): DraftExtras =
    withUpload(id, Upload.Ready(attachment))

  fun uploadFailed(id: String, reason: String): DraftExtras = withUpload(id, Upload.Failed(reason))

  fun retrying(id: String): DraftExtras = withUpload(id, Upload.Uploading())

  /** How far [id]'s upload is, while it's still uploading. */
  fun uploadProgress(id: String, progress: Double): DraftExtras = copy(
    attachments = attachments.map { attachment ->
      if (attachment.id != id || attachment.upload !is Upload.Uploading) return@map attachment
      attachment.copy(upload = Upload.Uploading(progress))
    },
  )

  fun withoutAttachment(id: String): DraftExtras =
    copy(attachments = attachments.filterNot { it.id == id }, notice = null)

  /** [comment] joins the draft, once per context id and up to upstream's record cap. */
  fun withComment(comment: ReviewCommentContext): DraftExtras {
    // By id only, like addComposerDraftReviewComment (composerDraftStore.ts:3765): upstream keeps
    // several notes on the same lines
    if (comments.any { it.contextId == comment.contextId }) return this
    if (comments.size >= COMPOSER_CONTEXT_MAX_RECORDS) {
      return copy(notice = "You can add up to $COMPOSER_CONTEXT_MAX_RECORDS context items.")
    }
    return copy(comments = comments + comment, notice = null)
  }

  fun withoutComment(contextId: String): DraftExtras =
    copy(comments = comments.filterNot { it.contextId == contextId })

  /** [record] joins the draft, once per context id, `addComposerDraftThreadContexts`. */
  fun withThread(record: ThreadContextRecord): DraftExtras {
    if (threads.any { it.contextId == record.contextId }) return this
    return copy(threads = threads + record)
  }

  /**
   * The records that go with [text], upstream's `buildMessageContext` order: review comments,
   * then threads. A thread only while [text] still cites it; deleting its link is the plugin's
   * way of removing upstream's inline chip.
   */
  fun records(text: String): List<JsonObject> {
    val cited: Set<String> = inlineContextIds(text).toSet()
    return comments.map { it.toRecord() } +
      threads.filter { it.contextId in cited }.map { it.toRecord() }
  }

  /** A refused send hands back what it took, ahead of anything added since. */
  fun restored(sent: DraftExtras): DraftExtras = DraftExtras(
    attachments = sent.attachments + attachments.filterNot { a ->
      sent.attachments.any { it.id == a.id }
    },
    comments = sent.comments + comments.filterNot { c ->
      sent.comments.any { it.contextId == c.contextId }
    },
    notice = notice,
    threads = sent.threads + threads.filterNot { t ->
      sent.threads.any { it.contextId == t.contextId }
    },
  )

  private fun withUpload(id: String, upload: Upload): DraftExtras =
    copy(attachments = attachments.map { if (it.id == id) it.copy(upload = upload) else it })
}

/**
 * The text a send carries: [text] citing every comment, trimmed like `promptForSend.trim()` in
 * ChatView.tsx, or `ATTACHMENT_ONLY_BOOTSTRAP_PROMPT` when only attachments are left.
 */
fun outgoingText(text: String, extras: DraftExtras): String {
  val cited: String = withContextReferences(text, extras.comments.map(::reviewCommentReference))
    .trim()
  if (cited.isEmpty() && extras.attachments.isNotEmpty()) return ATTACHMENT_ONLY_BOOTSTRAP_PROMPT
  return cited
}

/** `truncate`, packages/shared/src/String.ts: [text] trimmed, cut to [maxLength] plus "...". */
fun truncate(text: String, maxLength: Int = 50): String {
  val trimmed: String = text.trim()
  return if (trimmed.length <= maxLength) trimmed else "${trimmed.take(maxLength)}..."
}

/**
 * The title a thread shows until the server has generated one, as `ChatView.tsx:6854` seeds it:
 * the prompt without its context links, else the first image, file or review comment, else
 * `NEW_THREAD_TITLE`. Sent as `titleSeed` on every dispatch; the server only acts on it for a
 * thread's first message (`Orchestrator.ts:4660`), where it also queues title generation.
 * Without it the thread stays "New thread" for good. Assistant citations aren't flattened here
 * because the plugin's composer can't insert them.
 */
fun titleSeed(text: String, extras: DraftExtras): String {
  val fromText: String = stripInlineContextReferences(text).trim()
  val seed: String = when {
    fromText.isNotEmpty() -> fromText
    else -> {
      val image: DraftAttachment? =
        extras.attachments.firstOrNull { it.kind == AttachmentKind.IMAGE }
      val file: DraftAttachment? =
        extras.attachments.firstOrNull { it.kind != AttachmentKind.IMAGE }
      val comment: ReviewCommentContext? = extras.comments.firstOrNull()
      when {
        image != null -> "Image: ${image.name}"
        file != null -> "File: ${file.name}"
        comment != null -> "Review: ${reviewCommentContextLabel(comment)}"
        else -> NEW_THREAD_TITLE
      }
    }
  }
  return truncate(seed)
}
