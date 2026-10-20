package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.ProviderTurn
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ProviderTurnTokenUsage
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ThreadTokenUsageSnapshot
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// The context window meter's rules, from upstream's `apps/web/src/lib/contextWindow.ts` and
// `apps/web/src/components/chat/ContextWindowMeter.logic.ts`.

/** `ContextWindowSnapshot`, the parts the meter shows. */
data class ContextWindowSnapshot(
  val usedTokens: Long,
  val totalProcessedTokens: Long?,
  val maxTokens: Long?,
  val usedPercentage: Double?,
  val compactsAutomatically: Boolean?,
  val autoCompactThreshold: Long?,
  val cost: ThreadTokenUsageSnapshot.Cost?,
  val updatedAt: String,
  /** The live report's prompt cache lifetime, for [shouldOfferResumeCompaction]. */
  val promptCacheTtlMs: Long? = null,
)

/**
 * `latestProviderTurnTokenUsage`: the newest usage report across [turns]. Turns are ordered by
 * provider thread, not time, so this picks by timestamp; ties go to the later entry.
 */
fun latestProviderTurnTokenUsage(turns: List<ProviderTurn>): ProviderTurnTokenUsage? {
  var latest: ProviderTurnTokenUsage? = null
  for (turn in turns) {
    val usage: ProviderTurnTokenUsage = turn.tokenUsage ?: continue
    if (latest == null || instant(usage.updatedAt) >= instant(latest.updatedAt)) latest = usage
  }
  return latest
}

private fun instant(value: String?): Instant =
  value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

private fun percentage(used: Long, max: Long?): Double? =
  if (max != null && max > 0) minOf(100.0, used.toDouble() / max * 100) else null

/**
 * `deriveLatestContextWindowSnapshot`, contextWindow.ts:43: the live usage of a running turn,
 * else the active provider thread's last report, else the newest compaction's token count.
 */
fun deriveLatestContextWindowSnapshot(
  items: List<TurnItem>,
  liveUsage: ProviderTurnTokenUsage?,
  providerThread: ProviderThread?,
): ContextWindowSnapshot? {
  if (liveUsage != null) {
    val used: Long = maxOf(0, liveUsage.usedTokens)
    return ContextWindowSnapshot(
      usedTokens = used,
      totalProcessedTokens = null,
      maxTokens = liveUsage.maxTokens,
      usedPercentage = percentage(used, liveUsage.maxTokens),
      compactsAutomatically = true,
      autoCompactThreshold = null,
      cost = null,
      updatedAt = liveUsage.updatedAt,
      promptCacheTtlMs = liveUsage.promptCacheTtlMs,
    )
  }
  val usage: ThreadTokenUsageSnapshot? = providerThread?.contextUsage
  if (usage != null) {
    return ContextWindowSnapshot(
      usedTokens = usage.usedTokens,
      totalProcessedTokens = usage.totalProcessedTokens,
      maxTokens = usage.maxTokens,
      usedPercentage = percentage(usage.usedTokens, usage.maxTokens),
      compactsAutomatically = usage.compactsAutomatically,
      autoCompactThreshold = usage.autoCompactThreshold,
      cost = usage.cost,
      updatedAt = providerThread.updatedAt,
    )
  }
  val compaction: TurnItem.Compaction = items.asReversed()
    .filterIsInstance<TurnItem.Compaction>()
    .firstOrNull { (it.afterTokenCount ?: -1) >= 0 }
    ?: return null
  return ContextWindowSnapshot(
    usedTokens = compaction.afterTokenCount ?: 0,
    totalProcessedTokens = compaction.beforeTokenCount,
    maxTokens = null,
    usedPercentage = null,
    compactsAutomatically = true,
    autoCompactThreshold = null,
    cost = null,
    updatedAt = compaction.startedAt ?: compaction.updatedAt,
  )
}

/** The snapshot for [view]'s thread, as `ChatView.tsx:2547` derives `activeContextWindow`. */
fun activeContextWindow(view: ThreadView): ContextWindowSnapshot? =
  deriveLatestContextWindowSnapshot(
    items = view.items,
    liveUsage = latestProviderTurnTokenUsage(view.providerTurns),
    providerThread = view.providerThreads.find { it.id == view.thread.activeProviderThreadId },
  )

/** `formatContextWindowTokens`, contextWindow.ts: 950, 1.2k, 45k, 1.2m. */
fun formatContextWindowTokens(value: Long?): String {
  if (value == null) return "0"
  return when {
    value < 1_000 -> "$value"
    value < 10_000 -> trimZero(String.format(Locale.US, "%.1f", value / 1_000.0)) + "k"
    value < 999_500 -> "${(value / 1_000.0).roundToLong()}k"
    else -> trimZero(String.format(Locale.US, "%.1f", value / 1_000_000.0)) + "m"
  }
}

private fun trimZero(number: String): String = number.removeSuffix(".0")

/** `formatPercentage` in `ContextWindowMeter.tsx`: one decimal under 10%, whole above. */
fun formatContextWindowPercentage(value: Double?): String? {
  if (value == null || !value.isFinite()) return null
  if (value < 10) return trimZero(String.format(Locale.US, "%.1f", value)) + "%"
  return "${Math.round(value)}%"
}

/** `formatContextWindowCompactionMessage`, ContextWindowMeter.logic.ts:102. */
fun formatContextWindowCompactionMessage(modelDisplayName: String?, threshold: Long?): String {
  if (threshold != null && threshold > 0) {
    return "Compacts automatically at ${String.format(Locale.US, "%,d", threshold)} tokens."
  }
  return if (modelDisplayName != null) {
    "Context for $modelDisplayName compacts automatically when needed."
  } else {
    "Context compacts automatically when needed."
  }
}

/** `formatContextWindowCost`, ContextWindowMeter.logic.ts:128. */
fun formatContextWindowCost(cost: ThreadTokenUsageSnapshot.Cost): String {
  val digits: Int = if (abs(cost.amount) > 0 && abs(cost.amount) < 0.01) 4 else 2
  return "${cost.currency} ${String.format(Locale.US, "%.${digits}f", cost.amount)}"
}

/** What compacts a thread: upstream sends it as a message, `sendStandaloneCommand("/compact")`. */
const val COMPACT_COMMAND: String = "/compact"

/** `providerSupportsManualCompaction`: the provider lists a `/compact` slash command. */
fun providerSupportsManualCompaction(provider: ServerProvider?): Boolean =
  provider?.slashCommands.orEmpty().any { it.name == "compact" }

// Claude's 1h prompt cache plus slack, when the provider didn't report its TTL
private val CLAUDE_RESUME_COMPACTION_FALLBACK: Duration = Duration.ofMinutes(70)
private const val CLAUDE_RESUME_COMPACTION_TOKENS: Long = 100_000

/**
 * `shouldOfferResumeCompaction`, ContextWindowMeter.logic.ts:64: a Claude thread holding at
 * least 100k tokens whose prompt cache has gone cold since the last usage report. Sending to it
 * re-reads the whole history, so the composer offers to compact first.
 */
fun shouldOfferResumeCompaction(
  driver: String?,
  usedTokens: Long?,
  updatedAt: String?,
  promptCacheTtlMs: Long?,
  now: Instant,
): Boolean {
  if (driver != "claudeAgent" || (usedTokens ?: 0) < CLAUDE_RESUME_COMPACTION_TOKENS) return false
  val updated: Instant = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    ?: return false
  val ttl: Duration = promptCacheTtlMs?.let(Duration::ofMillis) ?: CLAUDE_RESUME_COMPACTION_FALLBACK
  return Duration.between(updated, now) >= ttl
}
