package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.actions.activeRun
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.PendingBackgroundTask
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// The background work banner's rules, packages/client-runtime/src/state/threadExecution.ts and
// packages/shared/src/orchestrationV2PendingBackgroundWork.ts

/** One line of the banner, upstream's `PendingBackgroundWorkItem`. */
data class PendingBackgroundWorkItem(
  val taskId: String,
  val kind: String,
  val label: String,
  /** The subagent task's child thread, what Open opens. Null for the other kinds. */
  val childThreadId: String?,
)

/** What the banner says, upstream's `PendingBackgroundWorkPresentation`. */
data class PendingBackgroundWorkPresentation(
  val title: String,
  val items: List<PendingBackgroundWorkItem>,
  /** Some task holds the thread's completion, so it says "Waiting on". */
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
 * One task of the work line with what its turn item says, the rows `backgroundWorkNowItem`
 * (ThreadActivityBanners.tsx) builds before it picks a title.
 */
data class BackgroundWorkRow(
  val task: PendingBackgroundWorkItem,
  /** The subagent's `progress`, whitespace collapsed; null for the other kinds or without one. */
  val activity: String?,
  /** When the task's item started, whatever its type; the timer counts from it. */
  val startedAtMs: Long?,
  /** The subagent item's `updatedAt`, 0 for the other kinds: the newest activity wins. */
  val updatedAtMs: Long,
  /** The task's child thread, else the subagent item's. */
  val childThreadId: String?,
) {
  val isCommand: Boolean get() = task.kind != "subagent"
}

/**
 * The rows of `backgroundWorkNowItem`: each task's item is the last one whose
 * `nativeItemRef.nativeId || id` is the task id, the same key `derivePendingBackgroundWork`
 * gives turn-item tasks. Subagent items carry the progress.
 */
fun backgroundWorkRows(
  items: List<PendingBackgroundWorkItem>,
  turnItems: List<TurnItem>,
): List<BackgroundWorkRow> = items.map { task ->
  val item: TurnItem? = turnItems.lastOrNull { candidate ->
    nativeItemId(candidate).orEmpty().ifEmpty { candidate.id } == task.taskId
  }
  val subagent: TurnItem.TurnItemSubagent? = item as? TurnItem.TurnItemSubagent
  BackgroundWorkRow(
    task = task,
    activity = subagent?.progress?.replace(JS_WHITESPACES, " ")?.trim()?.ifEmpty { null },
    startedAtMs = item?.startedAt?.let(::epochMillisOrNull),
    updatedAtMs = subagent?.updatedAt?.let(::epochMillisOrNull) ?: 0,
    childThreadId = task.childThreadId ?: subagent?.childThreadId,
  )
}

/** JavaScript's `/\s+/gu`. */
private val JS_WHITESPACES: Regex = Regex("[$JS_WHITESPACE]+")

private fun epochMillisOrNull(iso: String): Long? =
  runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

/**
 * The item's `nativeItemRef?.nativeId`. Every variant has the field but the generated union
 * doesn't, and one this build doesn't know keeps it in its raw object.
 */
private fun nativeItemId(item: TurnItem): String? = when (item) {
  is TurnItem.TurnItemNotification -> item.nativeItemRef?.nativeId
  is TurnItem.UserMessage -> item.nativeItemRef?.nativeId
  is TurnItem.AssistantMessage -> item.nativeItemRef?.nativeId
  is TurnItem.Reasoning -> item.nativeItemRef?.nativeId
  is TurnItem.ProposedPlan -> item.nativeItemRef?.nativeId
  is TurnItem.TodoList -> item.nativeItemRef?.nativeId
  is TurnItem.UserInputRequest -> item.nativeItemRef?.nativeId
  is TurnItem.FileChange -> item.nativeItemRef?.nativeId
  is TurnItem.CommandExecution -> item.nativeItemRef?.nativeId
  is TurnItem.FileSearch -> item.nativeItemRef?.nativeId
  is TurnItem.WebSearch -> item.nativeItemRef?.nativeId
  is TurnItem.ApprovalRequest -> item.nativeItemRef?.nativeId
  is TurnItem.TurnItemCheckpoint -> item.nativeItemRef?.nativeId
  is TurnItem.RunInterruptRequest -> item.nativeItemRef?.nativeId
  is TurnItem.RunInterruptResult -> item.nativeItemRef?.nativeId
  is TurnItem.SystemNotice -> item.nativeItemRef?.nativeId
  is TurnItem.Error -> item.nativeItemRef?.nativeId
  is TurnItem.Compaction -> item.nativeItemRef?.nativeId
  is TurnItem.Handoff -> item.nativeItemRef?.nativeId
  is TurnItem.Fork -> item.nativeItemRef?.nativeId
  is TurnItem.ThreadCreated -> item.nativeItemRef?.nativeId
  is TurnItem.SecretRequest -> item.nativeItemRef?.nativeId
  is TurnItem.LinkRequest -> item.nativeItemRef?.nativeId
  is TurnItem.TurnItemSubagent -> item.nativeItemRef?.nativeId
  is TurnItem.DynamicTool -> item.nativeItemRef?.nativeId
  is TurnItem.Unknown -> (item.raw["nativeItemRef"] as? JsonObject)
    ?.get("nativeId")?.let { it as? JsonPrimitive }?.contentOrNull
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
