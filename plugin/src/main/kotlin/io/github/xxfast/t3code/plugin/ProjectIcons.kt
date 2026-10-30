package io.github.xxfast.t3code.plugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.protocol.Project
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
 * Project favicons by project id, fetched once per project and favicon path. The list only
 * reads [favicons]; the tool window calls [ensure] whenever the project list changes.
 */
@Service(Service.Level.APP)
class ProjectIcons(private val scope: CoroutineScope) {
  private val _favicons: MutableStateFlow<Map<String, ImageBitmap>> = MutableStateFlow(emptyMap())
  private val requested: MutableSet<String> = HashSet()

  val favicons: StateFlow<Map<String, ImageBitmap>> = _favicons.asStateFlow()

  @Synchronized
  fun ensure(projects: List<Project>) {
    for (project in projects) {
      // An icon override never shows the favicon, so don't fetch it
      if (project.projectIcon != null) continue
      val key = "${project.id}:${project.faviconPath}"
      if (!requested.add(key)) continue
      scope.launch(Dispatchers.IO) {
        val bytes: ByteArray = try {
          T3.get().client.projectFavicon(project.workspaceRoot, project.faviconPath)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          LOG.warn("Favicon for ${project.title}: ${e.message}", e)
          return@launch
        } ?: run {
          LOG.debug("Favicon for ${project.title}: none on the server")
          return@launch
        }
        val bitmap: ImageBitmap = decode(bytes) ?: run {
          LOG.warn("Favicon for ${project.title}: ${bytes.size} bytes nobody can decode")
          return@launch
        }
        LOG.debug("Favicon for ${project.title}: ${bitmap.width}x${bitmap.height}")
        _favicons.update { it + (project.id to bitmap) }
      }
    }
  }

  /** Skia for the raster formats; [decodeSvg] for the rest, drawn at the size a row needs. */
  private fun decode(bytes: ByteArray): ImageBitmap? {
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()?.let { return it }
    return runCatching { decodeSvg(bytes, RASTER_SIZE) }.getOrNull()
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(ProjectIcons::class.java)

    /** 16px rows on a 2x display. */
    private const val RASTER_SIZE: Int = 64

    fun get(): ProjectIcons = service()
  }
}
