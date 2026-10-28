package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.PlanArtifact
import io.github.xxfast.t3code.protocol.PlanStep
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.PlanArtifact as GenPlanArtifact
import io.github.xxfast.t3code.protocol.generated.TurnItem as GenTurnItem

/** Status of a task step in the composer's tasks banner. */
enum class ComposerTaskStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
}

/** One step in a task plan, upstream's `ComposerTaskStep`. */
data class ComposerTaskStep(
  val step: String,
  val status: ComposerTaskStatus,
  val durationMs: Long? = null,
)

/** The active plan state, upstream's `ActivePlanState` (session-logic.ts:275). */
data class ActivePlanState(
  val runId: String?,
  val steps: List<ComposerTaskStep>,
  val explanation: String? = null,
  val createdAt: String? = null,
)

/** Progress summary for the collapsed tasks badge, upstream's `ComposerTasksProgress`. */
data class ComposerTasksProgress(
  val step: String,
  val completedSteps: Int,
  val totalSteps: Int,
  val live: Boolean,
)

/** Full state passed to the composer tasks banner and drawer. */
data class ComposerTasksState(
  val progress: ComposerTasksProgress,
  val steps: List<ComposerTaskStep>,
)

/**
 * `isLatestRunSettled`, packages/web/src/session-logic.ts:213: a run that has finished its work
 * and is not currently executing.
 */
fun isLatestRunSettled(
  latestRun: Run?,
  activeRunId: String?,
): Boolean {
  if (latestRun == null) return false
  if (
    latestRun.status == "preparing" ||
    latestRun.status == "queued" ||
    latestRun.status == "starting" ||
    latestRun.status == "running" ||
    latestRun.status == "waiting"
  ) {
    return false
  }
  return activeRunId != latestRun.id
}

/**
 * `deriveActivePlanState`, packages/web/src/session-logic.ts:241: selects the active todo list
 * plan for [latestRunId], or falls back to the plan with the highest run ordinal.
 */
fun deriveActivePlanState(
  view: ThreadView?,
  latestRunId: String?,
): ActivePlanState? {
  if (view == null) return null
  val plans: List<PlanArtifact> = view.plans
    .filter { it.kind == "todo_list" }
    .filter { it.status != "superseded" }
  val runOrdinals: Map<String?, Long> = view.runs.associate { it.id to it.ordinal }
  val plan: PlanArtifact? = plans.findLast { it.runId == latestRunId }
    ?: plans.fold<PlanArtifact, PlanArtifact?>(null) { latest, candidate ->
      if (
        latest == null ||
        (runOrdinals[candidate.runId] ?: -1L) >= (runOrdinals[latest.runId] ?: -1L)
      ) {
        candidate
      } else {
        latest
      }
    }
  if (plan == null) return null
  val rawSteps: List<PlanStep> = when (plan) {
    is GenPlanArtifact.TodoList -> plan.steps
    else -> emptyList()
  }
  if (rawSteps.isEmpty()) return null
  val explanation: String? = when (plan) {
    is GenPlanArtifact.TodoList -> plan.explanation
    else -> null
  }
  val steps: List<ComposerTaskStep> = rawSteps.map { raw ->
    val status: ComposerTaskStatus = when (raw.status) {
      "completed" -> ComposerTaskStatus.COMPLETED
      "inProgress", "running" -> ComposerTaskStatus.IN_PROGRESS
      else -> ComposerTaskStatus.PENDING
    }
    ComposerTaskStep(
      step = raw.text,
      status = status,
      durationMs = raw.durationMs,
    )
  }
  return ActivePlanState(
    runId = plan.runId,
    steps = steps,
    explanation = explanation,
    createdAt = planItemTime(view, plan),
  )
}

/**
 * `selectComposerTasksPlan`, packages/web/src/session-logic.ts:289: keeps an unfinished plan
 * visible for active runs, or across runs for Claude sessions where the plan's provider thread
 * is the latest started one.
 */
fun selectComposerTasksPlan(
  view: ThreadView?,
  plan: ActivePlanState?,
  activeRunId: String?,
  activeRunSettled: Boolean,
): ActivePlanState? {
  if (view == null || plan == null) return null
  if (activeRunId != null && !activeRunSettled && plan.runId == activeRunId) return plan
  if (plan.steps.all { it.status == ComposerTaskStatus.COMPLETED }) return null
  val planProviderThreadId: String = view.runs
    .find { it.id == plan.runId }
    ?.providerThreadId
    ?: return null
  val planDriver: String = view.providerThreads
    .find { it.id == planProviderThreadId }
    ?.driver
    ?: return null
  if (planDriver != "claudeAgent") return null
  var latestStartedRun: Run? = null
  for (run in view.runs) {
    if (
      run.startedAt != null &&
      run.providerThreadId != null &&
      (latestStartedRun == null || run.ordinal > latestStartedRun.ordinal)
    ) {
      latestStartedRun = run
    }
  }
  return if (latestStartedRun?.providerThreadId == planProviderThreadId) plan else null
}

/**
 * `activeComposerTasksProgress`, packages/web/src/components/ChatView.tsx:1027: calculates
 * step counts and the current step label.
 */
fun activeComposerTasksProgress(
  plan: ActivePlanState?,
  activeRunId: String?,
  activeRunSettled: Boolean,
): ComposerTasksProgress? {
  if (plan == null) return null
  val totalSteps: Int = plan.steps.size
  if (totalSteps == 0) return null
  val live: Boolean = !activeRunSettled && plan.runId == activeRunId
  val completedSteps: Int = plan.steps.count { it.status == ComposerTaskStatus.COMPLETED }
  val step: String = plan.steps.find { it.status == ComposerTaskStatus.IN_PROGRESS }?.step
    ?: plan.steps.find { it.status == ComposerTaskStatus.PENDING }?.step
    ?: plan.steps.last().step
  return ComposerTasksProgress(
    step = step,
    completedSteps = completedSteps,
    totalSteps = totalSteps,
    live = live,
  )
}

/** Derives the full [ComposerTasksState] for [view]. */
fun composerTasksState(view: ThreadView?): ComposerTasksState? {
  if (view == null) return null
  val activeActivityRun: Run? = activeRun(view.runs) ?: view.runs.lastOrNull()
  val activeRunSettled: Boolean = isLatestRunSettled(activeActivityRun, view.thread.activeRunId)
  val activePlan: ActivePlanState? = deriveActivePlanState(view, activeActivityRun?.id)
  val selectedPlan: ActivePlanState = selectComposerTasksPlan(
    view = view,
    plan = activePlan,
    activeRunId = activeActivityRun?.id,
    activeRunSettled = activeRunSettled,
  ) ?: return null
  val progress: ComposerTasksProgress = activeComposerTasksProgress(
    plan = selectedPlan,
    activeRunId = activeActivityRun?.id,
    activeRunSettled = activeRunSettled,
  ) ?: return null
  return ComposerTasksState(
    progress = progress,
    steps = selectedPlan.steps,
  )
}

private fun planItemTime(view: ThreadView, plan: PlanArtifact): String? {
  val item: GenTurnItem? = view.items.findLast { candidate ->
    (candidate.type == "proposed_plan" || candidate.type == "todo_list") &&
      (candidate as? GenTurnItem.TodoList)?.planId == plan.id
  }
  if (item != null) return item.updatedAt
  val run: Run? = view.runs.find { it.id == plan.runId }
  return run?.completedAt ?: run?.startedAt ?: run?.requestedAt ?: view.thread.createdAt
}
