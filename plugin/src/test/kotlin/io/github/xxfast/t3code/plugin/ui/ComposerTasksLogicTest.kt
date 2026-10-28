package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.PlanArtifact
import io.github.xxfast.t3code.protocol.PlanStep
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.TurnItem
import io.github.xxfast.t3code.protocol.generated.AppThreadLineage
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.PlanArtifact as GenPlanArtifact
import io.github.xxfast.t3code.protocol.T3Json
import kotlinx.serialization.json.JsonPrimitive
import io.github.xxfast.t3code.protocol.generated.TurnItem as GenTurnItem
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ComposerTasksLogicTest {

  private fun makeThread(id: String = "thr_1", activeRunId: String? = null): Thread = BASE_THREAD.copy(
    id = id,
    activeRunId = activeRunId,
  )

  private fun makeRun(
    id: String,
    ordinal: Long,
    status: String = "completed",
    startedAt: String? = "2026-10-19T08:00:00.000Z",
    completedAt: String? = "2026-10-19T08:01:00.000Z",
    providerThreadId: String? = "pthread_1",
  ): Run = Run(
    id = id,
    threadId = "thr_1",
    ordinal = ordinal,
    status = status,
    providerInstanceId = "codex",
    modelSelection = ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("gpt-5")),
    userMessageId = "msg_$id",
    requestedAt = "2026-10-19T08:00:00.000Z",
    startedAt = startedAt,
    completedAt = completedAt,
    providerThreadId = providerThreadId,
    rootNodeId = null,
    activeAttemptId = null,
    checkpointId = null,
    contextHandoffId = null,
  )

  private fun makePlan(
    id: String,
    runId: String?,
    status: String = "active",
    steps: List<PlanStep>,
  ): PlanArtifact = GenPlanArtifact.TodoList(
    id = id,
    threadId = "thr_1",
    runId = runId,
    nodeId = "node_$id",
    status = status,
    steps = steps,
    explanation = "Plan $id explanation",
  )

  private fun makeProviderThread(id: String, driver: String): ProviderThread = ProviderThread(
    id = id,
    driver = driver,
    providerInstanceId = "codex",
    providerSessionId = "psn_1",
    appThreadId = "thr_1",
    ownerNodeId = null,
    nativeThreadRef = null,
    nativeConversationHeadRef = null,
    status = "active",
    firstRunOrdinal = 1,
    lastRunOrdinal = 1,
    handoffIds = emptyList(),
    forkedFrom = null,
    createdAt = "2026-10-19T08:00:00.000Z",
    updatedAt = "2026-10-19T08:00:00.000Z",
  )

  @Test
  fun `isLatestRunSettled respects active statuses and activeRunId`() {
    assertFalse(isLatestRunSettled(null, null))
    assertFalse(isLatestRunSettled(null, "run_1"))

    for (activeStatus in listOf("preparing", "queued", "starting", "running", "waiting")) {
      val run = makeRun("run_1", 1, status = activeStatus)
      assertFalse(isLatestRunSettled(run, null), "Status $activeStatus should not be settled")
      assertFalse(isLatestRunSettled(run, "run_2"), "Status $activeStatus should not be settled")
    }

    val completedRun = makeRun("run_1", 1, status = "completed")
    assertFalse(isLatestRunSettled(completedRun, "run_1"))
    assertTrue(isLatestRunSettled(completedRun, null))
    assertTrue(isLatestRunSettled(completedRun, "run_2"))
  }

  @Test
  fun `deriveActivePlanState filters superseded plans and picks latest run or highest ordinal`() {
    assertNull(deriveActivePlanState(null, null))

    val step1 = PlanStep("s1", "Step 1", "completed", durationMs = 1200)
    val step2 = PlanStep("s2", "Step 2", "running")
    val step3 = PlanStep("s3", "Step 3", "pending")

    val run1 = makeRun("run_1", 1)
    val run2 = makeRun("run_2", 2)

    val plan1 = makePlan("p1", "run_1", steps = listOf(step1))
    val plan2 = makePlan("p2", "run_2", steps = listOf(step1, step2, step3))
    val supersededPlan = makePlan("p3", "run_2", status = "superseded", steps = listOf(step1))

    val view = ThreadView(
      thread = makeThread(),
      runs = listOf(run1, run2),
      items = emptyList(),
      plans = listOf(plan1, supersededPlan, plan2),
    )

    // Explicit latestRunId matches run_1
    val stateRun1 = deriveActivePlanState(view, "run_1")
    assertNotNull(stateRun1)
    assertEquals("run_1", stateRun1.runId)
    assertEquals(1, stateRun1.steps.size)
    assertEquals(ComposerTaskStatus.COMPLETED, stateRun1.steps[0].status)
    assertEquals(1200L, stateRun1.steps[0].durationMs)

    // Without matching latestRunId, picks highest ordinal (run_2 has ordinal 2 > 1)
    val stateFallback = deriveActivePlanState(view, "non_existent_run")
    assertNotNull(stateFallback)
    assertEquals("run_2", stateFallback.runId)
    assertEquals(3, stateFallback.steps.size)
    assertEquals(ComposerTaskStatus.COMPLETED, stateFallback.steps[0].status)
    assertEquals(ComposerTaskStatus.IN_PROGRESS, stateFallback.steps[1].status)
    assertEquals(ComposerTaskStatus.PENDING, stateFallback.steps[2].status)

    // Empty steps returns null
    val emptyPlan = makePlan("p_empty", "run_1", steps = emptyList())
    val viewEmpty = view.copy(plans = listOf(emptyPlan))
    assertNull(deriveActivePlanState(viewEmpty, "run_1"))
  }

  @Test
  fun `selectComposerTasksPlan returns plan for active unsettled run`() {
    val plan = ActivePlanState(
      runId = "run_active",
      steps = listOf(
        ComposerTaskStep("Step A", ComposerTaskStatus.IN_PROGRESS),
      ),
    )
    val view = ThreadView(
      thread = makeThread(activeRunId = "run_active"),
      runs = listOf(makeRun("run_active", 1, status = "running")),
      items = emptyList(),
    )

    val selected = selectComposerTasksPlan(
      view = view,
      plan = plan,
      activeRunId = "run_active",
      activeRunSettled = false,
    )
    assertEquals(plan, selected)
  }

  @Test
  fun `selectComposerTasksPlan returns null when all steps are completed`() {
    val plan = ActivePlanState(
      runId = "run_active",
      steps = listOf(
        ComposerTaskStep("Step A", ComposerTaskStatus.COMPLETED),
      ),
    )
    val view = ThreadView(
      thread = makeThread(),
      runs = listOf(makeRun("run_active", 1)),
      items = emptyList(),
    )

    val selected = selectComposerTasksPlan(
      view = view,
      plan = plan,
      activeRunId = "run_other",
      activeRunSettled = true,
    )
    assertNull(selected)
  }

  @Test
  fun `selectComposerTasksPlan handles Claude agent cross-run special case`() {
    val plan = ActivePlanState(
      runId = "run_1",
      steps = listOf(
        ComposerTaskStep("Step 1", ComposerTaskStatus.COMPLETED),
        ComposerTaskStep("Step 2", ComposerTaskStatus.PENDING),
      ),
    )

    val pThread1 = makeProviderThread("pthread_claude", "claudeAgent")
    val pThread2 = makeProviderThread("pthread_other", "codex")

    val run1 = makeRun("run_1", 1, providerThreadId = "pthread_claude")
    val run2 = makeRun("run_2", 2, providerThreadId = "pthread_claude")

    val viewClaude = ThreadView(
      thread = makeThread(),
      runs = listOf(run1, run2),
      items = emptyList(),
      providerThreads = listOf(pThread1),
    )

    // Latest started run (run_2) shares providerThreadId "pthread_claude"
    val kept = selectComposerTasksPlan(
      view = viewClaude,
      plan = plan,
      activeRunId = null,
      activeRunSettled = true,
    )
    assertEquals(plan, kept)

    // When latest started run has a different provider thread, plan is dropped
    val run3 = makeRun("run_3", 3, providerThreadId = "pthread_other")
    val viewSwitched = viewClaude.copy(
      runs = listOf(run1, run2, run3),
      providerThreads = listOf(pThread1, pThread2),
    )
    val dropped = selectComposerTasksPlan(
      view = viewSwitched,
      plan = plan,
      activeRunId = null,
      activeRunSettled = true,
    )
    assertNull(dropped)

    // Non-claudeAgent driver returns null
    val viewCodex = viewClaude.copy(
      providerThreads = listOf(makeProviderThread("pthread_claude", "codex")),
    )
    assertNull(selectComposerTasksPlan(viewCodex, plan, null, true))
  }

  @Test
  fun `activeComposerTasksProgress calculates counts and picks current step`() {
    assertNull(activeComposerTasksProgress(null, null, true))

    val stepDone = ComposerTaskStep("Done", ComposerTaskStatus.COMPLETED)
    val stepRunning = ComposerTaskStep("Running", ComposerTaskStatus.IN_PROGRESS)
    val stepPending = ComposerTaskStep("Pending", ComposerTaskStatus.PENDING)

    // Empty steps -> null
    val emptyPlan = ActivePlanState("run_1", emptyList())
    assertNull(activeComposerTasksProgress(emptyPlan, "run_1", false))

    // First inProgress is chosen
    val planWithRunning = ActivePlanState("run_1", listOf(stepDone, stepRunning, stepPending))
    val progressRunning = activeComposerTasksProgress(planWithRunning, "run_1", false)
    assertNotNull(progressRunning)
    assertEquals("Running", progressRunning.step)
    assertEquals(1, progressRunning.completedSteps)
    assertEquals(3, progressRunning.totalSteps)
    assertTrue(progressRunning.live)

    // If no inProgress, first pending is chosen
    val planWithPending = ActivePlanState("run_1", listOf(stepDone, stepPending))
    val progressPending = activeComposerTasksProgress(planWithPending, "run_other", true)
    assertNotNull(progressPending)
    assertEquals("Pending", progressPending.step)
    assertEquals(1, progressPending.completedSteps)
    assertEquals(2, progressPending.totalSteps)
    assertFalse(progressPending.live)

    // If all completed, last step is chosen
    val planAllDone = ActivePlanState("run_1", listOf(stepDone))
    val progressAllDone = activeComposerTasksProgress(planAllDone, "run_1", true)
    assertNotNull(progressAllDone)
    assertEquals("Done", progressAllDone.step)
    assertEquals(1, progressAllDone.completedSteps)
    assertEquals(1, progressAllDone.totalSteps)
    assertFalse(progressAllDone.live)
  }

  @Test
  fun `tasks demo scenario produces active composer tasks state`() {
    val path: Path = Path.of(System.getProperty("t3code.scenarios"))
      .resolve("default")
      .resolve("tasks.json")
    val scenario: Scenario = Scenario.decode(path.readText())
    val thread: Thread = scenario.threads.single()
    val view = ThreadView(
      thread = thread,
      runs = scenario.runs.getValue(thread.id),
      items = scenario.items.getValue(thread.id).map { it.item },
      plans = scenario.items.getValue(thread.id).mapNotNull { entry ->
        (entry.item as? GenTurnItem.TodoList)?.let { todo ->
          GenPlanArtifact.TodoList(
            id = todo.planId,
            threadId = todo.threadId,
            runId = todo.runId,
            nodeId = todo.nodeId ?: "",
            status = todo.status,
            steps = todo.steps,
            explanation = todo.explanation,
          )
        }
      },
    )

    val state = composerTasksState(view)
    assertNotNull(state)
    assertEquals(4, state.steps.size)
    assertEquals(4, state.progress.totalSteps)
    assertTrue(state.progress.live)
  }
  private companion object {
    val BASE_THREAD: Thread = T3Json.decodeFromString(
      Thread.serializer(),
      """
        {
          "id": "thr_1", "projectId": "prj_1", "title": "Test Thread", "createdBy": "user",
          "creationSource": "web", "providerInstanceId": "codex",
          "modelSelection": { "instanceId": "codex", "model": "gpt-5" },
          "runtimeMode": "full-access", "interactionMode": "default", "branch": "main",
          "worktreePath": null, "lineage": { "rootThreadId": "thr_1" }, "status": "running",
          "itemCount": 0, "visibleItemCount": 0, "hasActionableProposedPlan": false,
          "createdAt": "2026-10-19T08:00:00.000Z", "updatedAt": "2026-10-19T08:00:00.000Z"
        }
      """,
    )
  }
}
