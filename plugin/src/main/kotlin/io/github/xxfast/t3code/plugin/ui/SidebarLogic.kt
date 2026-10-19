package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.generated.ThreadLinkedPullRequest
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestLink
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestSnapshot
import io.github.xxfast.t3code.protocol.pinned
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** Thread and run statuses that mean work is in flight. */
val RUNNING: Set<String> = setOf("preparing", "queued", "starting", "running")

/** Settled rows shown before the first "Show N more", and how many each press reveals. */
const val FIRST_PAGE: Int = 10
const val NEXT_PAGE: Int = 25

/** The two sections of the threads panel: the inbox (pinned first) and settled. */
data class ThreadSections(val inbox: List<Thread>, val settled: List<Thread>)

val Thread.active: Boolean get() = activeRunId != null || status in RUNNING

val Thread.waitingOnYou: Boolean get() = pendingRuntimeRequest != null || status == "waiting"

// Everything below mirrors upstream's sidebar, see apps/web/src/components/Sidebar.tsx and
// packages/client-runtime/src/state/{threadShell,threadSort}.ts at the pinned tag.

/** Settled is the server's own verdict (`thread.settle`, auto-settle); `settledAt` alone isn't. */
val Thread.settled: Boolean get() = settledOverride == "settled"

/**
 * Subagents and delegated tasks live under their parent, not in the list. A delegated task whose
 * parent this client can't see stays listed, so its work is never out of reach.
 */
fun Thread.shownUnderParent(ids: Set<String>): Boolean =
  lineage.relationshipToParent == "subagent" || delegatedFrom?.threadId?.let { it in ids } == true

/** What an inbox row's time label shows. */
fun Thread.inboxAt(): Instant = (latestUserMessageAt ?: updatedAt).toInstant()

/** What a settled row shows and sorts by: when it settled, else its last activity. */
fun Thread.settledAt(): Instant {
  settledAt?.let { return it.toInstant() }
  val activity: Instant? = listOfNotNull(
    latestUserMessageAt, latestRunRequestedAt, latestRunStartedAt, latestRunCompletedAt,
  ).map { it.toInstant() }.maxOrNull()
  return activity ?: updatedAt.toInstant()
}

/** Where an active row anchors in time: created, or woken from settled, whichever is later. */
private fun Thread.activeAnchor(): Instant =
  maxOf(createdAt.toInstant(), (unsettledAt ?: createdAt).toInstant())

/**
 * Splits [threads] matching [query] by title. Inbox is pinned rows (by `pinOrderKey`, keyless
 * ones newest first) then active rows (keyless ones newest by `createdAt`/`unsettledAt`, then by
 * `activeOrderKey`). Settled is newest settled first. Archived and under-parent rows are dropped.
 */
fun sections(threads: List<Thread>, query: String): ThreadSections {
  val needle: String = query.trim()
  val ids: Set<String> = threads.mapTo(HashSet()) { it.id }
  val visible: List<Thread> = threads.filter {
    !it.archived && !it.shownUnderParent(ids) && it.title.contains(needle, ignoreCase = true)
  }
  val (settled: List<Thread>, inbox: List<Thread>) = visible.partition { it.settled }
  val (pinned: List<Thread>, active: List<Thread>) = inbox.partition { it.pinned }
  val pinnedOrder: Comparator<Thread> = compareBy<Thread> { it.pinOrderKey == null }
    .thenBy { it.pinOrderKey }
    .thenByDescending { it.createdAt.toInstant() }
    .thenBy { it.id }
  val activeOrder: Comparator<Thread> = compareBy<Thread> { it.activeOrderKey != null }
    .thenBy { it.activeOrderKey }
    .thenByDescending { it.activeAnchor() }
    .thenBy { it.id }
  val settledOrder: Comparator<Thread> = compareByDescending<Thread> { it.settledAt() }
    .thenBy { it.id }
  return ThreadSections(
    inbox = pinned.sortedWith(pinnedOrder) + active.sortedWith(activeOrder),
    settled = settled.sortedWith(settledOrder),
  )
}

/** How many rows "Show N more" reveals, `0` when all [total] are already [shown]. */
fun moreCount(total: Int, shown: Int): Int = (total - shown).coerceIn(0, NEXT_PAGE)

/**
 * Where the "Working" clock starts. The list row has no `Run`, so this is the shell's own copy of
 * the run start, falling back to `updatedAt` when the server didn't send one.
 */
fun Thread.runStartedAt(): Instant =
  (activityRunStartedAt ?: latestRunStartedAt ?: latestRunRequestedAt ?: updatedAt).toInstant()

/** `22s`, `1m 12s`, `2h 5m`. Zero trailing parts are dropped, negative durations are `0s`. */
fun duration(elapsed: Duration): String {
  val seconds: Long = elapsed.seconds.coerceAtLeast(0)
  fun parts(big: Long, bigUnit: String, small: Long, smallUnit: String): String =
    if (small == 0L) "$big$bigUnit" else "$big$bigUnit $small$smallUnit"
  return when {
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> parts(seconds / 60, "m", seconds % 60, "s")
    else -> parts(seconds / 3_600, "h", seconds % 3_600 / 60, "m")
  }
}

/**
 * What the row's pull request badge shows, mirroring upstream's `resolveThreadCurrentPullRequest`
 * minus stack chains: one open link shows as itself, several open ones as a stack of that many,
 * none open as the most recently updated link. Older servers only have `linkedPullRequest`.
 */
data class PullRequestBadge(val state: String, val text: String, val url: String)

fun Thread.pullRequestBadge(): PullRequestBadge? {
  val visible: List<ThreadPullRequestLink> =
    pullRequests.orEmpty().filter { it.source != "stack-dismissed" }
  if (visible.isEmpty()) {
    val legacy: ThreadLinkedPullRequest = linkedPullRequest ?: return null
    return PullRequestBadge("pending", legacy.number.toString(), legacy.url)
  }
  val open: List<ThreadPullRequestLink> =
    visible.filter { it.snapshot == null || it.snapshot?.state == "open" }
  if (open.size > 1) {
    val allDraft: Boolean = open.all { it.snapshot?.isDraft == true }
    val state: String = if (allDraft) "draft" else "stack"
    return PullRequestBadge(state, open.size.toString(), open.first().url)
  }
  val link: ThreadPullRequestLink = open.singleOrNull()
    ?: visible.maxBy { link -> link.lastTouched() }
  val snapshot: ThreadPullRequestSnapshot? = link.snapshot
  val state: String = when {
    snapshot == null -> "pending"
    snapshot.state == "open" && snapshot.isDraft -> "draft"
    else -> snapshot.state
  }
  return PullRequestBadge(state, link.number.toString(), link.url)
}

private fun ThreadPullRequestLink.lastTouched(): Instant =
  (snapshot?.updatedAt ?: snapshot?.syncedAt ?: linkedAt).toInstant()

/** `just now`, `5m`, `3h`, `12d`: upstream's `formatRelativeElapsed`, value only. */
fun relativeTime(then: Instant, now: Instant): String {
  val elapsed: Duration = Duration.between(then, now)
  return when {
    elapsed < Duration.ofMinutes(1) -> "just now"
    elapsed < Duration.ofHours(1) -> "${elapsed.toMinutes()}m"
    elapsed < Duration.ofDays(1) -> "${elapsed.toHours()}h"
    else -> "${elapsed.toDays()}d"
  }
}

fun String.toInstant(): Instant = toInstantOrNull() ?: Instant.EPOCH

/** `null` where upstream's `Date.parse` gives `NaN`. */
fun String.toInstantOrNull(): Instant? =
  try {
    Instant.parse(this)
  } catch (_: DateTimeParseException) {
    null
  }

/**
 * A run finished since the last visit: upstream's `hasUnseenCompletion`,
 * apps/web/src/components/Sidebar.logic.ts:718. No visit on record reads as seen, a visit that
 * doesn't parse reads as unseen.
 */
val Thread.unread: Boolean
  get() {
    val completedAt: Instant = latestRunCompletedAt?.toInstantOrNull() ?: return false
    val visited: String = lastVisitedAt?.takeIf { it.isNotEmpty() } ?: return false
    val visitedAt: Instant = visited.toInstantOrNull() ?: return true
    return completedAt.isAfter(visitedAt)
  }

/**
 * A card's top status after working and waiting on you, ahead of unread. Upstream's
 * `resolveSidebarThreadStatus` (apps/web/src/components/Sidebar.logic.ts:912) and the card's
 * precedence (apps/web/src/components/Sidebar.tsx:1705 to 1724). Woke isn't ported: the shell row
 * carries no snooze wake time.
 */
enum class CardStatus { Failed, Limited, Done }

fun Thread.cardStatus(): CardStatus? = when {
  status == "failed" && lastErrorClass == "usage_limit" -> CardStatus.Limited
  status == "failed" -> CardStatus.Failed
  unread -> CardStatus.Done
  else -> null
}

// Visits mirror `ThreadVisitWatermark`, apps/web/src/components/ChatView.tsx:8667 to 8725.

/** `VISIT_DISPATCH_THROTTLE_MS`, ChatView.tsx:566. */
val VISIT_THROTTLE: Duration = Duration.ofSeconds(10)

/** One visit per watermark: upstream's `${threadKey}:${updatedAt}` dispatch key. */
val Thread.visitKey: String get() = "$id:$updatedAt"

/**
 * Whether looking at [row] should send a `thread.visit` at its `updatedAt`: the last visit is
 * missing, unparsable or behind the row, and this watermark isn't [lastDispatched] already. The
 * shell's `lastVisitedAt` is `NullOr` and always sent, so upstream's `!== undefined` gate always
 * passes; a never-visited thread gets a visit. The dedupe is also what keeps a mark-unread on the
 * open thread sticky: the rewind leaves `updatedAt` alone.
 */
fun shouldVisit(row: Thread, lastDispatched: String?): Boolean {
  val updatedAt: Instant = row.updatedAt.toInstantOrNull() ?: return false
  val visitedAt: Instant? = row.lastVisitedAt?.toInstantOrNull()
  if (visitedAt != null && !visitedAt.isBefore(updatedAt)) return false
  return row.visitKey != lastDispatched
}

/**
 * The watermark's own `hasUnseenCompletion`, ChatView.tsx:8700 to 8703. Unlike the Sidebar's
 * [unread], a missing or unparsable visit counts as unseen.
 */
fun unseenCompletion(row: Thread): Boolean {
  val completedAt: Instant = row.latestRunCompletedAt?.toInstantOrNull() ?: return false
  val visitedAt: Instant = row.lastVisitedAt?.toInstantOrNull() ?: return true
  return completedAt.isAfter(visitedAt)
}

/** How long to hold a due visit: none for an unseen completion, else the rest of the throttle. */
fun visitDelay(row: Thread, sinceLastVisit: Duration): Duration =
  if (unseenCompletion(row) || sinceLastVisit >= VISIT_THROTTLE) {
    Duration.ZERO
  } else {
    VISIT_THROTTLE - sinceLastVisit
  }
