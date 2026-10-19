package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.ChatFileAttachment
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment

// Attachment rules, ported from upstream's packages/contracts/src/chatAttachment.ts,
// apps/web/src/components/chat/composerAttachmentFiles.ts,
// packages/client-runtime/src/state/attachments.ts and apps/web/src/lib/attachmentUploadQueue.ts.

private const val MIB: Long = 1024L * 1024L

/** `PROVIDER_SEND_TURN_MAX_ATTACHMENTS`. */
const val MAX_ATTACHMENTS: Int = 100

/** `PROVIDER_SEND_TURN_MAX_IMAGE_BYTES`. Upstream compresses to fit; the plugin refuses. */
const val MAX_IMAGE_BYTES: Long = 10L * 1024L * 1024L

/** `PROVIDER_SEND_TURN_MAX_TOTAL_IMAGE_BYTES`. */
const val MAX_TOTAL_IMAGE_BYTES: Long = 80L * 1024L * 1024L

/** `PROVIDER_SEND_TURN_MAX_FILE_BYTES`. */
const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L

/** `PROVIDER_SEND_TURN_SUPPORTED_IMAGE_MIME_TYPES`. */
val SUPPORTED_IMAGE_MIME_TYPES: Set<String> =
  setOf("image/gif", "image/jpeg", "image/png", "image/webp")

private val IMAGE_MIME_TYPE_BY_EXTENSION: Map<String, String> = mapOf(
  "gif" to "image/gif",
  "jpeg" to "image/jpeg",
  "jpg" to "image/jpeg",
  "png" to "image/png",
  "webp" to "image/webp",
)

/** `isProviderSendTurnSupportedImageMimeType`. */
fun isSupportedImageMimeType(mimeType: String): Boolean =
  mimeType.lowercase() in SUPPORTED_IMAGE_MIME_TYPES

/** `inferImageMimeTypeFromName`: an image type from the extension, or null. */
fun inferImageMimeTypeFromName(name: String): String? {
  val dot: Int = name.lastIndexOf('.')
  if (dot <= 0) return null
  return IMAGE_MIME_TYPE_BY_EXTENSION[name.substring(dot + 1).lowercase()]
}

enum class AttachmentKind(val wire: String) { IMAGE("image"), FILE("file") }

/** What a picked, dropped or pasted file becomes, or why it can't be attached. */
sealed interface AttachmentClass {
  data class Accepted(val kind: AttachmentKind, val mimeType: String) : AttachmentClass

  data class Refused(val reason: String) : AttachmentClass
}

/**
 * `classifyComposerAttachmentFile` and the `mimeType` it ends up with: an empty or generic type
 * with an image extension is that image, anything not `image/` is a file, and an image type the
 * provider can't take is refused. Upstream converts HEIC; the plugin doesn't, so HEIC is refused.
 */
fun classifyAttachment(name: String, mimeType: String?): AttachmentClass {
  val type: String = mimeType?.lowercase().orEmpty()
  if (type.isEmpty() || type == "application/octet-stream") {
    val inferred: String? = inferImageMimeTypeFromName(name)
    if (inferred != null) return AttachmentClass.Accepted(AttachmentKind.IMAGE, inferred)
  }
  if (!type.startsWith("image/")) {
    return AttachmentClass.Accepted(AttachmentKind.FILE, type.ifEmpty { OCTET_STREAM })
  }
  if (isSupportedImageMimeType(type)) return AttachmentClass.Accepted(AttachmentKind.IMAGE, type)
  return AttachmentClass.Refused(
    "'$name' is not a supported image type. Attach GIF, JPEG, PNG, or WebP images.",
  )
}

private const val OCTET_STREAM: String = "application/octet-stream"

/** `fileAttachmentTooLargeMessage`. */
fun attachmentTooLargeMessage(name: String, maxBytes: Long): String {
  val size: String = when {
    maxBytes >= MIB && maxBytes % MIB == 0L -> "${maxBytes / MIB} MB"
    maxBytes >= 1024 && maxBytes % 1024 == 0L -> "${maxBytes / 1024} KB"
    else -> "$maxBytes ${if (maxBytes == 1L) "byte" else "bytes"}"
  }
  return "'$name' exceeds the $size attachment limit."
}

/**
 * Why [name] can't join [existing], or null when it can: the per-item checks of
 * `addComposerAttachments` in ChatComposer.tsx, then `getProviderAttachmentLimitError` over the
 * draft with it added.
 */
fun attachmentLimitError(
  name: String,
  kind: AttachmentKind,
  mimeType: String,
  sizeBytes: Long,
  existing: List<DraftAttachment>,
): String? {
  if (existing.size >= MAX_ATTACHMENTS) {
    return "You can attach up to $MAX_ATTACHMENTS files per message."
  }
  if (sizeBytes <= 0) return "'$name' is empty or could not be read."
  if (kind == AttachmentKind.FILE && sizeBytes > MAX_FILE_BYTES) {
    return attachmentTooLargeMessage(name, MAX_FILE_BYTES)
  }
  if (kind == AttachmentKind.IMAGE && sizeBytes > MAX_IMAGE_BYTES) {
    return attachmentTooLargeMessage(name, MAX_IMAGE_BYTES)
  }
  val all: List<Triple<AttachmentKind, String, Long>> =
    existing.map { Triple(it.kind, it.mimeType, it.sizeBytes) } + Triple(kind, mimeType, sizeBytes)
  return providerAttachmentLimitError(all)
}

/** `getProviderAttachmentLimitError`, over (kind, mime type, size). */
fun providerAttachmentLimitError(attachments: List<Triple<AttachmentKind, String, Long>>): String? {
  if (attachments.size > MAX_ATTACHMENTS) {
    return "You can attach up to $MAX_ATTACHMENTS files per message or question response."
  }
  val imageBytes: Long = attachments.sumOf { (kind, mimeType, size) ->
    if (kind == AttachmentKind.IMAGE || isSupportedImageMimeType(mimeType)) size else 0L
  }
  if (imageBytes > MAX_TOTAL_IMAGE_BYTES) {
    return "Images can total up to 80 MiB per message or question response. Use smaller images " +
      "or send fewer at once."
  }
  return null
}

/** `formatAttachmentSize`. */
fun formatAttachmentSize(sizeBytes: Long): String =
  if (sizeBytes >= MIB) {
    String.format(java.util.Locale.ROOT, "%.1f MB", sizeBytes.toDouble() / MIB)
  } else {
    "${maxOf(1L, (sizeBytes + 1023) / 1024)} KB"
  }

/** Where one attachment's upload is: `attachmentUploadState.ts`'s uploading, ready, failed. */
sealed interface Upload {
  data object Uploading : Upload

  data class Ready(val attachment: ChatAttachment) : Upload

  data class Failed(val reason: String) : Upload
}

/** One attachment in a thread's draft. [id] is local; the server's id is in [Upload.Ready]. */
data class DraftAttachment(
  val id: String,
  val name: String,
  val kind: AttachmentKind,
  val mimeType: String,
  val sizeBytes: Long,
  val upload: Upload = Upload.Uploading,
)

/** What `attachments.createUploadUrl` takes for [attachment]. */
fun uploadUrlInput(attachment: DraftAttachment): AttachmentCreateUploadUrlInput =
  when (attachment.kind) {
    AttachmentKind.IMAGE -> AttachmentCreateUploadUrlInput.Image(
      name = attachment.name,
      mimeType = attachment.mimeType,
      sizeBytes = attachment.sizeBytes,
    )

    AttachmentKind.FILE -> AttachmentCreateUploadUrlInput.File(
      name = attachment.name,
      mimeType = attachment.mimeType,
      sizeBytes = attachment.sizeBytes,
    )
  }

/** `getUploadedAttachments`: the message's attachment, under the id the server minted. */
fun uploadedAttachment(attachment: DraftAttachment, attachmentId: String): ChatAttachment =
  when (attachment.kind) {
    AttachmentKind.IMAGE -> ChatImageAttachment(
      id = attachmentId,
      name = attachment.name,
      mimeType = attachment.mimeType,
      sizeBytes = attachment.sizeBytes,
    )

    AttachmentKind.FILE -> ChatFileAttachment(
      id = attachmentId,
      name = attachment.name,
      mimeType = attachment.mimeType,
      sizeBytes = attachment.sizeBytes,
    )
  }

/**
 * `attachmentUploadBlockReason` in apps/web/src/lib/attachmentUploadState.ts: why Send waits, or
 * null. Upstream disables Send until every upload is ready, failures first.
 */
fun attachmentBlockReason(attachments: List<DraftAttachment>): String? {
  val failed: Int = attachments.count { it.upload is Upload.Failed }
  if (failed > 0) {
    return if (failed == 1) {
      "Retry or remove the failed attachment"
    } else {
      "Retry or remove the failed attachments"
    }
  }
  val uploading: Int = attachments.count { it.upload is Upload.Uploading }
  if (uploading > 0) {
    return if (uploading == 1) "Attachment still uploading" else "Attachments still uploading"
  }
  return null
}
