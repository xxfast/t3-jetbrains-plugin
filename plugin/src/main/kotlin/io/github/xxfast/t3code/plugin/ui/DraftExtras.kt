package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ChatAttachment

/**
 * What one thread's draft carries besides its text: attachments and their uploads, review
 * comments from the editor, and why the last attach was refused. Pure, so the controller stays
 * a thin shell. Upstream keeps the same per thread in `composerDraftStore.ts`.
 */
data class DraftExtras(
  val attachments: List<DraftAttachment> = emptyList(),
  val comments: List<ReviewCommentContext> = emptyList(),
  val notice: String? = null,
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

  fun retrying(id: String): DraftExtras = withUpload(id, Upload.Uploading)

  fun withoutAttachment(id: String): DraftExtras =
    copy(attachments = attachments.filterNot { it.id == id }, notice = null)

  /** [comment] joins the draft, once per context id and up to upstream's record cap. */
  fun withComment(comment: ReviewCommentContext): DraftExtras {
    if (comments.any { it.contextId == comment.contextId }) return this
    if (comments.size >= COMPOSER_CONTEXT_MAX_RECORDS) {
      return copy(notice = "You can add up to $COMPOSER_CONTEXT_MAX_RECORDS context items.")
    }
    return copy(comments = comments + comment, notice = null)
  }

  fun withoutComment(contextId: String): DraftExtras =
    copy(comments = comments.filterNot { it.contextId == contextId })

  /** A refused send hands back what it took, ahead of anything added since. */
  fun restored(sent: DraftExtras): DraftExtras = DraftExtras(
    attachments = sent.attachments + attachments.filterNot { a ->
      sent.attachments.any { it.id == a.id }
    },
    comments = sent.comments + comments.filterNot { c ->
      sent.comments.any { it.contextId == c.contextId }
    },
    notice = notice,
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
