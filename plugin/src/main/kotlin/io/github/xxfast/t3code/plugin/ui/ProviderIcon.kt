package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.PathIconKey

/**
 * Upstream's `ProviderInstanceIcon` (apps/web/src/components/chat/ProviderInstanceIcon.tsx):
 * the driver's logo, else the display name's initials, at [size] and [alpha]; [muted] is its
 * `grayscale`. With [badge] the initials sit in a small disc at the bottom right, in the
 * instance's accent colour when it has one. Antigravity's logo is a bitmap upstream, not ported.
 */
@Composable
fun ProviderIcon(
  provider: ServerProvider,
  size: Dp,
  alpha: Float = 1f,
  muted: Boolean = false,
  badge: Boolean = false,
) {
  val initials: String = providerInitials(resolveProviderInstanceDisplayName(provider))
  val normal: Color = JewelTheme.globalColors.text.normal
  Box(contentAlignment = Alignment.Center) {
    Box(Modifier.alpha(alpha), contentAlignment = Alignment.Center) {
      when (provider.driver) {
        "codex" -> Logo("openai", normal, size) // upstream's `fill-current`
        "claudeAgent" -> Logo("claude", if (muted) normal else CLAUDE, size)
        else -> Text(
          text = initials,
          fontSize = 8.sp,
          fontWeight = FontWeight.SemiBold,
          lineHeight = 8.sp,
          color = normal,
        )
      }
    }
    if (badge) {
      val accent: Color? = provider.accentColor?.let(::parseHexColor)
      val panel: Color = JewelTheme.globalColors.panelBackground
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .offset(x = 3.dp, y = 3.dp)
          .heightIn(min = 12.dp)
          .widthIn(min = 12.dp)
          .clip(CircleShape)
          .background(accent ?: panel)
          .border(1.dp, panel, CircleShape)
          .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = initials,
          fontSize = 7.sp,
          lineHeight = 7.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (accent != null) Color.White else JewelTheme.globalColors.text.info,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun Logo(name: String, tint: Color, size: Dp) {
  Icon(
    key = PathIconKey("icons/providers/$name.svg", ProjectIconMarker::class.java),
    contentDescription = null,
    modifier = Modifier.size(size),
    iconClass = ProjectIconMarker::class.java,
    colorFilter = ColorFilter.tint(tint),
  )
}

/** Upstream's `fill-[#d97757]` on the Claude mark. */
private val CLAUDE: Color = Color(0xFFD97757)

/** `#rgb` or `#rrggbb`, else null. */
fun parseHexColor(value: String): Color? {
  val hex: String = value.trim().removePrefix("#")
  val rgb: String = when (hex.length) {
    3 -> hex.map { "$it$it" }.joinToString("")
    6 -> hex
    else -> return null
  }
  val packed: Long = rgb.toLongOrNull(16) ?: return null
  return Color(0xFF000000L or packed)
}
