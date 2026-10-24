package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Image as CommonmarkImage
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text as CommonmarkText
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.extensions.ImageRendererExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// Upstream puts the site's favicon in front of every external link in a message
// (`apps/web/src/components/chat/MarkdownExternalLink.tsx`, used by `ChatMarkdown.tsx`'s `a`
// renderer). Jewel's text can only carry inline content for images, so the parser gets a
// post-processor that gives each qualifying link a synthetic image child, and the renderer an
// image extension that draws that image as the favicon.

/** `resolveExternalWebLinkHost` (`chat/externalLinkContextMenu.ts`): the host of an http(s) link. */
fun resolveExternalWebLinkHost(href: String?): String? {
  if (href.isNullOrEmpty()) return null
  val url: URI = runCatching { URI(if (href.startsWith("//")) "https:$href" else href) }
    .getOrNull() ?: return null
  if (url.scheme != "http" && url.scheme != "https") return null
  return url.host?.lowercase()?.takeIf { it.isNotEmpty() }
}

/**
 * `faviconUrlForOrigin` (`packages/shared/src/favicon.ts`): Google's favicon service for the
 * host, only when the host is public. Only the host name goes out, never the port or path.
 */
fun faviconUrlForOrigin(rawUrl: String?, size: Int = 32): String? {
  if (rawUrl.isNullOrEmpty()) return null
  val url: URI = runCatching { URI(rawUrl) }.getOrNull() ?: return null
  val hostname: String = url.host?.takeIf { it.isNotEmpty() } ?: return null
  if (url.scheme != "http" && url.scheme != "https") return null
  if (!isPublicFaviconHost(hostname)) return null
  return "https://www.google.com/s2/favicons?domain=${URLEncoder.encode(hostname, Charsets.UTF_8)}&sz=$size"
}

/** `isGitHubHost` (`MarkdownExternalLink.tsx`): GitHub gets its brand icon, not a favicon. */
fun isGitHubHost(host: String): Boolean {
  val hostname: String = host.lowercase()
  return hostname == "github.com" || hostname.endsWith(".github.com")
}

/** The scheme of the synthetic image: `t3-favicon://<host>`. */
private const val FAVICON_SCHEME: String = "t3-favicon://"

fun linkFaviconHost(source: String): String? =
  if (source.startsWith(FAVICON_SCHEME)) source.removePrefix(FAVICON_SCHEME) else null

/**
 * `hastHasText`: the node or one of its descendants has non-blank text. Inline code counts, an
 * image's alt doesn't: upstream's `<img>` has no text child.
 */
private fun hasText(node: Node): Boolean {
  if (node is CommonmarkImage) return false
  val literal: String? = when (node) {
    is CommonmarkText -> node.literal
    is Code -> node.literal
    else -> null
  }
  if (literal != null && literal.isNotBlank()) return true
  var child: Node? = node.firstChild
  while (child != null) {
    if (hasText(child)) return true
    child = child.next
  }
  return false
}

/**
 * Gives every external link with text a `t3-favicon://host` image as its first child, which
 * [ExternalLinkFaviconRendererExtension] draws. Real images become their markdown text, which is
 * what Jewel showed for them before an image renderer existed; inline images aren't a thing yet.
 */
object ExternalLinkFaviconProcessorExtension : MarkdownProcessorExtension {
  override val parserExtension: Parser.ParserExtension = Parser.ParserExtension { builder ->
    builder.postProcessor(FaviconPostProcessor)
  }

  private object FaviconPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
      node.accept(
        object : AbstractVisitor() {
          override fun visit(link: Link) {
            val host: String? = resolveExternalWebLinkHost(link.destination)
            if (host != null && hasText(link)) {
              link.prependChild(CommonmarkImage(FAVICON_SCHEME + host, null))
            }
            visitChildren(link)
          }

          override fun visit(image: CommonmarkImage) {
            // Chips (`MessageChips.kt`) are synthetic images too, drawn by their own renderer
            if (linkFaviconHost(image.destination) != null) return
            if (messageChipSource(image.destination) != null) return
            // `renderImage`'s fallback text, so a real image reads as it did before
            val alt: String = textOf(image)
            val title: String = image.title?.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
            image.insertBefore(CommonmarkText("![$alt](${image.destination}$title)"))
            image.unlink()
          }
        },
      )
      return node
    }

    private fun textOf(node: Node): String = buildString {
      var child: Node? = node.firstChild
      while (child != null) {
        when (child) {
          is CommonmarkText -> append(child.literal)
          is Code -> append(child.literal)
          else -> append(textOf(child))
        }
        child = child.next
      }
    }
  }
}

/** Where the favicons come from: absent while loading, null when the fetch failed. */
interface LinkFaviconSource {
  val favicons: StateFlow<Map<String, ImageBitmap?>>

  fun ensure(host: String)
}

/** No fetching at all, every favicon is the globe. For callers without a network. */
object NoLinkFavicons : LinkFaviconSource {
  override val favicons: StateFlow<Map<String, ImageBitmap?>> = MutableStateFlow(emptyMap())

  override fun ensure(host: String) = Unit
}

/** Draws the `t3-favicon://host` images [ExternalLinkFaviconProcessorExtension] plants. */
class ExternalLinkFaviconRendererExtension(
  private val source: LinkFaviconSource,
) : MarkdownRendererExtension {
  override val imageRendererExtension: ImageRendererExtension = object : ImageRendererExtension {
    @Composable
    override fun renderImageContent(image: InlineMarkdown.Image): InlineTextContent {
      val host: String = linkFaviconHost(image.source) ?: ""
      // `size-[14px]` with `ms-[0.25em] me-[0.2em]` in a 14px font: one em and its margins
      return InlineTextContent(
        Placeholder(width = 1.45.em, height = 1.em, PlaceholderVerticalAlign.TextCenter),
      ) {
        MarkdownLinkFavicon(host, source)
      }
    }
  }
}

/** `MarkdownLinkFavicon`: GitHub's brand icon, the fetched favicon, or a globe when neither. */
@Composable
private fun MarkdownLinkFavicon(host: String, source: LinkFaviconSource) {
  val brandIcon: Boolean = isGitHubHost(host)
  val faviconUrl: String? = if (brandIcon) null else faviconUrlForOrigin("https://$host")
  val favicons: State<Map<String, ImageBitmap?>> = source.favicons.collectAsState()
  LaunchedEffect(host, faviconUrl) { if (faviconUrl != null) source.ensure(host) }
  Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
    Spacer(Modifier.weight(0.25f))
    Box(Modifier.fillMaxHeight().aspectRatio(1f)) {
      val favicon: ImageBitmap? = favicons.value[host]
      when {
        brandIcon -> Icon(
          key = AllIconsKeys.Vcs.Vendors.Github,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
        )
        faviconUrl == null || (host in favicons.value && favicon == null) -> Icon(
          key = AllIconsKeys.General.Web,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
        )
        favicon != null -> Image(
          bitmap = favicon,
          contentDescription = null,
          modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)),
        )
        // Else still loading: upstream's `<img>` is blank until it arrives
      }
    }
    Spacer(Modifier.weight(0.2f))
  }
}
