package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

// What survives an IDE restart: each thread's draft and the prompt stash. Ported from upstream's
// apps/web/src/composerDraftStore.ts (`partializeComposerDraftStoreState`) and
// apps/web/src/promptStashStore.ts, with the stash rules from ChatComposer.tsx
// (`stashCurrentPrompt`, `restoreStashEntry`) and ComposerStashMenu.tsx.

/** `COMPOSER_DRAFT_STORAGE_VERSION`'s counterpart: bump when a persisted shape changes. */
const val COMPOSER_DRAFT_STORAGE_VERSION: Int = 1

/** `MAX_STASH_ENTRIES`. */
const val MAX_STASH_ENTRIES: Int = 20

/** `SNIPPET_MAX_CHARS`, ComposerStashMenu.tsx. */
const val STASH_SNIPPET_MAX_CHARS: Int = 90

/** The chip's note for an attachment that wasn't up when the IDE closed, upstream's label. */
const val ATTACH_AGAIN: String = "Attach again"

const val STASH_WAIT_FOR_UPLOADS: String = "Wait for file uploads before stashing this prompt"
const val STASH_REATTACH_FIRST: String =
  "Attach dropped files again or remove them before stashing"
const val STASH_OTHER_ENVIRONMENT: String =
  "Stashed files belong to another environment. Restore this prompt on the server that " +
    "received its files."
const val STASH_EVICTED: String =
  "The stash holds $MAX_STASH_ENTRIES prompts; the oldest was removed to make room."

/**
 * `PersistedComposerDraftFileAttachment`: an attachment by reference. [attachmentId] is the
 * server's id, present only once the upload was ready; without it the chip comes back as a
 * needs-reattach row. Bytes never persist: upstream drops a file's `File` handle the same way.
 */
@Serializable
data class PersistedComposerAttachment(
  val id: String,
  val name: String,
  val kind: String, // `AttachmentKind.wire`
  val mimeType: String,
  val sizeBytes: Long,
  val attachmentId: String? = null,
)

/**
 * `PersistedComposerThreadDraftState`, the parts the plugin's composer has: the prompt, the
 * attachments, and the review comments (upstream's `reviewComments`).
 */
@Serializable
data class PersistedComposerDraft(
  val prompt: String,
  val attachments: List<PersistedComposerAttachment> = emptyList(),
  val reviewComments: List<ReviewCommentContext> = emptyList(),
)

/**
 * `PromptStashEntry`. [server] stands in for each file's `environmentId`: the plugin talks to one
 * server at a time, so the entry records which one received its uploads.
 */
@Serializable
data class PromptStashEntry(
  val id: String,
  val createdAt: Long, // epoch millis
  val server: String,
  val prompt: String,
  val attachments: List<PersistedComposerAttachment> = emptyList(),
  val reviewComments: List<ReviewCommentContext> = emptyList(),
)

/** Upstream's scoped thread key: the server and the thread, so another server never sees it. */
fun composerDraftKey(server: String, threadId: String): String = "$server|$threadId"

private fun DraftAttachment.persisted(): PersistedComposerAttachment = PersistedComposerAttachment(
  id = id,
  name = name,
  kind = kind.wire,
  mimeType = mimeType,
  sizeBytes = sizeBytes,
  attachmentId = (upload as? Upload.Ready)?.attachment?.id,
)

private fun PersistedComposerAttachment.hydrated(): DraftAttachment {
  val draft = DraftAttachment(
    id = id,
    name = name,
    kind = AttachmentKind.entries.find { it.wire == kind } ?: AttachmentKind.FILE,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
  )
  val upload: Upload = when (attachmentId) {
    null -> Upload.Failed(ATTACH_AGAIN)
    else -> Upload.Ready(uploadedAttachment(draft, attachmentId))
  }
  return draft.copy(upload = upload)
}

/**
 * `partializeComposerDraftStoreState` for one thread: null when there's nothing to keep, which
 * upstream judges by `prompt.length`, not the trimmed prompt. The notice is transient.
 */
fun persistedDraft(text: String, extras: DraftExtras): PersistedComposerDraft? {
  if (text.isEmpty() && extras.attachments.isEmpty() && extras.comments.isEmpty()) return null
  return PersistedComposerDraft(
    prompt = text,
    attachments = extras.attachments.map { it.persisted() },
    reviewComments = extras.comments,
  )
}

/** A persisted draft back as composer state. Images come back without their thumbnail. */
fun hydratedExtras(draft: PersistedComposerDraft): DraftExtras = DraftExtras(
  attachments = draft.attachments.map { it.hydrated() },
  comments = draft.reviewComments,
)

fun encodeDraft(draft: PersistedComposerDraft): String =
  T3Json.encodeToString(PersistedComposerDraft.serializer(), draft)

/** Null for what this build can't read, as upstream's normalizers drop malformed drafts. */
fun decodeDraft(raw: String): PersistedComposerDraft? =
  runCatching { T3Json.decodeFromString(PersistedComposerDraft.serializer(), raw) }.getOrNull()

fun encodeStash(entries: List<PromptStashEntry>): String =
  T3Json.encodeToString(StashSerializer, entries)

fun decodeStash(raw: String?): List<PromptStashEntry> {
  if (raw.isNullOrEmpty()) return emptyList()
  return runCatching { T3Json.decodeFromString(StashSerializer, raw) }.getOrDefault(emptyList())
}

private val StashSerializer: KSerializer<List<PromptStashEntry>> =
  ListSerializer(PromptStashEntry.serializer())

/** What the stash shortcut does, `stashCurrentPrompt`'s three ways out. */
sealed interface StashKey {
  /** Stash the draft and clear the composer. */
  data object Stash : StashKey

  /** An empty composer and one entry: restore it straight away. */
  data class Restore(val entry: PromptStashEntry) : StashKey

  /** An empty composer otherwise: open or close the stash menu. */
  data object ToggleMenu : StashKey

  /** Not now, and why. */
  data class Refused(val reason: String) : StashKey
}

/**
 * `stashCurrentPrompt`: an empty composer (no text, no attachments; context alone doesn't count)
 * restores the only entry or toggles the menu. Otherwise every attachment has to be up first.
 */
fun stashKey(text: String, extras: DraftExtras, entries: List<PromptStashEntry>): StashKey {
  if (text.trim().isEmpty() && extras.attachments.isEmpty()) {
    val only: PromptStashEntry? = entries.singleOrNull()
    return if (only != null) StashKey.Restore(only) else StashKey.ToggleMenu
  }
  for (attachment in extras.attachments) {
    val upload: Upload = attachment.upload
    if (upload is Upload.Failed && upload.reason == ATTACH_AGAIN) {
      return StashKey.Refused(STASH_REATTACH_FIRST)
    }
    if (upload !is Upload.Ready) return StashKey.Refused(STASH_WAIT_FOR_UPLOADS)
  }
  return StashKey.Stash
}

/** The entry `stashEntryToQueue` gets: the trimmed prompt, attachments by reference, context. */
fun stashEntry(
  id: String,
  createdAt: Long,
  server: String,
  text: String,
  extras: DraftExtras,
): PromptStashEntry = PromptStashEntry(
  id = id,
  createdAt = createdAt,
  server = server,
  prompt = text.trim(),
  attachments = extras.attachments.map { it.persisted() },
  reviewComments = extras.comments,
)

/** `stashEntry` in the store: newest first, the oldest evicted past [MAX_STASH_ENTRIES]. */
class Stashed(val entries: List<PromptStashEntry>, val evicted: PromptStashEntry?)

fun stashed(entries: List<PromptStashEntry>, entry: PromptStashEntry): Stashed {
  val next: List<PromptStashEntry> = listOf(entry) + entries
  if (next.size <= MAX_STASH_ENTRIES) return Stashed(next, evicted = null)
  return Stashed(next.dropLast(1), evicted = next.last())
}

/** `restoreStashEntry`'s guard: an entry's uploads only exist on the server that took them. */
fun stashRestoreBlock(entry: PromptStashEntry, server: String): String? =
  if (entry.attachments.isNotEmpty() && entry.server != server) STASH_OTHER_ENVIRONMENT else null

/** `restoreStashEntry`'s prompt: after what's there, a blank line apart, else as it was. */
fun restoredPrompt(current: String, stashed: String): String = when {
  stashed.isEmpty() -> current
  current.trim().isNotEmpty() -> "${current.trimEnd()}\n\n$stashed"
  else -> stashed
}

/**
 * `restoreStashEntry`'s attachments and context, merged into [current]: an attachment or a
 * comment already there by id stays once, and attachments past [MAX_ATTACHMENTS] are skipped.
 */
fun restoredExtras(current: DraftExtras, entry: PromptStashEntry): DraftExtras {
  val existing: Set<String> = current.attachments.map { it.id }.toSet()
  val capacity: Int = (MAX_ATTACHMENTS - current.attachments.size).coerceAtLeast(0)
  val added: List<DraftAttachment> = entry.attachments
    .filterNot { it.id in existing }
    .take(capacity)
    .map { it.hydrated() }
  val comments: List<ReviewCommentContext> = current.comments +
    entry.reviewComments.filterNot { c -> current.comments.any { it.contextId == c.contextId } }
  return current.copy(
    attachments = current.attachments + added,
    comments = comments.take(COMPOSER_CONTEXT_MAX_RECORDS),
  )
}

/** `stashEntrySnippet`, ComposerStashMenu.tsx: one line of the prompt, or what it carries. */
fun stashEntrySnippet(entry: PromptStashEntry): String {
  val trimmed: String = entry.prompt.trim().replace(Regex("\\s+"), " ")
  if (trimmed.isNotEmpty()) {
    return if (trimmed.length > STASH_SNIPPET_MAX_CHARS) {
      "${trimmed.take(STASH_SNIPPET_MAX_CHARS)}…"
    } else {
      trimmed
    }
  }
  val imageCount: Int = entry.attachments.count { it.kind == AttachmentKind.IMAGE.wire }
  val fileCount: Int = entry.attachments.size - imageCount
  val count: Int = imageCount + fileCount
  if (count == 0) return "(empty)"
  val label: String = when {
    imageCount > 0 && fileCount > 0 -> "attachment"
    fileCount > 0 -> "file"
    else -> "image"
  }
  return "($count $label${if (count == 1) "" else "s"})"
}
