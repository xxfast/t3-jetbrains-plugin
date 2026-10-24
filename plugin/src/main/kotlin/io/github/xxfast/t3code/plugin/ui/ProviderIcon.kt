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
import androidx.compose.ui.graphics.ColorMatrix
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
 * instance's accent colour when it has one. A logo with its own colours (a provider package's,
 * Antigravity's bitmap) isn't tinted; muted turns it grey, as upstream's `grayscale`.
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
  val logo: ProviderLogo? = providerLogo(provider.driver)
  Box(contentAlignment = Alignment.Center) {
    Box(Modifier.alpha(alpha), contentAlignment = Alignment.Center) {
      if (logo != null) {
        Logo(logo, tint = logo.brand?.takeUnless { muted } ?: normal, muted = muted, size = size)
      } else {
        Text(
          text = initials,
          fontSize = INITIALS_SIZE.sp,
          fontWeight = FontWeight.SemiBold,
          lineHeight = INITIALS_SIZE.sp,
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
          .offset(x = BADGE_OFFSET.dp, y = BADGE_OFFSET.dp)
          .heightIn(min = BADGE_SIZE.dp)
          .widthIn(min = BADGE_SIZE.dp)
          .clip(CircleShape)
          .background(accent ?: panel)
          .border(1.dp, panel, CircleShape)
          .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = initials,
          fontSize = BADGE_INITIALS_SIZE.sp,
          lineHeight = BADGE_INITIALS_SIZE.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (accent != null) Color.White else JewelTheme.globalColors.text.info,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun Logo(logo: ProviderLogo, tint: Color, muted: Boolean, size: Dp) {
  val filter: ColorFilter? = when {
    logo.tinted -> ColorFilter.tint(tint)
    muted -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    else -> null
  }
  Icon(
    key = PathIconKey(logo.path, ProjectIconMarker::class.java),
    contentDescription = null,
    modifier = Modifier.size(size),
    iconClass = ProjectIconMarker::class.java,
    colorFilter = filter,
  )
}

/**
 * A driver's logo under `icons/providers/` and its brand colour, null when it follows the text.
 * An untinted logo is drawn in its own colours, a PNG when [bitmap], else an SVG with a `_dark`
 * twin the theme picks. Shared with the Swing icons so both draw the same one.
 */
class ProviderLogo(
  val name: String,
  val brand: Color?,
  val bitmap: Boolean = false,
  val tinted: Boolean = !bitmap,
) {
  val path: String = "icons/providers/$name.${if (bitmap) "png" else "svg"}"
}

/**
 * `ProviderInstanceIcon`'s precedence (apps/web/src/components/chat/ProviderInstanceIcon.tsx):
 * the provider package's `icon` first, then `PROVIDER_ICON_BY_PROVIDER` and
 * `PROVIDER_TEXT_COLOR_BY_PROVIDER`. A package icon is its `client.ts` paths with the light and
 * dark fills baked into `<driver>.svg` and `<driver>_dark.svg`. The ACP registry's per-agent
 * icons come from a CDN upstream; here every agent gets the registry's own mark, upstream's
 * fallback.
 */
fun providerLogo(driver: String): ProviderLogo? = when (driver) {
  in PACKAGE_ICONS -> ProviderLogo(driver, brand = null, tinted = false)
  "codex" -> ProviderLogo("openai", brand = null) // upstream's `text-black dark:text-white`
  "claudeAgent" -> ProviderLogo("claude", brand = CLAUDE)
  // A 128 px PNG in an `<image>` upstream (`AntigravityIcon`, components/Icons.tsx), not tinted
  "antigravity" -> ProviderLogo("antigravity", brand = null, bitmap = true)
  else -> null
}

/** The drivers whose provider package (`packages/provider-<name>/src/client.ts`) has an `icon`. */
private val PACKAGE_ICONS: Set<String> =
  setOf("grok", "cursor", "opencode", "pi", "muse", "acpRegistry")

/** Upstream's `text-3xs`, the initials when a driver has no logo. */
const val INITIALS_SIZE: Float = 8f

/** The badge: a disc at least this wide and tall, hanging this far off the bottom right. */
const val BADGE_SIZE: Int = 12
const val BADGE_OFFSET: Int = 3
const val BADGE_INITIALS_SIZE: Float = 7f

/** Upstream's `text-[#d97757]` on the Claude mark. */
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
