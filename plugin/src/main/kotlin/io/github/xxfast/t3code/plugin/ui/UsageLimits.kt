package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * The status bar's popup: upstream's pooled usage limits (`UsageLimitsPooled`,
 * apps/web/src/components/usage/UsageLimitsPooled.tsx). A section per driver with a card per
 * window, then the `externalUsage` links and the notices. Text is the IDE's label size, what's
 * left a heading. No segment popover and no reset credit redemption, that needs
 * `providers:manage`.
 */
@Composable
fun UsageLimits(
  providers: List<ServerProvider>,
  now: Long,
  onOpenUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pools: List<LimitPool> = collectLimitPools(collectLimitAccounts(providers), now)
  val notices: List<String> = collectLimitNotices(providers)
  val links: List<ExternalUsageLink> = collectExternalUsageLinks(providers)
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (pools.isEmpty() && notices.isEmpty() && links.isEmpty()) {
      Text(
        text = "No provider on the selected environments reports subscription limits.",
        color = JewelTheme.globalColors.text.info,
      )
    }
    pools.forEach { pool -> PoolSection(pool, now) }
    links.forEach { link -> ExternalUsage(link, onOpenUrl) }
    LimitNotices(notices)
  }
}

/** `PoolSection`: the driver's icon and name, then a card per [displayLimitWindows]. */
@Composable
private fun PoolSection(pool: LimitPool, now: Long) {
  val color: Color = usageBarColor(pool.driver)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ProviderIcon(pool.accounts.first(), size = 16.dp)
      Text(providerKindName(pool.driver), fontWeight = FontWeight.Medium)
    }
    displayLimitWindows(pool).forEach { window ->
      val details: CursorUsageWindow? =
        if (pool.driver == "cursor") cursorUsageWindowDetails(window.id) else null
      PoolWindowCard(window, color, now, details?.label, details?.description)
    }
  }
}

/**
 * `PoolWindowCard`: the window's label, what's left in large type with "left" and the pace,
 * the next refill when several accounts share the window, and the bar.
 */
@Composable
private fun PoolWindowCard(
  pool: LimitPoolWindow,
  color: Color,
  now: Long,
  label: String?,
  description: String?,
) {
  val nextRefill: LimitPoolReset? = pool.resets.find { it.restoresPercent > 0 }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, cardBorder(), RoundedCornerShape(8.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = label ?: pool.label,
          fontWeight = FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${pool.remainingPercent}%",
            style = JewelTheme.typography.h2TextStyle,
            fontWeight = FontWeight.SemiBold,
          )
          Text("left", color = JewelTheme.globalColors.text.info)
          pool.pace?.let { PaceIcon(it) }
        }
        if (nextRefill != null && pool.columns.size > 1) {
          Text("↻ +${nextRefill.restoresPercent}%", fontWeight = FontWeight.Medium)
        }
      }
      Spacer(Modifier.width(12.dp))
      PoolBar(pool, color, now, Modifier.weight(1f))
    }
    description?.let { Text(it, style = small(), color = JewelTheme.globalColors.text.info) }
  }
}

/** `PoolBar`: a segment per account, side by side. */
@Composable
private fun PoolBar(pool: LimitPoolWindow, color: Color, now: Long, modifier: Modifier) {
  val restores: Map<String, LimitPoolReset> =
    pool.resets.associateBy { it.member.account.instanceId }
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    pool.columns.forEach { column ->
      val member: LimitPoolMember = column.member ?: return@forEach
      Segment(
        member = member,
        reset = restores[member.account.instanceId],
        color = color,
        now = now,
        showAccountName = pool.columns.size > 1,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * `SegmentTriggers`' wide bar: what's left at 35% of the provider's colour, the hatched share
 * the next reset refills, the pace tick in a gap cut from the whole bar, and on top the share
 * and a chip with when it resets and the banked reset credits.
 */
@Composable
private fun Segment(
  member: LimitPoolMember,
  reset: LimitPoolReset?,
  color: Color,
  now: Long,
  showAccountName: Boolean,
  modifier: Modifier,
) {
  val remaining: Int = remainingPercent(member.window)
  val timeLeft: Int? = paceRemainingPercent(member.window, now)
  val resetsIn: String? = formatResetsIn(member.window, now)
  val credits: Long = member.account.usageLimits?.resetCredits?.availableCount ?: 0
  val track: Color = JewelTheme.globalColors.text.normal.copy(alpha = 0.06f)
  val tick: Color = JewelTheme.globalColors.text.normal
  val refill: Double = if (remaining < 100 && reset != null) {
    member.refillPercent.coerceAtMost(100.0 - remaining)
  } else {
    0.0
  }
  Box(modifier.height(28.dp).clip(RoundedCornerShape(6.dp))) {
    Canvas(Modifier.matchParentSize()) {
      val radius = CornerRadius(6.dp.toPx())
      val gap: Float = 3.dp.toPx()
      val at: Float? = timeLeft
        ?.takeIf { size.width >= 6.dp.toPx() }
        ?.let { (size.width * it / 100f).coerceIn(gap, size.width - gap) }
      val bar: DrawScope.() -> Unit = {
        drawRoundRect(track, cornerRadius = radius)
        if (remaining > 0) {
          drawRoundRect(
            color = color.copy(alpha = 0.35f),
            size = Size(size.width * remaining / 100f, size.height),
            cornerRadius = radius,
          )
        }
        if (refill > 0) hatch(color.copy(alpha = 0.2f), (size.width * refill / 100).toFloat())
      }
      if (at == null) {
        bar()
      } else {
        clipRect(left = at - gap, right = at + gap, clipOp = ClipOp.Difference) { bar() }
        val width: Float = 2.dp.toPx()
        drawRoundRect(
          color = tick,
          topLeft = Offset(at - width / 2, 2.dp.toPx()),
          size = Size(width, size.height - 4.dp.toPx()),
          cornerRadius = CornerRadius(width / 2),
        )
      }
    }
    Row(
      modifier = Modifier.matchParentSize().padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (showAccountName) {
        Text(
          text = limitAccountName(member.account),
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
      }
      Text("$remaining%", fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.weight(1f))
      if (resetsIn != null || credits > 0) ResetChip(resetsIn, credits)
    }
  }
}

/** The segment's chip: "↻ 2h 55m", then "· [ticket] 2" with banked reset credits. */
@Composable
private fun ResetChip(resetsIn: String?, credits: Long) {
  val background: Color = JewelTheme.globalColors.panelBackground.copy(alpha = 0.85f)
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(background)
      .padding(horizontal = 6.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    resetsIn?.let { Text(it.replace("resets in ", "↻ "), style = small(), maxLines = 1) }
    if (credits > 0) {
      if (resetsIn != null) {
        Text("·", style = small(), color = JewelTheme.globalColors.text.info)
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Glyph("ticket", JewelTheme.globalColors.text.normal, size = 12.dp)
        Text("$credits", style = small(), fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

/** `PaceIcon`, apps/web/src/components/usage/UsageLimits.tsx, with its tooltip. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun PaceIcon(pace: LimitPace) {
  val (glyph: String, label: String) = when (pace) {
    LimitPace.Ahead -> "trending-up" to "Ahead of pace: spending faster than the window elapses"
    LimitPace.On -> "gauge" to "On pace with the window"
    LimitPace.Under -> "trending-down" to "Under pace: headroom left for the rest of the window"
  }
  Tooltip({ Text(label) }) {
    Glyph(glyph, JewelTheme.globalColors.text.info, size = 14.dp)
  }
}

/** One `externalUsage` link's section, the end of `UsageLimitsPooled`. */
@Composable
private fun ExternalUsage(link: ExternalUsageLink, onOpenUrl: (String) -> Unit) {
  val message: String? = if (link.url == CHATGPT_USAGE_URL) {
    "View usage in ChatGPT with your connected account."
  } else {
    link.message
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, cardBorder(), RoundedCornerShape(12.dp))
      .padding(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(link.label, fontWeight = FontWeight.Medium)
      message?.let { Text(it, style = small(), color = JewelTheme.globalColors.text.info) }
    }
    Link("Manage usage", onClick = { onOpenUrl(link.url) })
  }
}

/** `LimitNotices`: upstream's warning alert, a line per notice. */
@Composable
private fun LimitNotices(notices: List<String>) {
  if (notices.isEmpty()) return
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(AllIconsKeys.General.Warning, contentDescription = null)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      notices.forEach { notice -> Text(notice) }
    }
  }
}

/** Upstream's `border-border/60`. */
@Composable
private fun cardBorder(): Color = JewelTheme.globalColors.borders.normal.copy(alpha = 0.6f)

/**
 * The refill share's `repeating-linear-gradient(135deg, color 0 1px, transparent 1px 5px)`:
 * 1 px lines every 5 px across the last [width] of the bar.
 */
private fun DrawScope.hatch(color: Color, width: Float) {
  val left: Float = size.width - width
  val step: Float = 5.dp.toPx()
  val stroke: Float = 1.dp.toPx()
  clipRect(left = left) {
    var x: Float = left - size.height
    while (x < size.width) {
      drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), stroke)
      x += step
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
