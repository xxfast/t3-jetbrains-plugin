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
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthUnit
import org.jetbrains.skia.svg.SVGSVG

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

  /**
   * Skia for the raster formats; skiko's SVG DOM for the rest, the same renderer Compose Desktop
   * uses for SVG resources. The platform has `com.intellij.ui.svg.loadSvg` but it's internal API
   * and the plugin verifier rejects it. An SVG has no pixels of its own, so it's drawn at the
   * size a row needs.
   */
  private fun decode(bytes: ByteArray): ImageBitmap? {
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()?.let { return it }
    return runCatching { decodeSvg(bytes) }.getOrNull()
  }

  private fun decodeSvg(bytes: ByteArray): ImageBitmap? {
    val side: Int = RASTER_SIZE.toInt()
    return SVGDOM(Data.makeFromBytes(bytes)).use { dom ->
      val root: SVGSVG = dom.root ?: return null
      Surface.makeRasterN32Premul(side, side).use { surface ->
        val natural: Pair<Float, Float>? = naturalSize(root)
        if (natural != null) {
          // An absolute width and height on the root is the viewport skia draws into, viewBox or
          // not, and the container size doesn't rescale it. So scale the canvas instead. An
          // `800px` icon otherwise lands outside a 64px raster and decodes to a blank bitmap.
          val scale: Float = RASTER_SIZE / maxOf(natural.first, natural.second)
          surface.canvas.scale(scale, scale)
          dom.setContainerSize(natural.first, natural.second)
        } else {
          dom.setContainerSize(RASTER_SIZE, RASTER_SIZE)
        }
        dom.render(surface.canvas)
        surface.makeImageSnapshot().toComposeImageBitmap()
      }
    }
  }

  /** The SVG's own width and height, when they're absolute and positive. */
  private fun naturalSize(root: SVGSVG): Pair<Float, Float>? {
    val width = root.width
    val height = root.height
    val absolute = setOf(SVGLengthUnit.NUMBER, SVGLengthUnit.PX)
    if (width.unit !in absolute || height.unit !in absolute) return null
    if (width.value <= 0f || height.value <= 0f) return null
    return width.value to height.value
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(ProjectIcons::class.java)

    /** 16px rows on a 2x display. */
    private const val RASTER_SIZE: Float = 64f

    fun get(): ProjectIcons = service()
  }
}
