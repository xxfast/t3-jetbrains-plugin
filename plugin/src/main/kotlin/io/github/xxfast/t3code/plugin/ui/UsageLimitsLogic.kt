package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageLimits
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageWindow
import java.time.Instant
import kotlin.math.roundToInt

private const val MINUTE: Long = 60_000
private const val HOUR: Long = 60 * MINUTE
private const val DAY: Long = 24 * HOUR

/**
 * `providersWithLimits`, packages/shared/src/usageLimits.ts: enabled, installed, available
 * (`isProviderAvailable`) and reporting `usageLimits`.
 */
fun providersWithLimits(providers: List<ServerProvider>): List<ServerProvider> =
  providers.filter { provider ->
    provider.enabled &&
      provider.installed &&
      provider.availability != "unavailable" &&
      provider.usageLimits != null
  }

/** `limitsNotice`, packages/shared/src/usageLimits.ts: what shows in place of the bars. */
fun limitsNotice(limits: ServerProviderUsageLimits): String? = when (limits.unavailable?.reason) {
  "unsupported" -> limits.unavailable?.message ?: "This account has no subscription limits."
  "probeFailed" -> limits.unavailable?.message ?: "Could not read limits."
  else -> if (limits.windows.filterNotNull().isEmpty()) "No limits reported." else null
}

/** `remainingPercent`, packages/shared/src/usageLimits.ts. */
fun remainingPercent(window: ServerProviderUsageWindow): Int =
  (100 - window.usedPercent.coerceIn(0.0, 100.0)).roundToInt()

private fun resetMillis(window: ServerProviderUsageWindow): Long? =
  window.resetsAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** `elapsedShare`, packages/shared/src/usageLimits.ts. */
fun elapsedShare(window: ServerProviderUsageWindow, now: Long): Double? {
  val resetsAt: Long = resetMillis(window) ?: return null
  val minutes: Long = window.windowDurationMins ?: return null
  val length: Long = minutes * MINUTE
  if (length <= 0) return null
  return ((length - (resetsAt - now)).toDouble() / length).coerceIn(0.0, 1.0)
}

/**
 * `paceRemainingPercent`, packages/shared/src/usageLimits.ts: where the bar would be with even
 * spending. Bars show quota remaining, so even pace tracks time remaining.
 */
fun paceRemainingPercent(window: ServerProviderUsageWindow, now: Long): Int? =
  elapsedShare(window, now)?.let { elapsed -> ((1 - elapsed) * 100).roundToInt() }

/** `formatDuration`, packages/shared/src/usageLimits.ts, not the timeline's. */
fun formatLimitDuration(ms: Long): String {
  val remaining: Long = ms.coerceAtLeast(0)
  val days: Long = remaining / DAY
  val hours: Long = (remaining % DAY) / HOUR
  val minutes: Long = (remaining % HOUR) / MINUTE
  return when {
    days > 0 -> "${days}d ${hours}h"
    hours > 0 -> "${hours}h ${minutes}m"
    else -> "${minutes}m"
  }
}

/** `formatResetsIn`, packages/shared/src/usageLimits.ts. */
fun formatResetsIn(window: ServerProviderUsageWindow, now: Long): String? {
  val resetsAt: Long = resetMillis(window) ?: return null
  return when {
    resetsAt <= now -> "resets now"
    resetsAt - now < MINUTE -> "Resets momentarily"
    else -> "resets in ${formatLimitDuration(resetsAt - now)}"
  }
}

/**
 * `accountLabel`, apps/web/src/components/chat/ComposerUsageLimits.tsx, for a provider's own
 * account: the driver's name, then the instance's when it's a different one.
 */
fun usageAccountLabel(provider: ServerProvider): String {
  val driver: String = providerKindName(provider.driver)
  val instance: String = provider.displayName?.trim()?.takeIf { it.isNotEmpty() }
    ?: provider.instanceId.takeIf { it != provider.driver }
    ?: ""
  return if (instance.isNotEmpty() && instance.lowercase() != driver.lowercase()) {
    "$driver · $instance"
  } else {
    driver
  }
}

/** One provider's part of the status bar: its tightest window. Not upstream's, it has no bar. */
data class UsageSegment(
  val instanceId: String,
  val label: String,
  val window: ServerProviderUsageWindow,
  val remaining: Int,
  val level: UsageLevel,
)

enum class UsageLevel { Normal, Low, Exhausted }

/** Below this much left a segment warns. */
const val USAGE_LOW_PERCENT: Int = 20

/**
 * The status bar's segments: per provider in [providersWithLimits] that has windows and no
 * [limitsNotice], the window with the least left.
 */
fun usageSegments(providers: List<ServerProvider>): List<UsageSegment> =
  providersWithLimits(providers).mapNotNull { provider ->
    val limits: ServerProviderUsageLimits = provider.usageLimits ?: return@mapNotNull null
    if (limitsNotice(limits) != null) return@mapNotNull null
    val tightest: ServerProviderUsageWindow =
      limits.windows.filterNotNull().minByOrNull(::remainingPercent) ?: return@mapNotNull null
    val remaining: Int = remainingPercent(tightest)
    UsageSegment(
      instanceId = provider.instanceId,
      label = resolveProviderInstanceDisplayName(provider),
      window = tightest,
      remaining = remaining,
      level = when {
        remaining <= 0 -> UsageLevel.Exhausted
        remaining < USAGE_LOW_PERCENT -> UsageLevel.Low
        else -> UsageLevel.Normal
      },
    )
  }

/** A segment's tooltip, `WindowBar`'s summary in apps/web/src/components/usage/UsageLimits.tsx. */
fun usageSegmentSummary(segment: UsageSegment, now: Long): String {
  val resetsIn: String? = formatResetsIn(segment.window, now)
  return "${segment.label} · ${segment.window.label}: ${segment.remaining}% left" +
    (resetsIn?.let { ", $it" } ?: "")
}
