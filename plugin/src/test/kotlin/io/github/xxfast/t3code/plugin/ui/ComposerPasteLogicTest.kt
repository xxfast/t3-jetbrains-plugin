package io.github.xxfast.t3code.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComposerPasteLogicTest {
  // packages/shared/src/composerTrigger.test.ts:46
  @Test
  fun serializeComposerFileLinkEncodesMarkdownSensitiveDestinationCharacters() {
    assertEquals(
      "[My File (draft).md](docs/My%20File%20%28draft%29.md)",
      serializeComposerFileLink("docs/My File (draft).md"),
    )
  }

  @Test
  fun serializeComposerFileLinkSupportsWindowsPaths() {
    assertEquals(
      "[index.ts](C:%5Crepo%5Csrc%5Cindex.ts)",
      serializeComposerFileLink("C:\\repo\\src\\index.ts"),
    )
  }

  @Test
  fun serializeComposerFileLinkPreservesPathsThatStartWithAnAtSign() {
    assertEquals(
      "[package.json](@scope/package.json)",
      serializeComposerFileLink("@scope/package.json"),
    )
  }

  @Test
  fun serializeComposerFileLinkEscapesTheLabelAndEncodesUnicodeAsUtf8() {
    assertEquals("[a\\[1\\]](/tmp/a%5B1%5D)", serializeComposerFileLink("/tmp/a[1]"))
    assertEquals("[caf\u00e9](/x/caf%C3%A9)", serializeComposerFileLink("/x/caf\u00e9"))
    assertEquals("[q](/a%3Fb%23c/q)", serializeComposerFileLink("/a?b#c/q"))
  }

  @Test
  fun aFolderIsItsLinkAndASpaceWithABoundaryBeforeIt() {
    assertEquals("[src](/repo/src) ", folderLinkText("/repo/src"))
    assertEquals("x", textForEndInsertion("", "x"))
    assertEquals("x", textForEndInsertion("hello ", "x"))
    assertEquals(" x", textForEndInsertion("hello", "x"))
    assertEquals("x", textForEndInsertion("hello\n", "x"))
  }

  @Test
  fun pastedTextFoldsFromThirtyTwoKibibytesByCharactersOrBytes() {
    val small: String = "a".repeat(PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES - 1)
    val large: String = "a".repeat(PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES)
    // Three bytes each in UTF-8, so a third of the threshold in characters is enough
    val wide: String = "\u20ac".repeat(PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES / 3 + 1)
    assertEquals(PastedTextDisposition.INLINE, pastedTextDisposition(small, canAttach = true))
    assertEquals(PastedTextDisposition.ATTACHMENT, pastedTextDisposition(large, canAttach = true))
    assertEquals(PastedTextDisposition.ATTACHMENT, pastedTextDisposition(wide, canAttach = true))
    assertEquals(
      PastedTextDisposition.ATTACHMENT,
      pastedTextDisposition("short", canAttach = true, wouldExceedInputLimit = true),
    )
    assertEquals(
      PastedTextDisposition.INLINE,
      pastedTextDisposition(large, canAttach = true, bypassAutoAttachment = true),
    )
    assertEquals(PastedTextDisposition.INLINE, pastedTextDisposition(large, canAttach = false))
    assertEquals(PastedTextDisposition.INLINE, pastedTextDisposition("", canAttach = true))
  }

  @Test
  fun pastedTextFileNamesCountUpCaseInsensitively() {
    assertEquals("pasted-text.txt", nextPastedTextFileName(emptyList()))
    assertEquals("pasted-text-2.txt", nextPastedTextFileName(listOf("Pasted-Text.TXT")))
    assertEquals(
      "pasted-text-3.txt",
      nextPastedTextFileName(listOf("pasted-text.txt", "pasted-text-2.txt")),
    )
  }

  @Test
  fun aPasteExceedsTheLimitByWhatItAddsOverTheSelection() {
    assertFalse(wouldTextPasteExceedLimit(10, 0, 0, 90, maxLength = 100))
    assertTrue(wouldTextPasteExceedLimit(10, 0, 0, 91, maxLength = 100))
    assertFalse(wouldTextPasteExceedLimit(10, 0, 10, 100, maxLength = 100))
    // Out of range selections are clamped
    assertFalse(wouldTextPasteExceedLimit(10, -5, 50, 100, maxLength = 100))
  }

  @Test
  fun foldPastedTextAttachesALargePasteAndRefusesOnlyWhatCantGoInline() {
    val large: String = "a".repeat(PASTED_TEXT_ATTACHMENT_THRESHOLD_BYTES)
    assertEquals(PasteFold.Inline, fold("small"))
    assertEquals(PasteFold.Attach, fold(large))
    assertEquals(PasteFold.Inline, fold(large, bypass = true))
    // A full draft: inline while it still fits the input, refused once it can't
    assertEquals(PasteFold.Inline, fold(large, attachments = MAX_ATTACHMENTS))
    val overflow: String = "a".repeat(MAX_INPUT_CHARS + 1)
    val full: PasteFold.TooLarge = assertIs(fold(overflow, attachments = MAX_ATTACHMENTS))
    assertEquals("Pasted text is too large for this message", full.title)
    assertEquals(PasteFold.Attach, fold(overflow))
  }

  private fun fold(text: String, bypass: Boolean = false, attachments: Int = 0): PasteFold =
    foldPastedText(
      text = text,
      bypassAutoAttachment = bypass,
      valueLength = 0,
      selectionStart = 0,
      selectionEnd = 0,
      attachmentCount = attachments,
    )
}
