package io.github.xxfast.t3code.plugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthUnit
import org.jetbrains.skia.svg.SVGSVG

/**
 * [bytes] as an SVG drawn into a [side] pixel square, or null when skia can't parse them. Skiko's
 * SVG DOM is the renderer Compose Desktop uses for SVG resources; the platform has
 * `com.intellij.ui.svg.loadSvg` but it's internal API and the plugin verifier rejects it. An SVG
 * has no pixels of its own, so it's drawn at the size the caller needs.
 */
fun decodeSvg(bytes: ByteArray, side: Int): ImageBitmap? {
  val size: Float = side.toFloat()
  return SVGDOM(Data.makeFromBytes(bytes)).use { dom ->
    val root: SVGSVG = dom.root ?: return null
    Surface.makeRasterN32Premul(side, side).use { surface ->
      val natural: Pair<Float, Float>? = naturalSize(root)
      if (natural != null) {
        // An absolute width and height on the root is the viewport skia draws into, viewBox or
        // not, and the container size doesn't rescale it. So scale the canvas instead. An
        // `800px` icon otherwise lands outside a 64px raster and decodes to a blank bitmap.
        val scale: Float = size / maxOf(natural.first, natural.second)
        surface.canvas.scale(scale, scale)
        dom.setContainerSize(natural.first, natural.second)
      } else {
        dom.setContainerSize(size, size)
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
