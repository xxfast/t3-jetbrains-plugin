package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.generated.ServerProvider
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

/**
 * Which shelves are open: upstream keeps `t3code:sidebar:settled-expanded` and friends in local
 * storage (Sidebar.tsx:366), closed until opened.
 */
data class Shelves(val settled: Boolean = false, val snoozed: Boolean = false)

/**
 * What a row's hover buttons do. A null member hides its button: the server doesn't do it, or
 * this pairing can't operate.
 */
class RowActions(
  /** Settles a card, un-settles a settled row. */
  val settle: ((threadId: String) -> Unit)? = null,
  /** Opens the snooze presets at x, y on screen. */
  val snooze: ((threadId: String, x: Int, y: Int) -> Unit)? = null,
  /** Wakes a snoozed row now. */
  val wake: ((threadId: String) -> Unit)? = null,
  /** Dismisses "Woke": a visit at the wake time. Reading needs no operate scope. */
  val dismissWoke: ((threadId: String, wokeAt: String) -> Unit)? = null,
)

/**
 * The sections of the threads panel: the inbox (pinned first), then the snoozed and settled
 * shelves.
 */
data class ThreadSections(
  val inbox: List<Thread>,
  val settled: List<Thread>,
  val snoozed: List<Thread> = emptyList(),
)

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
 * Which T3 projects the Sidebar lists. Upstream's sidebar has no such filter; this one is ours,
 * so an IDE window can default to the projects it has open.
 */
sealed interface ProjectFilter {
  data object All : ProjectFilter

  data class Only(val ids: Set<String>) : ProjectFilter
}

fun ProjectFilter.allows(projectId: String): Boolean = when (this) {
  ProjectFilter.All -> true
  is ProjectFilter.Only -> projectId in ids
}

/**
 * The project picker's entries: "All projects", then "N projects" while a match picked several,
 * then each project. [filters] runs parallel to [labels]; [selected] indexes both.
 */
data class FilterChoices(
  val labels: List<String>,
  val filters: List<ProjectFilter>,
  val selected: Int,
  val names: List<String>, // what the current filter lists, for the tooltip
)

fun filterChoices(projects: List<Project>, filter: ProjectFilter): FilterChoices {
  val chosen: List<Project> = projects.filter { filter.allows(it.id) }
  val several: Boolean = filter is ProjectFilter.Only && chosen.size > 1
  val labels: List<String> = buildList {
    add(ALL_PROJECTS)
    if (several) add("${chosen.size} projects")
    projects.forEach { add(it.title) }
  }
  val filters: List<ProjectFilter> = buildList {
    add(ProjectFilter.All)
    if (several) add(filter)
    projects.forEach { add(ProjectFilter.Only(setOf(it.id))) }
  }
  val selected: Int = filters.indexOf(filter).coerceAtLeast(0)
  val names: List<String> =
    if (filter == ProjectFilter.All) emptyList() else chosen.map { it.title }
  return FilterChoices(labels, filters, selected, names)
}

const val ALL_PROJECTS: String = "All projects"

/** What the Sidebar says with no rows to show, so a narrowing filter is visible as one. */
fun emptyHint(noProjects: Boolean, query: String, filter: ProjectFilter): String = when {
  noProjects -> "No projects yet. Open the T3 Code desktop app to add one."
  query.isNotBlank() -> "No threads match"
  filter is ProjectFilter.Only && filter.ids.size == 1 -> "No threads in this project"
  filter is ProjectFilter.Only -> "No threads in these projects"
  else -> "No threads"
}

/**
 * Splits [threads] matching [query] by title. Inbox is pinned rows (by `pinOrderKey`, keyless
 * ones newest first) then active rows (keyless ones newest by `createdAt`/`unsettledAt`, then by
 * `activeOrderKey`). Snoozed is soonest to wake first, settled newest settled first, each only on
 * a server that does [snooze] or [settlement]; a snooze outranks settled, which outranks a pin
 * (`resolveSidebarThreadSection`, Sidebar.logic.ts:123). Archived and under-parent rows are
 * dropped, and so are rows from projects outside [filter].
 */
fun sections(
  threads: List<Thread>,
  query: String,
  filter: ProjectFilter = ProjectFilter.All,
  settlement: Boolean = true,
  snooze: Boolean = true,
  now: Instant = Instant.now(),
): ThreadSections {
  val needle: String = query.trim()
  val ids: Set<String> = threads.mapTo(HashSet()) { it.id }
  val visible: List<Thread> = threads.filter {
    !it.archived &&
      !it.shownUnderParent(ids) &&
      filter.allows(it.projectId) &&
      it.title.contains(needle, ignoreCase = true)
  }
  val (snoozed: List<Thread>, awake: List<Thread>) = visible.partition { snooze && it.snoozed(now) }
  val (settled: List<Thread>, inbox: List<Thread>) =
    awake.partition { settlement && it.settled }
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
  val snoozedOrder: Comparator<Thread> = compareBy<Thread> { it.snoozedUntil?.toInstantOrNull() }
    .thenBy { it.id }
  return ThreadSections(
    inbox = pinned.sortedWith(pinnedOrder) + active.sortedWith(activeOrder),
    settled = settled.sortedWith(settledOrder),
    snoozed = snoozed.sortedWith(snoozedOrder),
  )
}

/**
 * Where settling the open [current] thread moves to: the next inbox card after it, wrapping
 * round, as `planForwardNavigation` (Sidebar.tsx:3213) picks it. Null when [current] isn't a
 * card or it's the only one; upstream then starts a new thread, which the plugin can't yet (#17).
 */
fun nextCard(inbox: List<Thread>, current: String): String? {
  val index: Int = inbox.indexOfFirst { it.id == current }
  if (index == -1) return null
  return (inbox.drop(index + 1) + inbox.take(index)).firstOrNull()?.id
}

/** A collapsed shelf's header counts its rows, as the section headers at Sidebar.tsx:4982 do. */
fun shelfLabel(name: String, open: Boolean, count: Int): String =
  if (open) name else "$name ($count)"

/** How many rows "Show N more" reveals, `0` when all [total] are already [shown]. */
fun moreCount(total: Int, shown: Int): Int = (total - shown).coerceIn(0, NEXT_PAGE)

/** Run statuses `resolveSidebarThreadStatus` reads as working. */
private val WORKING: Set<String> = setOf("preparing", "queued", "starting", "running", "waiting")

/** Run statuses that ended: `terminalRunStatus`, packages/client-runtime/src/state/models.ts. */
private val TERMINAL: Set<String> =
  setOf("completed", "interrupted", "failed", "cancelled", "rolled_back")

/**
 * The runtime's status as `shellRuntime` (packages/client-runtime/src/state/models.ts) derives
 * it from the shell row: none without a run or provider thread, idle while background work that
 * holds completion is pending, else the activity run's status, else the thread's.
 */
fun Thread.runtimeStatus(): String? {
  val parkAtIdle: Boolean =
    pendingBackgroundTasks.orEmpty().any { it.kind != "command" } && status != "failed"
  if (latestRunId == null && activeProviderThreadId == null && !parkAtIdle) return null
  return if (parkAtIdle) "idle" else activityRunStatus ?: status
}

/** Upstream's `SidebarThreadStatus`, apps/web/src/components/Sidebar.logic.ts:883. */
enum class ThreadStatus { Approval, Input, Working, Waiting, Failed, Limited, Ready }

/**
 * `resolveSidebarThreadStatus`, Sidebar.logic.ts:912, with `hasPendingApprovals` and
 * `hasPendingUserInput` as `presentThreadShell` derives them from the pending request's kind.
 * Requests on subagent rows are flagged on their parent upstream; the plugin hides those rows.
 */
fun Thread.sidebarStatus(): ThreadStatus {
  val kind: String? = pendingRuntimeRequest?.kind
  if (kind != null && kind != "user_input" && kind != "auth_refresh") return ThreadStatus.Approval
  if (kind == "user_input") return ThreadStatus.Input
  val runtime: String? = runtimeStatus()
  return when {
    runtime in WORKING -> ThreadStatus.Working
    runtime == "idle" -> ThreadStatus.Waiting
    runtime == "failed" ->
      if (lastErrorClass == "usage_limit") ThreadStatus.Limited else ThreadStatus.Failed
    else -> ThreadStatus.Ready
  }
}

/** A card's top-right status, Sidebar.tsx:1676 to 1724. [woke] is [Thread.woke]. */
enum class TopStatus { Working, Waiting, Approval, Input, Limited, Failed, Woke, Done }

fun Thread.topStatus(woke: Boolean = false): TopStatus? = when (sidebarStatus()) {
  ThreadStatus.Working -> TopStatus.Working
  ThreadStatus.Waiting -> TopStatus.Waiting
  ThreadStatus.Approval -> TopStatus.Approval
  ThreadStatus.Input -> TopStatus.Input
  ThreadStatus.Limited -> TopStatus.Limited
  ThreadStatus.Failed -> TopStatus.Failed
  ThreadStatus.Ready -> when {
    woke -> TopStatus.Woke
    unread -> TopStatus.Done
    else -> null
  }
}

/** The status label; a working thread with an active goal says "Goal". */
fun Thread.topStatusLabel(top: TopStatus): String = when (top) {
  TopStatus.Working -> if (goal?.status == "active") "Goal" else "Working"
  else -> top.name
}

/**
 * `shouldRecedeSidebarThread`, Sidebar.logic.ts:892. [open] is upstream's `isActive`, the thread
 * the ChatView shows. Multi-select isn't ported.
 */
fun Thread.recedes(open: Boolean, woke: Boolean = false): Boolean {
  val status: ThreadStatus = sidebarStatus()
  return when {
    open || status == ThreadStatus.Input -> false
    status == ThreadStatus.Working || status == ThreadStatus.Waiting -> true
    status == ThreadStatus.Ready || status == ThreadStatus.Approval -> !unread && !woke
    else -> false
  }
}

/**
 * Where the "Working" clock starts, `resolveThreadWorkingStartedAt` (models.ts:311): the
 * activity run's start, else the active run's start or request time while it's unfinished.
 * Null means no clock. Upstream stops at an explicit null activity start; the shell's optional
 * and null collapse here, so that case falls through to the run.
 */
fun Thread.workingStartedAt(): Instant? {
  activityRunStartedAt?.let { return it.toInstantOrNull() }
  val active: Boolean =
    latestRunId != null && latestRunCompletedAt == null && latestRunId == activeRunId
  if (!active) return null
  return (latestRunStartedAt ?: latestRunRequestedAt)?.toInstantOrNull()
}

/**
 * Where the "Waiting" clock starts: `latestRun.completedAt` as `presentThreadShell` fills it,
 * which is `updatedAt` when the run ended without a stamp.
 */
fun Thread.waitingSince(): Instant? {
  if (latestRunId == null) return null
  latestRunCompletedAt?.let { return it.toInstantOrNull() }
  return if (status == "idle" || status in TERMINAL) updatedAt.toInstantOrNull() else null
}

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

/** The card's clock, `formatWorkingDurationLabel` (Sidebar.logic.ts:1080): `22s`, `3m`, `2h 5m`. */
fun workingDuration(elapsed: Duration): String {
  val seconds: Long = elapsed.seconds.coerceAtLeast(0)
  if (seconds < 60) return "${seconds}s"
  val minutes: Long = seconds / 60
  if (minutes < 60) return "${minutes}m"
  return "${minutes / 60}h ${minutes % 60}m"
}

/**
 * `resolveThreadProviderStack`, models.ts:299: up to two earlier instances, then the current
 * one. The runtime's instance is the row's own; without a runtime the model selection's.
 */
fun Thread.providerStack(): List<String> {
  val current: String = when {
    runtimeStatus() != null -> providerInstanceId
    else -> modelSelection.instance ?: providerInstanceId
  }
  val previous: List<String> = providerInstanceHistory.orEmpty().filter { it != current }
  return previous.takeLast(PROVIDER_STACK_LIMIT - 1) + current
}

private const val PROVIDER_STACK_LIMIT: Int = 3

/** `providerInstanceInitials`, packages/client-runtime/src/state/providerInstanceDisplay.ts:29. */
fun providerInitials(label: String): String {
  val words: List<String> =
    label.replace(Regex("[_-]+"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
  return when (words.size) {
    0 -> ""
    1 -> words[0].take(2).uppercase()
    else -> words.take(2).joinToString("") { it.take(1).uppercase() }
  }
}

/**
 * `shouldShowInstanceBadge`, providerInstanceDisplay.ts:45: an instance with an accent colour,
 * or one of several on the same driver, wears its initials.
 */
fun showInstanceBadge(entry: ServerProvider, entries: Collection<ServerProvider>): Boolean {
  if (!entry.accentColor.isNullOrBlank()) return true
  return entries.count { it.driver == entry.driver } > 1
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

/** The sidebar's row time, `compactSidebarTimeLabel` (Sidebar.tsx:832): "now", "5m", "3h". */
fun compactTime(then: Instant, now: Instant): String =
  relativeTime(then, now).let { if (it == "just now") "now" else it }

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
