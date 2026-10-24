package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageLimits
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageWindow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/**
 * The status bar's popup: upstream's usage limits banner body (`UsageLimitsBannerBody`,
 * apps/web/src/components/chat/ComposerUsageLimits.tsx) for every provider in
 * [providersWithLimits], with its account label, then [limitsNotice] or the windows, then
 * "Manage usage" for an `externalUsage` link. No reset credits, those need `providers:manage`.
 */
@Composable
fun UsageLimits(
  providers: List<ServerProvider>,
  now: Long,
  onOpenUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val shown: List<ServerProvider> = providersWithLimits(providers)
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Usage limits", fontWeight = FontWeight.SemiBold)
    shown.forEach { provider ->
      val limits: ServerProviderUsageLimits = provider.usageLimits ?: return@forEach
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = usageAccountLabel(provider) + (provider.auth.label?.let { " · $it" } ?: ""),
          style = small(),
          color = JewelTheme.globalColors.text.info,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val notice: String? = limitsNotice(limits)
        if (notice != null) {
          Text(notice, style = small(), color = JewelTheme.globalColors.text.info)
        } else {
          LimitWindows(provider.driver, limits.windows.filterNotNull(), now)
        }
        limits.externalUsage?.let { external ->
          Link("Manage usage", onClick = { onOpenUrl(external.url) }, textStyle = small())
        }
      }
    }
  }
}

/** `LimitWindows`, apps/web/src/components/usage/UsageLimits.tsx, its compact grid. */
@Composable
private fun LimitWindows(driver: String, windows: List<ServerProviderUsageWindow>, now: Long) {
  val color: Color = usageBarColor(driver)
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    windows.forEach { window ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(144.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = window.label,
            style = small(),
            color = JewelTheme.globalColors.text.info,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = "${remainingPercent(window)}% left",
            style = small(),
            fontWeight = FontWeight.Medium,
          )
        }
        Spacer(Modifier.width(12.dp))
        WindowBar(color, window, now, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
          text = formatResetsIn(window, now).orEmpty(),
          style = small(),
          color = JewelTheme.globalColors.text.info,
          maxLines = 1,
          modifier = Modifier.width(104.dp),
        )
      }
    }
  }
}

/**
 * `WindowBar`, apps/web/src/components/usage/UsageLimits.tsx: the remaining share in the
 * provider's colour over a muted track, and the pace tick in a gap cut from the bar where even
 * spending would be. Below 6px there's no room for the gap and the tick, as upstream.
 */
@Composable
private fun WindowBar(
  color: Color,
  window: ServerProviderUsageWindow,
  now: Long,
  modifier: Modifier = Modifier,
) {
  val remaining: Int = remainingPercent(window)
  val timeLeft: Int? = paceRemainingPercent(window, now)
  val track: Color = JewelTheme.globalColors.text.info.copy(alpha = 0.24f)
  val tick: Color = JewelTheme.globalColors.text.normal
  Canvas(modifier.height(16.dp)) {
    val barHeight: Float = 6.dp.toPx()
    val top: Float = (size.height - barHeight) / 2
    val radius = CornerRadius(barHeight / 2)
    val gap: Float = 3.dp.toPx()
    val at: Float? = timeLeft
      ?.takeIf { size.width >= 6.dp.toPx() }
      ?.let { (size.width * it / 100f).coerceIn(gap, size.width - gap) }
    val bar: () -> Unit = {
      drawRoundRect(track, Offset(0f, top), Size(size.width, barHeight), radius)
      if (remaining > 0) {
        val filled = Size(size.width * remaining / 100f, barHeight)
        drawRoundRect(color, Offset(0f, top), filled, radius)
      }
    }
    if (at == null) {
      bar()
    } else {
      clipRect(left = at - gap, right = at + gap, clipOp = ClipOp.Difference) { bar() }
      val tickWidth: Float = 2.dp.toPx()
      drawRoundRect(
        color = tick,
        topLeft = Offset(at - tickWidth / 2, 2.dp.toPx()),
        size = Size(tickWidth, size.height - 4.dp.toPx()),
        cornerRadius = CornerRadius(tickWidth / 2),
      )
    }
  }
}

/** `barColor`, apps/web/src/components/usage/UsageLimits.tsx, from `PROVIDER_PRESENTATION`. */
@Composable
private fun usageBarColor(driver: String): Color {
  val foreground: Color = JewelTheme.globalColors.text.normal
  return when (driver) {
    "codex" -> foreground
    "claudeAgent" -> Color(0xFFD97757)
    "grok" -> lerp(JewelTheme.globalColors.panelBackground, foreground, 0.72f)
    "cursor" -> Color(0xFF8B8B8B)
    "opencode" -> Color(0xFF5B9BBD)
    "antigravity" -> Color(0xFF8C7BD1)
    "pi" -> Color(0xFF4D9F8A)
    else -> foreground
  }
}
