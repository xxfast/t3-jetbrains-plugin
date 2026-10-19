package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.generated.StoredProjectIcon
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.typography

// Mirrors upstream's ProjectFavicon: an icon override wins, then the favicon the server serves,
// then a monogram from the title. Lucide overrides get a folder glyph in the chosen colour, the
// plugin doesn't ship the whole lucide set.

@Composable
fun ProjectIcon(
  project: Project,
  favicon: ImageBitmap?,
  size: Dp = 16.dp,
  muted: Boolean = false, // upstream's `grayscale` on rows that aren't open or hovered
) {
  val dark: Boolean = JewelTheme.isDark
  // `muted` is upstream's `grayscale`: the image loses its saturation, the tints go grey
  fun tone(color: Color): Color = if (muted) color.greyscale() else color
  when (val icon: StoredProjectIcon? = project.projectIcon) {
    is StoredProjectIcon.Monogram ->
      Monogram(icon.text, tone(projectIconColor(icon.color, dark)), size)
    is StoredProjectIcon.Emoji -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
      Text(icon.emoji, style = TextStyle(fontSize = (size.value * 0.8f).sp))
    }

    is StoredProjectIcon.Lucide ->
      Glyph("folder-code", tone(projectIconColor(icon.color, dark)), size)
    else -> if (favicon != null) {
      Image(
        bitmap = favicon,
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(25)),
        colorFilter = if (muted) GREYSCALE else null,
      )
    } else {
      val color: Color = tone(projectIconColor(projectColorName(project.title), dark))
      Monogram(projectMonogram(project.title), color, size)
    }
  }
}

/** The monogram a project with only a title gets, for places that don't have the project. */
@Composable
fun TitleMonogram(title: String, size: Dp = 16.dp) {
  val color: Color = projectIconColor(projectColorName(title), JewelTheme.isDark)
  Monogram(projectMonogram(title), color, size)
}

/** Two bold mono glyphs on a 14% tint of their own colour, corners rounded by a quarter. */
@Composable
fun Monogram(text: String, color: Color, size: Dp = 16.dp) {
  Box(
    modifier = Modifier.size(size).background(color.copy(alpha = 0.14f), RoundedCornerShape(25)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = color,
      style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = (size.value * 0.52f).sp,
        letterSpacing = (-0.5).sp,
      ),
      maxLines = 1,
      softWrap = false,
    )
  }
}

/** One of the lucide glyphs in `resources/icons/lucide`, tinted. */
@Composable
fun Glyph(name: String, tint: Color, size: Dp = 14.dp) {
  Icon(
    key = PathIconKey("icons/lucide/$name.svg", ProjectIconMarker::class.java),
    contentDescription = null,
    modifier = Modifier.size(size),
    iconClass = ProjectIconMarker::class.java,
    colorFilter = ColorFilter.tint(tint),
  )
}

/** The pull request badge on a row: the state's glyph and the PR number, in the state's tone. */
@Composable
fun PullRequestBadgeView(badge: PullRequestBadge) {
  val dark: Boolean = JewelTheme.isDark
  val tone: Color = when (badge.state) {
    "open" -> if (dark) Color(0xFF6EE7B7) else Color(0xFF059669)
    "draft" -> if (dark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    "closed" -> if (dark) Color(0xFFFCA5A5) else Color(0xFFDC2626)
    "merged" -> if (dark) Color(0xFFC4B5FD) else Color(0xFF7C3AED)
    else -> JewelTheme.globalColors.text.info
  }
  val glyph: String = when (badge.state) {
    "draft" -> "git-pull-request-draft"
    "closed" -> "git-pull-request-closed"
    "merged" -> "git-merge"
    "stack" -> "layers"
    else -> "git-pull-request-arrow"
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph(glyph, tone, 13.dp)
    Text(badge.text, color = tone, style = JewelTheme.typography.small, maxLines = 1)
  }
}

private val GREYSCALE: ColorFilter =
  ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** The colour's luminance as a grey, what a CSS `grayscale()` does to it. */
private fun Color.greyscale(): Color {
  val l: Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
  return Color(l, l, l, alpha)
}

/** Anchors the plugin's class loader for [PathIconKey]. */
internal object ProjectIconMarker
