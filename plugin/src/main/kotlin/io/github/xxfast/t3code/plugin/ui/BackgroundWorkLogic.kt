package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.actions.activeRun
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask

// The background work banner's rules, packages/client-runtime/src/state/threadExecution.ts and
// packages/shared/src/orchestrationV2PendingBackgroundWork.ts

/** One line of the banner, upstream's `PendingBackgroundWorkItem`. */
data class PendingBackgroundWorkItem(
  val taskId: String,
  val kind: String,
  val label: String,
  /** The subagent's child thread, what "Show" or its label opens. Null for the other kinds. */
  val childThreadId: String?,
)

/** What the banner says, upstream's `PendingBackgroundWorkPresentation`. */
data class PendingBackgroundWorkPresentation(
  val title: String,
  val items: List<PendingBackgroundWorkItem>,
  /** Some task holds the thread's completion, so the dot pulses and it says "Waiting on". */
  val waiting: Boolean,
)

/** `BACKGROUND_WORK_KINDS`: each kind's place in the list and its nouns. */
private class BackgroundWorkKind(val order: Int, val singular: String, val plural: String)

private val BACKGROUND_WORK_KINDS: Map<String, BackgroundWorkKind> = mapOf(
  "subagent" to BackgroundWorkKind(0, "subagent", "subagents"),
  "command" to BackgroundWorkKind(1, "command", "commands"),
  "monitor" to BackgroundWorkKind(2, "monitor", "monitors"),
  "background_task" to BackgroundWorkKind(3, "background task", "background tasks"),
)

/** A kind the generated union doesn't know reads as a background task, its catch-all noun. */
private fun kindOf(kind: String): BackgroundWorkKind =
  BACKGROUND_WORK_KINDS[kind] ?: BACKGROUND_WORK_KINDS.getValue("background_task")

/**
 * `backgroundWorkHoldsCompletion`, orchestrationV2PendingBackgroundWork.ts:95: a command runs
 * on its own, everything else keeps the thread from settling.
 */
fun backgroundWorkHoldsCompletion(tasks: List<PendingBackgroundTask>): Boolean =
  tasks.any { it.kind != "command" }

/** `joinWithAnd`: "a", "a and b", "a, b and c". */
private fun joinWithAnd(parts: List<String>): String =
  if (parts.size <= 1) parts.joinToString("") else {
    "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
  }

/**
 * `presentPendingBackgroundWork`, threadExecution.ts:314. Null without tasks. One task is
 * named by its description ("Running: ./gradlew check", "Waiting on subagent Code Reviewer",
 * a monitor by its description alone), or by its noun without one ("Running a command").
 * Several are counted per kind in kind order, "Waiting on 1 subagent and 2 commands".
 */
fun presentPendingBackgroundWork(
  tasks: List<PendingBackgroundTask>,
): PendingBackgroundWorkPresentation? {
  if (tasks.isEmpty()) return null
  val waiting: Boolean = backgroundWorkHoldsCompletion(tasks)
  val items: List<PendingBackgroundWorkItem> = tasks
    .map { task ->
      val description: String? = task.description?.trim()
      val label: String? =
        if (task.kind == "subagent" && description != null) {
          formatSubagentDisplayTitle(description).trim()
        } else {
          description
        }
      PendingBackgroundWorkItem(
        taskId = task.taskId,
        kind = task.kind,
        label = label.takeUnless { it.isNullOrEmpty() } ?: kindOf(task.kind).singular,
        childThreadId = (task as? PendingBackgroundTask.PendingBackgroundTaskSubagent)
          ?.childThreadId,
      )
    }
    .sortedBy { kindOf(it.kind).order }
  val only: PendingBackgroundWorkItem? = items.singleOrNull()
  if (only != null) {
    val noun: String = kindOf(only.kind).singular
    val named: Boolean = only.label != noun
    val title: String = when {
      waiting && named -> (if (only.kind == "monitor") "" else "Waiting on $noun ") + only.label
      waiting -> "Waiting on a $noun"
      named -> "Running: ${only.label}"
      else -> "Running a $noun"
    }
    return PendingBackgroundWorkPresentation(title, items, waiting)
  }
  val groups: List<String> = items
    .groupingBy { it.kind }
    .eachCount()
    .map { (kind, count) ->
      val nouns: BackgroundWorkKind = kindOf(kind)
      "$count ${if (count == 1) nouns.singular else nouns.plural}"
    }
  val verb: String = if (waiting) "Waiting on" else "Running"
  return PendingBackgroundWorkPresentation("$verb ${joinWithAnd(groups)}", items, waiting)
}

/**
 * The tasks the banner lists, `activeBackgroundTasks` (useThreadLifecycle.tsx:388): the shell's
 * `pendingBackgroundTasks`, which the server derives with the same `derivePendingBackgroundWork`
 * the web client reruns on the projection, and nothing while a run is going, since the
 * interrupt path covers that.
 */
fun activeBackgroundTasks(view: ThreadView?, working: Boolean): List<PendingBackgroundTask> =
  if (working || view == null) emptyList() else view.thread.pendingBackgroundTasks.orEmpty()

/**
 * `interruptThreadTurn` without a run id, commands.ts:805 to 833, what the banner's Stop sends:
 * `thread.stop` when the server has `capabilities.threadStop`, else `run.interrupt` on the run
 * in flight, else on the latest run while background work is pending. Null when there's
 * nothing to stop; upstream then unwatches the thread's pull requests, which isn't ported.
 */
fun stopBackgroundWorkCommand(
  view: ThreadView,
  server: ServerInfo?,
  commandId: String,
): Command? {
  if (server.supports(THREAD_STOP)) {
    return Command.ThreadStop(commandId = commandId, threadId = view.thread.id)
  }
  val pending: Boolean = view.thread.pendingBackgroundTasks.orEmpty().isNotEmpty()
  val runId: String = activeRun(view)?.id
    ?: view.runs.lastOrNull()?.id?.takeIf { pending }
    ?: return null
  return Command.RunInterrupt(commandId = commandId, threadId = view.thread.id, runId = runId)
}
