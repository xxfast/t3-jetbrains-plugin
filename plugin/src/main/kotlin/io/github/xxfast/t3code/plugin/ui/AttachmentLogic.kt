package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.ChatFileAttachment
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment
import kotlin.math.floor

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
 * `classifyComposerAttachmentFile` and the `mimeType` it ends up with: HEIC is an image (the
 * controller converts it to JPEG first, see `HeicConversion.kt`), an empty or generic type with
 * an image extension is that image, anything not `image/` is a file (a video's type from its
 * extension when the type is generic, `ChatComposer.tsx:5452`), and an image type the provider
 * can't take is refused.
 */
fun classifyAttachment(name: String, mimeType: String?): AttachmentClass {
  val type: String = mimeType?.lowercase().orEmpty()
  if (isHeicImageFile(name, type)) {
    val heic: String = if (type.startsWith("image/")) type else HEIC_MIME_TYPE
    return AttachmentClass.Accepted(AttachmentKind.IMAGE, heic)
  }
  if (type.isEmpty() || type == OCTET_STREAM) {
    val inferred: String? = inferImageMimeTypeFromName(name)
    if (inferred != null) return AttachmentClass.Accepted(AttachmentKind.IMAGE, inferred)
  }
  if (!type.startsWith("image/")) {
    val fileType: String = videoMimeType(name, type) ?: type.ifEmpty { OCTET_STREAM }
    return AttachmentClass.Accepted(AttachmentKind.FILE, fileType)
  }
  if (isSupportedImageMimeType(type)) return AttachmentClass.Accepted(AttachmentKind.IMAGE, type)
  return AttachmentClass.Refused(
    "'$name' is not a supported image type. Attach GIF, HEIC, HEIF, JPEG, PNG, or WebP images.",
  )
}

private const val HEIC_MIME_TYPE: String = "image/heic"

private val HEIC_IMAGE_MIME_TYPE: Regex = Regex("^image/hei(?:c|f)$", RegexOption.IGNORE_CASE)

private val HEIC_IMAGE_EXTENSION: Regex = Regex("""\.(?:heic|heif)$""", RegexOption.IGNORE_CASE)

/**
 * `isHeicImageFile`, apps/web/src/lib/imageCompression.ts:47. Finder and some browsers omit the
 * MIME type when dragging HEIC photos, so the extension counts when the type is generic.
 */
fun isHeicImageFile(name: String, mimeType: String): Boolean {
  if (HEIC_IMAGE_MIME_TYPE.matches(mimeType)) return true
  return (mimeType.isEmpty() || mimeType.lowercase() == OCTET_STREAM) &&
    HEIC_IMAGE_EXTENSION.containsMatchIn(name)
}

/** `fileNameForMimeType`, imageCompression.ts:158: [name] with a converted image's extension. */
fun fileNameForMimeType(name: String, mimeType: String): String {
  val extension: String = if (mimeType == "image/webp") ".webp" else ".jpg"
  val dot: Int = name.lastIndexOf('.')
  val base: String = if (dot > 0) name.substring(0, dot) else name
  return "$base$extension"
}

/** `VIDEO_MIME_TYPE_BY_EXTENSION`, packages/shared/src/video.ts:3. */
private val VIDEO_MIME_TYPE_BY_EXTENSION: Map<String, String> = mapOf(
  "avi" to "video/x-msvideo",
  "m4v" to "video/mp4",
  "mkv" to "video/x-matroska",
  "mov" to "video/quicktime",
  "mp4" to "video/mp4",
  "ogv" to "video/ogg",
  "webm" to "video/webm",
)

/**
 * `videoMimeType`, packages/shared/src/video.ts:15: the video type an attachment is, or null. A
 * definite type answers; the extension only counts when the type is empty or generic.
 */
fun videoMimeType(name: String, mimeType: String): String? {
  val type: String = mimeType.substringBefore(';').trim().lowercase()
  if (type.startsWith("video/")) return type
  if (type.isNotEmpty() && type !in GENERIC_MIME_TYPES) return null
  val dot: Int = name.lastIndexOf('.')
  if (dot < 0) return null
  return VIDEO_MIME_TYPE_BY_EXTENSION[name.substring(dot + 1).lowercase()]
}

/** `isVideoAttachment`, apps/web/src/types.ts:79, for a draft's file. */
fun isVideoAttachment(attachment: DraftAttachment): Boolean =
  attachment.kind == AttachmentKind.FILE &&
    videoMimeType(attachment.name, attachment.mimeType) != null

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

/** `formatAttachmentUploadProgress`, apps/web/src/lib/attachmentUploadState.ts:51. */
fun formatAttachmentUploadProgress(progress: Double): String {
  val bounded: Double = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 0.0
  return "${floor(bounded * 100).toInt()}%"
}

/** Where one attachment's upload is: `attachmentUploadState.ts`'s uploading, ready, failed. */
sealed interface Upload {
  /** [progress] runs 0 to 1: the share of the bytes handed to the connection so far. */
  data class Uploading(val progress: Double = 0.0) : Upload

  data class Ready(val attachment: ChatAttachment) : Upload

  data class Failed(val reason: String) : Upload
}

/**
 * One attachment in a thread's draft. [id] is local; the server's id is in [Upload.Ready].
 * [preview] is an image's thumbnail, decoded from the local bytes, upstream's `previewUrl`.
 */
data class DraftAttachment(
  val id: String,
  val name: String,
  val kind: AttachmentKind,
  val mimeType: String,
  val sizeBytes: Long,
  val upload: Upload = Upload.Uploading(),
  val preview: ImageBitmap? = null,
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

/** `GENERIC_MIME_TYPES`, packages/shared/src/image.ts:16. */
private val GENERIC_MIME_TYPES: Set<String> =
  setOf("application/octet-stream", "binary/octet-stream", "application/unknown")

/**
 * `imageMimeType`, packages/shared/src/image.ts:22: the picture type an attachment is, or null.
 * A definite type answers; the name is only evidence when the type is empty or generic.
 */
fun imageMimeType(name: String, mimeType: String): String? {
  val type: String = mimeType.substringBefore(';').trim().lowercase()
  if (type in SUPPORTED_IMAGE_MIME_TYPES) return type
  if (type.startsWith("image/")) return null
  if (type.isNotEmpty() && type !in GENERIC_MIME_TYPES) return null
  val dot: Int = name.lastIndexOf('.')
  if (dot < 0) return null
  return IMAGE_MIME_TYPE_BY_EXTENSION[name.substring(dot + 1).trim().lowercase()]
}

/**
 * `isImageAttachment`, apps/web/src/types.ts:64: an `image`, or a legacy `file` that carries a
 * picture. A type this build doesn't know is never a picture, whatever its name says.
 */
fun isImageAttachment(attachment: ChatAttachment): Boolean =
  attachment.type == "image" ||
    (attachment.type == "file" && imageMimeType(attachment.name, attachment.mimeType) != null)

/**
 * The longest side a decoded preview keeps. Upstream's message grid is 210px wide in two columns
 * (`MessagesTimeline.tsx:2344`) and the composer's tiles 64px (`ChatComposer.tsx:6800`), so this
 * is a grid tile on a 2x display with room to spare. The full image opens in the IDE instead.
 */
const val PREVIEW_MAX_SIDE: Int = 512

/** [name] with the characters a file name can't carry on any OS replaced, never empty. */
fun safeFileName(name: String): String =
  name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "attachment" }

/**
 * [name] as a file the IDE's image viewer opens: safe on disk, with an image extension. A name
 * without one, a pasted `image` say, gets its type's (`image/jpeg` is `.jpeg`).
 */
fun imageFileName(name: String, mimeType: String): String {
  val safe: String = safeFileName(name)
  if (inferImageMimeTypeFromName(safe) != null) return safe
  val type: String = imageMimeType(safe, mimeType) ?: "image/png"
  return "$safe.${type.substringAfter('/')}"
}

/** [width] by [height] scaled down to fit [maxSide], never up and never below a pixel. */
fun previewSize(width: Int, height: Int, maxSide: Int = PREVIEW_MAX_SIDE): Pair<Int, Int> {
  val longest: Int = maxOf(width, height)
  if (longest <= maxSide) return width to height
  val scale: Double = maxSide.toDouble() / longest
  return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}
