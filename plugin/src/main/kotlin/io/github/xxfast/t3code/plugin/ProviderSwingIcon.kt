package io.github.xxfast.t3code.plugin

import androidx.compose.ui.graphics.toArgb
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.TextIcon
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import io.github.xxfast.t3code.plugin.ui.BADGE_INITIALS_SIZE
import io.github.xxfast.t3code.plugin.ui.BADGE_OFFSET
import io.github.xxfast.t3code.plugin.ui.BADGE_SIZE
import io.github.xxfast.t3code.plugin.ui.INITIALS_SIZE
import io.github.xxfast.t3code.plugin.ui.ProviderLogo
import io.github.xxfast.t3code.plugin.ui.parseHexColor
import io.github.xxfast.t3code.plugin.ui.providerInitials
import io.github.xxfast.t3code.plugin.ui.providerLogo
import io.github.xxfast.t3code.plugin.ui.resolveProviderInstanceDisplayName
import io.github.xxfast.t3code.plugin.ui.showInstanceBadge
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.max

/**
 * The Swing side of `ProviderIcon`: the same logo, brand colour, initials and badge, the badge
 * when [showInstanceBadge] says [provider] needs telling apart from the rest of [providers]. Its
 * ring is [background], what the icon sits on.
 */
fun providerSwingIcon(
  provider: ServerProvider,
  providers: Collection<ServerProvider>,
  background: Color,
  sizeUnscaled: Int = 16,
): Icon {
  val size: Int = JBUIScale.scale(sizeUnscaled)
  val initials: String = providerInitials(resolveProviderInstanceDisplayName(provider))
  val logo: ProviderLogo? = providerLogo(provider.driver)
  val icon: Icon = if (logo != null) {
    val image: Icon = IconLoader.getIcon("/${logo.path}", TintedIcon::class.java)
    val scaled: Icon = IconUtil.scale(image, null, size.toFloat() / image.iconWidth)
    if (!logo.tinted) {
      scaled
    } else {
      val tint: Color = logo.brand?.let { Color(it.toArgb(), true) } ?: UIUtil.getLabelForeground()
      TintedIcon(scaled, tint)
    }
  } else {
    // A `TextIcon` measures nothing until it has a font
    val text: TextIcon = TextIcon(initials, UIUtil.getLabelForeground(), null, 0)
      .apply { setFont(JBFont.label().deriveFont(Font.BOLD, JBUIScale.scale(INITIALS_SIZE))) }
    IconUtil.toSize(text, size, size)
  }
  val badge: Badge? = if (showInstanceBadge(provider, providers)) {
    val accent: Color? = provider.accentColor?.let(::parseHexColor)
      ?.let { Color(it.toArgb(), true) }
    Badge(initials, accent, background)
  } else {
    null
  }
  return BadgedIcon(icon, badge)
}

/**
 * Compose's `ColorFilter.tint` for Swing: [tint] under [icon]'s alpha. `IconUtil.colorize`
 * keeps the source's brightness, so the black logos stayed black whatever the tint.
 */
private class TintedIcon(private val icon: Icon, private val tint: Color) : Icon {
  override fun getIconWidth(): Int = icon.iconWidth

  override fun getIconHeight(): Int = icon.iconHeight

  override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
    val image: BufferedImage =
      ImageUtil.createImage(g, iconWidth, iconHeight, BufferedImage.TYPE_INT_ARGB)
    val buffer: Graphics2D = image.createGraphics()
    icon.paintIcon(c, buffer, 0, 0)
    buffer.composite = AlphaComposite.SrcIn
    buffer.color = tint
    buffer.fillRect(0, 0, iconWidth, iconHeight)
    buffer.dispose()
    UIUtil.drawImage(g, image, x, y, null)
  }
}

/** `ProviderIcon`'s badge: [initials] on [accent], ringed in the [background] it sits on. */
private class Badge(val initials: String, val accent: Color?, val background: Color)

/**
 * [icon] with `ProviderIcon`'s badge hanging off its bottom right. Every icon is as wide as a
 * badged one, so what follows lines up; the badge's drop below the icon is the host's padding.
 */
private class BadgedIcon(private val icon: Icon, private val badge: Badge?) : Icon {
  private val offset: Int = JBUIScale.scale(BADGE_OFFSET)

  override fun getIconWidth(): Int = icon.iconWidth + offset

  override fun getIconHeight(): Int = icon.iconHeight

  override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
    icon.paintIcon(c, g, x, y)
    if (badge == null) return
    val g2: Graphics2D = g.create() as Graphics2D
    try {
      GraphicsUtil.setupAntialiasing(g2)
      GraphicsUtil.setupAAPainting(g2)
      g2.font = JBFont.label().deriveFont(Font.BOLD, JBUIScale.scale(BADGE_INITIALS_SIZE))
      val metrics: FontMetrics = g2.fontMetrics
      val height: Int = JBUIScale.scale(BADGE_SIZE)
      val width: Int = max(height, metrics.stringWidth(badge.initials) + JBUIScale.scale(4))
      val left: Int = x + icon.iconWidth + offset - width
      val top: Int = y + icon.iconHeight + offset - height
      val ring: Int = JBUIScale.scale(1)
      g2.color = badge.background
      g2.fillRoundRect(left, top, width, height, height, height)
      g2.color = badge.accent ?: badge.background
      g2.fillRoundRect(
        left + ring,
        top + ring,
        width - 2 * ring,
        height - 2 * ring,
        height - 2 * ring,
        height - 2 * ring,
      )
      g2.color = if (badge.accent != null) Color.WHITE else INFO_FOREGROUND
      val textX: Int = left + (width - metrics.stringWidth(badge.initials)) / 2
      val textY: Int = top + (height - metrics.ascent - metrics.descent) / 2 + metrics.ascent
      g2.drawString(badge.initials, textX, textY)
    } finally {
      g2.dispose()
    }
  }
}

/** Jewel's `text.info`, what the Compose badge's initials use without an accent. */
private val INFO_FOREGROUND: Color =
  JBColor.namedColor("Component.infoForeground", UIUtil.getContextHelpForeground())
