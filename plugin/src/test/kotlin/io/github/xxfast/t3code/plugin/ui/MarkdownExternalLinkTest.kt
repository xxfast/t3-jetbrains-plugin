package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.junit.jupiter.api.Test

class MarkdownExternalLinkTest {
  private val processor: MarkdownProcessor =
    MarkdownProcessor(listOf(AutolinkProcessorExtension, ExternalLinkFaviconProcessorExtension))

  private fun inlines(markdown: String): List<InlineMarkdown> {
    val paragraph: MarkdownBlock.Paragraph =
      assertIs(processor.processMarkdownDocument(markdown).single())
    return paragraph.inlineContent
  }

  private fun link(markdown: String): InlineMarkdown.Link = assertIs(inlines(markdown).single())

  @Test
  fun `host of an external link`() {
    assertEquals("example.com", resolveExternalWebLinkHost("https://example.com/path?q=1"))
    assertEquals("example.com", resolveExternalWebLinkHost("//example.com/path"))
    assertEquals("github.com", resolveExternalWebLinkHost("http://GitHub.com/x"))
    assertNull(resolveExternalWebLinkHost("file:///tmp/private"))
    assertNull(resolveExternalWebLinkHost("mailto:someone@example.com"))
    assertNull(resolveExternalWebLinkHost("#fragment"))
    assertNull(resolveExternalWebLinkHost("src/Main.kt"))
    assertNull(resolveExternalWebLinkHost(null))
  }

  /** Upstream's `favicon.test.ts`: only the public host name and the size go out, never the port. */
  @Test
  fun `favicon url sends only the public hostname and requested size`() {
    assertEquals(
      "https://www.google.com/s2/favicons?domain=github.com&sz=64",
      faviconUrlForOrigin("https://github.com:8443/pingdotgg/t3code?private=query", 64),
    )
    assertEquals(
      "https://www.google.com/s2/favicons?domain=example.com&sz=32",
      faviconUrlForOrigin("https://example.com"),
    )
    for (origin in listOf(null, "invalid URL", "file:///tmp/private")) {
      assertNull(faviconUrlForOrigin(origin), origin)
    }
    for (origin in listOf("http://192.168.1.10:8080", "http://localhost:3000", "http://home.arpa")) {
      assertNull(faviconUrlForOrigin(origin), origin)
    }
  }

  @Test
  fun `an external link with text gets a favicon image first`() {
    val link: InlineMarkdown.Link = link("[Link](https://example.com)")
    val image: InlineMarkdown.Image = assertIs(link.inlineContent.first())
    assertEquals("t3-favicon://example.com", image.source)
    assertEquals("example.com", linkFaviconHost(image.source))
    val text: InlineMarkdown.Text = assertIs(link.inlineContent[1])
    assertEquals("Link", text.content)
  }

  @Test
  fun `a bare url autolinks with a favicon`() {
    val link: InlineMarkdown.Link = link("https://example.com/docs")
    assertEquals("t3-favicon://example.com", assertIs<InlineMarkdown.Image>(link.inlineContent.first()).source)
  }

  @Test
  fun `a private host gets the image too, the renderer draws the globe`() {
    // Upstream's `<a>` always has `MarkdownExternalLinkContent` for an http(s) host; it's the
    // favicon component that picks the globe when `faviconUrlForOrigin` says no
    val link: InlineMarkdown.Link = link("[Home](http://192.168.1.10:8080)")
    assertEquals("t3-favicon://192.168.1.10", assertIs<InlineMarkdown.Image>(link.inlineContent.first()).source)
  }

  @Test
  fun `a file link and a link without text get no favicon`() {
    assertIs<InlineMarkdown.Text>(link("[Main](src/Main.kt)").inlineContent.single())
    val imageOnly: InlineMarkdown.Link = link("[![alt](https://example.com/a.png)](https://example.com)")
    assertNull(imageOnly.inlineContent.firstOrNull { it is InlineMarkdown.Image && linkFaviconHost(it.source) != null })
  }

  @Test
  fun `a real image reads as its markdown`() {
    val text: InlineMarkdown.Text = assertIs(inlines("![Chart](https://example.com/chart.png \"Title\")").single())
    assertEquals("![Chart](https://example.com/chart.png \"Title\")", text.content)
  }
}
