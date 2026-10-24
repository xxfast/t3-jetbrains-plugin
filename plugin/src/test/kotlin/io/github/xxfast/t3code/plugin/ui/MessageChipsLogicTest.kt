package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test

class MessageChipsLogicTest {
  private fun record(json: String): JsonElement = Json.parseToJsonElement(json)

  private fun skill(name: String, displayName: String? = null): ServerProviderSkill =
    ServerProviderSkill(
      name = name,
      path = "/skills/$name/SKILL.md",
      enabled = true,
      displayName = displayName,
    )

  @Test
  fun `a mention chip opens its path with the file's icon`() {
    val chip: MessageChip = messageContextChip(
      "mention",
      "mention_ROADMAP-md",
      "ROADMAP.md",
      record(
        """{"kind":"mention","contextId":"mention_ROADMAP-md","label":"ROADMAP.md",
          "path":"docs/ROADMAP.md"}""",
      ),
    )
    assertEquals(MessageChipKind.MENTION, chip.kind)
    assertEquals("ROADMAP.md", chip.label)
    assertEquals(MessageChipIcon.FILE, chip.icon)
    assertEquals("docs/ROADMAP.md", chip.iconPath)
    assertEquals(MessageChipAction.OpenFile("docs/ROADMAP.md"), chip.action)
    assertFalse(chip.unresolved)
  }

  @Test
  fun `a folder mention shows the folder icon`() {
    val chip: MessageChip = messageContextChip(
      "mention",
      "m",
      "src/",
      record("""{"kind":"mention","contextId":"m","label":"src/","path":"src/"}"""),
    )
    assertEquals(MessageChipIcon.FOLDER, chip.icon)
  }

  @Test
  fun `a missing record is an unresolved chip with the link's label`() {
    val chip: MessageChip = messageContextChip("review-comment", "gone", "old selection", null)
    assertTrue(chip.unresolved)
    assertEquals("old selection", chip.label)
    assertEquals(MessageChipIcon.UNAVAILABLE, chip.icon)
    assertEquals(MessageChipKind.NEUTRAL, chip.kind)
  }

  @Test
  fun `a record of another kind than the link is unresolved too`() {
    val chip: MessageChip = messageContextChip(
      "mention",
      "x",
      "x",
      record("""{"kind":"skill","contextId":"x","name":"x"}"""),
    )
    assertTrue(chip.unresolved)
  }

  @Test
  fun `a skill record labels by its label then its name`() {
    val labelled: MessageChip = messageContextChip(
      "skill",
      "s",
      "s",
      record("""{"kind":"skill","contextId":"s","label":"KMP Targets","name":"kmp-targets"}"""),
    )
    assertEquals("KMP Targets", labelled.label)
    assertEquals("\$kmp-targets", labelled.tooltip)
    val bare: MessageChip = messageContextChip(
      "skill",
      "s",
      "s",
      record("""{"kind":"skill","contextId":"s","label":"","name":"kmp-targets"}"""),
    )
    assertEquals("kmp-targets", bare.label)
  }

  @Test
  fun `a file comment is a review comment chip with the file and line`() {
    val chip: MessageChip = messageContextChip(
      "review-comment",
      "c",
      "ROADMAP.md L25",
      record(
        """{"kind":"review-comment","contextId":"c","label":"ROADMAP.md L25",
          "sectionId":"file:ROADMAP.md","sectionTitle":"File comment","filePath":"docs/ROADMAP.md",
          "startIndex":24,"endIndex":24,"rangeLabel":"L25","text":"note","diff":"- x"}""",
      ),
    )
    assertEquals(MessageChipKind.REVIEW_COMMENT, chip.kind)
    assertEquals("ROADMAP.md L25", chip.label)
    assertEquals(MessageChipIcon.REVIEW_COMMENT, chip.icon)
    assertNull(chip.action)
  }

  @Test
  fun `a pull request summary takes the state's kind and opens its url`() {
    fun chip(state: String, draft: Boolean = false): MessageChip = messageContextChip(
      "review-comment",
      "p",
      "#41",
      record(
        """{"kind":"review-comment","contextId":"p","label":"#41",
          "sectionId":"pull-request:41","sectionTitle":"Pull request","filePath":"PR #41",
          "startIndex":0,"endIndex":0,"rangeLabel":"","text":"","diff":"",
          "pullRequest":{"number":41,"title":"Bump","url":"https://github.com/x/y/pull/41",
          "headBranch":"a","baseBranch":"b","state":"$state","isDraft":$draft}}""",
      ),
    )
    assertEquals(MessageChipKind.PR_MERGED, chip("merged").kind)
    assertEquals(MessageChipIcon.PULL_REQUEST_MERGED, chip("merged").icon)
    assertEquals(MessageChipKind.PR_OPEN, chip("open").kind)
    assertEquals(MessageChipKind.PR_DRAFT, chip("open", draft = true).kind)
    assertEquals(MessageChipIcon.PULL_REQUEST_DRAFT, chip("open", draft = true).icon)
    assertEquals(MessageChipKind.PR_CLOSED, chip("closed").kind)
    assertEquals("#41", chip("merged").label)
    assertEquals(MessageChipAction.OpenUrl("https://github.com/x/y/pull/41"), chip("merged").action)
  }

  @Test
  fun `an image record shows its size as the detail`() {
    val chip: MessageChip = messageContextChip(
      "image",
      "i",
      "shot.png",
      record(
        """{"kind":"image","contextId":"i","name":"shot.png","mimeType":"image/png",
          "sizeBytes":2048}""",
      ),
    )
    assertEquals(MessageChipKind.IMAGE, chip.kind)
    assertEquals("2 KB", chip.detail)
    assertEquals(MessageChipIcon.IMAGE, chip.icon)
  }

  @Test
  fun `a file record that is a video looks like one`() {
    val chip: MessageChip = messageContextChip(
      "file",
      "f",
      "clip.mp4",
      record(
        """{"kind":"file","contextId":"f","name":"clip.mp4","mimeType":"video/mp4",
          "sizeBytes":1}""",
      ),
    )
    assertEquals(MessageChipKind.VIDEO, chip.kind)
    assertEquals(MessageChipIcon.VIDEO, chip.icon)
  }

  @Test
  fun `a thread record opens the thread`() {
    val chip: MessageChip = messageContextChip(
      "thread",
      "t",
      "Fix CI",
      record(
        """{"kind":"thread","contextId":"t","title":"Fix CI","threadId":"thr_1",
          "environmentId":"env"}""",
      ),
    )
    assertEquals(MessageChipKind.THREAD, chip.kind)
    assertEquals(MessageChipAction.OpenThread("thr_1"), chip.action)
  }

  @Test
  fun `middle truncation keeps both ends of a long name`() {
    assertEquals("short.png", middleTruncateAttachmentName("short.png"))
    val long = "a-very-long-attachment-name-that-goes-on-and-on-forever.png"
    val truncated: String = middleTruncateAttachmentName(long)
    assertEquals(36, truncated.length)
    // 21 characters, an ellipsis, the last 14
    assertEquals("a-very-long-attachmen…on-forever.png", truncated)
  }

  @Test
  fun `inline skills match only known names at word boundaries`() {
    val skills: List<ServerProviderSkill> =
      listOf(skill("gh-fix-ci", "Gh Fix Ci"), skill("kmp-targets"))
    val text = "run \$gh-fix-ci then \$HOME and \$kmp-targets"
    val matches: List<InlineSkillMatch> = matchInlineSkills(text, skills)
    assertEquals(listOf("gh-fix-ci", "kmp-targets"), matches.map { it.skill.name })
    assertEquals("\$gh-fix-ci", text.substring(matches[0].start, matches[0].end))
    assertEquals("\$kmp-targets", text.substring(matches[1].start, matches[1].end))
  }

  @Test
  fun `inline skills skip money and glued tokens`() {
    val skills: List<ServerProviderSkill> = listOf(skill("5k"), skill("x"))
    assertTrue(matchInlineSkills("costs \$5k today", skills).isEmpty())
    assertTrue(matchInlineSkills("foo\$x bar", skills).isEmpty())
    assertEquals(1, matchInlineSkills("foo \$x", skills).size)
  }

  @Test
  fun `the skill chip shows the display name`() {
    assertEquals("Gh Fix Ci", skillChip(skill("gh-fix-ci", "Gh Fix Ci")).label)
    assertEquals("Kmp Targets", skillChip(skill("kmp-targets")).label)
    assertEquals("\$kmp-targets", skillChip(skill("kmp-targets")).tooltip)
  }

  @Test
  fun `a markdown file link parses relative and absolute paths with positions`() {
    assertEquals(FilePathPosition("ROADMAP.md"), parseMarkdownFileLink("ROADMAP.md"))
    assertEquals(FilePathPosition("src/Foo.kt", 12), parseMarkdownFileLink("src/Foo.kt:12"))
    assertEquals(FilePathPosition("src/Foo.kt", 12, 3), parseMarkdownFileLink("src/Foo.kt#L12C3"))
    assertEquals(FilePathPosition("./a/b.txt"), parseMarkdownFileLink("<./a/b.txt>"))
    assertEquals(FilePathPosition("/Users/x/a.kt"), parseMarkdownFileLink("/Users/x/a.kt"))
    assertEquals(FilePathPosition("/Users/x/a.kt"), parseMarkdownFileLink("file:///Users/x/a.kt"))
    assertEquals(FilePathPosition("Makefile"), parseMarkdownFileLink("Makefile"))
    assertEquals(FilePathPosition("C:\\x\\a.kt"), parseMarkdownFileLink("C:\\x\\a.kt"))
  }

  @Test
  fun `a markdown file link rejects urls fragments and words`() {
    assertNull(parseMarkdownFileLink("https://example.com/a.kt"))
    assertNull(parseMarkdownFileLink("#section"))
    assertNull(parseMarkdownFileLink("//share/a.kt"))
    assertNull(parseMarkdownFileLink("mailto:x@y.z"))
    assertNull(parseMarkdownFileLink("word"))
    assertNull(parseMarkdownFileLink(""))
    assertNull(parseMarkdownFileLink("t3-context://v1/mention/x"))
  }

  @Test
  fun `a label is the file link's when it names the same file`() {
    assertTrue(isMarkdownFileLinkLabel("ROADMAP.md", "ROADMAP.md"))
    assertTrue(isMarkdownFileLinkLabel("Foo.kt", "src/Foo.kt"))
    assertTrue(isMarkdownFileLinkLabel("Foo.kt:12", "src/Foo.kt:12"))
    assertTrue(isMarkdownFileLinkLabel("", "src/Foo.kt"))
    assertFalse(isMarkdownFileLinkLabel("the roadmap", "ROADMAP.md"))
    assertFalse(isMarkdownFileLinkLabel("Foo.kt:13", "src/Foo.kt:12"))
  }

  @Test
  fun `inline code is a path when it has a separator or a position and no code smell`() {
    assertEquals("src/Foo.kt", inlineCodeFilePathCandidate("src/Foo.kt"))
    assertEquals("Foo.kt:12", inlineCodeFilePathCandidate("Foo.kt:12"))
    assertEquals("./a", inlineCodeFilePathCandidate("./a"))
    assertEquals("/usr/bin/env", inlineCodeFilePathCandidate("/usr/bin/env"))
    assertNull(inlineCodeFilePathCandidate("Foo.kt"))
    assertNull(inlineCodeFilePathCandidate("example.com/x"))
    assertNull(inlineCodeFilePathCandidate("kotlinx-io/0.8.0"))
    assertNull(inlineCodeFilePathCandidate("patches/"))
    assertNull(inlineCodeFilePathCandidate("a b/c.kt"))
    assertNull(inlineCodeFilePathCandidate("src/main"))
    assertNull(inlineCodeFilePathCandidate(""))
  }

  @Test
  fun `the file link chip labels basename and position and opens the file there`() {
    val chip: MessageChip = fileLinkChip(FilePathPosition("src/Foo.kt", 12, 3))
    assertEquals("Foo.kt · L12:C3", chip.label)
    assertEquals(MessageChipAction.OpenFile("src/Foo.kt", 12), chip.action)
    assertEquals("src/Foo.kt", chip.iconPath)
    assertEquals("ROADMAP.md", fileLinkChip(FilePathPosition("ROADMAP.md")).label)
  }

  @Test
  fun `records are keyed by context id and uncited ones keep a label line`() {
    val context = io.github.xxfast.t3code.protocol.generated.MessageContext(
      version = 1,
      records = listOf(
        record("""{"contextId":"a","kind":"mention","label":"A","path":"A"}"""),
        record("""{"contextId":"b","kind":"mention","label":"B","path":"B"}"""),
      ),
    )
    assertEquals(setOf("a", "b"), messageContextRecords(context).keys)
    assertEquals(listOf("B"), uncitedContextLabels(context, "see [A](t3-context://v1/mention/a)"))
  }
}
