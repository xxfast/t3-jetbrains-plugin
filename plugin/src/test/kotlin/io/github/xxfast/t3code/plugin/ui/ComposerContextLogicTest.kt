package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.MessageContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class ComposerContextLogicTest {
  private val contents: String = (1..30).joinToString("\n") { "line $it" }

  @Test
  fun aFileCommentSerialisesLikeUpstream() {
    val comment: ReviewCommentContext = fileReviewComment(
      id = "file-comment-1700000000000-1",
      filePath = "plugin/src/Paths.kt",
      startLine = 10,
      endLine = 12,
      text = "  why this?  ",
      contents = contents,
    )

    val expected: JsonElement = T3Json.parseToJsonElement(
      """
      {
        "version": 1,
        "contextId": "review-comment_file-comment-1700000000000-1",
        "kind": "review-comment",
        "label": "Paths.kt L10 to L12",
        "sectionId": "file:plugin/src/Paths.kt",
        "sectionTitle": "File comment",
        "filePath": "plugin/src/Paths.kt",
        "startIndex": 9,
        "endIndex": 11,
        "rangeLabel": "L10 to L12",
        "text": "why this?",
        "diff": "line 10\nline 11\nline 12",
        "fenceLanguage": "kt"
      }
      """,
    )
    assertEquals(expected, comment.toRecord())
  }

  @Test
  fun oneLineIsJustThatLineAndReversedRangesSwap() {
    val single: ReviewCommentContext = fileReviewComment("c", "a.kt", 5, 5, "", contents)
    assertEquals("L5", single.rangeLabel)
    assertEquals("line 5", single.diff)
    assertEquals(4 to 4, single.startIndex to single.endIndex)

    val reversed: ReviewCommentContext = fileReviewComment("c", "a.kt", 8, 6, "", contents)
    assertEquals("L6 to L8", reversed.rangeLabel)
    assertEquals(5 to 7, reversed.startIndex to reversed.endIndex)

    val clamped: ReviewCommentContext = fileReviewComment("c", "a.kt", 0, 0, "", contents)
    assertEquals("L1", clamped.rangeLabel)
  }

  @Test
  fun aRangePastTheEndKeepsWhatThereIs() {
    val comment: ReviewCommentContext = fileReviewComment("c", "a.kt", 29, 40, "", contents)
    assertEquals("line 29\nline 30", comment.diff)
    assertEquals("L29 to L40", comment.rangeLabel)
  }

  @Test
  fun anEditorSelectionIsTheSameCommentAsTheWholeFileGives() {
    val selection = EditorSelection(
      id = "file-comment-1700000000000-1",
      path = Path.of("/repo/plugin/src/Paths.kt"),
      lines = 10..12,
      text = "line 10\nline 11\nline 12",
    )

    val comment: ReviewCommentContext? = selectionComment(selection, "/repo", "/repo")

    val expected: ReviewCommentContext =
      fileReviewComment(selection.id, "plugin/src/Paths.kt", 10, 12, "", contents)
    assertEquals(expected, comment)
  }

  @Test
  fun aSelectionInTheMainCheckoutGoesToAWorktreeThreadByItsRelativePath() {
    val selection = EditorSelection("c", Path.of("/repo/src/a.kt"), 1..1, "line 1")

    val comment: ReviewCommentContext? =
      selectionComment(selection, cwd = "/worktrees/feature", workspaceRoot = "/repo")

    assertEquals("src/a.kt", comment?.filePath)
  }

  @Test
  fun aSelectionOutsideTheThreadsProjectIsNotOffered() {
    val selection = EditorSelection("c", Path.of("/elsewhere/a.kt"), 1..1, "line 1")

    assertNull(selectionComment(selection, cwd = "/repo", workspaceRoot = "/repo"))
    assertNull(selectionComment(selection, cwd = null, workspaceRoot = null))
  }

  @Test
  fun fenceLanguageFollowsUpstream() {
    assertEquals("kt", inferReviewCommentFenceLanguage("src/Main.KT"))
    assertEquals("gitignore", inferReviewCommentFenceLanguage("repo/.gitignore"))
    assertEquals("text", inferReviewCommentFenceLanguage("Makefile"))
    assertEquals("text", inferReviewCommentFenceLanguage("odd."))
    assertEquals("ts", inferReviewCommentFenceLanguage("C:\\repo\\index.ts"))
  }

  @Test
  fun labelsUseTheFileNameAndDiffRangesReadAsLines() {
    val comment = ReviewCommentContext(
      id = "c",
      sectionId = "s",
      sectionTitle = "t",
      filePath = "a/b/Paths.kt",
      startIndex = 0,
      endIndex = 0,
      rangeLabel = "-3 to -5",
      text = "",
      diff = "",
    )
    assertEquals("Paths.kt L3 to L5 (before)", reviewCommentContextLabel(comment))
    assertEquals("Paths.kt L7", reviewCommentContextLabel(comment.copy(rangeLabel = "+7")))
  }

  @Test
  fun contextIdsAreKindScopedAndMadeSafe() {
    assertEquals("review-comment_file-comment-1-2", reviewCommentContextId("file-comment-1-2"))
    // Not a valid id, so a slug and upstream's two-pass FNV-1a digest, checked by hand
    assertEquals("a-b-10a3f9f24eebefbd", toComposerContextId("a b"))
    assertEquals("e40c292c", fnv1a64("a").take(8), "FNV-1a 32 of 'a'")
  }

  @Test
  fun labelsAreSanitised() {
    assertEquals("a b c", sanitizeComposerContextLabel(" a [b]\n\\c ", "review-comment"))
    assertEquals("review-comment", sanitizeComposerContextLabel(" [] ", "review-comment"))
    assertEquals(200, sanitizeComposerContextLabel("x".repeat(500), "k").length)
  }

  @Test
  fun longTextAndDiffAreClampedWithAMarker() {
    val big: String = "x".repeat(40_000)
    val comment: ReviewCommentContext = fileReviewComment("c", "a.kt", 1, 1, "", big)
    val record: JsonObject = comment.toRecord()
    val diff: String = T3Json.decodeFromJsonElement(
      kotlinx.serialization.serializer<String>(),
      record.getValue("diff"),
    )
    assertEquals(COMPOSER_CONTEXT_REVIEW_DIFF_MAX_CHARS, diff.length)
    assertTrue(diff.endsWith("\n\u2026 truncated \u2026"))
  }

  @Test
  fun messageContextIsNullWhenEmpty() {
    assertNull(messageContext(emptyList()))
    val record: JsonObject = fileReviewComment("c", "a.kt", 1, 1, "", contents).toRecord()
    val expected = MessageContext(version = 1, records = listOf(record))
    assertEquals(expected, messageContext(listOf(record)))
  }

  @Test
  fun referencesGoAtTheEndLikeAnInlineInsertAndStripBackOut() {
    val comment: ReviewCommentContext = fileReviewComment("c1", "src/a.kt", 2, 3, "", contents)
    val reference: String = reviewCommentReference(comment)
    assertEquals("[a.kt L2 to L3](t3-context://v1/review-comment/review-comment_c1)", reference)

    assertEquals("fix this $reference ", withContextReferences("fix this", listOf(reference)))
    assertEquals("fix this\n$reference ", withContextReferences("fix this\n", listOf(reference)))
    assertEquals("$reference ", withContextReferences("", listOf(reference)))
    // ensureInlineContextReferences: a reference the text already cites isn't added again
    assertEquals("see $reference ", withContextReferences("see $reference ", listOf(reference)))
    assertEquals("$reference ", withContextReferences("", listOf(reference, reference)))
    // By context id, like collectInlineContextIds: the same link under another label counts
    val relabelled = "see [mine](t3-context://v1/review-comment/review-comment_c1) "
    assertEquals(relabelled, withContextReferences(relabelled, listOf(reference)))
    assertEquals(listOf("review-comment_c1"), inlineContextIds(relabelled))
    assertEquals(emptyList(), inlineContextIds("plain [b](https://x)"))
    assertEquals("fix", withContextReferences("fix", emptyList()))

    assertEquals("fix this", stripContextReferences("fix this $reference "))
    assertEquals("a b", stripContextReferences("a $reference b"))
    assertEquals("keep  two", stripContextReferences("keep  two $reference "))
    assertEquals("a [b](https://x) c", stripContextReferences("a [b](https://x) c"))
  }

  @Test
  fun contextLabelsComeFromTheRecords() {
    val record: JsonObject = fileReviewComment("c", "src/a.kt", 4, 4, "", contents).toRecord()
    val context = MessageContext(version = 1, records = listOf(record, JsonObject(emptyMap())))
    assertEquals(listOf("a.kt L4"), contextLabels(context))
    assertEquals(emptyList(), contextLabels(null))
  }

  @Test
  fun pathsAreRelativeToTheThreadWhenUnderIt() {
    val root: Path = Path.of("repo").toAbsolutePath()
    assertEquals("src/a.kt", commentFilePath(root.toString(), root.resolve("src/a.kt")))
    val elsewhere: Path = Path.of("other", "b.kt").toAbsolutePath()
    assertEquals(elsewhere.toString(), commentFilePath(root.toString(), elsewhere))
    assertEquals(elsewhere.toString(), commentFilePath(null, elsewhere))
  }

  @Test
  fun aSelectionEndingAtALineStartStopsTheLineBefore() {
    assertEquals(3..5, selectedLines(startLine = 2, endLine = 5, endsAtLineStart = true))
    assertEquals(3..6, selectedLines(startLine = 2, endLine = 5, endsAtLineStart = false))
    assertEquals(3..3, selectedLines(startLine = 2, endLine = 2, endsAtLineStart = true))
  }
}
