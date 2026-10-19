package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.ChatFileAttachment
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AttachmentLogicTest {
  @Test
  fun imagesFilesAndRefusalsAreClassifiedLikeUpstream() {
    val png = AttachmentClass.Accepted(AttachmentKind.IMAGE, "image/png")
    assertEquals(png, classifyAttachment("shot.png", "image/png"))
    assertEquals(png, classifyAttachment("shot.png", null), "Inferred from the extension")
    assertEquals(png, classifyAttachment("shot.PNG", "application/octet-stream"))
    assertEquals(
      AttachmentClass.Accepted(AttachmentKind.FILE, "text/plain"),
      classifyAttachment("notes.txt", "Text/Plain"),
    )
    assertEquals(
      AttachmentClass.Accepted(AttachmentKind.FILE, "application/octet-stream"),
      classifyAttachment("blob", null),
    )
    val svg: AttachmentClass.Refused = assertIs(classifyAttachment("logo.svg", "image/svg+xml"))
    assertEquals(
      "'logo.svg' is not a supported image type. Attach GIF, JPEG, PNG, or WebP images.",
      svg.reason,
    )
  }

  @Test
  fun perItemLimits() {
    fun error(kind: AttachmentKind, size: Long): String? =
      attachmentLimitError("a", kind, "x/y", size, emptyList())
    assertNull(error(AttachmentKind.FILE, MAX_FILE_BYTES))
    assertEquals(
      "'a' exceeds the 50 MB attachment limit.",
      error(AttachmentKind.FILE, MAX_FILE_BYTES + 1),
    )
    assertEquals(
      "'a' exceeds the 10 MB attachment limit.",
      error(AttachmentKind.IMAGE, MAX_IMAGE_BYTES + 1),
    )
    assertEquals("'a' is empty or could not be read.", error(AttachmentKind.FILE, 0))
  }

  @Test
  fun draftLimitsCountAndImageTotal() {
    val hundred: List<DraftAttachment> = List(MAX_ATTACHMENTS) { draft("f$it") }
    assertEquals(
      "You can attach up to 100 files per message.",
      attachmentLimitError("one-more", AttachmentKind.FILE, "text/plain", 1, hundred),
    )

    val images: List<DraftAttachment> = List(8) {
      draft("i$it", kind = AttachmentKind.IMAGE, mimeType = "image/png", size = MAX_IMAGE_BYTES)
    }
    assertEquals(
      "Images can total up to 80 MiB per message or question response. Use smaller images or " +
        "send fewer at once.",
      attachmentLimitError("ninth.png", AttachmentKind.IMAGE, "image/png", 1, images),
    )
    assertNull(attachmentLimitError("small.txt", AttachmentKind.FILE, "text/plain", 1, images))
  }

  @Test
  fun tooLargeMessageNamesTheUnit() {
    assertEquals("'a' exceeds the 50 MB attachment limit.", attachmentTooLargeMessage("a", 50 * MB))
    assertEquals("'a' exceeds the 2 KB attachment limit.", attachmentTooLargeMessage("a", 2048))
    assertEquals("'a' exceeds the 1 byte attachment limit.", attachmentTooLargeMessage("a", 1))
  }

  @Test
  fun sizesFormatLikeUpstream() {
    assertEquals("1 KB", formatAttachmentSize(1))
    assertEquals("2 KB", formatAttachmentSize(1025))
    assertEquals("1024 KB", formatAttachmentSize(MB - 1))
    assertEquals("1.0 MB", formatAttachmentSize(MB))
    assertEquals("2.5 MB", formatAttachmentSize(MB * 5 / 2))
  }

  @Test
  fun uploadsMintTheRightKindAndAttachUnderTheMintedId() {
    val image: DraftAttachment =
      draft("a.png", kind = AttachmentKind.IMAGE, mimeType = "image/png", size = 10)
    assertEquals(
      AttachmentCreateUploadUrlInput.Image(name = "a.png", mimeType = "image/png", sizeBytes = 10),
      uploadUrlInput(image),
    )
    assertEquals(
      ChatImageAttachment(id = "att_1", name = "a.png", mimeType = "image/png", sizeBytes = 10),
      uploadedAttachment(image, "att_1"),
    )
    val file: DraftAttachment = draft("a.txt")
    assertIs<AttachmentCreateUploadUrlInput.File>(uploadUrlInput(file))
    assertIs<ChatFileAttachment>(uploadedAttachment(file, "att_2"))
  }

  @Test
  fun sendWaitsOnUploadsAndFailuresComeFirst() {
    assertNull(attachmentBlockReason(listOf(draft("a", upload = ready("a")))))
    assertEquals(
      "Attachment still uploading",
      attachmentBlockReason(listOf(draft("a"), draft("b", upload = ready("b")))),
    )
    assertEquals(
      "Attachments still uploading",
      attachmentBlockReason(listOf(draft("a"), draft("b"))),
    )
    assertEquals(
      "Retry or remove the failed attachment",
      attachmentBlockReason(listOf(draft("a"), draft("b", upload = Upload.Failed("no")))),
    )
  }
}

private const val MB: Long = 1024L * 1024L

internal fun draft(
  name: String,
  kind: AttachmentKind = AttachmentKind.FILE,
  mimeType: String = "text/plain",
  size: Long = 1,
  upload: Upload = Upload.Uploading,
) = DraftAttachment(
  id = "local-$name",
  name = name,
  kind = kind,
  mimeType = mimeType,
  sizeBytes = size,
  upload = upload,
)

internal fun ready(name: String): Upload.Ready = Upload.Ready(
  ChatFileAttachment(id = "att_$name", name = name, mimeType = "text/plain", sizeBytes = 1),
)
