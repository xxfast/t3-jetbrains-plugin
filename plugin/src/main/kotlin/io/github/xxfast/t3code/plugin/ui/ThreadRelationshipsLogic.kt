package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.Subagent
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ProviderRef
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ThreadForkSourcePoint
import io.github.xxfast.t3code.protocol.generated.ThreadShell
import java.time.Instant
import java.time.format.DateTimeParseException

// The Lineage section of upstream's thread details, at the tag in contracts/T3CODE_VERSION:
// the graph is packages/client-runtime/src/state/threadRelationships.ts, the panel is
// `ThreadRelationshipsPanel` in apps/web/src/components/chat/ThreadRelationshipsControl.tsx.

/** `ThreadRelationshipKind`, threadRelationships.ts:9. */
enum class ThreadRelationshipKind { Parent, Fork, Subagent, Transfer }

/** `ThreadRelationshipNode`, threadRelationships.ts:11. A missing node has no shell. */
data class ThreadRelationshipNode(val threadId: String, val thread: Thread?, val missing: Boolean)

/** `ThreadRelationshipEdge`, threadRelationships.ts:17. */
data class ThreadRelationshipEdge(
  val sourceThreadId: String,
  val targetThreadId: String,
  val kind: ThreadRelationshipKind,
  val status: String?,
)

/** `ThreadRelationshipGraph`, threadRelationships.ts:24. Nodes keep insertion order. */
data class ThreadRelationshipGraph(
  val nodes: Map<String, ThreadRelationshipNode>,
  val edges: List<ThreadRelationshipEdge>,
)

/** `ThreadRelationshipWalkRow`, threadRelationships.ts:29. */
data class ThreadRelationshipWalkRow(
  val threadId: String,
  val fromThreadId: String,
  val depth: Int,
  val edge: ThreadRelationshipEdge,
)

/** `forkedFrom.threadId` for a run fork, else `lineage.parentThreadId`, as upstream reads both. */
private val Thread.relationshipParentThreadId: String?
  get() = (forkedFrom as? ThreadShell.ForkedFrom.ForkedFromRun)?.threadId
    ?: lineage.parentThreadId

/** `resolveMergeBackTargetThreadId`, threadRelationships.ts:36. Only a fork merges back. */
fun resolveMergeBackTargetThreadId(projection: ThreadView?): String? {
  val thread: Thread = projection?.thread ?: return null
  if (thread.lineage.relationshipToParent != "fork") return null
  return thread.relationshipParentThreadId
}

private fun edgeKey(edge: ThreadRelationshipEdge): String =
  "${edge.sourceThreadId}\u001f${edge.targetThreadId}\u001f${edge.kind}"

/** `deriveThreadRelationshipGraph`, threadRelationships.ts:49. */
fun deriveThreadRelationshipGraph(
  threads: List<Thread>,
  projection: ThreadView?,
): ThreadRelationshipGraph {
  val threadsById: LinkedHashMap<String, Thread> = LinkedHashMap()
  // Callers order shells from most to least authoritative. In particular, live shells precede
  // archived snapshots, which may still contain a stale copy during archive refresh.
  for (thread in threads) threadsById.putIfAbsent(thread.id, thread)
  val nodes: LinkedHashMap<String, ThreadRelationshipNode> = LinkedHashMap()
  for (thread in threadsById.values) {
    nodes[thread.id] = ThreadRelationshipNode(thread.id, thread, missing = false)
  }
  val edgesByKey: LinkedHashMap<String, ThreadRelationshipEdge> = LinkedHashMap()
  fun ensureNode(threadId: String) {
    if (threadId !in nodes) nodes[threadId] = ThreadRelationshipNode(threadId, null, missing = true)
  }
  fun addEdge(edge: ThreadRelationshipEdge) {
    ensureNode(edge.sourceThreadId)
    ensureNode(edge.targetThreadId)
    val rolledBack: Boolean =
      edge.kind == ThreadRelationshipKind.Subagent && edge.status == "rolled_back"
    edgesByKey[edgeKey(edge)] = if (rolledBack) edge.copy(status = "interrupted") else edge
  }

  for (thread in threadsById.values) {
    val parentThreadId: String = thread.relationshipParentThreadId ?: continue
    addEdge(
      ThreadRelationshipEdge(
        sourceThreadId = parentThreadId,
        targetThreadId = thread.id,
        kind = if (thread.lineage.relationshipToParent == "subagent") {
          ThreadRelationshipKind.Subagent
        } else {
          ThreadRelationshipKind.Fork
        },
        status = thread.activityRunStatus ?: thread.status,
      ),
    )
  }

  if (projection != null) {
    val ownerThreadId: String = projection.thread.id
    for (subagent in projection.subagents) {
      val childThreadId: String = subagent.childThreadId ?: continue
      val child: Thread? = threadsById[childThreadId]
      // A finished subagent whose child ran since says what the child's run ended as
      val status: String = child?.activityRunStatus ?: when {
        child?.latestRunId != null && !isOrchestrationV2WorkActive(subagent.status) -> child.status
        else -> subagent.status
      }
      addEdge(
        ThreadRelationshipEdge(
          sourceThreadId = ownerThreadId,
          targetThreadId = childThreadId,
          kind = ThreadRelationshipKind.Subagent,
          status = status,
        ),
      )
    }
    for (transfer in projection.contextTransfers) {
      if (transfer.sourceThreadId == transfer.targetThreadId) continue
      addEdge(
        ThreadRelationshipEdge(
          sourceThreadId = transfer.sourceThreadId,
          targetThreadId = transfer.targetThreadId,
          kind = ThreadRelationshipKind.Transfer,
          status = transfer.status,
        ),
      )
    }
  }

  return ThreadRelationshipGraph(nodes, edgesByKey.values.toList())
}

/** The thread at the other end of [edge] from [threadId], or null when it doesn't touch it. */
private fun ThreadRelationshipEdge.other(threadId: String): String? = when (threadId) {
  sourceThreadId -> targetThreadId
  targetThreadId -> sourceThreadId
  else -> null
}

/** `relatedThreadIds`, threadRelationships.ts:127. */
fun relatedThreadIds(graph: ThreadRelationshipGraph, threadId: String): List<String> {
  val ids: LinkedHashSet<String> = LinkedHashSet()
  for (edge in graph.edges) {
    if (edge.sourceThreadId == threadId) ids += edge.targetThreadId
    if (edge.targetThreadId == threadId) ids += edge.sourceThreadId
  }
  return ids.toList()
}

/** `walkThreadRelationships`, threadRelationships.ts:139: breadth first, each thread once. */
fun walkThreadRelationships(
  graph: ThreadRelationshipGraph,
  threadId: String,
): List<ThreadRelationshipWalkRow> {
  val visited: MutableSet<String> = mutableSetOf(threadId)
  val pending: ArrayDeque<Pair<String, Int>> = ArrayDeque(listOf(threadId to 0))
  val rows: MutableList<ThreadRelationshipWalkRow> = mutableListOf()
  while (pending.isNotEmpty()) {
    val (currentId: String, currentDepth: Int) = pending.removeFirst()
    for (edge in graph.edges) {
      val relatedId: String = edge.other(currentId) ?: continue
      if (relatedId in visited) continue
      visited += relatedId
      val depth: Int = currentDepth + 1
      rows += ThreadRelationshipWalkRow(relatedId, currentId, depth, edge)
      pending += relatedId to depth
    }
  }
  return rows
}

/** `immediateThreadRelationships`, threadRelationships.ts:170: first edge per related thread. */
fun immediateThreadRelationships(
  graph: ThreadRelationshipGraph,
  threadId: String,
): List<ThreadRelationshipWalkRow> {
  val visited: MutableSet<String> = mutableSetOf()
  val rows: MutableList<ThreadRelationshipWalkRow> = mutableListOf()
  for (edge in graph.edges) {
    val relatedId: String = edge.other(threadId) ?: continue
    if (relatedId in visited) continue
    visited += relatedId
    rows += ThreadRelationshipWalkRow(relatedId, threadId, depth = 1, edge = edge)
  }
  return rows
}

/** `isParentThreadRelationship`, threadRelationships.ts:192. */
fun isParentThreadRelationship(edge: ThreadRelationshipEdge, currentThreadId: String): Boolean =
  edge.kind != ThreadRelationshipKind.Transfer && edge.targetThreadId == currentThreadId

/**
 * `threadRelationshipRowStatus`, threadRelationships.ts:199: the edge's status for a transfer or
 * a child, else the related thread's own.
 */
fun threadRelationshipRowStatus(
  graph: ThreadRelationshipGraph,
  row: ThreadRelationshipWalkRow,
): String? {
  if (row.edge.kind == ThreadRelationshipKind.Transfer || row.threadId == row.edge.targetThreadId) {
    return row.edge.status
  }
  val thread: Thread? = graph.nodes[row.threadId]?.thread
  return thread?.activityRunStatus ?: thread?.status
}

/** Milliseconds since the epoch of an ISO timestamp, null when it doesn't parse (`Date.parse`). */
private fun epochMillis(iso: String?): Long? = iso?.let {
  try {
    Instant.parse(it).toEpochMilli()
  } catch (_: DateTimeParseException) {
    null
  }
}

/** `threadCreatedAtMillis`, threadRelationships.ts:210. */
private fun threadCreatedAtMillis(node: ThreadRelationshipNode?): Long? =
  epochMillis(node?.thread?.createdAt)

/**
 * `orderWebThreadLineageRows`, threadRelationships.ts:224. Web-specific by design: the parent
 * row first, a distinct merge-back target second, then newest created first. Missing and undated
 * threads sink.
 */
fun orderWebThreadLineageRows(
  graph: ThreadRelationshipGraph,
  rows: List<ThreadRelationshipWalkRow>,
  currentThreadId: String,
  mergeTargetThreadId: String?,
): List<ThreadRelationshipWalkRow> {
  fun pinRank(row: ThreadRelationshipWalkRow): Int = when {
    isParentThreadRelationship(row.edge, currentThreadId) -> 0
    row.threadId == mergeTargetThreadId -> 1
    else -> 2
  }
  return rows.sortedWith { left, right ->
    val rankDelta: Int = pinRank(left) - pinRank(right)
    if (rankDelta != 0) return@sortedWith rankDelta
    val leftCreatedAt: Long? = threadCreatedAtMillis(graph.nodes[left.threadId])
    val rightCreatedAt: Long? = threadCreatedAtMillis(graph.nodes[right.threadId])
    if (leftCreatedAt != rightCreatedAt) {
      if (leftCreatedAt == null) return@sortedWith 1
      if (rightCreatedAt == null) return@sortedWith -1
      return@sortedWith rightCreatedAt.compareTo(leftCreatedAt)
    }
    left.threadId.compareTo(right.threadId)
  }
}

/** `threadRelationshipStatusLabel`, apps/web/src/components/chat/ThreadRelationshipIcon.tsx:6. */
fun threadRelationshipStatusLabel(status: String?): String = when (status) {
  "preparing", "starting" -> "Starting"
  "running", "in_progress" -> "Running"
  "pending", "queued" -> "Queued"
  "waiting", "blocked" -> "Waiting"
  "completed" -> "Done"
  "failed", "error" -> "Failed"
  "cancelled", "interrupted" -> "Stopped"
  "rolled_back" -> "Reverted"
  "resolved_native" -> "Resolved (native)"
  "resolved_portable" -> "Resolved (portable)"
  "consumed" -> "Consumed"
  "superseded" -> "Superseded"
  "idle" -> "Idle"
  else -> "Unknown"
}

/** The colour of a relationship icon's status dot, `bg-info` and friends. */
enum class ThreadRelationshipTone { Info, Destructive, Success, Muted }

/** `ThreadRelationshipIcon`'s dot, ThreadRelationshipIcon.tsx:77. */
fun threadRelationshipStatusTone(status: String?): ThreadRelationshipTone = when (status) {
  "running", "in_progress", "pending", "waiting" -> ThreadRelationshipTone.Info
  "failed", "error" -> ThreadRelationshipTone.Destructive
  "completed" -> ThreadRelationshipTone.Success
  else -> ThreadRelationshipTone.Muted
}

/**
 * `formatSubagentDisplayTitle`, packages/client-runtime/src/state/subagentDisplay.ts:41: drops a
 * `Subagent:` prefix, and makes a `/root/.../some_agent` path its last segment, title cased.
 */
fun formatSubagentDisplayTitle(title: String): String {
  val displayTitle: String = title.replace(SUBAGENT_PREFIX, "")
  val path: MatchResult = ROOT_PATH.find(displayTitle) ?: return displayTitle
  val name: String = path.groupValues[1].replace(SEPARATORS, " ").trim()
  return name.replace(WORD_START) { start -> start.value.uppercase() }.ifEmpty { displayTitle }
}

private val SUBAGENT_PREFIX: Regex = Regex("^Subagent:\\s*", RegexOption.IGNORE_CASE)
private val ROOT_PATH: Regex = Regex("^/root/(?:[^/]+/)*([^/]+)/?$")
private val SEPARATORS: Regex = Regex("[_\\s]+")
private val WORD_START: Regex = Regex("(^|\\s)\\S")

/** `isOrchestrationV2WorkActive`, packages/contracts/src/orchestrationV2.ts:735. */
fun isOrchestrationV2WorkActive(status: String): Boolean =
  status == "pending" || status == "running" || status == "waiting"

/**
 * `deriveSubagentElapsedMs`, packages/shared/src/orchestrationTiming.ts:3: running time up to
 * [nowMs] while active, else up to `completedAt`. Null without a start, or finished without an
 * end.
 */
fun deriveSubagentElapsedMs(
  status: String,
  startedAt: String?,
  completedAt: String?,
  nowMs: Long,
): Long? {
  if (startedAt == null) return null
  val end: Long =
    (if (isOrchestrationV2WorkActive(status)) nowMs else epochMillis(completedAt)) ?: return null
  val start: Long = epochMillis(startedAt) ?: return null
  return maxOf(0, end - start)
}

/** `formatCompactElapsedSeconds`, apps/web/src/components/chat/AgentElapsed.tsx:19. */
fun formatCompactElapsedSeconds(totalSeconds: Long): String {
  val seconds: Long = maxOf(0, totalSeconds)
  if (seconds < 60) return "${seconds}s"
  val minutes: Long = seconds / 60
  if (minutes < 60) return "${minutes}m"
  if (minutes >= 600) return "${minutes / 60}h"
  // `Math.floor(minutes / 6) / 10`, which JavaScript prints without a trailing `.0`
  val tenths: Long = minutes / 6
  return if (tenths % 10 == 0L) "${tenths / 10}h" else "${tenths / 10}.${tenths % 10}h"
}

/**
 * A subagent as the panel reads it: `projectedSubagentsToRuntime`
 * (packages/client-runtime/src/state/subagentRuntime.ts:175) for the title and timing, plus the
 * projection fields `subagentsByThreadId` copies over (ThreadRelationshipsControl.tsx:260).
 * [nativeStatus] is the projection's status, before [currentSubagent] looks at the child.
 */
data class LineageSubagent(
  val id: String,
  val threadId: String,
  val runId: String?,
  val nativeTaskRef: ProviderRef?,
  val nativeStatus: String,
  val driver: String,
  val providerInstanceId: String,
  val origin: String,
  val title: String,
  val status: String,
  val startedAt: String?,
  val completedAt: String?,
  val updatedAt: String,
)

fun lineageSubagent(subagent: Subagent): LineageSubagent = LineageSubagent(
  id = subagent.id,
  threadId = subagent.threadId,
  runId = subagent.runId,
  nativeTaskRef = subagent.nativeTaskRef,
  nativeStatus = subagent.status,
  driver = subagent.driver,
  providerInstanceId = subagent.providerInstanceId,
  origin = subagent.origin,
  title = subagent.title
    ?: if (subagent.prompt.length > 80) "${subagent.prompt.take(77)}..." else subagent.prompt,
  status = subagent.status,
  startedAt = subagent.startedAt,
  completedAt = subagent.completedAt,
  updatedAt = subagent.updatedAt,
)

/**
 * `currentSubagent`, ThreadRelationshipsControl.tsx:179: a finished subagent whose child thread
 * runs again (or was asked to, after it finished) takes the child's run for status and timing.
 */
fun currentSubagent(agent: LineageSubagent?, childThread: Thread?): LineageSubagent? {
  if (agent == null || childThread == null || isOrchestrationV2WorkActive(agent.status)) {
    return agent
  }
  val liveStatus: String? = childThread.activityRunStatus
    ?: if (childThread.status == "queued") "queued" else null
  val requestedAt: Long? = epochMillis(childThread.latestRunRequestedAt)
  val finishedAt: Long? = epochMillis(agent.completedAt ?: agent.startedAt ?: agent.updatedAt)
  val newerRun: Boolean = requestedAt != null && finishedAt != null && requestedAt > finishedAt
  if (liveStatus == null && !newerRun) return agent
  val status: String = liveStatus ?: childThread.status
  return agent.copy(
    status = when (status) {
      "preparing", "starting", "queued" -> "pending"
      "rolled_back" -> "interrupted"
      else -> status
    },
    startedAt = if (liveStatus != null) {
      childThread.activityRunStartedAt
    } else {
      childThread.latestRunStartedAt
    },
    completedAt = if (liveStatus != null) null else childThread.latestRunCompletedAt,
  )
}

private val STOPPABLE_STATUSES: Set<String> = setOf("pending", "running", "waiting")

/**
 * `canStop`, ThreadRelationshipsControl.tsx:463: an app-owned subagent, or a Claude native one
 * with a strong task ref, that has started and is still working.
 */
fun canStopSubagent(agent: LineageSubagent): Boolean {
  val ref: ProviderRef? = agent.nativeTaskRef
  val stoppable: Boolean = agent.origin == "app_owned" ||
    (
      agent.origin == "provider_native" &&
        agent.driver == "claudeAgent" &&
        agent.runId != null &&
        ref?.strength == "strong" &&
        ref.nativeId != null &&
        agent.nativeStatus in STOPPABLE_STATUSES
      )
  return stoppable && agent.startedAt != null && agent.status in STOPPABLE_STATUSES
}

/** `MERGE_BACK_RUN_STATUSES`, packages/client-runtime/src/state/threadWorkflows.ts:15. */
private val MERGE_BACK_RUN_STATUSES: Set<String> = setOf("waiting", "completed")

/** `MERGE_BACK_BLOCKING_RUN_STATUSES`, threadWorkflows.ts:16. */
private val MERGE_BACK_BLOCKING_RUN_STATUSES: Set<String> =
  setOf("preparing", "starting", "running")

/**
 * `resolveLatestMergeBackRun`, threadWorkflows.ts:47: the newest finished run, a `waiting` one
 * included while its checkpoint is captured, unless a newer run is under way.
 */
fun resolveLatestMergeBackRun(projection: ThreadView): Run? {
  val latest: Run = projection.runs
    .filter { run -> run.status in MERGE_BACK_RUN_STATUSES }
    .maxByOrNull { run -> run.ordinal }
    ?: return null
  val hasNewerActiveRun: Boolean = projection.runs.any { run ->
    run.ordinal > latest.ordinal && run.status in MERGE_BACK_BLOCKING_RUN_STATUSES
  }
  return if (hasNewerActiveRun) null else latest
}

/** `canDetachThreadProviderSession`, threadWorkflows.ts:185. */
fun canDetachThreadProviderSession(projection: ThreadView): Boolean {
  val session: ProviderSession = resolveThreadProviderSession(projection) ?: return false
  return session.status != "stopped" && session.status != "error"
}

/** `relationshipLabel`, ThreadRelationshipsControl.tsx:163. */
fun relationshipLabel(edge: ThreadRelationshipEdge, currentThreadId: String): String =
  when (edge.kind) {
    ThreadRelationshipKind.Transfer -> "Context transfer"
    ThreadRelationshipKind.Subagent ->
      if (edge.sourceThreadId == currentThreadId) "Subagent" else "Parent agent"
    else -> if (edge.sourceThreadId == currentThreadId) "Fork" else "Parent thread"
  }

/** `relationshipThreadTitle`, ThreadRelationshipsControl.tsx:171. */
fun relationshipThreadTitle(title: String, isSubagent: Boolean): String =
  if (isSubagent) formatSubagentDisplayTitle(title) else title

/** `THREAD_LINEAGE_INITIAL_COUNT`, ThreadRelationshipsControl.tsx:80. */
const val THREAD_LINEAGE_INITIAL_COUNT: Int = 6

/** `THREAD_LINEAGE_PAGE_COUNT`, ThreadRelationshipsControl.tsx:81. */
const val THREAD_LINEAGE_PAGE_COUNT: Int = 12

/** `resolveThreadLineageWindow`, ThreadRelationshipsControl.tsx:83. */
fun <Row> resolveThreadLineageWindow(rows: List<Row>, visibleCount: Int): Pair<List<Row>, Int> {
  val visibleRows: List<Row> = rows.take(visibleCount)
  return visibleRows to rows.size - visibleRows.size
}

/** The glyph a row falls back to without a provider logo: `CornerLeftUpIcon` and friends. */
enum class ThreadLineageIcon { Parent, Subagent, Fork }

/** One related thread in the Lineage, everything `ThreadRelationshipsPanel` works out per row. */
data class ThreadLineageRow(
  val threadId: String,
  val title: String,
  /** [relationshipLabel]. */
  val relationship: String,
  /** The row's status: [threadRelationshipRowStatus]. */
  val status: String?,
  val icon: ThreadLineageIcon,
  /** The subagent child's provider, whose logo stands in for [icon] when there is one. */
  val provider: ServerProvider?,
  /** The subagent child's driver, upstream's `providerDriver`; null for other rows. */
  val driver: String?,
  /** The subagent behind a child row, after [currentSubagent]; its timing is the elapsed. */
  val agent: LineageSubagent?,
  val canStop: Boolean,
  val isMergeTarget: Boolean,
  val missing: Boolean,
) {
  val failed: Boolean get() = status == "failed" || status == "error"

  /** `relationshipHint`, the row's tooltip. */
  val hint: String
    get() = if (missing) {
      "This related thread is unavailable"
    } else {
      "Open ${relationship.lowercase()} in this chat"
    }

  /** The compact running time at [nowMs], `AgentElapsed compact`; null when there's none. */
  fun elapsed(nowMs: Long): String? {
    val agent: LineageSubagent = agent ?: return null
    val ms: Long = deriveSubagentElapsedMs(agent.status, agent.startedAt, agent.completedAt, nowMs)
      ?: return null
    return formatCompactElapsedSeconds(ms / 1000)
  }

  /** Whether the trailing slot shows elapsed time: an agent, not failed, with timing. */
  val showsElapsed: Boolean
    get() = agent != null && !failed &&
      deriveSubagentElapsedMs(agent.status, agent.startedAt, agent.completedAt, 0) != null
}

enum class ThreadLineageGroupId { Related, Active, Previous }

/** `ThreadLineageGroup`'s props, ThreadRelationshipsControl.tsx:123. */
data class ThreadLineageGroup(
  val id: ThreadLineageGroupId,
  val label: String?,
  val rows: List<ThreadLineageRow>,
  /** Open to begin with; "Previous agents" starts closed. */
  val expanded: Boolean,
  /** Rows whose edge failed, the closed header's accessory. */
  val failedCount: Int,
)

/** What the Lineage shows for one thread, `ThreadRelationshipsPanel`'s derived state. */
data class ThreadLineage(
  /** "Lineage", or "Lineage · N running". */
  val title: String,
  /** The groups with rows, in order: related, active agents, previous agents. */
  val groups: List<ThreadLineageGroup>,
  val runningCount: Int,
  val mergeTargetThreadId: String?,
  /** `parentTitle`: the merge target's title, when its shell is known. */
  val mergeTargetTitle: String?,
  /** `latestMergeBackRun`'s id: what a merge back sends. */
  val mergeBackRunId: String?,
  val canMerge: Boolean,
  val canDetach: Boolean,
) {
  /** The merge button's tooltip, ThreadRelationshipsControl.tsx:650. */
  val mergeHint: String
    get() = when {
      mergeBackRunId == null -> "Complete a run in this fork before merging it back"
      mergeTargetTitle != null -> "Merge this conversation back into $mergeTargetTitle"
      else -> "Merge this conversation back into its source"
    }
}

/** What puts a subagent row under "Previous agents", ThreadRelationshipsControl.tsx:315. */
private val PREVIOUS_AGENT_STATUSES: Set<String> =
  setOf("completed", "failed", "error", "cancelled", "interrupted", "idle")

/**
 * `ThreadRelationshipsPanel`, ThreadRelationshipsControl.tsx:210, up to its render: [threadId]'s
 * related threads from [shells] (live first, archived after) and its [projection], grouped and
 * ordered. Null when there's nothing to show, where upstream renders nothing.
 *
 * Not ported: workflow groups (`deriveWorkflowGroups` in subagentRuntime.ts and
 * `ThreadLineageWorkflowRow`), so a workflow coordinator is a plain subagent row; and the parent
 * row's native status from the parent's own projection (`deriveProviderSubagentStatus`), which
 * would need a second subscription.
 */
fun deriveThreadLineage(
  threadId: String,
  projection: ThreadView?,
  shells: List<Thread>,
  providers: List<ServerProvider>,
): ThreadLineage? {
  val subagents: List<Subagent> = projection?.subagents.orEmpty()
  val subagentsByThreadId: Map<String, LineageSubagent> = subagents
    .mapNotNull { subagent -> subagent.childThreadId?.let { it to lineageSubagent(subagent) } }
    .toMap()
  val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(shells, projection)
  val latestMergeBackRun: Run? = projection?.let(::resolveLatestMergeBackRun)
  val mergeTargetThreadId: String? = resolveMergeBackTargetThreadId(projection)
  val relationshipRows: List<ThreadRelationshipWalkRow> = orderWebThreadLineageRows(
    graph = graph,
    rows = immediateThreadRelationships(graph, threadId),
    currentThreadId = threadId,
    mergeTargetThreadId = mergeTargetThreadId,
  )

  val grouped: Map<ThreadLineageGroupId, List<ThreadRelationshipWalkRow>> =
    relationshipRows.groupBy { (_, _, _, edge) ->
      when {
        edge.kind != ThreadRelationshipKind.Subagent ||
          isParentThreadRelationship(edge, threadId) -> ThreadLineageGroupId.Related

        (edge.status ?: "") in PREVIOUS_AGENT_STATUSES -> ThreadLineageGroupId.Previous
        else -> ThreadLineageGroupId.Active
      }
    }
  val active: List<ThreadRelationshipWalkRow> = grouped[ThreadLineageGroupId.Active].orEmpty()
  val runningCount: Int =
    subagents.count { agent -> agent.childThreadId == null && agent.status == "running" } +
      active.count { row -> row.edge.status == "running" }
  if (relationshipRows.isEmpty() && runningCount == 0) return null

  fun row(walk: ThreadRelationshipWalkRow): ThreadLineageRow {
    val (relatedId: String, _, _, edge: ThreadRelationshipEdge) = walk
    val node: ThreadRelationshipNode? = graph.nodes[relatedId]
    val isSubagent: Boolean = edge.kind == ThreadRelationshipKind.Subagent
    val isParent: Boolean = isParentThreadRelationship(edge, threadId)
    val projectedAgent: LineageSubagent? =
      if (isSubagent && !isParent) subagentsByThreadId[relatedId] else null
    val agent: LineageSubagent? = currentSubagent(projectedAgent, node?.thread)
    val provider: ServerProvider? = providers.find { entry ->
      entry.instanceId == (agent?.providerInstanceId ?: node?.thread?.providerInstanceId)
    }
    val providerDriver: String? = agent?.driver ?: provider?.driver
    return ThreadLineageRow(
      threadId = relatedId,
      title = relationshipThreadTitle(
        title = node?.thread?.title ?: agent?.title ?: relatedId,
        isSubagent = isSubagent,
      ),
      relationship = relationshipLabel(edge, threadId),
      status = threadRelationshipRowStatus(graph, walk),
      icon = when {
        isParent -> ThreadLineageIcon.Parent
        isSubagent -> ThreadLineageIcon.Subagent
        else -> ThreadLineageIcon.Fork
      },
      provider = provider.takeIf { isSubagent && !isParent },
      driver = providerDriver.takeIf { isSubagent && !isParent },
      agent = agent,
      canStop = agent != null && canStopSubagent(agent),
      isMergeTarget = relatedId == mergeTargetThreadId,
      missing = node?.missing == true,
    )
  }

  fun group(id: ThreadLineageGroupId, label: String?, expanded: Boolean): ThreadLineageGroup {
    val rows: List<ThreadRelationshipWalkRow> = grouped[id].orEmpty()
    return ThreadLineageGroup(
      id = id,
      label = label,
      rows = rows.map(::row),
      expanded = expanded,
      failedCount = rows.count { it.edge.status == "failed" || it.edge.status == "error" },
    )
  }

  val groups: List<ThreadLineageGroup> = listOf(
    group(ThreadLineageGroupId.Related, label = null, expanded = true),
    group(ThreadLineageGroupId.Active, label = null, expanded = true),
    group(ThreadLineageGroupId.Previous, label = "Previous agents", expanded = false),
  ).filter { it.rows.isNotEmpty() }

  return ThreadLineage(
    title = if (runningCount > 0) "Lineage · $runningCount running" else "Lineage",
    groups = groups,
    runningCount = runningCount,
    mergeTargetThreadId = mergeTargetThreadId,
    mergeTargetTitle = mergeTargetThreadId?.let { graph.nodes[it]?.thread?.title },
    mergeBackRunId = latestMergeBackRun?.id,
    canMerge = mergeTargetThreadId != null && latestMergeBackRun != null,
    canDetach = projection?.let(::canDetachThreadProviderSession) ?: false,
  )
}

/** `mergeThreadBack`, packages/client-runtime/src/operations/commands.ts:964. */
fun mergeBackCommand(
  sourceThreadId: String,
  targetThreadId: String,
  runId: String,
  commandId: String,
): Command.ThreadMergeBack = Command.ThreadMergeBack(
  createdBy = "user",
  creationSource = "web",
  commandId = commandId,
  sourceThreadId = sourceThreadId,
  targetThreadId = targetThreadId,
  sourcePoint = ThreadForkSourcePoint.ThreadForkSourcePointRun(runId = runId),
)

/**
 * `interruptThreadTurn`'s subagent path, commands.ts:772: `subagent.stop` on the subagent's run.
 * Null without a run, where upstream answers `{ sequence: 0 }` and sends nothing.
 */
fun subagentStopCommand(agent: LineageSubagent, commandId: String): Command.SubagentStop? =
  agent.runId?.let { runId ->
    Command.SubagentStop(
      commandId = commandId,
      threadId = agent.threadId,
      runId = runId,
      subagentId = agent.id,
    )
  }
