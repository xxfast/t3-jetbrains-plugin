package io.github.xxfast.t3code.plugin.ui

// What a paste into the composer becomes. Ported from upstream's
// packages/client-runtime/src/textPaste.ts and the decisions of `foldPastedText` and
// `addComposerFolders` in apps/web/src/components/chat/ChatComposer.tsx:5659 and :5801.

/** `PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES`. */
const val PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES: Int = 32 * 1024

/** `PROVIDER_SEND_TURN_MAX_INPUT_CHARS`, packages/contracts/src/chatAttachment.ts:13. */
const val MAX_INPUT_CHARS: Int = 120_000

/** The type a folded paste is attached as, `ChatComposer.tsx:5715`. */
const val PASTED_TEXT_MIME_TYPE: String = "text/plain;charset=utf-8"

enum class PastedTextDisposition { ATTACHMENT, INLINE }

/**
 * `pastedTextDisposition`. The threshold is byte-based: character counts understate the context
 * cost of some Unicode-heavy clipboard contents.
 */
fun pastedTextDisposition(
  text: String,
  canAttach: Boolean,
  bypassAutoAttachment: Boolean = false,
  wouldExceedInputLimit: Boolean = false,
): PastedTextDisposition {
  if (bypassAutoAttachment || !canAttach || text.isEmpty()) return PastedTextDisposition.INLINE
  val large: Boolean = wouldExceedInputLimit ||
    text.length >= PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES ||
    text.toByteArray(Charsets.UTF_8).size >= PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES
  return if (large) PastedTextDisposition.ATTACHMENT else PastedTextDisposition.INLINE
}

/** `nextPastedTextFileName`: `pasted-text.txt`, then `pasted-text-2.txt` and on. */
fun nextPastedTextFileName(existingNames: Collection<String>): String {
  val names: Set<String> = existingNames.mapTo(HashSet()) { it.lowercase() }
  if ("pasted-text.txt" !in names) return "pasted-text.txt"
  return generateSequence(2) { it + 1 }
    .map { sequence -> "pasted-text-$sequence.txt" }
    .first { candidate -> candidate !in names }
}

/** `wouldTextPasteExceedLimit`: whether replacing the selection with the paste overflows. */
fun wouldTextPasteExceedLimit(
  valueLength: Int,
  selectionStart: Int,
  selectionEnd: Int,
  textLength: Int,
  maxLength: Int = MAX_INPUT_CHARS,
): Boolean {
  val start: Int = selectionStart.coerceIn(0, valueLength)
  val end: Int = selectionEnd.coerceIn(start, valueLength)
  return valueLength - (end - start) + textLength > maxLength
}

/** What `foldPastedText` does with a text paste. */
sealed interface PasteFold {
  /** Not folded: the text field pastes it as usual. */
  data object Inline : PasteFold

  /** Folded into a text file attachment. */
  data object Attach : PasteFold

  /** Too large to paste and to attach: the paste is dropped with this toast. */
  data class TooLarge(val title: String, val description: String) : PasteFold
}

/**
 * `foldPastedText`'s decision for [text] pasted over [selectionStart]..[selectionEnd] of a
 * prompt [valueLength] long, with [attachmentCount] already in the draft. The plugin's composer
 * always has a thread and never reverts checkpoints, so staging only waits on a free slot, and
 * the staging limit is `PROVIDER_SEND_TURN_MAX_FILE_BYTES`.
 */
fun foldPastedText(
  text: String,
  bypassAutoAttachment: Boolean,
  valueLength: Int,
  selectionStart: Int,
  selectionEnd: Int,
  attachmentCount: Int,
): PasteFold {
  val wouldExceedInputLimit: Boolean =
    wouldTextPasteExceedLimit(valueLength, selectionStart, selectionEnd, text.length)
  val disposition: PastedTextDisposition = pastedTextDisposition(
    text = text,
    canAttach = true,
    bypassAutoAttachment = bypassAutoAttachment,
    wouldExceedInputLimit = wouldExceedInputLimit,
  )
  if (disposition != PastedTextDisposition.ATTACHMENT) return PasteFold.Inline
  if (attachmentCount >= MAX_ATTACHMENTS) {
    if (!wouldExceedInputLimit) return PasteFold.Inline
    return PasteFold.TooLarge(
      title = "Pasted text is too large for this message",
      description = "Remove some text or an attachment, then paste again.",
    )
  }
  if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
    if (!wouldExceedInputLimit) return PasteFold.Inline
    return PasteFold.TooLarge(
      title = "Pasted text is too large to attach",
      description = "Reduce the clipboard contents or save a smaller excerpt as a file.",
    )
  }
  return PasteFold.Attach
}

/**
 * `insertComposerText(text, "end", { ensureLeadingBoundary: true })` as `addComposerFolders`
 * calls it: what goes after [prompt], a space first when the prompt doesn't already end in one.
 */
fun textForEndInsertion(prompt: String, text: String): String {
  val needsLeadingSpace: Boolean = prompt.isNotEmpty() && !prompt.last().isWhitespace()
  return if (needsLeadingSpace) " $text" else text
}

/** What `addComposerFolders` inserts for one folder: its file link and a space. */
fun folderLinkText(path: String): String = "${serializeComposerFileLink(path)} "

/** `addComposerFolders`' toast when the server isn't on this machine. */
const val REMOTE_FOLDER_REFUSAL: String =
  "Folders can only be referenced in the local desktop environment"
