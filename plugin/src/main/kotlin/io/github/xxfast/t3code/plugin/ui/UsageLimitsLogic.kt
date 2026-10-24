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
 * `AccountName`, apps/web/src/components/usage/UsageLimitsPooled.tsx: the instance's display
 * name, else its email, else the driver's name. Upstream draws an email as an initials chip.
 */
fun limitAccountName(provider: ServerProvider): String =
  provider.displayName?.trim()?.takeIf { it.isNotEmpty() }
    ?: provider.auth.email
    ?: providerKindName(provider.driver)

/** `LimitPace`, packages/shared/src/usageLimits.ts. */
enum class LimitPace { Ahead, On, Under }

/** `paceOfShares`, packages/shared/src/usageLimits.ts. */
fun paceOfShares(usedPercent: Double, elapsed: Double): LimitPace {
  val gap: Double = usedPercent - elapsed * 100
  return when {
    gap > 5 -> LimitPace.Ahead
    gap < -5 -> LimitPace.Under
    else -> LimitPace.On
  }
}

/**
 * The accounts the pooled view draws, `collectLimitAccounts` in packages/shared/src/usageLimits.ts
 * for one server: every provider in [providersWithLimits] without a [limitsNotice]. Upstream
 * also merges instances signed in to the same account; here each instance is its own.
 */
fun collectLimitAccounts(providers: List<ServerProvider>): List<ServerProvider> =
  providersWithLimits(providers).filter { provider ->
    provider.usageLimits?.let(::limitsNotice) == null
  }

/** `collectLimitNotices`, packages/shared/src/usageLimits.ts, for one server. */
fun collectLimitNotices(providers: List<ServerProvider>): List<String> =
  providersWithLimits(providers).mapNotNull { provider ->
    val limits: ServerProviderUsageLimits = provider.usageLimits ?: return@mapNotNull null
    if (limits.unavailable?.reason == "unsupported") return@mapNotNull null
    val notice: String = limitsNotice(limits) ?: return@mapNotNull null
    val name: String = provider.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: provider.driver
    "$name: $notice"
  }

/** One `externalUsage` link, `collectExternalUsageLinks` in packages/shared/src/usageLimits.ts. */
data class ExternalUsageLink(val label: String, val url: String, val message: String?)

/** `collectExternalUsageLinks`, packages/shared/src/usageLimits.ts, for one server. */
fun collectExternalUsageLinks(providers: List<ServerProvider>): List<ExternalUsageLink> =
  providersWithLimits(providers)
    .filter { it.auth.status == "authenticated" }
    .mapNotNull { provider ->
      val external = provider.usageLimits?.externalUsage ?: return@mapNotNull null
      ExternalUsageLink(external.label, external.url, provider.usageLimits?.unavailable?.message)
    }
    .distinctBy { it.url }

/** `CHATGPT_USAGE_URL`, packages/shared/src/usageLimits.ts. */
const val CHATGPT_USAGE_URL: String = "https://chatgpt.com/#settings/Usage"

/** `LimitPoolMember`, packages/shared/src/usageLimits.ts. */
data class LimitPoolMember(
  val account: ServerProvider,
  val window: ServerProviderUsageWindow,
  val refillPercent: Double,
)

/** A pool window's column: the account's member, null when it doesn't report the window. */
data class LimitPoolColumn(val account: ServerProvider, val member: LimitPoolMember?)

/** One of `LimitPoolWindow.resets`, packages/shared/src/usageLimits.ts. */
data class LimitPoolReset(val member: LimitPoolMember, val at: Long, val restoresPercent: Int)

/** `LimitPoolWindow`, packages/shared/src/usageLimits.ts. */
data class LimitPoolWindow(
  val id: String,
  val kind: String,
  val label: String,
  val members: List<LimitPoolMember>,
  val columns: List<LimitPoolColumn>,
  val remainingPercent: Int,
  val usedPercent: Int,
  val pace: LimitPace?,
  val resets: List<LimitPoolReset>,
)

/** `LimitPool`, packages/shared/src/usageLimits.ts: one driver's accounts and windows. */
data class LimitPool(
  val driver: String,
  val accounts: List<ServerProvider>,
  val windows: List<LimitPoolWindow>,
)

/** `WINDOW_KIND_ORDER`, packages/shared/src/usageLimits.ts. */
private fun windowKindOrder(kind: String): Int = when (kind) {
  "session" -> 0
  "weekly" -> 1
  "monthly" -> 2
  else -> 3
}

/**
 * `poolKey`, packages/shared/src/usageLimits.ts. Codex ids are slots, not allowances: a
 * weekly-only plan reports its weekly window as `primary`, other plans as `secondary`. Its
 * windows pool by kind; other drivers' ids name distinct allowances.
 */
private fun poolKey(driver: String, window: ServerProviderUsageWindow): String =
  if (driver == "codex") window.kind else "${window.kind}:${window.id}"

private fun limitWindows(account: ServerProvider): List<ServerProviderUsageWindow> =
  account.usageLimits?.windows.orEmpty().filterNotNull()

/**
 * `poolMember`, packages/shared/src/usageLimits.ts: a session window while the weekly one is
 * spent counts as spent, refilling when the first of the two resets frees it.
 */
private fun poolMember(
  account: ServerProvider,
  reported: ServerProviderUsageWindow,
  now: Long,
): LimitPoolMember {
  val spentWeekly: ServerProviderUsageWindow? = if (reported.kind == "session") {
    limitWindows(account).find { window ->
      window.usedPercent >= 100 && when (account.driver) {
        "claudeAgent" -> window.id == "seven_day"
        else -> account.driver == "codex" && window.kind == "weekly"
      }
    }
  } else {
    null
  }
  if (spentWeekly == null) return LimitPoolMember(account, reported, reported.usedPercent)
  val weeklyReset: Long? = resetMillis(spentWeekly)
  val sessionReset: Long? = resetMillis(reported)
  val checkedAt: Long = account.usageLimits?.checkedAt
    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    ?: Long.MIN_VALUE
  val cutoff: Long = maxOf(now, checkedAt)
  var resetsAt: String? = null
  var refillPercent = 0.0
  // A stale or missing clock cannot tell us when blocked quota becomes usable
  val clocked: Boolean = weeklyReset != null && sessionReset != null &&
    weeklyReset > cutoff && sessionReset > cutoff
  if (clocked) {
    when {
      sessionReset <= weeklyReset -> {
        resetsAt = spentWeekly.resetsAt
        refillPercent = 100.0
      }
      reported.usedPercent >= 100 -> {
        resetsAt = reported.resetsAt
        refillPercent = 100.0
      }
      else -> {
        resetsAt = spentWeekly.resetsAt
        refillPercent = 100 - reported.usedPercent.coerceIn(0.0, 100.0)
      }
    }
  }
  // Suppression is not session spending, so it has no session pace clock
  val suppressed: ServerProviderUsageWindow =
    reported.copy(usedPercent = 100.0, resetsAt = resetsAt, windowDurationMins = null)
  return LimitPoolMember(account, suppressed, refillPercent)
}

/** `poolWindows`, packages/shared/src/usageLimits.ts. */
private fun poolWindows(accounts: List<ServerProvider>, now: Long): List<LimitPoolWindow> {
  val byKey: LinkedHashMap<String, MutableList<LimitPoolMember>> = linkedMapOf()
  for (account in accounts) {
    for (reported in limitWindows(account)) {
      val member: LimitPoolMember = poolMember(account, reported, now)
      val pooled: String = poolKey(account.driver, member.window)
      val key: String = if (byKey[pooled]?.any { it.account == account } == true) {
        "${member.window.kind}:${member.window.id}"
      } else {
        pooled
      }
      byKey.getOrPut(key) { mutableListOf() }.add(member)
    }
  }
  val pools: List<LimitPoolWindow> = byKey.map { (key, members) ->
    val first: ServerProviderUsageWindow = members.first().window
    val id: String = if (members.first().account.driver == "codex") key else first.id
    val usedPercent: Double = members.sumOf { it.window.usedPercent } / members.size
    // Pace compares spend against the clock, so it's judged only over the members with one
    val timed: List<Pair<Double, Double>> = members.mapNotNull { member ->
      elapsedShare(member.window, now)?.let { member.window.usedPercent to it }
    }
    val pace: LimitPace? = if (timed.isEmpty()) {
      null
    } else {
      paceOfShares(timed.sumOf { it.first } / timed.size, timed.sumOf { it.second } / timed.size)
    }
    val resets: List<LimitPoolReset> = members
      .mapNotNull { member ->
        resetMillis(member.window)?.let { at ->
          LimitPoolReset(member, at, (member.refillPercent / members.size).roundToInt())
        }
      }
      .sortedBy { it.at }
    LimitPoolWindow(
      id = id,
      kind = first.kind,
      label = first.label,
      members = members,
      columns = accounts.map { account ->
        LimitPoolColumn(account, members.find { it.account.instanceId == account.instanceId })
      },
      usedPercent = usedPercent.roundToInt(),
      remainingPercent = (100 - usedPercent).roundToInt(),
      pace = pace,
      resets = resets,
    )
  }
  return pools.sortedBy { windowKindOrder(it.kind) }
}

/**
 * `collectLimitPools`, packages/shared/src/usageLimits.ts: the accounts by driver, each
 * driver's in the order their soonest window resets, then by name.
 */
fun collectLimitPools(accounts: List<ServerProvider>, now: Long): List<LimitPool> =
  accounts.groupBy { it.driver }.map { (driver, members) ->
    val orderWindow: ServerProviderUsageWindow? =
      members.flatMap(::limitWindows).minByOrNull { windowKindOrder(it.kind) }
    val orderReset: (ServerProvider) -> Long = { account ->
      val window: ServerProviderUsageWindow? = orderWindow?.let { order ->
        limitWindows(account).find { poolKey(driver, it) == poolKey(driver, order) }
      }
      window?.let { resetMillis(poolMember(account, it, now).window) } ?: Long.MAX_VALUE
    }
    val sorted: List<ServerProvider> = members.sortedWith(
      compareBy<ServerProvider>(orderReset)
        .thenBy { accountSortName(it) }
        .thenBy { it.instanceId },
    )
    LimitPool(driver, sorted, poolWindows(sorted, now))
  }

private fun accountSortName(account: ServerProvider): String =
  (account.displayName ?: account.auth.email ?: account.instanceId).lowercase()

/** One of `CURSOR_USAGE_WINDOWS`, packages/shared/src/usageLimits.ts. */
data class CursorUsageWindow(val id: String, val label: String, val description: String)

/** `CURSOR_USAGE_WINDOWS`, packages/shared/src/usageLimits.ts. */
val CURSOR_USAGE_WINDOWS: List<CursorUsageWindow> = listOf(
  CursorUsageWindow(
    "totalPercentUsed",
    "Overall",
    "Combined usage across both allowances, not a third quota.",
  ),
  CursorUsageWindow(
    "autoPercentUsed",
    "Cursor Models",
    "Grok and Composer use this first. Auto can use either pool.",
  ),
  CursorUsageWindow(
    "apiPercentUsed",
    "Other Models",
    "Claude, GPT, and Gemini use this pool. Grok and Composer fall back here.",
  ),
)

/** `cursorUsageWindowDetails`, packages/shared/src/usageLimits.ts. */
fun cursorUsageWindowDetails(id: String): CursorUsageWindow? =
  CURSOR_USAGE_WINDOWS.find { it.id == id }

private fun cursorUsageWindowRank(id: String): Int =
  CURSOR_USAGE_WINDOWS.indexOfFirst { it.id == id }
    .let { rank -> if (rank < 0) CURSOR_USAGE_WINDOWS.size else rank }

/**
 * `displayLimitWindows`, packages/shared/src/usageLimits.ts: Antigravity's Gemini windows
 * first then by model, Cursor's without "Overall" when both pools report, in their order.
 */
fun displayLimitWindows(pool: LimitPool): List<LimitPoolWindow> = when (pool.driver) {
  "antigravity" -> {
    val model: (String) -> String = { it.replace(Regex("-(5h|weekly|quota)$"), "") }
    pool.windows.sortedWith(
      compareBy<LimitPoolWindow> { if (it.id.startsWith("gemini-")) 0 else 1 }
        .thenBy { model(it.id) }
        .thenBy { windowKindOrder(it.kind) },
    )
  }
  "cursor" -> {
    val hasBothPools: Boolean = pool.windows.any { it.id == "autoPercentUsed" } &&
      pool.windows.any { it.id == "apiPercentUsed" }
    pool.windows
      .filter { !hasBothPools || it.id != "totalPercentUsed" }
      .sortedBy { cursorUsageWindowRank(it.id) }
  }
  else -> pool.windows
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
