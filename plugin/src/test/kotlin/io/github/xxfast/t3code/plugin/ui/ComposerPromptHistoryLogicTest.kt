package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.text.TextRange
import io.github.xxfast.t3code.plugin.ui.ComposerPromptHistoryDirection.BACKWARD
import io.github.xxfast.t3code.plugin.ui.ComposerPromptHistoryDirection.FORWARD
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// apps/web/src/components/chat/composerPromptHistory.test.ts
class ComposerPromptHistoryLogicTest {
  private fun user(id: String, text: String): ComposerPromptHistoryMessage =
    ComposerPromptHistoryMessage(id = id, role = "user", text = text)

  private val entries: List<ComposerPromptHistoryEntry> = buildComposerPromptHistoryEntries(
    listOf(
      user("m1", "first"),
      ComposerPromptHistoryMessage(id = "a1", role = "assistant", text = "reply"),
      user("m2", "second"),
      user("m3", "third"),
    ),
  )

  private fun backward(position: ComposerPromptHistoryPosition?, currentPrompt: String) =
    stepComposerPromptHistory(BACKWARD, entries, position, currentPrompt)

  private fun forward(position: ComposerPromptHistoryPosition?, currentPrompt: String) =
    stepComposerPromptHistory(FORWARD, entries, position, currentPrompt)

  // recallableComposerPrompt

  @Test
  fun `strips an annotation whose comment contains a literal opening tag`() {
    listOf(
      "Render <preview_annotation>",
      "Render\n<preview_annotation>\nhere",
      "Render &lt;preview_annotation&gt;",
    ).forEach { comment ->
      assertEquals(
        "Prompt",
        recallableComposerPrompt(
          "Prompt\n<preview_annotation>\nComment: $comment\n</preview_annotation>",
        ),
      )
    }
  }

  @Test
  fun `preserves a malformed annotation containing a nested opening tag`() {
    val text = "Prompt\n<preview_annotation>\nouter literal\n<preview_annotation>\ninner\n" +
      "</preview_annotation>"
    assertEquals(text, recallableComposerPrompt(text))
  }

  @Test
  fun `does not strip a terminal label embedded in ordinary prose`() {
    assertEquals(
      "email@build:7",
      recallableComposerPrompt(
        "email@build:7\n<terminal_context>\n- Build line 7:\n  output\n</terminal_context>",
      ),
    )
  }

  @Test
  fun `strips legacy send-time context blocks and the ultrathink prefix`() {
    val sent = "Ultrathink:\nInvestigate this\n\n<terminal_context>\n- Terminal 1 lines 12-13:\n" +
      "  12 | git status\n  13 | On branch main\n</terminal_context>\n\n<element_context>\n" +
      "- <button>:\n  url: https://example.com\n</element_context>"
    assertEquals("Investigate this", recallableComposerPrompt(sent))
  }

  @Test
  fun `removes only the matching legacy terminal label`() {
    listOf(
      "Look at @terminal-1:4 please" to "Look at please",
      "@terminal-1:4 typed twice: @terminal-1:4\n    indented  code" to
        "typed twice: @terminal-1:4\n    indented  code",
      "see @terminal-1:40 and @terminal-1:4-12 then @terminal-1:4" to
        "see @terminal-1:40 and @terminal-1:4-12 then",
    ).forEach { (typed, expected) ->
      val sent = typed +
        "\n\n<terminal_context>\n- Terminal 1 line 4:\n  4 | ls\n</terminal_context>"
      assertEquals(expected, recallableComposerPrompt(sent))
    }
  }

  @Test
  fun `strips only review comments appended at the end`() {
    val block = "<review_comment filePath=\"src/app.ts\">Keep this configurable.</review_comment>"
    assertEquals("Please update this.", recallableComposerPrompt("Please update this.\n\n$block"))
    val midPrompt = "Before\n\n$block\n\nAfter"
    assertEquals(midPrompt, recallableComposerPrompt(midPrompt))
    assertEquals(midPrompt, recallableComposerPrompt("$midPrompt\n\n$block"))
  }

  @Test
  fun `removes canonical references without restoring dangling chips`() {
    val sent = "Look at [Terminal](t3-context://v1/terminal/term-1) please\n    indented  code\n" +
      "![image](t3-context://v1/image/img-1)"
    assertEquals("Look at please\n    indented  code", recallableComposerPrompt(sent))
    assertEquals("", recallableComposerPrompt("![image](t3-context://v1/image/img-1)"))
  }

  @Test
  fun `recalls nothing for a bootstrap or plan implementation prompt`() {
    assertEquals("", recallableComposerPrompt(ATTACHMENT_ONLY_BOOTSTRAP_PROMPT))
    assertEquals("", recallableComposerPrompt("${PLAN_IMPLEMENTATION_PROMPT_PREFIX}do it"))
  }

  // buildComposerPromptHistoryEntries

  @Test
  fun `collapses consecutive duplicates onto the newest message id`() {
    val collapsed: List<ComposerPromptHistoryEntry> = buildComposerPromptHistoryEntries(
      listOf(user("m1", "same"), user("m2", "same"), user("m3", "other"), user("m4", "same")),
    )
    assertEquals(
      listOf(
        ComposerPromptHistoryEntry("m2", "same"),
        ComposerPromptHistoryEntry("m3", "other"),
        ComposerPromptHistoryEntry("m4", "same"),
      ),
      collapsed,
    )
  }

  @Test
  fun `skips messages sent by automations, agents, and T3 Code`() {
    val typed: List<ComposerPromptHistoryEntry> = buildComposerPromptHistoryEntries(
      listOf(
        user("m1", "typed"),
        user("m2", "scheduled").copy(scheduledTaskId = "task"),
        user(
          "scheduled-task-message:task:1:scheduled",
          "[Triggered by schedule task: Daily]\n\nlegacy scheduled",
        ),
        user("m3", "from agent").copy(createdBy = "agent", creationSource = "mcp"),
        user("m4", "from server").copy(createdBy = "agent", creationSource = "server"),
      ),
    )
    assertEquals(listOf(ComposerPromptHistoryEntry("m1", "typed")), typed)
  }

  // stepComposerPromptHistory

  @Test
  fun `does not start browsing from a non-empty draft`() {
    assertNull(backward(null, "typing"))
  }

  @Test
  fun `walks back from the newest entry`() {
    val first: ComposerPromptHistoryStep? = backward(null, "")
    assertEquals(
      ComposerPromptHistoryStep(ComposerPromptHistoryPosition("m3", "third"), "third"),
      first,
    )
    assertEquals("second", backward(first!!.position, "third")?.prompt)
  }

  @Test
  fun `walks forward and empties the composer past the newest entry`() {
    val newer: ComposerPromptHistoryStep? =
      forward(ComposerPromptHistoryPosition("m2", "second"), "second")
    assertEquals("third", newer?.prompt)
    assertEquals(ComposerPromptHistoryStep(null, ""), forward(newer!!.position, "third"))
  }

  @Test
  fun `treats an edited recall as a fresh draft`() {
    val position = ComposerPromptHistoryPosition("m3", "third")
    assertNull(backward(position, "third edited"))
    assertNull(forward(position, "third edited"))
    assertEquals(ComposerPromptHistoryPosition("m3", "third"), backward(position, "")?.position)
  }

  @Test
  fun `follows the entry by id when the list changes under it`() {
    val grown: List<ComposerPromptHistoryEntry> = buildComposerPromptHistoryEntries(
      listOf(user("m0", "zeroth"), user("m1", "A"), user("m2", "B"), user("m3", "A")),
    )
    val older: ComposerPromptHistoryStep? =
      stepComposerPromptHistory(BACKWARD, grown, ComposerPromptHistoryPosition("m1", "A"), "A")
    assertEquals("zeroth", older?.prompt)
    val missing: ComposerPromptHistoryStep? = stepComposerPromptHistory(
      FORWARD,
      grown,
      ComposerPromptHistoryPosition("gone", "not sent"),
      "not sent",
    )
    assertNull(missing)
  }

  @Test
  fun `falls back to matching text when a duplicate collapse retires the id`() {
    val collapsed: List<ComposerPromptHistoryEntry> =
      buildComposerPromptHistoryEntries(listOf(user("m1", "first"), user("m3", "A")))
    val step: ComposerPromptHistoryStep? =
      stepComposerPromptHistory(BACKWARD, collapsed, ComposerPromptHistoryPosition("m2", "A"), "A")
    assertEquals("first", step?.prompt)
  }

  // isCaretOnVisualEdge and the navigatePromptHistory guards

  @Test
  fun `an empty prompt is on both edges`() {
    assertTrue(isCaretOnVisualEdge("", TextRange(0), BACKWARD, null, null))
    assertTrue(isCaretOnVisualEdge("", TextRange(0), FORWARD, null, null))
  }

  @Test
  fun `a newline between the caret and the end is not an edge`() {
    assertFalse(isCaretOnVisualEdge("a\nb", TextRange(3), BACKWARD, 1, 2))
    assertFalse(isCaretOnVisualEdge("a\nb", TextRange(0), FORWARD, 0, 2))
    assertTrue(isCaretOnVisualEdge("a\nb", TextRange(1), BACKWARD, 0, 2))
    assertTrue(isCaretOnVisualEdge("a\nb", TextRange(2), FORWARD, 1, 2))
  }

  @Test
  fun `a wrapped line counts by its visual line`() {
    assertFalse(isCaretOnVisualEdge("a long wrapped line", TextRange(15), BACKWARD, 1, 2))
    assertTrue(isCaretOnVisualEdge("a long wrapped line", TextRange(15), FORWARD, 1, 2))
  }

  @Test
  fun `a selection or a missing layout is no edge`() {
    assertFalse(isCaretOnVisualEdge("abc", TextRange(0, 2), BACKWARD, 0, 1))
    assertFalse(isCaretOnVisualEdge("abc", TextRange(0), BACKWARD, null, null))
  }

  @Test
  fun `recall waits for an empty prompt, no modifiers, no request, nothing attached`() {
    val position = ComposerPromptHistoryPosition("m3", "third")
    assertTrue(canNavigatePromptHistory(false, false, false, null, ""))
    assertTrue(canNavigatePromptHistory(false, false, false, position, "third"))
    assertFalse(canNavigatePromptHistory(false, false, false, null, "typing"))
    assertFalse(canNavigatePromptHistory(true, false, false, null, ""))
    assertFalse(canNavigatePromptHistory(false, true, false, null, ""))
    assertFalse(canNavigatePromptHistory(false, false, true, null, ""))
  }

  @Test
  fun `shortcuts open only a registered control`() {
    val shortcuts = ComposerShortcuts()
    var opened = 0
    val opener: () -> Unit = { opened++ }
    assertFalse(shortcuts.open(ComposerControlKind.EFFORT))
    shortcuts.register(ComposerControlKind.EFFORT, opener)
    assertTrue(shortcuts.canOpen(ComposerControlKind.EFFORT))
    assertTrue(shortcuts.open(ComposerControlKind.EFFORT))
    assertEquals(1, opened)
    shortcuts.unregister(ComposerControlKind.EFFORT) {}
    assertTrue(shortcuts.canOpen(ComposerControlKind.EFFORT))
    shortcuts.unregister(ComposerControlKind.EFFORT, opener)
    assertFalse(shortcuts.canOpen(ComposerControlKind.EFFORT))
  }
}
