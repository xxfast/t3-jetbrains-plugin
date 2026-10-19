package io.github.xxfast.t3code.plugin.ui

import java.awt.Image
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncodePngTest {
  @Test
  fun aBufferedImageEncodesAtItsSize() {
    val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
    val decoded: BufferedImage = decode(encodePng(image))
    assertEquals(3 to 2, decoded.width to decoded.height)
  }

  @Test
  fun aToolkitImageIsLoadedBeforeEncoding() {
    // A Toolkit image loads lazily: its size is -1 until something waits for it
    val png: ByteArray = encodePng(BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB))
    val lazy: Image = Toolkit.getDefaultToolkit().createImage(png)
    val decoded: BufferedImage = decode(encodePng(lazy))
    assertEquals(5 to 4, decoded.width to decoded.height)
  }

  @Test
  fun anImageThatNeverLoadsIsRefused() {
    val broken: Image = Toolkit.getDefaultToolkit().createImage(byteArrayOf(1, 2, 3))
    assertFailsWith<IllegalArgumentException> { encodePng(broken) }
  }

  private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))
}
