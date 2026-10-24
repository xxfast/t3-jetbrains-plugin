package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.WithInlineMarkdown
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.junit.jupiter.api.Test

class MessageChipsProcessorTest {
  private val skills: List<ServerProviderSkill> = listOf(
    ServerProviderSkill(name = "gh-fix-ci", path = "/s", enabled = true, displayName = "Gh Fix Ci"),
  )

  private fun inlines(markdown: String): List<InlineMarkdown> {
    val processor = MarkdownProcessor(
      listOf(MessageChipsProcessorExtension(skills), ExternalLinkFaviconProcessorExtension),
    )
    val blocks: List<MarkdownBlock> = processor.processMarkdownDocument(markdown)
    return (blocks.single() as MarkdownBlock.Paragraph).inlineContent
  }

  // Nested too, as Jewel's `getImages` collects them: a favicon sits inside its link
  private fun images(markdown: String): List<InlineMarkdown.Image> {
    fun collect(items: List<InlineMarkdown>): List<InlineMarkdown.Image> = items.flatMap { item ->
      when (item) {
        is InlineMarkdown.Image -> listOf(item)
        is WithInlineMarkdown -> collect(item.inlineContent)
        else -> emptyList()
      }
    }
    return collect(inlines(markdown))
  }

  @Test
  fun `an inline code path becomes a chip image`() {
    val image: InlineMarkdown.Image = images("see `src/Foo.kt:12` here").single()
    assertEquals(
      MessageChipSource.File(FilePathPosition("src/Foo.kt", 12)),
      messageChipSource(image.source),
    )
  }

  @Test
  fun `a context link becomes a chip image with its label`() {
    val image: InlineMarkdown.Image =
      images("read [ROADMAP.md](t3-context://v1/mention/mention_ROADMAP-md) first").single()
    assertEquals(
      MessageChipSource.Context("mention", "mention_ROADMAP-md", "ROADMAP.md"),
      messageChipSource(image.source),
    )
  }

  @Test
  fun `a context image keeps its chip source past the favicon processor`() {
    val image: InlineMarkdown.Image =
      images("![shot.png](t3-context://v1/image/image_1)").single()
    assertEquals(
      MessageChipSource.Context("image", "image_1", "shot.png"),
      messageChipSource(image.source),
    )
  }

  @Test
  fun `a known skill token becomes a chip, an unknown one stays text`() {
    val content: List<InlineMarkdown> = inlines("run \$gh-fix-ci then \$HOME")
    val image: InlineMarkdown.Image = content.filterIsInstance<InlineMarkdown.Image>().single()
    assertEquals(MessageChipSource.Skill("gh-fix-ci", "Gh Fix Ci"), messageChipSource(image.source))
    val text: String =
      content.filterIsInstance<InlineMarkdown.Text>().joinToString("") { it.content }
    assertTrue("\$HOME" in text)
    assertTrue("gh-fix-ci" !in text)
  }

  @Test
  fun `a file link with another label keeps the text and adds the chip`() {
    val content: List<InlineMarkdown> = inlines("[the readme](README.md)")
    assertTrue(content.filterIsInstance<InlineMarkdown.Text>().any { "the readme" in it.content })
    val image: InlineMarkdown.Image = content.filterIsInstance<InlineMarkdown.Image>().single()
    assertEquals(
      MessageChipSource.File(FilePathPosition("README.md")),
      messageChipSource(image.source),
    )
  }

  @Test
  fun `an external link keeps its favicon image`() {
    val image: InlineMarkdown.Image = images("[T3](https://github.com/pingdotgg/t3code)").single()
    assertEquals("github.com", linkFaviconHost(image.source))
  }
}
