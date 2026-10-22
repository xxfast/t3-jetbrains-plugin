package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.ContextTransfer
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.Subagent
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.AppThreadLineage
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ContextSourcePoint
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProviderRef
import io.github.xxfast.t3code.protocol.generated.ThreadForkSourcePoint
import io.github.xxfast.t3code.protocol.generated.ThreadShell
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ThreadRelationshipsLogicTest {
  // threadRelationships.test.ts, "thread relationships"

  @Test
  fun `keeps the parent's status independent of the child's`() {
    val pairs: List<Pair<String, String>> = listOf(
      "running" to "completed",
      "completed" to "running",
      "running" to "running",
      "completed" to "completed",
      "waiting" to "failed",
      "interrupted" to "pending",
    )
    for ((parentStatus: String, childStatus: String) in pairs) {
      val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(
        threads = listOf(
          shell("parent", status = "cancelled", activityRunStatus = parentStatus),
          shell(
            id = "child",
            parent = "parent",
            relationship = "fork",
            activityRunStatus = childStatus,
          ),
        ),
        projection = null,
      )
      val fromChild: ThreadRelationshipWalkRow = immediateThreadRelationships(graph, "child")[0]
      val fromParent: ThreadRelationshipWalkRow = immediateThreadRelationships(graph, "parent")[0]
      assertEquals(parentStatus, threadRelationshipRowStatus(graph, fromChild))
      assertEquals(childStatus, threadRelationshipRowStatus(graph, fromParent))
    }
  }

  @Test
  fun `does not label a missing parent with its child's running status`() {
    val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(
      threads = listOf(
        shell("child", status = "running", parent = "missing-parent", relationship = "subagent"),
      ),
      projection = null,
    )
    val row: ThreadRelationshipWalkRow = immediateThreadRelationships(graph, "child")[0]
    assertNull(threadRelationshipRowStatus(graph, row))
  }

  @Test
  fun `keeps an older activity run visible over a newer cancelled run`() {
    val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(
      threads = listOf(
        shell(
          id = "thread-child",
          status = "cancelled",
          activityRunStatus = "running",
          parent = "thread-parent",
          relationship = "subagent",
          forkedFrom = "thread-parent",
        ),
      ),
      projection = null,
    )
    val edge: ThreadRelationshipEdge = graph.edges.single()
    assertEquals("thread-child", edge.targetThreadId)
    assertEquals("running", edge.status)
  }

  @Test
  fun `keeps missing parents and cycles navigable without recursive traversal`() {
    val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(
      threads = listOf(
        shell("root", parent = "child", relationship = "fork", forkedFrom = "child"),
        shell("child", parent = "missing", relationship = "fork", forkedFrom = "missing"),
      ),
      projection = null,
    )
    assertEquals(true, graph.nodes["missing"]?.missing)
    assertEquals(listOf("child"), relatedThreadIds(graph, "root"))
    assertEquals(listOf("root", "missing"), relatedThreadIds(graph, "child"))
    assertEquals(
      listOf("child" to 1, "missing" to 2),
      walkThreadRelationships(graph, "root").map { it.threadId to it.depth },
    )
    assertEquals(listOf("child"), immediateThreadRelationships(graph, "root").map { it.threadId })
  }

  @Test
  fun `combines subagent and transfer edges with archived shell state`() {
    val parent: Thread = shell("parent")
    val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(
      threads = listOf(
        parent,
        shell("child", parent = "parent", relationship = "subagent")
          .copy(archivedAt = "2026-06-24T00:00:00.000Z"),
      ),
      projection = view(
        parent,
        subagents = listOf(subagent("a", child = "child", status = "completed")),
        transfers = listOf(transfer("child", "transfer-target", "completed")),
      ),
    )
    assertNotNull(graph.nodes["child"]?.thread?.archivedAt)
    assertEquals(true, graph.nodes["transfer-target"]?.missing)
    val edges: List<Triple<String, String, ThreadRelationshipKind>> =
      graph.edges.map { Triple(it.sourceThreadId, it.targetThreadId, it.kind) }
    assertTrue(Triple("parent", "child", ThreadRelationshipKind.Subagent) in edges)
    assertTrue(Triple("child", "transfer-target", ThreadRelationshipKind.Transfer) in edges)
  }

  @Test
  fun `keeps the live shell when an archived snapshot contains the same thread id`() {
    val live: Thread = shell(
      id = "child",
      title = "Live child",
      status = "running",
      parent = "parent",
      relationship = "fork",
      forkedFrom = "parent",
    )
    val staleArchived: Thread = shell(
      id = "child",
      title = "Stale archived child",
      parent = "stale-parent",
      relationship = "fork",
      forkedFrom = "stale-parent",
    ).copy(archivedAt = "2026-06-24T00:00:00.000Z")

    val graph: ThreadRelationshipGraph =
      deriveThreadRelationshipGraph(listOf(live, staleArchived), projection = null)

    assertEquals(live, graph.nodes["child"]?.thread)
    val edge: ThreadRelationshipEdge = graph.edges.single()
    assertEquals("parent" to "child", edge.sourceThreadId to edge.targetThreadId)
    assertFalse("stale-parent" in graph.nodes)
  }

  @Test
  fun `resolves merge-back only for forks and prefers the recorded fork source`() {
    fun target(forkedFrom: String?, relationship: String): String? =
      resolveMergeBackTargetThreadId(
        view(
          shell(
            id = "fork",
            parent = "fallback",
            relationship = relationship,
            forkedFrom = forkedFrom,
          ),
        ),
      )
    assertEquals("source", target(forkedFrom = "source", relationship = "fork"))
    assertEquals("fallback", target(forkedFrom = null, relationship = "fork"))
    assertNull(target(forkedFrom = null, relationship = "subagent"))
    assertNull(resolveMergeBackTargetThreadId(null))
  }

  @Test
  fun `a rolled back subagent edge reads as interrupted`() {
    val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(
      threads = listOf(
        shell("child", parent = "owner", relationship = "subagent", status = "rolled_back"),
      ),
      projection = null,
    )
    assertEquals("interrupted", graph.edges.single().status)
  }

  // threadRelationships.test.ts, "web thread lineage ordering"

  @Test
  fun `does not reorder when related-thread activity arrives`() {
    val before: List<String> = orderedLineageIds(
      threads = listOf(
        forkShell(CURRENT, null, "2026-06-01T00:00:00.000Z"),
        forkShell("fork-a", CURRENT, "2026-06-02T00:00:00.000Z"),
        forkShell("fork-b", CURRENT, "2026-06-03T00:00:00.000Z"),
        forkShell("fork-c", CURRENT, "2026-06-04T00:00:00.000Z"),
      ),
    )
    val after: List<String> = orderedLineageIds(
      threads = listOf(
        forkShell("fork-b", CURRENT, "2026-06-03T00:00:00.000Z")
          .copy(updatedAt = "2026-07-30T00:00:00.000Z", status = "running"),
        forkShell("fork-c", CURRENT, "2026-06-04T00:00:00.000Z"),
        forkShell(CURRENT, null, "2026-06-01T00:00:00.000Z"),
        forkShell("fork-a", CURRENT, "2026-06-02T00:00:00.000Z")
          .copy(updatedAt = "2026-07-31T00:00:00.000Z", status = "failed"),
      ),
    )
    assertEquals(listOf("fork-c", "fork-b", "fork-a"), before)
    assertEquals(before, after)
  }

  @Test
  fun `pins the parent first and a distinct merge-back target second`() {
    val ordered: List<String> = orderedLineageIds(
      threads = listOf(
        forkShell("parent", null, "2026-06-01T00:00:00.000Z"),
        forkShell(CURRENT, "parent", "2026-06-02T00:00:00.000Z"),
        forkShell("merge-target", CURRENT, "2026-06-03T00:00:00.000Z"),
        forkShell("newest-fork", CURRENT, "2026-06-09T00:00:00.000Z"),
      ),
      mergeTargetThreadId = "merge-target",
    )
    assertEquals(listOf("parent", "merge-target", "newest-fork"), ordered)
  }

  @Test
  fun `sinks missing nodes and shells without a decoded createdAt`() {
    val current: Thread = forkShell(CURRENT, null, "2026-06-01T00:00:00.000Z")
    val ordered: List<String> = orderedLineageIds(
      threads = listOf(
        current,
        forkShell("undated-fork", CURRENT, createdAt = ""),
        forkShell("dated-fork", CURRENT, "2026-06-02T00:00:00.000Z"),
      ),
      projection = view(current, transfers = listOf(transfer(CURRENT, "missing", "completed"))),
    )
    assertEquals(listOf("dated-fork", "missing", "undated-fork"), ordered)
  }

  // ThreadRelationshipIcon.tsx, subagentDisplay.ts, orchestrationTiming.ts, AgentElapsed.tsx

  @Test
  fun `status labels and dots`() {
    assertEquals("Starting", threadRelationshipStatusLabel("preparing"))
    assertEquals("Running", threadRelationshipStatusLabel("in_progress"))
    assertEquals("Queued", threadRelationshipStatusLabel("pending"))
    assertEquals("Waiting", threadRelationshipStatusLabel("blocked"))
    assertEquals("Done", threadRelationshipStatusLabel("completed"))
    assertEquals("Failed", threadRelationshipStatusLabel("error"))
    assertEquals("Stopped", threadRelationshipStatusLabel("interrupted"))
    assertEquals("Reverted", threadRelationshipStatusLabel("rolled_back"))
    assertEquals("Resolved (native)", threadRelationshipStatusLabel("resolved_native"))
    assertEquals("Idle", threadRelationshipStatusLabel("idle"))
    assertEquals("Unknown", threadRelationshipStatusLabel(null))
    assertEquals(ThreadRelationshipTone.Info, threadRelationshipStatusTone("waiting"))
    assertEquals(ThreadRelationshipTone.Destructive, threadRelationshipStatusTone("failed"))
    assertEquals(ThreadRelationshipTone.Success, threadRelationshipStatusTone("completed"))
    // Upstream's dot only knows these; "queued" and "blocked" are muted, labels aside
    assertEquals(ThreadRelationshipTone.Muted, threadRelationshipStatusTone("queued"))
  }

  @Test
  fun `subagent titles drop the prefix and shorten root paths`() {
    assertEquals("Survey the issues", formatSubagentDisplayTitle("Subagent: Survey the issues"))
    assertEquals("Survey", formatSubagentDisplayTitle("subagent:Survey"))
    assertEquals("Code Reviewer", formatSubagentDisplayTitle("/root/review/code_reviewer/"))
    assertEquals("Explorer", formatSubagentDisplayTitle("Subagent: /root/explorer"))
    assertEquals("/root/", formatSubagentDisplayTitle("/root/"))
    assertEquals("Fix /root/a", formatSubagentDisplayTitle("Fix /root/a"))
  }

  @Test
  fun `elapsed runs to now while active and to completion after`() {
    val start = "2026-10-22T08:00:00Z"
    val now: Long = java.time.Instant.parse("2026-10-22T08:01:30Z").toEpochMilli()
    assertEquals(90_000L, deriveSubagentElapsedMs("running", start, null, now))
    assertEquals(
      40_000L,
      deriveSubagentElapsedMs("completed", start, "2026-10-22T08:00:40Z", now),
    )
    assertNull(deriveSubagentElapsedMs("completed", start, null, now))
    assertNull(deriveSubagentElapsedMs("running", null, null, now))
    assertEquals(0L, deriveSubagentElapsedMs("running", start, null, 0), "Never negative")
  }

  @Test
  fun `compact elapsed`() {
    assertEquals("0s", formatCompactElapsedSeconds(-4L))
    assertEquals("59s", formatCompactElapsedSeconds(59L))
    assertEquals("1m", formatCompactElapsedSeconds(60L))
    assertEquals("59m", formatCompactElapsedSeconds(3_599L))
    assertEquals("1h", formatCompactElapsedSeconds(3_600L))
    assertEquals("1.5h", formatCompactElapsedSeconds(5_400L))
    assertEquals("9.9h", formatCompactElapsedSeconds(35_940L))
    assertEquals("10h", formatCompactElapsedSeconds(36_000L))
  }

  // ThreadRelationshipsControl.tsx

  @Test
  fun `relationship labels`() {
    fun label(kind: ThreadRelationshipKind, source: String): String =
      relationshipLabel(ThreadRelationshipEdge(source, "other", kind, null), "me")
    assertEquals("Context transfer", label(ThreadRelationshipKind.Transfer, "me"))
    assertEquals("Subagent", label(ThreadRelationshipKind.Subagent, "me"))
    assertEquals("Parent agent", label(ThreadRelationshipKind.Subagent, "other"))
    assertEquals("Fork", label(ThreadRelationshipKind.Fork, "me"))
    assertEquals("Parent thread", label(ThreadRelationshipKind.Fork, "other"))
  }

  @Test
  fun `a finished subagent whose child runs again takes the child's run`() {
    val agent: LineageSubagent = lineageSubagent(
      subagent(
        "a",
        child = "child",
        status = "completed",
        startedAt = "2026-10-22T08:00:00Z",
        completedAt = "2026-10-22T08:05:00Z",
      ),
    )
    val child: Thread = shell("child", parent = "me", relationship = "subagent")
    assertEquals(agent, currentSubagent(agent, child), "Nothing newer")

    val live: LineageSubagent? = currentSubagent(
      agent,
      child.copy(activityRunStatus = "starting", activityRunStartedAt = "2026-10-22T09:00:00Z"),
    )
    assertEquals("pending", live?.status)
    assertEquals("2026-10-22T09:00:00Z", live?.startedAt)
    assertNull(live?.completedAt)

    val rerun: LineageSubagent? = currentSubagent(
      agent,
      child.copy(
        status = "rolled_back",
        latestRunRequestedAt = "2026-10-22T09:00:00Z",
        latestRunStartedAt = "2026-10-22T09:00:01Z",
        latestRunCompletedAt = "2026-10-22T09:02:00Z",
      ),
    )
    assertEquals("interrupted", rerun?.status)
    assertEquals("2026-10-22T09:02:00Z", rerun?.completedAt)

    val running: LineageSubagent = agent.copy(status = "running")
    assertEquals(running, currentSubagent(running, child.copy(activityRunStatus = "failed")))
  }

  @Test
  fun `stop is for started app-owned subagents and strong native Claude ones`() {
    val started = "2026-10-22T08:00:00Z"
    val appOwned: LineageSubagent =
      lineageSubagent(subagent("a", child = "c", status = "running", startedAt = started))
    assertTrue(canStopSubagent(appOwned))
    assertFalse(canStopSubagent(appOwned.copy(startedAt = null)))
    assertFalse(canStopSubagent(appOwned.copy(status = "completed")))

    val native: LineageSubagent = appOwned.copy(
      origin = "provider_native",
      driver = "claudeAgent",
      nativeTaskRef = ProviderRef(driver = "claudeAgent", nativeId = "task", strength = "strong"),
    )
    assertTrue(canStopSubagent(native))
    assertFalse(canStopSubagent(native.copy(driver = "codex")))
    assertFalse(canStopSubagent(native.copy(runId = null)))
    val weak: ProviderRef? = native.nativeTaskRef?.copy(nativeId = null)
    assertFalse(canStopSubagent(native.copy(nativeTaskRef = weak)))
    assertFalse(canStopSubagent(native.copy(nativeStatus = "completed")))
  }

  @Test
  fun `merge back takes the newest finished run unless a newer one is under way`() {
    val thread: Thread = shell(CURRENT)
    fun latest(vararg runs: Run): String? =
      resolveLatestMergeBackRun(view(thread, runs = runs.toList()))?.id
    assertNull(latest())
    assertEquals("r2", latest(run("r1", 1, "completed"), run("r2", 2, "waiting")))
    assertEquals("r1", latest(run("r1", 1, "completed"), run("r2", 2, "failed")))
    assertNull(latest(run("r1", 1, "completed"), run("r2", 2, "running")))
    assertEquals("r1", latest(run("r1", 1, "completed"), run("r2", 2, "queued")))
  }

  @Test
  fun `nothing related is nothing to show, unless a detached subagent runs`() {
    val alone: Thread = shell(CURRENT)
    assertNull(deriveThreadLineage(CURRENT, view(alone), listOf(alone), emptyList()))
    val explorer: Subagent = subagent("x", child = null, status = "running")
    val projection: ThreadView = view(alone, subagents = listOf(explorer))
    val lineage: ThreadLineage? =
      deriveThreadLineage(CURRENT, projection, listOf(alone), emptyList())
    assertEquals("Lineage · 1 running", lineage?.title)
    assertEquals(emptyList(), lineage?.groups)
  }

  @Test
  fun `the lineage demo groups, titles and pins as upstream`() {
    val lineage: ThreadLineage = assertNotNull(demoLineage(DEMO))

    assertEquals("Lineage · 3 running", lineage.title)
    assertEquals(
      ThreadLineageGroupId.entries.toList(),
      lineage.groups.map { it.id },
    )
    val (related: ThreadLineageGroup, active: ThreadLineageGroup, previous: ThreadLineageGroup) =
      lineage.groups
    assertEquals(
      listOf(DEMO_PARENT, "thr_01JC5L1N3XFERTO1", "thr_01JC5L1N2FORKED1", "thr_01JC5L1N4GONE001"),
      related.rows.map { it.threadId },
    )
    val parent: ThreadLineageRow = related.rows.first()
    assertEquals("Parent thread", parent.relationship)
    assertEquals(ThreadLineageIcon.Parent, parent.icon)
    assertTrue(parent.isMergeTarget)
    val gone: ThreadLineageRow = related.rows.last()
    assertTrue(gone.missing)
    assertEquals("This related thread is unavailable", gone.hint)
    assertEquals("Superseded", threadRelationshipStatusLabel(gone.status))

    assertEquals(listOf("Write the migration guide", "Code Reviewer"), active.rows.map { it.title })
    assertTrue(active.rows.all { it.canStop })
    assertEquals("Open subagent in this chat", active.rows.first().hint)

    assertEquals("Previous agents", previous.label)
    assertFalse(previous.expanded)
    assertEquals(8, previous.rows.size)
    assertEquals(1, previous.failedCount)
    assertTrue(previous.rows.first().failed)
    assertFalse(previous.rows.first().showsElapsed, "A failed agent shows its status instead")
    assertEquals("4m", previous.rows[1].elapsed(0))
    val (shown: List<ThreadLineageRow>, hidden: Int) =
      resolveThreadLineageWindow(previous.rows, THREAD_LINEAGE_INITIAL_COUNT)
    assertEquals(6 to 2, shown.size to hidden)

    // A running turn blocks merging back, and the session can be detached
    assertFalse(lineage.canMerge)
    assertEquals("Complete a run in this fork before merging it back", lineage.mergeHint)
    assertTrue(lineage.canDetach)
  }

  @Test
  fun `the demo's fork merges back into it`() {
    val lineage: ThreadLineage = assertNotNull(demoLineage("thr_01JC5L1N2FORKED1"))
    assertEquals(DEMO, lineage.mergeTargetThreadId)
    assertTrue(lineage.canMerge)
    assertEquals(
      "Merge this conversation back into Demo: lineage, a fork with subagents and transfers",
      lineage.mergeHint,
    )
    assertEquals("run_01JC5L1R3FORK", lineage.mergeBackRunId)
    assertFalse(lineage.canDetach)
  }

  @Test
  fun `commands`() {
    assertEquals(
      Command.ThreadMergeBack(
        createdBy = "user",
        creationSource = "web",
        commandId = "c",
        sourceThreadId = "fork",
        targetThreadId = "source",
        sourcePoint = ThreadForkSourcePoint.ThreadForkSourcePointRun(runId = "r"),
      ),
      mergeBackCommand("fork", "source", "r", "c"),
    )
    val agent: LineageSubagent = lineageSubagent(subagent("a", child = "c", status = "running"))
    assertEquals(
      Command.SubagentStop(commandId = "c", threadId = CURRENT, runId = "run", subagentId = "a"),
      subagentStopCommand(agent, "c"),
    )
    assertNull(subagentStopCommand(agent.copy(runId = null), "c"))
  }

  @Test
  fun `detach needs a live session`() {
    val session: ProviderSession =
      demo.providerSessions.getValue(DEMO).single().copy(status = "ready")
    val thread: Thread = shell(CURRENT)
    assertTrue(canDetachThreadProviderSession(view(thread, sessions = listOf(session))))
    val stopped: ProviderSession = session.copy(status = "stopped")
    assertFalse(canDetachThreadProviderSession(view(thread, sessions = listOf(stopped))))
    assertFalse(canDetachThreadProviderSession(view(thread)))
  }

  private val demo: Scenario = Scenario.decode(
    Path.of(System.getProperty("t3code.scenarios"), "default", "lineage.json").readText(),
  )

  private fun demoLineage(threadId: String): ThreadLineage? {
    val thread: Thread = demo.threads.single { it.id == threadId }
    val projection: ThreadView = ThreadView(
      thread = thread,
      runs = demo.runs[threadId].orEmpty(),
      items = emptyList(),
      providerSessions = demo.providerSessions[threadId].orEmpty(),
      subagents = demo.subagents[threadId].orEmpty(),
      contextTransfers = demo.contextTransfers[threadId].orEmpty(),
    )
    return deriveThreadLineage(threadId, projection, demo.threads, emptyList())
  }

  private fun orderedLineageIds(
    threads: List<Thread>,
    mergeTargetThreadId: String? = null,
    projection: ThreadView? = null,
  ): List<String> {
    val graph: ThreadRelationshipGraph = deriveThreadRelationshipGraph(threads, projection)
    return orderWebThreadLineageRows(
      graph = graph,
      rows = immediateThreadRelationships(graph, CURRENT),
      currentThreadId = CURRENT,
      mergeTargetThreadId = mergeTargetThreadId,
    ).map { it.threadId }
  }

  /** threadRelationships.test.ts's `forkShell`: a fork from a run of [parent]. */
  private fun forkShell(id: String, parent: String?, createdAt: String): Thread =
    shell(id, parent = parent, relationship = "fork".takeIf { parent != null }, forkedFrom = parent)
      .copy(createdAt = createdAt)

  private fun shell(
    id: String,
    title: String = id,
    status: String = "completed",
    activityRunStatus: String? = null,
    parent: String? = null,
    relationship: String? = null,
    forkedFrom: String? = null,
  ): Thread = BASE.copy(
    id = id,
    title = title,
    status = status,
    activityRunStatus = activityRunStatus,
    lineage = AppThreadLineage(
      parentThreadId = parent,
      relationshipToParent = relationship,
      rootThreadId = parent ?: id,
    ),
    forkedFrom = forkedFrom?.let { from ->
      ThreadShell.ForkedFrom.ForkedFromRun(threadId = from, runId = "run-$id")
    },
  )

  private fun view(
    thread: Thread,
    runs: List<Run> = emptyList(),
    sessions: List<ProviderSession> = emptyList(),
    subagents: List<Subagent> = emptyList(),
    transfers: List<ContextTransfer> = emptyList(),
  ): ThreadView = ThreadView(
    thread = thread,
    runs = runs,
    items = emptyList(),
    providerSessions = sessions,
    subagents = subagents,
    contextTransfers = transfers,
  )

  private fun subagent(
    id: String,
    child: String?,
    status: String,
    startedAt: String? = null,
    completedAt: String? = null,
  ): Subagent = Subagent(
    id = id,
    threadId = CURRENT,
    runId = "run",
    parentNodeId = "node",
    origin = "app_owned",
    createdBy = "agent",
    driver = "codex",
    providerInstanceId = "codex",
    providerThreadId = null,
    childThreadId = child,
    nativeTaskRef = null,
    prompt = "Do $id",
    title = null,
    model = null,
    status = status,
    result = null,
    startedAt = startedAt,
    completedAt = completedAt,
    updatedAt = "2026-10-22T08:00:00Z",
  )

  private fun transfer(source: String, target: String, status: String): ContextTransfer =
    ContextTransfer(
      id = "$source-$target",
      type = "fork",
      sourceThreadId = source,
      targetThreadId = target,
      sourcePoint = ContextSourcePoint(threadId = source),
      basePoint = null,
      sourceProviderInstanceId = null,
      targetProviderInstanceId = null,
      targetRunId = null,
      status = status,
      resolution = null,
      createdBy = "user",
      error = null,
      createdAt = "2026-10-22T08:00:00Z",
      updatedAt = "2026-10-22T08:00:00Z",
      consumedAt = null,
    )

  private fun run(id: String, ordinal: Long, status: String): Run = Run(
    id = id,
    threadId = CURRENT,
    ordinal = ordinal,
    providerInstanceId = "codex",
    modelSelection =
      ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("m")),
    providerThreadId = null,
    userMessageId = "m-$id",
    rootNodeId = null,
    activeAttemptId = null,
    status = status,
    requestedAt = "2026-10-22T08:00:00Z",
    startedAt = null,
    completedAt = null,
    checkpointId = null,
    contextHandoffId = null,
  )

  private companion object {
    const val CURRENT: String = "thread-current"
    const val DEMO: String = "thr_01JC5L1N1LINEAG1"
    const val DEMO_PARENT: String = "thr_01JC5L1N0PARENT1"

    val BASE: Thread = T3Json.decodeFromString(
      Thread.serializer(),
      """
        {
          "id": "t", "projectId": "p", "title": "t", "createdBy": "user",
          "creationSource": "web", "providerInstanceId": "codex",
          "modelSelection": { "instanceId": "codex", "model": "gpt-5" },
          "runtimeMode": "full-access", "interactionMode": "default", "branch": null,
          "worktreePath": null, "lineage": { "rootThreadId": "t" }, "status": "idle",
          "itemCount": 0, "visibleItemCount": 0, "hasActionableProposedPlan": false,
          "createdAt": "2026-06-20T00:00:00Z", "updatedAt": "2026-06-20T00:00:00Z"
        }
      """,
    )
  }
}
