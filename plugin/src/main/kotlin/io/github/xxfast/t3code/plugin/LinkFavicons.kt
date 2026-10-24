package io.github.xxfast.t3code.plugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.plugin.ui.LinkFaviconSource
import io.github.xxfast.t3code.plugin.ui.faviconUrlForOrigin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

/**
 * Favicons for the external links in messages, by host, fetched once each from the same service
 * upstream's `MarkdownLinkFavicon` loads its `<img>` from. A host that fails maps to null and
 * stays failed, as upstream's `failedFaviconHosts` does. The private-host check happens in
 * [faviconUrlForOrigin], so an internal name never gets here.
 */
@Service(Service.Level.APP)
class LinkFavicons(private val scope: CoroutineScope) : LinkFaviconSource {
  private val _favicons: MutableStateFlow<Map<String, ImageBitmap?>> = MutableStateFlow(emptyMap())
  private val requested: MutableSet<String> = HashSet()
  private val http: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  override val favicons: StateFlow<Map<String, ImageBitmap?>> = _favicons.asStateFlow()

  @Synchronized
  override fun ensure(host: String) {
    if (!requested.add(host)) return
    val url: String = faviconUrlForOrigin("https://$host") ?: return
    scope.launch(Dispatchers.IO) {
      val bitmap: ImageBitmap? = try {
        val request: HttpRequest =
          HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
        val response: HttpResponse<ByteArray> =
          http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() == 200) {
          Image.makeFromEncoded(response.body()).toComposeImageBitmap()
        } else {
          LOG.debug("Favicon for $host: HTTP ${response.statusCode()}")
          null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.debug("Favicon for $host: ${e.message}")
        null
      }
      _favicons.update { it + (host to bitmap) }
    }
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(LinkFavicons::class.java)

    fun get(): LinkFavicons = service()
  }
}
