package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ComposerPromptChipsLogicTest {
  private fun skill(
    name: String,
    displayName: String? = null,
    enabled: Boolean = true,
    userInvocable: Boolean? = null,
  ): ServerProviderSkill = ServerProviderSkill(
    name = name,
    path = "/skills/$name/SKILL.md",
    enabled = enabled,
    displayName = displayName,
    userInvocable = userInvocable,
  )

  private fun chips(
    text: String,
    skills: List<ServerProviderSkill> = emptyList(),
    extras: DraftExtras = DraftExtras(),
  ): List<ComposerPromptChip> = composerPromptChips(
    text = text,
    tokens = collectComposerInlineTokens(text),
    skills = skills,
    records = composerContextRecordsFromDraft(extras),
  )

  private fun token(type: ComposerInlineTokenType, value: String, source: String, start: Int) =
    ComposerInlineToken(type, value, source, start, start + source.length)

  // collectComposerInlineTokens, mirroring packages/shared/src/composerInlineTokens.test.ts

  @Test
  fun `collects file links, mentions, and skills with source ranges`() {
    val text = "Use \$ui and inspect [Chat.tsx](src/Chat.tsx) with @AGENTS.md please"
    assertEquals(
      listOf(
        token(ComposerInlineTokenType.SKILL, "ui", "\$ui", 4),
        token(ComposerInlineTokenType.MENTION, "src/Chat.tsx", "[Chat.tsx](src/Chat.tsx)", 20),
        token(ComposerInlineTokenType.MENTION, "AGENTS.md", "@AGENTS.md", 50),
      ),
      collectComposerInlineTokens(text),
    )
  }

  @Test
  fun `collects skill names that begin with a digit after any currency sign`() {
    listOf("$", "€", "𑿝").forEach { prefix ->
      assertEquals(
        listOf(token(ComposerInlineTokenType.SKILL, "2spec", "${prefix}2spec", 4)),
        collectComposerInlineTokens("Use ${prefix}2spec next"),
        prefix,
      )
    }
  }

  @Test
  fun `leaves digits-only dollar amounts and compact monetary expressions as text`() {
    listOf(
      "I'll pay \$20 tomorrow",
      "Budget is \$1_000 total",
      "Budget is \$20k tomorrow",
      "Cost is \$100M total",
      "Limit is \$1e6 here",
    ).forEach { text -> assertEquals(emptyList(), collectComposerInlineTokens(text), text) }
  }

  @Test
  fun `does not convert incomplete trailing tokens`() {
    assertEquals(emptyList(), collectComposerInlineTokens("Use \$ui"))
    assertEquals(emptyList(), collectComposerInlineTokens("Inspect @AGENTS.md"))
  }

  @Test
  fun `preserves a confirmed chip when only its trailing delimiter is removed`() {
    val withDelimiter = "[package.json](package.json) "
    val confirmed: List<ComposerInlineToken> = collectComposerInlineTokens(withDelimiter)
    assertEquals(
      listOf(
        token(ComposerInlineTokenType.MENTION, "package.json", "[package.json](package.json)", 0),
      ),
      collectComposerInlineTokens(withDelimiter.trimEnd(), confirmed),
    )
  }

  @Test
  fun `does not preserve a chip after its source is edited`() {
    val confirmed: List<ComposerInlineToken> =
      collectComposerInlineTokens("[package.json](package.json) ")
    assertEquals(
      emptyList(),
      collectComposerInlineTokens("[package.json](package-json)", confirmed),
    )
  }

  @Test
  fun `keeps scoped package references as plain text`() {
    listOf("@expo/ui", "@jane/foo.js", "@scope/pkg/sub/path").forEach { reference ->
      assertEquals(emptyList(), collectComposerInlineTokens("Install $reference next"), reference)
    }
  }

  @Test
  fun `keeps decorator calls as plain text`() {
    listOf("@namespace.foo(bar)", "@écrire(option)", "@écrire(option)").forEach { reference ->
      assertEquals(
        emptyList(),
        collectComposerInlineTokens("$reference\nclass Example {}"),
        reference,
      )
    }
  }

  @Test
  fun `keeps an explicit decorator-shaped file reference as a mention`() {
    listOf("@\"foo(bar)\"", "[foo(bar)](foo%28bar%29)").forEach { reference ->
      assertEquals(
        listOf(token(ComposerInlineTokenType.MENTION, "foo(bar)", reference, 0)),
        collectComposerInlineTokens("$reference "),
        reference,
      )
    }
  }

  @Test
  fun `keeps bare file mentions before whitespace-separated parentheses`() {
    assertEquals(
      listOf(token(ComposerInlineTokenType.MENTION, "README.md", "@README.md", 8)),
      collectComposerInlineTokens("Inspect @README.md (notes)"),
    )
  }

  @Test
  fun `keeps canonical file links for scoped paths as mentions`() {
    assertEquals(
      listOf(
        token(ComposerInlineTokenType.MENTION, "@scope/pkg/sub", "[sub](@scope/pkg/sub)", 8),
      ),
      collectComposerInlineTokens("Inspect [sub](@scope/pkg/sub) next"),
    )
  }

  @Test
  fun `allows ambiguous scoped paths through explicit quoted mentions`() {
    assertEquals(
      listOf(token(ComposerInlineTokenType.MENTION, "expo/ui", "@\"expo/ui\"", 8)),
      collectComposerInlineTokens("Inspect @\"expo/ui\" next"),
    )
  }

  @Test
  fun `caps a file link's label at 512 characters`() {
    val atCap = "${"a".repeat(508)}.tsx"
    assertEquals(
      listOf("src/$atCap"),
      collectComposerInlineTokens("see [$atCap](src/$atCap) ok").map { it.value },
    )
    val pastCap = "${"a".repeat(509)}.tsx"
    assertEquals(emptyList(), collectComposerInlineTokens("see [$pastCap](src/$pastCap) ok"))
  }

  @Test
  fun `leaves a context reference link alone`() {
    assertEquals(
      emptyList(),
      collectComposerInlineTokens("see [checkout.png](t3-context://v1/image/ctx_abc) ok"),
    )
    assertEquals(
      emptyList(),
      collectComposerInlineTokens("see ![ctx_abc](t3-context://v1/image/ctx_abc) ok"),
    )
  }

  // Chips for what the plugin's composer inserts

  @Test
  fun `a picked skill draws as a chip with its display name`() {
    val review: ServerProviderSkill = skill("review", displayName = "Code Review")
    val text = "Run ${skillReplacement(review)}now"
    assertEquals(
      listOf(ComposerPromptChip(4, 11, "skill", "Code Review", ComposerPromptChipIcon.SKILL)),
      chips(text, listOf(review)),
    )
  }

  @Test
  fun `a dollar name that isn't one of the provider's skills stays text`() {
    val hidden: ServerProviderSkill = skill("internal", userInvocable = false)
    val off: ServerProviderSkill = skill("off", enabled = false)
    assertEquals(emptyList(), chips("echo \$HOME \$internal \$off ", listOf(hidden, off)))
  }

  @Test
  fun `a picked file draws as a chip labelled with its name`() {
    val text = "Look at ${serializeComposerFileLink("src/my file.kt")} please"
    val link: String = serializeComposerFileLink("src/my file.kt")
    assertEquals(
      listOf(
        ComposerPromptChip(
          start = 8,
          end = 8 + link.length,
          kind = "mention",
          label = "my file.kt",
          icon = ComposerPromptChipIcon.FILE,
        ),
      ),
      chips(text),
    )
  }

  @Test
  fun `a picked thread draws as a thread chip`() {
    val record: ThreadContextRecord = threadContextRecord("env", "thread-1", "Fix the build")
    val reference: String = threadContextReference(record)
    val text = "See $reference "
    assertEquals(
      listOf(
        ComposerPromptChip(
          start = 4,
          end = 4 + reference.length,
          kind = "thread",
          label = "Fix the build",
          icon = ComposerPromptChipIcon.THREAD,
        ),
      ),
      chips(text, extras = DraftExtras().withThread(record)),
    )
  }

  @Test
  fun `a picked pull request draws as a pull request chip in its state`() {
    val metadata = PullRequestContextMetadata(
      number = 42,
      title = "Add chips",
      url = "https://github.com/xxfast/KStore/pull/42",
      headBranch = "chips",
      baseBranch = "main",
      state = "merged",
      isDraft = false,
    )
    val comment: ReviewCommentContext = buildPullRequestReferenceContext(metadata)
    val text = "${reviewCommentReference(comment)} "
    assertEquals(
      listOf(
        ComposerPromptChip(
          start = 0,
          end = text.length - 1,
          kind = "review-comment",
          label = "#42",
          icon = ComposerPromptChipIcon.PULL_REQUEST_MERGED,
        ),
      ),
      chips(text, extras = DraftExtras().withComment(comment)),
    )
  }

  @Test
  fun `image and file references show their attachment's name and size`() {
    val image = DraftAttachment(
      id = "a1",
      name = "shot.png",
      kind = AttachmentKind.IMAGE,
      mimeType = "image/png",
      sizeBytes = 2 * 1024 * 1024,
      upload = Upload.Ready(
        ChatImageAttachment(id = "srv", name = "shot.png", mimeType = "image/png", sizeBytes = 1),
      ),
    )
    val file = DraftAttachment(
      id = "a2",
      name = "notes.txt",
      kind = AttachmentKind.FILE,
      mimeType = "text/plain",
      sizeBytes = 1500,
      upload = Upload.Uploading(0.5),
    )
    val imageReference: String =
      contextReference("image", toKindScopedComposerContextId("image", "a1"), "shot.png")
    val fileReference: String =
      contextReference("file", toKindScopedComposerContextId("file", "a2"), "notes.txt")
    val text = "$imageReference $fileReference"
    val extras = DraftExtras(attachments = listOf(image, file))
    assertEquals(
      listOf(
        "shot.png" to "2.0 MB",
        "notes.txt" to "2 KB · 50%",
      ),
      chips(text, extras = extras).map { it.label to it.detail },
    )
    assertEquals(
      listOf(ComposerPromptChipIcon.IMAGE, ComposerPromptChipIcon.FILE),
      chips(text, extras = extras).map { it.icon },
    )
  }

  @Test
  fun `a reference the draft has no record for draws as unavailable`() {
    val text = "![gone.png](t3-context://v1/image/ctx_gone) "
    assertEquals(
      listOf(
        ComposerPromptChip(
          start = 0,
          end = text.length - 1,
          kind = "image",
          label = "gone.png",
          icon = ComposerPromptChipIcon.UNAVAILABLE,
        ),
      ),
      chips(text),
    )
  }

  @Test
  fun `adjacent references are separate chips`() {
    val skills: List<ServerProviderSkill> = listOf(skill("a"), skill("b"))
    assertEquals(listOf(0 to 2, 3 to 5), chips("\$a \$b ", skills).map { it.start to it.end })
    val text = "[a.kt](a.kt) [b.kt](b.kt) "
    assertEquals(listOf(0 to 12, 13 to 25), chips(text).map { it.start to it.end })
  }

  @Test
  fun `malformed references stay text`() {
    listOf(
      "[label](src/other.kt) ", // label isn't the basename
      "[site](https://example.com) ", // external scheme
      "[x](t3-context://v1/Bad/ctx) ", // kind isn't lower case
      "[x](t3-context://v1/image/a/b) ", // too many segments
      "[x](t3-context://v1/image/ctx", // unclosed
      "\$20 ",
    ).forEach { text -> assertEquals(emptyList(), chips(text, listOf(skill("x"))), text) }
  }

  @Test
  fun `a skill inside a context link label isn't a chip of its own`() {
    val text = "[\$review notes](t3-context://v1/review-comment/ctx_1) "
    assertEquals(listOf("review-comment"), chips(text, listOf(skill("review"))).map { it.kind })
  }

  // Display: what the field lays out

  @Test
  fun `a chip shows its icon slot, label, size and a closing space`() {
    val chip =
      ComposerPromptChip(0, 10, "image", "my shot.png", ComposerPromptChipIcon.IMAGE, "2 KB")
    val display: ComposerChipDisplay = composerChipDisplay(chip)
    assertEquals(" my shot.png 2 KB ", display.text)
    assertEquals("2 KB", display.text.substring(display.detail!!.first, display.detail.last + 1))
  }

  @Test
  fun `a long label ellipsises`() {
    val chip = ComposerPromptChip(0, 1, "thread", "x".repeat(60), ComposerPromptChipIcon.THREAD)
    assertEquals(COMPOSER_CHIP_LABEL_MAX_CHARS + 2, composerChipDisplay(chip).text.length)
    assertTrue(composerChipDisplay(chip).text.trimEnd(' ').endsWith("…"))
  }

  @Test
  fun `draft offsets map into the laid out text`() {
    val skills: List<ServerProviderSkill> = listOf(skill("ab", displayName = "Abc"))
    val text = "x \$ab y"
    val found: List<ComposerPromptChip> = chips(text, skills)
    // "x " + " Abc " + " y"
    assertEquals(9, composerPromptDisplayLength(text.length, found))
    assertEquals(2, composerPromptDisplayOffset(found, 2))
    assertEquals(2, composerPromptDisplayOffset(found, 3)) // inside: the chip's start
    assertEquals(7, composerPromptDisplayOffset(found, 5))
    assertEquals(9, composerPromptDisplayOffset(found, 7))
    assertEquals(listOf(2 to 7), composerChipDisplayRanges(found).map { it.start to it.end })
  }

  // Atomic edits

  private val chip = ComposerPromptChip(4, 10, "skill", "Ab", ComposerPromptChipIcon.SKILL)

  @Test
  fun `backspace into a chip deletes the whole chip`() {
    assertEquals(TextRange(4, 10), atomicComposerPromptEdit(listOf(chip), 5, 10))
  }

  @Test
  fun `delete into a chip deletes the whole chip`() {
    assertEquals(TextRange(4, 10), atomicComposerPromptEdit(listOf(chip), 4, 9))
  }

  @Test
  fun `a deletion across text and part of a chip takes the chip`() {
    assertEquals(TextRange(1, 10), atomicComposerPromptEdit(listOf(chip), 1, 6))
    assertEquals(TextRange(4, 12), atomicComposerPromptEdit(listOf(chip), 7, 12))
  }

  @Test
  fun `edits that don't cut a chip stay as they are`() {
    assertEquals(TextRange(4, 10), atomicComposerPromptEdit(listOf(chip), 4, 10))
    assertEquals(TextRange(0, 4), atomicComposerPromptEdit(listOf(chip), 0, 4))
    assertEquals(TextRange(10, 11), atomicComposerPromptEdit(listOf(chip), 10, 11))
    assertEquals(TextRange(4), atomicComposerPromptEdit(listOf(chip), 4, 4))
    assertEquals(TextRange(10), atomicComposerPromptEdit(listOf(chip), 10, 10))
  }

  @Test
  fun `text typed inside a chip goes in at its nearer edge`() {
    assertEquals(TextRange(4), atomicComposerPromptEdit(listOf(chip), 6, 6))
    assertEquals(TextRange(10), atomicComposerPromptEdit(listOf(chip), 8, 8))
  }

  @Test
  fun `a caret arrowing into a chip steps over it`() {
    // The field maps a caret inside the label to the inside of the chip's source
    assertEquals(
      TextRange(10),
      snapComposerPromptSelection(listOf(chip), TextRange(4), TextRange(5, 9)),
    )
    assertEquals(
      TextRange(4),
      snapComposerPromptSelection(listOf(chip), TextRange(10), TextRange(5, 9)),
    )
  }

  @Test
  fun `a click inside a chip selects it`() {
    assertEquals(
      TextRange(4, 10),
      snapComposerPromptSelection(listOf(chip), TextRange(0), TextRange(5, 9)),
    )
  }

  @Test
  fun `a selection takes the chips it cuts`() {
    assertEquals(
      TextRange(1, 10),
      snapComposerPromptSelection(listOf(chip), TextRange(1, 4), TextRange(1, 9)),
    )
    assertEquals(
      TextRange(12, 4),
      snapComposerPromptSelection(listOf(chip), TextRange(12, 10), TextRange(12, 5)),
    )
  }

  @Test
  fun `a caret left inside a chip by an edit goes to the nearer edge`() {
    assertEquals(
      TextRange(4),
      snapComposerPromptSelection(listOf(chip), TextRange(4), TextRange(5), textChanged = true),
    )
    assertEquals(
      TextRange(3),
      snapComposerPromptSelection(listOf(chip), TextRange(0), TextRange(3)),
    )
  }

  // The field's transformations over a real TextFieldState

  @Test
  fun `the draft keeps the markdown that gets sent`() {
    val review: ServerProviderSkill = skill("review")
    val record: ThreadContextRecord = threadContextRecord("env", "thread-1", "Fix the build")
    val markdown = "${skillReplacement(review)}${threadContextReference(record)} and " +
      "${serializeComposerFileLink("src/App.kt")} now"
    val draft = TextFieldState(markdown)
    val promptChips = ComposerPromptDecorations().apply {
      skills = listOf(review)
      records = composerContextRecordsFromDraft(DraftExtras().withThread(record))
    }
    // What the field shows, laid out from a copy
    val shown = TextFieldState(markdown)
    shown.edit { with(promptChips.output) { transformOutput() } }
    assertEquals(
      " Review   Fix the build  and  App.kt  now",
      shown.text.toString(),
    )
    assertEquals(markdown, draft.text.toString())
    assertEquals(markdown, outgoingText(draft.text.toString(), DraftExtras().withThread(record)))
  }

  @Test
  fun `backspace after a chip removes it whole through the input transformation`() {
    val review: ServerProviderSkill = skill("review")
    val draft = TextFieldState("Run \$review now", initialSelection = TextRange(11))
    val promptChips = ComposerPromptDecorations().apply { skills = listOf(review) }
    // What the field asks for: the chip's last character
    draft.edit {
      delete(5, 11)
      with(promptChips.input) { transformInput() }
    }
    assertEquals("Run  now", draft.text.toString())
    assertEquals(TextRange(4), draft.selection)
  }

  @Test
  fun `typing inside a chip moves the text out through the input transformation`() {
    val review: ServerProviderSkill = skill("review")
    val draft = TextFieldState("Run \$review now", initialSelection = TextRange(4))
    val promptChips = ComposerPromptDecorations().apply { skills = listOf(review) }
    draft.edit {
      insert(9, "x")
      with(promptChips.input) { transformInput() }
    }
    // At the nearer edge, then spaced off so the chip still parses
    assertEquals("Run \$review x now", draft.text.toString())
    assertEquals(TextRange(13), draft.selection)
    assertEquals(listOf("skill"), chips(draft.text.toString(), listOf(review)).map { it.kind })
  }

  // Separating typed text from a chip, upstream's paste padding

  private val mention = ComposerPromptChip(4, 10, "mention", "a.kt", ComposerPromptChipIcon.FILE)

  @Test
  fun `text typed at a chip's leading edge gets a space after it`() {
    assertEquals(
      ComposerPromptInsertion("X ", 1),
      separateComposerPromptInsertion(listOf(mention), 4, 4, "X"),
    )
    assertEquals(
      ComposerPromptInsertion("X ", 1),
      separateComposerPromptInsertion(listOf(chip), 4, 4, "X"),
    )
  }

  @Test
  fun `text typed at a chip's trailing edge gets a space before it`() {
    assertEquals(
      ComposerPromptInsertion(" X", 2),
      separateComposerPromptInsertion(listOf(mention), 10, 10, "X"),
    )
  }

  @Test
  fun `whitespace or text away from a chip goes in as typed`() {
    assertEquals(
      ComposerPromptInsertion(" ", 1),
      separateComposerPromptInsertion(listOf(mention), 4, 4, " "),
    )
    assertEquals(
      ComposerPromptInsertion("\nX", 2),
      separateComposerPromptInsertion(listOf(mention), 10, 10, "\nX"),
    )
    assertEquals(
      ComposerPromptInsertion("X", 1),
      separateComposerPromptInsertion(listOf(mention), 2, 2, "X"),
    )
    assertEquals(
      ComposerPromptInsertion("", 0),
      separateComposerPromptInsertion(listOf(mention), 3, 4, ""),
    )
  }

  @Test
  fun `a context link needs no space`() {
    val thread = ComposerPromptChip(4, 10, "thread", "Fix", ComposerPromptChipIcon.THREAD)
    assertEquals(
      ComposerPromptInsertion("X", 1),
      separateComposerPromptInsertion(listOf(thread), 4, 4, "X"),
    )
  }

  @Test
  fun `typing at a file chip's leading edge keeps the chip`() {
    val link: String = serializeComposerFileLink("kstore-file/FileCodec.kt")
    val draft = TextFieldState("$link ", initialSelection = TextRange(0))
    val promptChips = ComposerPromptDecorations()
    draft.edit {
      insert(0, "X")
      with(promptChips.input) { transformInput() }
    }
    assertEquals("X $link ", draft.text.toString())
    assertEquals(TextRange(1), draft.selection)
    assertEquals(listOf("mention"), chips(draft.text.toString()).map { it.kind })
    // Typing on stays in the word
    draft.edit {
      insert(1, "y")
      with(promptChips.input) { transformInput() }
    }
    assertEquals("Xy $link ", draft.text.toString())
  }
}
