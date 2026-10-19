package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DraftExtrasTest {
  private val image: DraftAttachment =
    draft("a.png", kind = AttachmentKind.IMAGE, mimeType = "image/png", size = 3)
  private val minted =
    ChatImageAttachment(id = "att_1", name = "a.png", mimeType = "image/png", sizeBytes = 3)
  private val comment: ReviewCommentContext =
    fileReviewComment("c1", "src/a.kt", 1, 2, "", "one\ntwo")

  @Test
  fun anAttachmentGoesUploadingThenReadyThenAway() {
    val uploading: DraftExtras = DraftExtras(notice = "old").attaching(image)
    assertEquals(Upload.Uploading, uploading.attachments.single().upload)
    assertNull(uploading.notice, "A new attach clears the last refusal")
    assertEquals("Attachment still uploading", uploading.blockReason)
    assertEquals(emptyList(), uploading.ready)

    val ready: DraftExtras = uploading.uploaded(image.id, minted)
    assertEquals(listOf(minted), ready.ready)
    assertNull(ready.blockReason)

    val removed: DraftExtras = ready.withoutAttachment(image.id)
    assertEquals(DraftExtras(), removed)
  }

  @Test
  fun aFailedUploadBlocksUntilRetriedOrRemoved() {
    val failed: DraftExtras = DraftExtras().attaching(image).uploadFailed(image.id, "413")
    assertEquals(Upload.Failed("413"), failed.attachments.single().upload)
    assertEquals("Retry or remove the failed attachment", failed.blockReason)
    assertEquals(Upload.Uploading, failed.retrying(image.id).attachments.single().upload)
  }

  @Test
  fun anUploadThatFinishesAfterRemovalChangesNothing() {
    val removed: DraftExtras = DraftExtras().attaching(image).withoutAttachment(image.id)
    assertEquals(removed, removed.uploaded(image.id, minted))
  }

  @Test
  fun commentsJoinOncePerContextIdAndLeaveById() {
    val one: DraftExtras = DraftExtras().withComment(comment)
    assertSame(one, one.withComment(comment))
    assertEquals(listOf(comment), one.comments)
    assertFalse(one.hasContent, "A comment needs text to go with it")
    assertEquals(DraftExtras(), one.withoutComment(comment.contextId))
  }

  @Test
  fun aSecondCommentOnTheSameLinesReplacesTheFirst() {
    val contents = "a\nb\nc\nd"
    val first: ReviewCommentContext = fileReviewComment("c1", "src/a.kt", 2, 3, "one", contents)
    val other: ReviewCommentContext = fileReviewComment("c5", "src/a.kt", 4, 4, "x", contents)
    val second: ReviewCommentContext = fileReviewComment("c9", "src/a.kt", 2, 3, "two", contents)
    val extras: DraftExtras =
      DraftExtras().withComment(first).withComment(other).withComment(second)
    assertEquals(listOf(second, other), extras.comments, "The newer note, in the older's place")
  }

  @Test
  fun aRefusedSendGetsItsExtrasBackFirst() {
    val sent: DraftExtras = DraftExtras().withComment(comment).attaching(image)
    val later: ReviewCommentContext = fileReviewComment("c2", "src/b.kt", 1, 1, "", "x")
    val since: DraftExtras = DraftExtras().withComment(later)

    val restored: DraftExtras = since.restored(sent)
    assertEquals(listOf(comment, later), restored.comments)
    assertEquals(listOf(image), restored.attachments)
  }

  @Test
  fun attachmentsAreEnoughToSendButCommentsAloneAreNot() {
    val state: ComposerState =
      composerState(ConnectionState.Disconnected, false, false, null, false).copy(enabled = true)
    assertFalse(sendable("", state, DraftExtras()))
    assertTrue(sendable("hi", state, DraftExtras()))
    assertFalse(sendable("", state, DraftExtras().withComment(comment)), "Comments alone never")
    assertTrue(sendable("hi", state, DraftExtras().withComment(comment)))
    assertFalse(sendable("hi", state, DraftExtras().attaching(image)), "Still uploading")
    val ready: DraftExtras = DraftExtras().attaching(image).uploaded(image.id, minted)
    assertTrue(sendable("", state, ready))
    assertFalse(sendable("hi", state.copy(enabled = false), ready))
  }

  @Test
  fun outgoingTextCitesCommentsOrBootstrapsAttachmentsOnly() {
    val reference: String = reviewCommentReference(comment)
    assertEquals("fix $reference", outgoingText(" fix ", DraftExtras().withComment(comment)))
    assertEquals(reference, outgoingText("", DraftExtras().withComment(comment)))
    val ready: DraftExtras = DraftExtras().attaching(image).uploaded(image.id, minted)
    assertEquals(ATTACHMENT_ONLY_BOOTSTRAP_PROMPT, outgoingText("  ", ready))
    assertEquals("look", outgoingText("look", ready))
  }
}
