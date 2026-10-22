package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ComposerRichTextLogicTest {
  private val nbsp: Char = ' '

  private fun block(text: String, offset: Int = text.length): ComposerRichBlock? =
    ComposerRichDocument(text).blockAt(offset)

  private fun shown(
    text: String,
    selection: TextRange = TextRange(text.length),
    chips: List<ComposerPromptChip> = emptyList(),
    richText: Boolean = true,
  ): String {
    val document: ComposerRichDocument? = if (richText) ComposerRichDocument(text) else null
    return composerPromptPresentation(document, chips, selection).display(text)
  }

  private fun styles(text: String, selection: TextRange = TextRange(0)): List<ComposerPromptStyle> =
    composerPromptPresentation(ComposerRichDocument(text), emptyList(), selection).styles

  /** Types [typed] at the caret through the field's input transformation. */
  private fun TextFieldState.type(
    typed: String,
    decorations: ComposerPromptDecorations = ComposerPromptDecorations(),
  ) {
    for (char in typed) {
      edit {
        replace(selection.min, selection.max, char.toString())
        with(decorations.input) { transformInput() }
      }
    }
  }

  private fun field(text: String, selection: TextRange = TextRange(text.length)) =
    TextFieldState(text, selection)

  private fun ComposerPromptEdit?.on(text: String): Pair<String, TextRange>? =
    this?.let { it.applyTo(text) to it.selection }

  private fun kind(text: String): ComposerListKind =
    assertIs<ComposerRichBlock.ListItem>(block(text)).prefix.kind

  private fun newline(text: String, caret: Int): Pair<String, TextRange>? =
    composerRichNewline(text, TextRange(caret)).on(text)

  private fun enter(text: String, caret: Int): Pair<String, TextRange>? =
    composerRichEnter(text, TextRange(caret)).on(text)

  private fun noSurround(text: String, selection: TextRange, chips: List<ComposerPromptChip>) =
    assertNull(composerSurroundEdit(text, selection, "(", chips, emptyList()))

  // Lists

  @Test
  fun `star and plus start a bullet on their space`() {
    assertIs<ComposerRichBlock.ListItem>(block("* "))
    assertIs<ComposerRichBlock.ListItem>(block("+ "))
    assertIs<ComposerRichBlock.Paragraph>(block("*"))
    assertEquals("•$nbsp", shown("* "))
  }

  @Test
  fun `dash waits for the next character and carries it in`() {
    assertIs<ComposerRichBlock.Paragraph>(block("- "))
    assertEquals("- ", shown("- "))
    val item: ComposerRichBlock = assertIs<ComposerRichBlock.ListItem>(block("- x"))
    assertEquals(2, item.contentStart)
    assertEquals("•${nbsp}x", shown("- x"))
  }

  @Test
  fun `a dash with a bracket waits for the task rule`() {
    assertIs<ComposerRichBlock.Paragraph>(block("- ["))
    assertIs<ComposerRichBlock.Paragraph>(block("- [x"))
    assertIs<ComposerRichBlock.Paragraph>(block("- [ ]"))
    val task = assertIs<ComposerRichBlock.ListItem>(block("- [ ] "))
    assertEquals(ComposerListKind.TASK, task.prefix.kind)
  }

  @Test
  fun `a number starts an ordered list from that number`() {
    val dot = assertIs<ComposerRichBlock.ListItem>(block("1. "))
    assertEquals(ComposerListKind.ORDERED, dot.prefix.kind)
    val paren = assertIs<ComposerRichBlock.ListItem>(block("3) "))
    assertEquals("3)", paren.prefix.marker)
    assertIs<ComposerRichBlock.Paragraph>(block("1."))
    assertEquals("3) a", shown("3) a"))
  }

  // Tasks

  @Test
  fun `dash bracket space starts a task`() {
    val open = assertIs<ComposerRichBlock.ListItem>(block("- [ ] "))
    assertEquals(false, open.prefix.checked)
    val done = assertIs<ComposerRichBlock.ListItem>(block("- [x] "))
    assertEquals(true, done.prefix.checked)
    assertEquals("$nbsp${nbsp}a", shown("- [x] a"))
    val decorations: List<ComposerPromptDecoration> =
      composerPromptPresentation(ComposerRichDocument("- [x] a"), emptyList(), TextRange(0))
        .decorations
    assertEquals(listOf(ComposerTaskDecoration(0, 6, true, 3)), decorations)
  }

  @Test
  fun `brackets typed at the start of a star or plus bullet make it a task`() {
    val star: TextFieldState = field("")
    star.type("* [ ] ")
    assertEquals("- [ ] ", star.text.toString())
    assertEquals(ComposerListKind.TASK, kind("- [ ] "))
    val plus: TextFieldState = field("")
    plus.type("+ [x] ")
    assertEquals("- [x] ", plus.text.toString())
    // A dash bullet's markdown already is the task's
    val dash: TextFieldState = field("")
    dash.type("- [ ] ")
    assertEquals("- [ ] ", dash.text.toString())
  }

  @Test
  fun `brackets on an ordered item stay text`() {
    val ordered: TextFieldState = field("")
    ordered.type("1. [ ] ")
    assertEquals("1. [ ] ", ordered.text.toString())
    assertEquals(ComposerListKind.ORDERED, kind("1. [ ] "))
  }

  @Test
  fun `toggling a checkbox flips its x`() {
    val task = ComposerTaskDecoration(0, 6, false, 3)
    assertEquals("- [x] a", composerTaskToggleEdit(task).applyTo("- [ ] a"))
    assertEquals("- [ ] a", composerTaskToggleEdit(task.copy(checked = true)).applyTo("- [x] a"))
  }

  // Enter in a list, Tab

  @Test
  fun `enter splits an item and the new one is an item at once`() {
    assertEquals("- a\n- " to TextRange(6), composerRichNewline("- a", TextRange(3)).on("- a"))
    assertIs<ComposerRichBlock.ListItem>(block("- a\n- "))
    assertEquals("- a\n- b" to TextRange(6), composerRichNewline("- ab", TextRange(3)).on("- ab"))
    assertEquals("* a\n* " to TextRange(6), composerRichNewline("* a", TextRange(3)).on("* a"))
    assertEquals(
      "- [x] a\n- [ ] " to TextRange(14),
      composerRichNewline("- [x] a", TextRange(7)).on("- [x] a"),
    )
  }

  @Test
  fun `enter in an ordered item counts on and renumbers what follows`() {
    assertEquals("1. a\n2. " to TextRange(8), composerRichNewline("1. a", TextRange(4)).on("1. a"))
    assertEquals(
      "1. a\n2. \n3. b" to TextRange(8),
      composerRichNewline("1. a\n2. b", TextRange(4)).on("1. a\n2. b"),
    )
  }

  @Test
  fun `enter on an empty item leaves the list`() {
    assertEquals("- a\n" to TextRange(4), newline("- a\n- ", 6))
    // A nested one goes up to its parent's level
    assertEquals(
      "- a\n- " to TextRange(6),
      composerRichNewline("- a\n  - ", TextRange(8)).on("- a\n  - "),
    )
  }

  @Test
  fun `enter outside a list or quote is a plain newline`() {
    assertNull(composerRichNewline("hello", TextRange(5)))
    assertNull(composerRichNewline("# a", TextRange(3)))
    // `- ` alone is still a paragraph
    assertNull(composerRichNewline("- ", TextRange(2)))
  }

  @Test
  fun `tab and shift tab don't nest a list item`() {
    assertNull(composerRichTab("- a", TextRange(3), outdent = false))
    assertNull(composerRichTab("  - a", TextRange(5), outdent = true))
  }

  // Blocks

  @Test
  fun `quote on a space, enter continues it and leaves it on an empty line`() {
    assertIs<ComposerRichBlock.Quote>(block("> "))
    assertIs<ComposerRichBlock.Paragraph>(block(">foo"))
    assertEquals("${nbsp}a", shown("> a"))
    assertEquals("> a\n> " to TextRange(6), composerRichNewline("> a", TextRange(3)).on("> a"))
    assertEquals("> a\n" to TextRange(4), newline("> a\n> ", 6))
  }

  @Test
  fun `hashes and a space make a heading, without the space it's a pull request`() {
    for (level in 1..6) {
      val heading = assertIs<ComposerRichBlock.Heading>(block("${"#".repeat(level)} a"))
      assertEquals(level, heading.level)
    }
    assertIs<ComposerRichBlock.Paragraph>(block("####### a"))
    assertIs<ComposerRichBlock.Paragraph>(block("#1234"))
    assertEquals("#1234", shown("#1234"))
    assertEquals("a", shown("## a"))
    assertEquals(
      ComposerPromptStyle(ComposerPromptStyleKind.HEADING_2, 3, 4),
      styles("## a").single(),
    )
    // `#1234` stays the `#` trigger
    val trigger: ComposerTrigger? = detectComposerTrigger("#1234", 5)
    assertEquals(ComposerTriggerKind.PULL_REQUEST, trigger?.kind)
  }

  @Test
  fun `dashes make a rule at once, stars and underscores with a space`() {
    assertIs<ComposerRichBlock.Rule>(block("---"))
    assertIs<ComposerRichBlock.Paragraph>(block("***"))
    assertIs<ComposerRichBlock.Rule>(block("*** "))
    assertIs<ComposerRichBlock.Paragraph>(block("___"))
    assertIs<ComposerRichBlock.Rule>(block("___ "))
    assertEquals("a\n", shown("a\n---"))
  }

  @Test
  fun `typing a rule moves the caret off its line`() {
    val end: TextFieldState = field("--")
    end.type("-")
    assertEquals("---\n", end.text.toString())
    assertEquals(TextRange(4), end.selection)
    val above: TextFieldState = field("--\nnext", TextRange(2))
    above.type("-")
    assertEquals("---\nnext", above.text.toString())
    assertEquals(TextRange(4), above.selection)
    val stars: TextFieldState = field("***")
    stars.type(" ")
    assertEquals("*** \n", stars.text.toString())
  }

  @Test
  fun `backspace at a heading's start or after a rule takes the block off`() {
    assertEquals("" to TextRange(0), composerRichBackspace("# ", TextRange(2)).on("# "))
    val heading = "x\n## a"
    assertEquals("x\na" to TextRange(2), composerRichBackspace(heading, TextRange(5)).on(heading))
    assertEquals("\nb" to TextRange(0), composerRichBackspace("---\nb", TextRange(3)).on("---\nb"))
    assertNull(composerRichBackspace("# ab", TextRange(3)))
    assertNull(composerRichBackspace("- a", TextRange(2)))
  }

  // Code blocks

  @Test
  fun `a fence and enter open a code block`() {
    assertIs<ComposerRichBlock.Paragraph>(block("```ts"))
    assertEquals(
      "```ts\n\n```" to TextRange(6),
      composerRichEnter("```ts", TextRange(5)).on("```ts"),
    )
    val code = assertIs<ComposerRichBlock.CodeBlock>(block("```ts\n\n```", 6))
    assertEquals("ts", code.info)
    // Not at the end of the line, and not a fence: Enter sends
    assertNull(composerRichEnter("```ts", TextRange(3)))
    assertNull(composerRichEnter("hello", TextRange(5)))
  }

  @Test
  fun `enter in a code block is a newline that keeps the indent`() {
    val text = "```\n  a\n```"
    assertEquals("```\n  a\n  \n```" to TextRange(10), enter(text, 7))
  }

  @Test
  fun `a closing fence or trailing blank lines and enter leave the block`() {
    val closed = "```\na\n```"
    assertEquals("```\na\n```\n" to TextRange(10), enter(closed, 9))
    val blank = "```\na\n\n\n```"
    assertEquals("```\na\n```\n" to TextRange(10), enter(blank, 7))
  }

  @Test
  fun `tab and shift tab indent inside a code block`() {
    val text = "```\na\nb\n```"
    assertEquals(
      "```\n  a\nb\n```" to TextRange(6),
      composerRichTab(text, TextRange(4), false).on(text),
    )
    assertEquals(
      "```\n  a\n  b\n```" to TextRange(6, 11),
      composerRichTab(text, TextRange(4, 7), false).on(text),
    )
    val indented = "```\n  a\n  b\n```"
    assertEquals(
      "```\na\nb\n```" to TextRange(4, 7),
      composerRichTab(indented, TextRange(6, 11), true).on(indented),
    )
  }

  @Test
  fun `the fences hide while the caret is outside the block`() {
    val text = "hi\n```kt\nval a\n```"
    assertEquals("hi\n\nval a\n", shown(text, TextRange(0)))
    assertEquals(text, shown(text, TextRange(10)))
  }

  @Test
  fun `picking a language writes it into the fence`() {
    val text = "```\na\n```"
    val code = assertIs<ComposerRichBlock.CodeBlock>(block(text, 4))
    assertEquals("```kotlin\na\n```", composerCodeLanguageEdit(text, code, "kotlin").applyTo(text))
    val titled = "```ts title=a\na\n```"
    val titledCode = assertIs<ComposerRichBlock.CodeBlock>(block(titled, 14))
    assertEquals(
      "```text title=a\na\n```",
      composerCodeLanguageEdit(titled, titledCode, "").applyTo(titled),
    )
    assertEquals("TypeScript", codeLanguageLabel("ts"))
    assertEquals("file.kt", syntheticFileNameForLanguageId("kotlin"))
    assertEquals("Dockerfile", syntheticFileNameForLanguageId("dockerfile"))
  }

  @Test
  fun `a code block holds raw text, no marks or chips`() {
    val link: String = serializeComposerFileLink("src/App.kt")
    val text = "```\n**a** $link \n```"
    val chips: List<ComposerPromptChip> =
      composerPromptChips(text, collectComposerInlineTokens(text), emptyList(), emptyMap())
    assertEquals(1, chips.size)
    val presentation: ComposerPromptPresentation =
      composerPromptPresentation(ComposerRichDocument(text), chips, TextRange(5))
    assertTrue(presentation.chips.isEmpty())
    assertTrue(presentation.styles.none { it.kind == ComposerPromptStyleKind.BOLD })
  }

  // Marks

  @Test
  fun `marks style their text and show markers only with the caret in range`() {
    val text = "a **b** c"
    assertEquals("a b c", shown(text, TextRange(0)))
    assertEquals(text, shown(text, TextRange(4)))
    // Touching counts
    assertEquals(text, shown(text, TextRange(2)))
    assertEquals(text, shown(text, TextRange(7)))
    assertEquals("a b c", shown(text, TextRange(8)))
    assertTrue(ComposerPromptStyle(ComposerPromptStyleKind.BOLD, 4, 5) in styles(text))
  }

  @Test
  fun `italic, strike and code`() {
    assertEquals("x i s c", shown("x *i* ~~s~~ `c`", TextRange(0)))
    val all: List<ComposerPromptStyle> = styles("*i* ~~s~~ `c`")
    assertTrue(ComposerPromptStyle(ComposerPromptStyleKind.ITALIC, 1, 2) in all)
    assertTrue(ComposerPromptStyle(ComposerPromptStyleKind.STRIKE, 6, 7) in all)
    assertTrue(ComposerPromptStyle(ComposerPromptStyleKind.CODE, 11, 12) in all)
  }

  @Test
  fun `underscores inside a word and unclosed markers stay text`() {
    assertEquals("node_modules and _build", shown("node_modules and _build", TextRange(0)))
    assertEquals("**a", shown("**a", TextRange(0)))
    assertTrue(styles("**a").isEmpty())
  }

  @Test
  fun `nested marks are one range`() {
    val parsed: ComposerInlineMarkdown = parseComposerInlineMarkdown("**a *b***")
    assertEquals(1, parsed.styled.size)
    assertEquals(0, parsed.styled.single().start)
    assertEquals(9, parsed.styled.single().end)
    assertEquals("x a b", shown("x **a *b***", TextRange(0)))
  }

  @Test
  fun `a chip's source isn't read as markdown`() {
    val link: String = serializeComposerFileLink("src/__init__.py")
    val text = "see $link now"
    val chips: List<ComposerPromptChip> =
      composerPromptChips(text, collectComposerInlineTokens(text), emptyList(), emptyMap())
    val presentation: ComposerPromptPresentation =
      composerPromptPresentation(ComposerRichDocument(text), chips, TextRange(0))
    assertTrue(presentation.styles.isEmpty())
  }

  // Block rules only in a top-level paragraph

  @Test
  fun `block syntax inside a quote or list item stays its text`() {
    assertIs<ComposerRichBlock.Quote>(block("> # a"))
    assertIs<ComposerRichBlock.Quote>(block("> - a"))
    assertIs<ComposerRichBlock.Quote>(block("> ---"))
    assertIs<ComposerRichBlock.ListItem>(block("- # a"))
    assertIs<ComposerRichBlock.ListItem>(block("- > a"))
    assertEquals("$nbsp# a", shown("> # a"))
    assertEquals("•$nbsp# a", shown("- # a"))
    // A fence in an item opens nothing
    assertNull(composerRichEnter("- ```ts", TextRange(7)))
    assertNull(composerRichEnter("> ```ts", TextRange(7)))
    // Typing a rule inside a quote doesn't move the caret
    val quoted: TextFieldState = field("> --")
    quoted.type("-")
    assertEquals("> ---", quoted.text.toString())
    // Brackets in a quoted bullet stay text
    val bullet: TextFieldState = field("> * ")
    bullet.type("[ ] ")
    assertEquals("> * [ ] ", bullet.text.toString())
  }

  @Test
  fun `marks still format inside quotes and items`() {
    assertEquals("${nbsp}a b", shown("> a **b**", TextRange(0)))
    assertEquals("•${nbsp}a b", shown("- a **b**", TextRange(0)))
  }

  // Surround

  @Test
  fun `typing a pair character over a selection wraps it`() {
    for ((open, close) in SURROUND_CLOSE) {
      val edit: ComposerPromptEdit =
        composerSurroundEdit("say hello", TextRange(4, 9), open, emptyList(), emptyList())!!
      assertEquals("say ${open}hello$close", edit.applyTo("say hello"))
      assertEquals(TextRange(5, 10), edit.selection)
    }
    assertNull(composerSurroundEdit("say hello", TextRange(4), "(", emptyList(), emptyList()))
    assertNull(composerSurroundEdit("say hello", TextRange(4, 9), "x", emptyList(), emptyList()))
  }

  @Test
  fun `surround goes through the input transformation`() {
    val draft: TextFieldState = field("say hello", TextRange(4, 9))
    draft.type("*")
    assertEquals("say *hello*", draft.text.toString())
    assertEquals(TextRange(5, 10), draft.selection)
  }

  @Test
  fun `no wrapping around chips or styled text`() {
    val chip = ComposerPromptChip(4, 10, "mention", "a.kt", ComposerPromptChipIcon.FILE)
    noSurround("say [a.kt](a.kt) x", TextRange(2, 6), listOf(chip))
    val styled: List<ComposerRichStyledRange> = parseComposerInlineMarkdown("a **b** c").styled
    assertNull(composerSurroundEdit("a **b** c", TextRange(0, 5), "(", emptyList(), styled))
    // Nor over the space that keeps a mention a mention
    noSurround("see @a.kt now", TextRange(0, 4), emptyList())
    val draft: TextFieldState = field("a **b** c", TextRange(0, 5))
    draft.type("(")
    // The default replace, as upstream leaves styled text to it
    assertEquals("(** c", draft.text.toString())
  }

  // The setting

  @Test
  fun `off formats nothing and keeps the chips`() {
    val link: String = serializeComposerFileLink("src/App.kt")
    val text = "# a **b**\n- c\n> d $link "
    val chips: List<ComposerPromptChip> =
      composerPromptChips(text, collectComposerInlineTokens(text), emptyList(), emptyMap())
    val presentation: ComposerPromptPresentation =
      composerPromptPresentation(null, chips, TextRange(0))
    assertEquals(chips, presentation.chips)
    assertTrue(presentation.styles.isEmpty())
    assertEquals(chips, presentation.decorations)
    val chip: String = composerChipDisplay(chips.single()).text
    assertEquals("# a **b**\n- c\n> d $chip ", presentation.display(text))
    // No input rules either
    val decorations = ComposerPromptDecorations().apply { richText = false }
    val draft: TextFieldState = field("")
    draft.type("* [ ] ", decorations)
    assertEquals("* [ ] ", draft.text.toString())
    val rule: TextFieldState = field("--")
    rule.type("-", decorations)
    assertEquals("---", rule.text.toString())
  }

  // The caret

  @Test
  fun `the caret stays out of a block prefix`() {
    val prefixes: List<ComposerRichPrefix> = ComposerRichDocument("x\n# a").prefixes
    // A click at the line's start lands after the prefix
    assertEquals(TextRange(4), snapComposerRichSelection(prefixes, TextRange(0), TextRange(2)))
    // Left from the content steps to the line above
    assertEquals(TextRange(1), snapComposerRichSelection(prefixes, TextRange(4), TextRange(3)))
    // A selection takes the prefix whole
    assertEquals(
      TextRange(0, 4),
      snapComposerRichSelection(prefixes, TextRange(0), TextRange(0, 3)),
    )
  }

  // The prompt that goes out

  @Test
  fun `the draft keeps the markdown that gets sent`() {
    val markdown = "# Plan\n- [ ] **check** `x`\n> quoted\n---\n```kt\nval a = 1\n```\n1. one"
    val draft = TextFieldState(markdown, TextRange(0))
    val decorations = ComposerPromptDecorations()
    val shownField = TextFieldState(markdown, TextRange(0))
    shownField.edit { with(decorations.output) { transformOutput() } }
    assertEquals(
      "Plan\n$nbsp${nbsp}check x\n${nbsp}quoted\n\n\nval a = 1\n\n1. one",
      shownField.text.toString(),
    )
    assertEquals(markdown, draft.text.toString())
    assertEquals(markdown, outgoingText(draft.text.toString(), DraftExtras()))
  }
}
