package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ComposerTriggerLogicTest {
  // detectComposerTrigger, mirroring apps/web/src/composer-logic.test.ts:236

  @Test
  fun `keeps a path out of the slash menu`() {
    assertNull(detectComposerTrigger("Run /tmp/build.sh", "Run /tmp/build.sh".length))
    assertNull(detectComposerTrigger("/etc/hosts", "/etc/hosts".length))
    assertNull(detectComposerTrigger("Use /etc/hosts now", "Use /et".length))
  }

  @Test
  fun `detects inline slash skills after any whitespace`() {
    listOf("Use ", "Use\t", "Use\n  ").forEach { prefix ->
      val text = "$prefix/skill:review later"
      val cursor: Int = "$prefix/skill:review".length
      assertEquals(
        ComposerTrigger(ComposerTriggerKind.SLASH_COMMAND, "skill:review", prefix.length, cursor),
        detectComposerTrigger(text, cursor),
        prefix,
      )
    }
  }

  @Test
  fun `does not trigger inside a word or after a space`() {
    listOf("Use/review", "https://example.com/review", "Use /review ").forEach { text ->
      assertNull(detectComposerTrigger(text, text.length), text)
    }
  }

  @Test
  fun `does not keep a subcommand trigger active after model arguments`() {
    assertNull(detectComposerTrigger("/model spark", "/model spark".length))
  }

  @Test
  fun `any currency sigil is a skill trigger`() {
    listOf("$", "€", "£", "¥", "₹", "₩", "₿", "𑿝").forEach { prefix ->
      val text = "Use ${prefix}gh-fi"
      assertEquals(
        ComposerTrigger(ComposerTriggerKind.SKILL, "gh-fi", "Use ".length, text.length),
        detectComposerTrigger(text, text.length),
        prefix,
      )
    }
  }

  @Test
  fun `detects pull requests at a token boundary`() {
    val number = "Compare this with #8737"
    assertEquals(
      ComposerTrigger(ComposerTriggerKind.PULL_REQUEST, "8737", 18, number.length),
      detectComposerTrigger(number, number.length),
    )
    val bare = "Compare with #"
    assertEquals(
      ComposerTrigger(ComposerTriggerKind.PULL_REQUEST, "", 13, bare.length),
      detectComposerTrigger(bare, bare.length),
    )
    val hyphenated = "Find #inline-context"
    assertEquals(
      ComposerTrigger(ComposerTriggerKind.PULL_REQUEST, "inline-context", 5, hyphenated.length),
      detectComposerTrigger(hyphenated, hyphenated.length),
    )
  }

  @Test
  fun `headings and embedded hashes are not pull requests`() {
    assertNull(detectComposerTrigger("# Heading", "# Heading".length))
    assertNull(detectComposerTrigger("issue#123", "issue#123".length))
  }

  @Test
  fun `detects a path trigger in the middle of existing text`() {
    val text = "Please inspect @in this sentence"
    val cursor: Int = "Please inspect @".length
    assertEquals(
      ComposerTrigger(ComposerTriggerKind.PATH, "", "Please inspect ".length, cursor),
      detectComposerTrigger(text, cursor),
    )
  }

  @Test
  fun `the cursor is clamped`() {
    assertEquals(
      ComposerTrigger(ComposerTriggerKind.SKILL, "re", 0, 3),
      detectComposerTrigger("\$re", 99),
    )
    assertNull(detectComposerTrigger("\$re", -1))
  }

  // Replacement

  @Test
  fun `a replacement ending in a space swallows the next one`() {
    assertEquals(5, extendReplacementRangeForTrailingSpace("\$rev later", 4, "\$review "))
    assertEquals(4, extendReplacementRangeForTrailingSpace("\$revlater", 4, "\$review "))
    assertEquals(4, extendReplacementRangeForTrailingSpace("\$rev later", 4, "\$review"))
    assertEquals(4, extendReplacementRangeForTrailingSpace("\$rev", 4, "\$review "))
  }

  @Test
  fun `replaceTextRange clamps and puts the cursor after the replacement`() {
    assertEquals(
      TextReplacement("Use \$review later", 12),
      replaceTextRange("Use \$rev later", 4, 9, "\$review "),
    )
    assertEquals(TextReplacement("ab!", 3), replaceTextRange("ab", 5, 9, "!"))
  }

  // useComposerTriggerState

  @Test
  fun `a dismissed trigger stays closed while its token grows`() {
    val state = ComposerTriggerState()
    val first: ComposerTrigger = detectComposerTrigger("Use \$re", 7)!!
    assertEquals(first, state.setTrigger(first))
    state.dismissTrigger(first)
    assertNull(state.trigger)
    assertNull(state.setTrigger(detectComposerTrigger("Use \$rev", 8)))
    // A new token reopens, and forgets the dismissal
    val next: ComposerTrigger = detectComposerTrigger("Use \$rev \$g", 11)!!
    assertEquals(next, state.setTrigger(next))
    assertEquals(first, state.setTrigger(first))
  }

  @Test
  fun `no trigger forgets the dismissal`() {
    val state = ComposerTriggerState()
    val trigger: ComposerTrigger = detectComposerTrigger("\$re", 3)!!
    state.dismissTrigger(trigger)
    assertNull(state.setTrigger(null))
    assertEquals(trigger, state.setTrigger(trigger))
  }

  // Highlight and keys

  @Test
  fun `the highlight holds only under the search it was made in`() {
    val ids: List<String> = listOf("a", "b", "c")
    assertEquals("b", resolveComposerMenuActiveItemId(ids, "b", "SKILL:r", "SKILL:r"))
    assertEquals("a", resolveComposerMenuActiveItemId(ids, "b", "SKILL:re", "SKILL:r"))
    assertEquals("a", resolveComposerMenuActiveItemId(ids, "z", "SKILL:r", "SKILL:r"))
    assertNull(resolveComposerMenuActiveItemId(emptyList(), "b", "SKILL:r", "SKILL:r"))
  }

  @Test
  fun `the arrows wrap`() {
    val ids: List<String> = listOf("a", "b", "c")
    assertEquals("b", nudgeComposerMenuHighlight(ids, "a", down = true))
    assertEquals("a", nudgeComposerMenuHighlight(ids, "c", down = true))
    assertEquals("c", nudgeComposerMenuHighlight(ids, "a", down = false))
    assertEquals("a", nudgeComposerMenuHighlight(ids, null, down = true))
    assertEquals("c", nudgeComposerMenuHighlight(ids, null, down = false))
    assertNull(nudgeComposerMenuHighlight(emptyList(), null, down = true))
  }

  @Test
  fun `menu keys`() {
    fun key(
      input: ComposerMenuInput,
      shift: Boolean = false,
      submission: ComposerKey = ComposerKey.IGNORE,
      hasItems: Boolean = true,
      keyDown: Boolean = true,
    ): ComposerMenuKey = composerMenuKey(input, keyDown, shift, submission, hasItems)

    assertEquals(ComposerMenuKey.DISMISS, key(ComposerMenuInput.ESCAPE, hasItems = false))
    assertEquals(ComposerMenuKey.NEXT, key(ComposerMenuInput.DOWN))
    assertEquals(ComposerMenuKey.PREVIOUS, key(ComposerMenuInput.UP))
    assertEquals(ComposerMenuKey.PICK, key(ComposerMenuInput.ENTER, submission = ComposerKey.SEND))
    assertEquals(
      ComposerMenuKey.PICK,
      key(ComposerMenuInput.ENTER, shift = true, submission = ComposerKey.NEWLINE),
    )
    assertEquals(ComposerMenuKey.PICK, key(ComposerMenuInput.TAB))
    assertEquals(ComposerMenuKey.IGNORE, key(ComposerMenuInput.TAB, shift = true))
    assertEquals(
      ComposerMenuKey.IGNORE,
      key(ComposerMenuInput.ENTER, submission = ComposerKey.SEND_ALTERNATE),
    )
    assertEquals(ComposerMenuKey.IGNORE, key(ComposerMenuInput.ENTER, hasItems = false))
    assertEquals(ComposerMenuKey.IGNORE, key(ComposerMenuInput.DOWN, keyDown = false))
  }

  @Test
  fun `status text`() {
    assertEquals(
      SKILL_MENU_EMPTY_TEXT,
      composerMenuStatusText(ComposerTriggerKind.SKILL, false, null),
    )
    assertEquals(
      "Searching workspace skills...",
      composerMenuStatusText(ComposerTriggerKind.SKILL, true, null),
    )
    assertEquals("Nope", composerMenuStatusText(ComposerTriggerKind.PATH, false, "Nope"))
    assertEquals(
      "No matching files or folders.",
      composerMenuStatusText(ComposerTriggerKind.PATH, false, null),
    )
  }
}
