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
data class Shelves(
  val settled: Boolean = false,
  val snoozed: Boolean = false,
  val working: Boolean = false,
)

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
  /** Dismisses "Woke": a visit at the wake time. */
  val dismissWoke: ((threadId: String, wokeAt: String) -> Unit)? = null,
  /** Starts renaming a row in place, on a double-click. */
  val rename: ((threadId: String) -> Unit)? = null,
  /** Ends renaming in place: what was typed, or null for Escape. */
  val renamed: (threadId: String, typed: String?) -> Unit = { _, _ -> },
)

/** What rows show besides the thread: their running terminals. */
class RowDetails(
  /** Running terminal ids by thread id, see `RunningTerminals`. */
  val terminals: Map<String, List<String>> = emptyMap(),
  /** Opens a running terminal of a thread. Null hides the row's terminal button's action. */
  val openTerminal: ((threadId: String, terminalId: String) -> Unit)? = null,
)

/**
 * The sections of the threads panel: the inbox (pinned first), then the working, snoozed and
 * settled shelves. In boring mode [folders] holds the inbox's active rows by project, and the
 * inbox is the pinned rows then the folders' rows in folder order.
 */
data class ThreadSections(
  val inbox: List<Thread>,
  val settled: List<Thread>,
  val snoozed: List<Thread> = emptyList(),
  val working: List<Thread> = emptyList(),
  val folders: List<ProjectFolder>? = null,
)

/**
 * Upstream's boring sidebar (Labs, "Boring sidebar (group by project)"): active threads in one
 * folder per project. [multilineRows] is `boringSidebarMultilineRows`, [projectExpandedById]
 * the folders' stored open state, upstream's `projectExpandedById`, by project id.
 */
data class BoringSidebar(
  val multilineRows: Boolean = false,
  val projectExpandedById: Map<String, Boolean> = emptyMap(),
)

/** What a folder header's buttons do, by project id. A null [newThread] hides its pen. */
class FolderActions(
  val toggle: (projectId: String) -> Unit = {},
  val newThread: ((projectId: String) -> Unit)? = null,
  /** The folder was right-clicked at x, y on screen. */
  val contextMenu: (projectId: String, x: Int, y: Int) -> Unit = { _, _, _ -> },
)

/** One boring sidebar folder, as `groupSidebarThreadsByProject` makes them. */
data class ProjectFolder(val project: Project, val threads: List<Thread>)

/**
 * `groupSidebarThreadsByProject`, apps/web/src/components/Sidebar.logic.ts:925. Folders follow
 * [groups] order and threads keep their input order. A project with no threads gets a folder
 * only with [keepEmpty]. A thread whose project is in no group has no folder and is left out.
 * Upstream's groups are logical projects with member refs across environments; with one server
 * a group is one project.
 */
fun groupSidebarThreadsByProject(
  groups: List<Project>,
  threads: List<Thread>,
  keepEmpty: Boolean,
): List<ProjectFolder> {
  val byProject: Map<String, List<Thread>> = threads.groupBy { it.projectId }
  val folders: List<ProjectFolder> =
    groups.map { group -> ProjectFolder(group, byProject[group.id].orEmpty()) }
  return if (keepEmpty) folders else folders.filter { it.threads.isNotEmpty() }
}

/**
 * `resolveProjectExpanded`, apps/web/src/uiStateStore.ts:354: the first of [preferenceKeys]
 * with a stored state, else open. Upstream's legacy default key isn't ported, the plugin never
 * had the old per-project sidebar.
 */
fun resolveProjectExpanded(
  projectExpandedById: Map<String, Boolean>,
  preferenceKeys: List<String>,
): Boolean {
  for (key in preferenceKeys) projectExpandedById[key]?.let { return it }
  return true
}

/**
 * Upstream's `folderGroups` (Sidebar.tsx:1284): in boring mode the projects [filter] scopes,
 * or every project with no scope. Null while it's off.
 */
fun sidebarFolderGroups(
  projects: List<Project>,
  filter: ProjectFilter,
  enabled: Boolean,
): List<Project>? = if (enabled) filter.scopedProjects(projects).ifEmpty { projects } else null

/**
 * Upstream's `keepEmptyFolders: scopedProjectGroups.length > 0`: a scope keeps its empty
 * folders, so a project the user filtered to can start a thread. Not while searching, where
 * upstream shows its search results instead of the folders.
 */
fun keepEmptyFolders(projects: List<Project>, filter: ProjectFilter, query: String): Boolean =
  query.isBlank() && filter.scopedProjects(projects).isNotEmpty()

/**
 * The rows forward navigation walks, upstream's `orderedThreadKeys` (Sidebar.tsx:523) minus the
 * shelves: the inbox, or in boring mode the pinned rows then each folder's visible rows.
 */
fun ThreadSections.cards(
  projectExpandedById: Map<String, Boolean>,
  routeThreadId: String?,
): List<Thread> {
  val folders: List<ProjectFolder> = folders ?: return inbox
  val visible: List<Thread> =
    projectFolders(folders, projectExpandedById, routeThreadId).flatMap { it.threads }
  return inbox.filter { it.pinned } + visible
}

/** A folder as the Sidebar draws it: what it shows, and what a closed one hides. */
data class FolderView(
  val project: Project,
  val collapsed: Boolean,
  val threads: List<Thread>,
  val hiddenThreads: List<Thread>,
)

/**
 * The `projectFolders` of `useSidebarThreads` (Sidebar.tsx:504). A closed folder still shows
 * the open thread, and hints at the rest. Keyed by plain project id where upstream reads
 * `sidebarFolderExpansionKeys`, the old sidebar's keys.
 */
fun projectFolders(
  folders: List<ProjectFolder>,
  projectExpandedById: Map<String, Boolean>,
  routeThreadId: String?,
): List<FolderView> = folders.map { folder ->
  val collapsed: Boolean = !resolveProjectExpanded(projectExpandedById, listOf(folder.project.id))
  FolderView(
    project = folder.project,
    collapsed = collapsed,
    threads = if (collapsed) routeThreadOnly(folder.threads, routeThreadId) else folder.threads,
    hiddenThreads = if (collapsed) {
      folder.threads.filter { it.id != routeThreadId }
    } else {
      emptyList()
    },
  )
}

/** The dot on a closed folder, upstream's `"attention" | "failed" | "unread" | "working"`. */
enum class FolderDot { Attention, Failed, Unread, Working }

/**
 * `resolveSidebarFolderDot`, Sidebar.logic.ts:879: the most urgent state among a closed
 * folder's hidden threads, with [pendingSubagentRequests] by thread id. Waiting threads stay
 * quiet, like their rows. Upstream's "request" (a guest waiting on the owner) counts as
 * attention too; [sidebarStatus] doesn't have it yet.
 */
fun resolveSidebarFolderDot(
  threads: List<Thread>,
  pendingSubagentRequests: Map<String, PendingSubagentRequest> = emptyMap(),
): FolderDot? {
  fun status(thread: Thread): ThreadStatus =
    thread.sidebarStatus(pendingSubagentRequests[thread.id])
  val statuses: Set<ThreadStatus> = threads.map(::status).toSet()
  if (ThreadStatus.Input in statuses ||
    ThreadStatus.Approval in statuses ||
    ThreadStatus.Limited in statuses
  ) {
    return FolderDot.Attention
  }
  if (ThreadStatus.Failed in statuses) return FolderDot.Failed
  // Rows show Done only for ready threads, so the dot follows the same rule
  val unread: Boolean = threads.any { status(it) == ThreadStatus.Ready && it.unread }
  if (unread) return FolderDot.Unread
  if (ThreadStatus.Working in statuses) return FolderDot.Working
  return null
}

val Thread.active: Boolean get() = activeRunId != null || status in RUNNING

val Thread.waitingOnYou: Boolean get() = pendingRuntimeRequest != null || status == "waiting"

// Everything below mirrors upstream's sidebar, see apps/web/src/components/Sidebar.tsx and
// packages/client-runtime/src/state/{threadShell,threadSort}.ts at the pinned tag.

/** Settled is the server's own verdict (`thread.settle`, auto-settle); `settledAt` alone isn't. */
val Thread.settled: Boolean get() = settledOverride == "settled"

/** Subagents live under their parent, not in the list (`navigationThreadShellsAtom`). */
val Thread.shownUnderParent: Boolean get() = lineage.relationshipToParent == "subagent"

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
 * Which T3 projects the Sidebar lists: upstream's project scope (`projectScopeKeys`,
 * Sidebar.tsx), one project or several. Defaulting to the projects an IDE window has open is ours.
 */
sealed interface ProjectFilter {
  data object All : ProjectFilter

  data class Only(val ids: Set<String>) : ProjectFilter
}

fun ProjectFilter.allows(projectId: String): Boolean = when (this) {
  ProjectFilter.All -> true
  is ProjectFilter.Only -> projectId in ids
}

/** Upstream's `scopedProjectGroups`: the [projects] this filter lists, in server order. */
fun ProjectFilter.scopedProjects(projects: List<Project>): List<Project> =
  if (this == ProjectFilter.All) emptyList() else projects.filter { allows(it.id) }

/**
 * `resolveSidebarProjectScopeKeys` (apps/web/src/sidebarProjectGrouping.ts): the stored [keys]
 * that still name one of [projects], duplicates collapsed. A missing one is only dropped when
 * [canClearMissingScope], once every project has loaded; until then it's kept as it is.
 */
fun resolveSidebarProjectScopeKeys(
  projects: List<Project>,
  keys: List<String>,
  canClearMissingScope: Boolean,
): List<String> = keys
  .mapNotNull { key ->
    projects.find { it.id == key }?.id ?: if (canClearMissingScope) null else key
  }
  .distinct()

/**
 * `toggleProjectScopeKey` (Sidebar.tsx): a Shift-click puts [id] in the scope or takes it out.
 * Taking out the last one is all projects again.
 */
fun toggleProjectScopeKey(filter: ProjectFilter, id: String): ProjectFilter {
  val keys: Set<String> = (filter as? ProjectFilter.Only)?.ids.orEmpty()
  val next: Set<String> = if (id in keys) keys - id else keys + id
  return if (next.isEmpty()) ProjectFilter.All else ProjectFilter.Only(next)
}

/**
 * One entry in the project picker. [project] is null for "All projects". A click [picks] it
 * alone, a Shift-click [toggles] it; [selected] is upstream's check badge (`ProjectScopeRowIcon`).
 */
data class FilterChoice(
  val label: String,
  val project: Project?,
  val selected: Boolean,
  val picks: ProjectFilter,
  val toggles: ProjectFilter,
)

/**
 * The project picker: "All projects", then the projects this window has [open], then the rest,
 * with a [divider] above the rest when both are there (ours: an IDE window is about its own
 * projects). [scoped] is upstream's `scopedProjectGroups`, the projects the filter lists in server
 * order, for the trigger's favicons and [tooltip].
 */
data class FilterChoices(
  val entries: List<FilterChoice>,
  val scoped: List<Project>,
  val tooltip: String,
  val divider: Int? = null, // the entry a separator goes above
)

fun filterChoices(
  projects: List<Project>,
  filter: ProjectFilter,
  open: Set<String> = emptySet(),
): FilterChoices {
  val scoped: List<Project> = filter.scopedProjects(projects)
  val (ours: List<Project>, rest: List<Project>) = projects.partition { it.id in open }
  val entries: List<FilterChoice> = buildList {
    // Upstream's "all" always clears the scope, Shift or not
    add(FilterChoice(ALL_PROJECTS, null, scoped.isEmpty(), ProjectFilter.All, ProjectFilter.All))
    (ours + rest).forEach { project ->
      add(
        FilterChoice(
          label = project.title,
          project = project,
          selected = project in scoped,
          picks = ProjectFilter.Only(setOf(project.id)),
          toggles = toggleProjectScopeKey(filter, project.id),
        ),
      )
    }
  }
  val tooltip: String = when {
    scoped.isEmpty() -> FILTER_BY_PROJECT
    else -> "$FILTER_BY_PROJECT: ${scoped.joinToString(", ") { it.title }}"
  }
  val divider: Int? =
    if (ours.isNotEmpty() && rest.isNotEmpty()) entries.size - rest.size else null
  return FilterChoices(entries, scoped, tooltip, divider)
}

const val ALL_PROJECTS: String = "All projects"
const val FILTER_BY_PROJECT: String = "Filter threads by project"

/**
 * What the Sidebar says with no rows to show, so a narrowing filter is visible as one. The scoped
 * wording is upstream's (Sidebar.tsx, the empty thread list).
 */
fun emptyHint(projects: List<Project>, query: String, filter: ProjectFilter): String {
  val scoped: List<Project> = filter.scopedProjects(projects)
  return when {
    projects.isEmpty() -> "No projects yet. Open the T3 Code desktop app to add one."
    query.isNotBlank() -> "No threads match"
    scoped.size > 1 -> "No threads in these projects yet"
    scoped.isNotEmpty() -> "No threads in ${scoped.single().title} yet"
    else -> "No threads"
  }
}

/**
 * Splits [threads] matching [query] by title, as `partitionSidebarThreads` (Sidebar.tsx:594)
 * does. Inbox is pinned rows (by `pinOrderKey`, keyless ones newest first) then active rows.
 * With the Working shelf on ([working] is the tracker's observation) active rows that are
 * working move to the shelf, by `sortWorkingThreadsBySend`, and the rest sort by
 * `sortInboxThreadsByReturn`; with it off active rows sort keyless ones newest by
 * `createdAt`/`unsettledAt`, then by `activeOrderKey`. Snoozed is soonest to wake first, settled
 * newest settled first, each only on a server that does [snooze] or [settlement]; a snooze
 * outranks settled, which outranks a pin (`resolveSidebarThreadSection`, Sidebar.logic.ts:123).
 * Archived and under-parent rows are dropped, and so are rows from projects outside [filter].
 * With [folderGroups] (boring mode, upstream's `folderGroups`) active rows sort by
 * `sortWorkingThreadsBySend` (`sortActiveBySend`) and fold into one folder per group, empty
 * ones kept with [keepEmptyFolders]. The caller turns the Working shelf off for that.
 */
fun sections(
  threads: List<Thread>,
  query: String,
  filter: ProjectFilter = ProjectFilter.All,
  settlement: Boolean = true,
  snooze: Boolean = true,
  now: Instant = Instant.now(),
  working: InboxObservation? = null,
  pendingSubagentRequests: Map<String, PendingSubagentRequest> =
    pendingSubagentRequestsByThread(threads),
  folderGroups: List<Project>? = null,
  keepEmptyFolders: Boolean = false,
): ThreadSections {
  val needle: String = query.trim()
  val visible: List<Thread> = threads.filter {
    !it.archived &&
      !it.shownUnderParent &&
      filter.allows(it.projectId) &&
      it.title.contains(needle, ignoreCase = true)
  }
  val (snoozed: List<Thread>, awake: List<Thread>) =
    visible.partition { snooze && it.snoozed(now, pendingSubagentRequests[it.id]) }
  val (settled: List<Thread>, inbox: List<Thread>) =
    awake.partition { settlement && it.settled }
  val (pinned: List<Thread>, unpinned: List<Thread>) = inbox.partition { it.pinned }
  val (shelved: List<Thread>, active: List<Thread>) = unpinned.partition { thread ->
    working?.isWorking(thread, pendingSubagentRequests[thread.id]) == true
  }
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
  val sortedActive: List<Thread> = when {
    folderGroups != null -> sortWorkingThreadsBySend(active)
    working == null -> active.sortedWith(activeOrder)
    else -> sortInboxThreadsByReturn(active, working.returnedAt)
  }
  val folders: List<ProjectFolder>? = folderGroups?.let { groups ->
    groupSidebarThreadsByProject(groups, sortedActive, keepEmptyFolders)
  }
  return ThreadSections(
    inbox = pinned.sortedWith(pinnedOrder) + (folders?.flatMap { it.threads } ?: sortedActive),
    settled = settled.sortedWith(settledOrder),
    snoozed = snoozed.sortedWith(snoozedOrder),
    working = sortWorkingThreadsBySend(shelved),
    folders = folders,
  )
}

/** `routeThreadOnly`, Sidebar.tsx:388: what a collapsed shelf still shows, the open thread. */
fun routeThreadOnly(threads: List<Thread>, routeThreadId: String?): List<Thread> {
  if (routeThreadId == null) return emptyList()
  return listOfNotNull(threads.find { it.id == routeThreadId })
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

/** How many rows "Show N more" reveals, `0` when all [total] are already [shown]. */
fun moreCount(total: Int, shown: Int): Int = (total - shown).coerceIn(0, NEXT_PAGE)

/** Run statuses `resolveSidebarThreadStatus` reads as working, `threadRunStatusIsActive`. */
internal val WORKING: Set<String> = setOf("preparing", "queued", "starting", "running", "waiting")

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
 * `resolveSidebarThreadStatus`, Sidebar.logic.ts:846, with `hasPendingApprovals` and
 * `hasPendingUserInput` as `presentThreadShell` derives them from the pending request's kind.
 * [pendingSubagentRequest] is a subagent's request flagged on this parent
 * ([pendingSubagentRequestsByThread]). Upstream's "request", a guest waiting on the owner,
 * isn't ported yet; it has no status of its own here.
 */
fun Thread.sidebarStatus(pendingSubagentRequest: PendingSubagentRequest? = null): ThreadStatus {
  val kind: String? = pendingRuntimeRequest?.kind
  val approval: Boolean = kind != null && kind != "user_input" && kind != "auth_refresh"
  if (approval || pendingSubagentRequest == PendingSubagentRequest.Approval) {
    return ThreadStatus.Approval
  }
  if (kind == "user_input" || pendingSubagentRequest == PendingSubagentRequest.UserInput) {
    return ThreadStatus.Input
  }
  val runtime: String? = runtimeStatus()
  if (runtime == "running" && blockedOnThreadWait == true) return ThreadStatus.Waiting
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

fun Thread.topStatus(
  woke: Boolean = false,
  pendingSubagentRequest: PendingSubagentRequest? = null,
): TopStatus? = when (sidebarStatus(pendingSubagentRequest)) {
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
fun Thread.recedes(
  open: Boolean,
  woke: Boolean = false,
  pendingSubagentRequest: PendingSubagentRequest? = null,
): Boolean {
  val status: ThreadStatus = sidebarStatus(pendingSubagentRequest)
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
 * `shouldShowInstanceBadge`, providerInstanceDisplay.ts:57: an instance with an accent colour,
 * or one of several on the same driver, wears its initials. An enabled instance only counts
 * enabled siblings. Upstream's `acpRegistryAgentId` check is left out, the wire doesn't have it.
 */
fun showInstanceBadge(entry: ServerProvider, entries: Collection<ServerProvider>): Boolean {
  if (!entry.accentColor.isNullOrBlank()) return true
  return entries.count { candidate: ServerProvider ->
    candidate.driver == entry.driver && (!entry.enabled || candidate.enabled)
  } > 1
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
