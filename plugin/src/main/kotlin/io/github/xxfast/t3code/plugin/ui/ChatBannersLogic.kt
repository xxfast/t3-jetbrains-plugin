package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.LimitRecovery
import io.github.xxfast.t3code.protocol.generated.LimitRecoveryUpdate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The rules behind the ChatView's banners: `ComposerBannerStack.tsx` for the order of the ones
// above the composer, the branch mismatch notice's from `ChatView.logic.ts` and
// `useThreadLifecycle.tsx` (the mismatch itself is `branchMismatch` in SidebarRowDetails.kt), the
// usage limit recovery banner's from `UsageLimitRecoveryBanner.tsx`, and the thread error
// banner's over the timeline from `ThreadErrorBanner.tsx` and `ChatView.tsx`.

/** `ComposerBannerVariant`, `ComposerBanner.tsx`. */
enum class ComposerBannerVariant { DEFAULT, ERROR, INFO, SUCCESS, WARNING }

/** `ComposerBannerStackItem.priority`. */
enum class ComposerBannerPriority { URGENT, ACTIVITY, NOTICE }

/** One banner in the stack, in the order `composerBannerItems` (ChatView.tsx) lists them. */
sealed interface ComposerBannerItem {
  val variant: ComposerBannerVariant
  val priority: ComposerBannerPriority?

  /** `usageLimitRecoveryBannerItem`, `UsageLimitRecoveryBanner.tsx`. */
  class LimitRecovery(val state: UsageLimitRecoveryState) : ComposerBannerItem {
    override val variant: ComposerBannerVariant = ComposerBannerVariant.WARNING
    override val priority: ComposerBannerPriority = ComposerBannerPriority.URGENT
  }

  /** `backgroundWorkBannerItem`, `ThreadActivityBanners.tsx`. */
  class BackgroundWork(val state: BackgroundWorkState) : ComposerBannerItem {
    override val variant: ComposerBannerVariant = ComposerBannerVariant.DEFAULT
    override val priority: ComposerBannerPriority = ComposerBannerPriority.ACTIVITY
  }

  /** `branchMismatchBannerItem`, `ThreadActivityBanners.tsx`. */
  class BranchMismatch(val state: BranchMismatchBannerState) : ComposerBannerItem {
    override val variant: ComposerBannerVariant = ComposerBannerVariant.INFO
    override val priority: ComposerBannerPriority? = null
  }
}

/** `bannerPriority`, `ComposerBannerStack.tsx`: urgent, errors and warnings, then activity. */
fun bannerPriority(item: ComposerBannerItem): Int = when {
  item.priority == ComposerBannerPriority.URGENT ||
    item.variant == ComposerBannerVariant.ERROR ||
    item.variant == ComposerBannerVariant.WARNING -> 0
  item.priority == ComposerBannerPriority.ACTIVITY -> 1
  else -> 2
}

/** `orderedItems`: a stable sort, so equal priorities keep upstream's listing order. */
fun orderComposerBanners(items: List<ComposerBannerItem>): List<ComposerBannerItem> =
  items.sortedBy(::bannerPriority)

/** What the branch mismatch notice shows. */
class BranchMismatchBannerState(
  val mismatch: BranchMismatch,
  /** Upstream's `isRestoringThreadBranch`: a restore is on its way. */
  val restoring: Boolean = false,
  /** `canWriteSourceControl`: the pairing may switch branches. */
  val canRestore: Boolean = true,
)

/** What the notice's controls do; the host dispatches. */
class BranchMismatchBannerActions(
  val onRestore: () -> Unit = {},
  val onDismiss: () -> Unit = {},
)

/** `branchMismatchKey`, ChatView.logic.ts: what a dismissal remembers. */
fun branchMismatchKey(threadId: String?, mismatch: BranchMismatch?): String? {
  if (threadId == null || mismatch == null) return null
  return "$threadId:${mismatch.threadBranch}:${mismatch.currentBranch}"
}

/**
 * `shouldShowBranchMismatchBanner`, ChatView.logic.ts. Draft content is the intent signal, and
 * once shown for a mismatch it stays until dismissed or the mismatch changes.
 */
fun shouldShowBranchMismatchBanner(
  hasMismatch: Boolean,
  isDismissed: Boolean,
  composerHasContent: Boolean,
  wasShownForCurrentMismatch: Boolean,
): Boolean {
  if (!hasMismatch || isDismissed) return false
  return composerHasContent || wasShownForCurrentMismatch
}

/** `nextRevealedBranchMismatchKey`, useThreadLifecycle.tsx: the mismatch the notice latched on. */
fun nextRevealedBranchMismatchKey(show: Boolean, activeKey: String?, revealed: String?): String? =
  when {
    show -> activeKey
    revealed != activeKey -> null
    else -> revealed
  }

/**
 * `composerDraftHasUserContent`, composerDraftStore.ts: text, attachments, review comments or
 * mentioned threads. The editor selection chip isn't counted; it follows the editor, not intent.
 */
fun composerDraftHasUserContent(text: CharSequence, extras: DraftExtras): Boolean =
  text.isNotBlank() ||
    extras.attachments.isNotEmpty() ||
    extras.comments.isNotEmpty() ||
    extras.threads.isNotEmpty()

/** `CHATGPT_USAGE_LIMIT_CODE`, packages/shared/src/usageLimits.ts. */
const val CHATGPT_USAGE_LIMIT_CODE: String = "subscription_sharing_usage_limit_exceeded"

/** `CHATGPT_WORKSPACE_USAGE_LIMIT_CODE`: a workspace admin capped plan use in other apps. */
const val CHATGPT_WORKSPACE_USAGE_LIMIT_CODE: String =
  "subscription_sharing_workplace_individual_usage_limit_exceeded"

/** `CHATGPT_WORKSPACE_ADMIN_REQUEST`: OpenAI's partner guidelines fix this wording. */
const val CHATGPT_WORKSPACE_ADMIN_REQUEST: String =
  "I've reached the limit for using my ChatGPT plan in T3 Code. Could you increase our " +
    "workspace's usage limits so I can continue?\n\nGo to admin.openai.com → Workspace → " +
    "Usage limits. Thank you!"

/** What the thread error banner shows: the error, its class and its code. */
data class ThreadErrorBannerState(
  val error: String,
  val errorClass: String? = null,
  val errorCode: String? = null,
) {
  val workspaceLimit: Boolean get() = errorCode == CHATGPT_WORKSPACE_USAGE_LIMIT_CODE
  val chatGptLimit: Boolean get() = workspaceLimit || errorCode == CHATGPT_USAGE_LIMIT_CODE

  /** A usage limit warns, anything else is an error. */
  val variant: ComposerBannerVariant
    get() = if (errorClass == "usage_limit" || chatGptLimit) {
      ComposerBannerVariant.WARNING
    } else {
      ComposerBannerVariant.ERROR
    }
}

/** `getThreadErrorBannerKey`: what a dismissal remembers, the thread and the exact text. */
fun threadErrorBannerKey(threadId: String, error: String?): String? =
  if (error == null) null else "$threadId\u0000$error"

/** `shouldShowThreadErrorBanner`. */
fun shouldShowThreadErrorBanner(threadId: String, error: String?, isDismissed: Boolean): Boolean =
  threadErrorBannerKey(threadId, error) != null && !isDismissed

/**
 * The banner over [thread]'s timeline, ChatView.tsx's `visibleThreadError` then
 * `timelineThreadError`: the thread's last error unless dismissed, and nothing while the usage
 * limit recovery banner speaks for it. Upstream's local dispatch errors show under the composer
 * here, so the error is always the server's and carries its class and code.
 */
fun resolveThreadErrorBanner(
  thread: Thread,
  dismissed: Set<String>,
  limitRecoveryShown: Boolean,
): ThreadErrorBannerState? {
  val error: String = thread.lastError ?: return null
  val key: String? = threadErrorBannerKey(thread.id, error)
  if (!shouldShowThreadErrorBanner(thread.id, error, key in dismissed)) return null
  if (limitRecoveryShown) return null
  return ThreadErrorBannerState(error, thread.lastErrorClass, thread.lastErrorCode)
}

/**
 * `getThreadErrorBannerMessage`: a provider's JSON error body collapses to its `error.message`;
 * anything else shows as is.
 */
fun getThreadErrorBannerMessage(error: String): String {
  val payload: JsonObject = runCatching { T3Json.parseToJsonElement(error) }.getOrNull()
    as? JsonObject ?: return error
  val nested: JsonObject = payload["error"] as? JsonObject ?: return error
  val message: JsonPrimitive = nested["message"] as? JsonPrimitive ?: return error
  if (!message.isString) return error
  return message.content.takeIf { it.isNotBlank() } ?: error
}

/** `ExpandableText`'s `canExpand`: a collapsed form, a long text or several lines. */
fun canExpandText(text: String, collapsed: String): Boolean =
  collapsed != text || text.length > 180 || text.contains('\n')

/** The usage limit a failed thread hit, and what's been arranged about it. */
data class UsageLimitRecoveryState(
  val runId: String,
  /** ISO, null when the provider didn't say. */
  val resetAt: String?,
  /** The reset is after the stop, so resume and snooze make sense, `canSchedule`. */
  val canSchedule: Boolean,
  /** Auto-resume at the reset is on, `scheduled`. */
  val scheduled: Boolean,
  /** Snoozed until the reset, `snoozed`. */
  val snoozed: Boolean,
  /** A change is on its way, `pending`. */
  val saving: Boolean = false,
)

/**
 * `limitRecoveryBanner` (ChatView.tsx) and `RecoveryActions`' derived state: a failed thread
 * whose last error class is `usage_limit`, with a latest run. The shell's fields stand in for
 * `serverRuntime` and `latestRun`; the server derives both the same way.
 */
fun usageLimitRecovery(thread: Thread): UsageLimitRecoveryState? {
  if (thread.status != "failed" || thread.lastErrorClass != "usage_limit") return null
  val runId: String = thread.latestRunId ?: return null
  val resetAt: String? = thread.usageLimitResetAt
  val stoppedAt: String = thread.latestRunCompletedAt ?: thread.updatedAt
  val resetMs: Long? = resetAt?.let(::epochMillis)
  val stoppedMs: Long? = epochMillis(stoppedAt)
  val recovery: LimitRecovery? =
    thread.limitRecovery?.takeIf { it.runId == runId && it.resetAt == resetAt }
  val snoozedUntil: Long? = thread.snoozedUntil?.let(::epochMillis)
  return UsageLimitRecoveryState(
    runId = runId,
    resetAt = resetAt,
    canSchedule = resetMs != null && stoppedMs != null && resetMs > stoppedMs,
    scheduled = recovery?.autoResume == true,
    snoozed = recovery?.snooze == true && resetMs != null && snoozedUntil == resetMs,
  )
}

/** The recovery banner's two buttons. */
enum class LimitRecoveryAction { RESUME, SNOOZE }

/** `toggle`: flips the one field the button is about, the server keeps the other. */
fun limitRecoveryUpdate(
  state: UsageLimitRecoveryState,
  action: LimitRecoveryAction,
): LimitRecoveryUpdate? {
  val resetAt: String = state.resetAt ?: return null
  return when (action) {
    LimitRecoveryAction.RESUME ->
      LimitRecoveryUpdate(state.runId, resetAt, autoResume = !state.scheduled)
    LimitRecoveryAction.SNOOZE ->
      LimitRecoveryUpdate(state.runId, resetAt, snooze = !state.snoozed)
  }
}

/** The reset has passed: a snooze until then would wake at once. */
fun limitResetPassed(resetAt: String?, now: Instant): Boolean {
  val reset: Long = resetAt?.let(::epochMillis) ?: return false
  return reset <= now.toEpochMilli()
}

/** `Resets ${new Date(resetAt).toLocaleString()}`, or upstream's line without a time. */
fun usageLimitResetDescription(
  resetAt: String?,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
): String {
  val reset: Instant = resetAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    ?: return "Reset time unavailable; retry manually"
  val format: DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
    .withLocale(locale)
    .withZone(zone)
  return "Resets ${format.format(reset)}"
}

private fun epochMillis(iso: String): Long? =
  runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
