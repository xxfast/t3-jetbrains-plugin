package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.generated.CheckpointFileSummary
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The chat timeline, ported from upstream in two stages like upstream does it:
 * `apps/web/src/session-logic.ts` turns items into entries, then
 * `apps/web/src/components/chat/MessagesTimeline.logic.ts` turns entries into rows. Labels and
 * group summaries come from `packages/client-runtime/src/work-log/presentation.ts`.
 *
 * Not ported: superseded attempt folds (we don't decode attempts), T3 MCP tool presentations,
 * tool sources ("Used X integration"), subagent groups, html renders, mcp apps and worktree
 * setup rows. Those items fall through to the generic rows.
 */

/** Upstream's `WorkLogToolLifecycleStatus`. */
enum class ToolStatus { InProgress, Completed, Idle, Failed, Declined, Stopped }

enum class WorkTone { Thinking, Tool, Info, Error }

/** Upstream's `WorkLogEntry`, the parts the plugin draws. */
data class WorkLogEntry(
  val item: TurnItem,
  val label: String,
  val tone: WorkTone,
  val status: ToolStatus?,
  val detail: String? = null,
  val command: String? = null,
  val changedFiles: List<String> = emptyList(),
  val toolTitle: String? = null,
  val toolName: String? = null,
  val sourceActivityKind: String? = null,
) {
  val id: String get() = item.id
  val runId: String? get() = item.runId
  val itemType: String get() = item.type
  val createdAt: String get() = item.startedAt ?: item.updatedAt
}

/** Upstream's `TimelineEntry`. */
sealed interface TimelineEntry {
  val id: String
  val runId: String?
  val createdAt: String

  /** A user or assistant message. */
  data class Message(val item: TurnItem) : TimelineEntry {
    override val id: String = item.id
    override val runId: String? = item.runId
    override val createdAt: String = item.startedAt ?: item.updatedAt
    val user: Boolean get() = item is TurnItem.UserMessage
    val text: String get() = (item as? TurnItem.UserMessage)?.text
      ?: (item as? TurnItem.AssistantMessage)?.text.orEmpty()
    val streaming: Boolean get() = (item as? TurnItem.AssistantMessage)?.streaming == true
    val inputIntent: String? get() = (item as? TurnItem.UserMessage)?.inputIntent
  }

  data class Work(val entry: WorkLogEntry) : TimelineEntry {
    override val id: String = entry.id
    override val runId: String? = entry.runId
    override val createdAt: String = entry.createdAt
  }

  data class Event(val item: TurnItem) : TimelineEntry {
    override val id: String = item.id
    override val runId: String? = item.runId
    override val createdAt: String = item.startedAt ?: item.updatedAt
  }

  data class Plan(val item: TurnItem.ProposedPlan) : TimelineEntry {
    override val id: String = item.id
    override val runId: String? = item.runId
    override val createdAt: String = item.startedAt ?: item.updatedAt
  }
}

/** `STANDALONE_V2_ITEM_TYPES`, session-logic.ts:363. */
private val STANDALONE: Set<String> = setOf(
  "fork", "handoff", "run_interrupt_request", "run_interrupt_result", "secret_request", "subagent",
)

/** `deriveTimelineEntriesFromVisibleTurnItems`, session-logic.ts:582. */
fun deriveTimelineEntries(items: List<TurnItem>): List<TimelineEntry> {
  val foldedAnswerMessageIds: Set<String> = items
    .filterIsInstance<TurnItem.UserInputRequest>()
    .mapNotNull { it.questionAnswer?.let { _ -> "async-answer:${it.requestId}" } }
    .toSet()
  return items.mapNotNull { item ->
    when {
      item.type == "todo_list" || item.type == "checkpoint" -> null
      item is TurnItem.UserMessage && item.messageId in foldedAnswerMessageIds -> null
      item is TurnItem.TurnItemNotification -> TimelineEntry.Work(
        WorkLogEntry(item = item, label = item.summary, tone = WorkTone.Info, status = null),
      )
      item is TurnItem.UserMessage || item is TurnItem.AssistantMessage ->
        TimelineEntry.Message(item)
      item is TurnItem.ProposedPlan -> TimelineEntry.Plan(item)
      item.type in STANDALONE -> TimelineEntry.Event(item)
      else -> TimelineEntry.Work(projectedWorkEntry(item))
    }
  }
}

/** `projectedWorkEntryStatus`. */
private fun workStatus(status: String): ToolStatus? = when (status) {
  "pending", "running", "waiting" -> ToolStatus.InProgress
  "completed" -> ToolStatus.Completed
  "idle" -> ToolStatus.Idle
  "failed" -> ToolStatus.Failed
  "declined" -> ToolStatus.Declined
  "cancelled", "interrupted" -> ToolStatus.Stopped
  else -> null
}

/** `projectedWorkEntryTone`. Errors are `info` upstream too; their failure shows otherwise. */
private fun workTone(item: TurnItem): WorkTone = when (item.type) {
  "reasoning" -> WorkTone.Thinking
  "command_execution", "file_change", "file_search", "web_search", "dynamic_tool", "subagent",
  "thread_created", "user_input_request", "approval_request" -> WorkTone.Tool
  else -> WorkTone.Info
}

/** `projectedWorkEntry`, session-logic.ts:423. */
fun projectedWorkEntry(item: TurnItem): WorkLogEntry {
  val title: String? = item.title?.trim()?.takeIf { it.isNotEmpty() }
  val common = WorkLogEntry(
    item = item,
    label = title ?: item.type.replace("_", " "),
    tone = workTone(item),
    status = workStatus(item.status),
  )
  return when (item) {
    is TurnItem.ThreadCreated -> common.copy(label = "Created thread")
    is TurnItem.Compaction -> common.copy(
      label = contextCompactionLabel(item),
      sourceActivityKind = CONTEXT_COMPACTION,
      detail = item.summary,
    )
    is TurnItem.Reasoning -> common.copy(
      label = title ?: "Thinking",
      detail = item.text.ifEmpty { null },
    )
    is TurnItem.CommandExecution -> common.copy(
      label = title ?: "Ran command",
      command = item.input,
      toolTitle = title ?: "Command",
    )
    is TurnItem.FileChange -> {
      val changes: Int = item.changes?.size ?: 0
      common.copy(
        label = title ?: if (changes > 1) "Changed $changes files" else "Changed ${item.fileName}",
        changedFiles = item.changes?.map { it.path } ?: listOf(item.fileName),
        toolTitle = title ?: "File change",
      )
    }
    is TurnItem.FileSearch -> common.copy(
      label = title ?: item.pattern?.let { "Searched $it" } ?: "Searched files",
      detail = item.pattern,
      toolTitle = title ?: "File search",
    )
    is TurnItem.WebSearch -> common.copy(
      label = title ?: "Searched the web",
      detail = item.patterns?.takeIf { it.isNotEmpty() }?.joinToString(", "),
      toolTitle = title ?: "Web search",
    )
    is TurnItem.SystemNotice -> common.copy(label = item.message, sourceActivityKind = WARNING)
    is TurnItem.Error -> {
      val usageLimit: Boolean = item.failure.`class` == "usage_limit"
      common.copy(
        label = providerErrorLabel(item),
        detail = item.failure.message,
        sourceActivityKind = when {
          usageLimit && item.status != "completed" -> WARNING
          item.retry == null -> RUNTIME_ERROR
          else -> null
        },
      )
    }
    is TurnItem.DynamicTool -> {
      val action: String = classifyToolName(item.toolName)
      val path: String? = inputString(item.input, PATH_KEYS)
      val query: String? = inputString(item.input, SEARCH_KEYS)
      common.copy(
        label = title ?: when (action) {
          "read" -> formatReadToolLabel(path.orEmpty())
          "search" -> query?.let { "Searched $it" } ?: item.toolName ?: "Tool call"
          else -> item.toolName ?: "Tool call"
        },
        toolTitle = title ?: item.toolName ?: "Tool",
        toolName = item.toolName,
        changedFiles = if (action == "read" && path != null) listOf(path) else emptyList(),
      )
    }
    is TurnItem.ApprovalRequest -> common.copy(
      label = title ?: "Approval requested",
      detail = item.prompt ?: item.requestKind,
    )
    is TurnItem.UserInputRequest -> common.copy(
      label = title ?: if (item.questionAnswer != null) "Answered questions" else "Input requested",
    )
    else -> common
  }
}

const val CONTEXT_COMPACTION: String = "context-compaction"
private const val WARNING: String = "runtime.warning"
private const val RUNTIME_ERROR: String = "runtime.error"

/** `contextCompactionLabel`, presentation.ts:51. */
fun contextCompactionLabel(item: TurnItem.Compaction): String {
  val before: Long? = item.beforeTokenCount
  val after: Long? = item.afterTokenCount
  return when {
    item.status == "running" -> "Compacting context"
    before != null && after != null ->
      "Context compacted ${formatTokens(before)} → ${formatTokens(after)} tokens"
    else -> "Context compacted"
  }
}

private fun formatTokens(count: Long): String = when {
  count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0).replace(".0M", "M")
  count >= 1_000 -> "%.1fk".format(count / 1_000.0).replace(".0k", "k")
  else -> "$count"
}

/** `providerErrorPresentation`'s label, session-logic.ts:387. */
private fun providerErrorLabel(item: TurnItem.Error): String {
  val usageLimit: Boolean = item.failure.`class` == "usage_limit"
  val retry = item.retry
    ?: return if (usageLimit) "Usage limit reached" else item.title?.trim()?.ifEmpty { null }
      ?: "Provider error"
  val progress: String =
    retry.maxAttempts?.let { "${retry.attempt}/$it" } ?: "${retry.attempt}"
  return when (item.status) {
    "running" -> "Retrying provider ($progress)"
    "completed" -> "Provider recovered ($progress retries)"
    "failed" ->
      "${if (usageLimit) "Usage limit reached" else "Provider error"} after $progress retries"
    else -> "Provider retry stopped ($progress)"
  }
}

/** `formatReadToolLabel`, toolActivity.ts:389. */
fun formatReadToolLabel(path: String, extraCount: Int = 0): String {
  val suffix: String = if (extraCount > 0) " +$extraCount more" else ""
  return if (path.isBlank()) "Read file$suffix" else "Read ${path.trim()}$suffix"
}

private val PATH_KEYS: List<String> = listOf("file_path", "filePath", "path")
private val SEARCH_KEYS: List<String> =
  listOf("pattern", "query", "searchTerm", "regex", "grep", "needle")

private fun inputString(input: Any?, keys: List<String>): String? {
  val record: JsonObject = input as? JsonObject ?: return null
  return keys.firstNotNullOfOrNull { key ->
    (record[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
  }
}

/** The tool name half of `classifyToolActivity`, toolActivity.ts:254. */
private fun classifyToolName(toolName: String?): String =
  when (toolName?.substringAfterLast('.')?.substringAfterLast("__")?.lowercase()) {
    "find", "grep", "glob", "rg", "ls" -> "search"
    "terminal", "bash", "shell" -> "command"
    "read", "readfile" -> "read"
    "edit", "write", "multiedit" -> "file_change"
    else -> "other"
  }

/** `workLogEntryIsToolLike`, presentation.ts:350. */
fun WorkLogEntry.isToolLike(): Boolean =
  tone != WorkTone.Info || !command.isNullOrBlank() || item is TurnItem.ApprovalRequest ||
    itemType in TOOL_LIFECYCLE

private val TOOL_LIFECYCLE: Set<String> = setOf(
  "command_execution", "file_change", "file_search", "web_search", "dynamic_tool",
)

/** `workEntryIndicatesToolFailure`, without upstream's output sniffing. */
fun WorkLogEntry.indicatesFailure(): Boolean {
  if (tone == WorkTone.Error || status == ToolStatus.Failed || status == ToolStatus.Declined) {
    return true
  }
  if (!isToolLike()) return false
  val command = item as? TurnItem.CommandExecution ?: return false
  return command.outputIndicatesFailure == true || (command.exitCode ?: 0L) != 0L
}

/** `workEntryIndicatesToolSuccess`. */
fun WorkLogEntry.indicatesSuccess(): Boolean = isToolLike() && !indicatesFailure() &&
  tone != WorkTone.Thinking && status != ToolStatus.Idle && status != ToolStatus.InProgress &&
  status != ToolStatus.Stopped

/** `workEntryIndicatesToolNeutralStatus`, session-logic.ts:204. */
private fun WorkLogEntry.indicatesNeutral(): Boolean =
  isToolLike() && !indicatesFailure() && !indicatesSuccess()

/** `workEntryIsVisibleInGroup`, MessagesTimeline.logic.ts:265. */
fun WorkLogEntry.visibleInGroup(expandedToolGroupEntry: Boolean = false): Boolean {
  if (itemType == "reasoning") return !detail.isNullOrBlank()
  return (expandedToolGroupEntry && status == ToolStatus.InProgress) ||
    status == ToolStatus.Stopped || !indicatesNeutral()
}

/** Upstream's `ToolGroupAction`, without the pull request and device actions. */
enum class ToolAction { Read, Edit, Command, ThreadCreate, CodeSearch, Search, Other, Update }

/** `toolGroupAction`, presentation.ts:417. */
fun WorkLogEntry.action(): ToolAction {
  if (itemType == "thread_created") return ToolAction.ThreadCreate
  val approval = item as? TurnItem.ApprovalRequest
  if (approval?.requestKind == "file-read") return ToolAction.Read
  if (approval != null || item is TurnItem.UserInputRequest) {
    return if (isToolLike()) ToolAction.Other else ToolAction.Update
  }
  val classified: String = when (item) {
    is TurnItem.CommandExecution -> "command"
    is TurnItem.FileChange -> "file_change"
    is TurnItem.WebSearch -> "search"
    else -> classifyToolName(toolName)
  }
  return when {
    classified == "read" -> ToolAction.Read
    classified == "file_change" -> ToolAction.Edit
    classified == "command" || !command.isNullOrBlank() -> ToolAction.Command
    classified == "search" ->
      if (itemType == "web_search" && !isLocalCodeSearch()) ToolAction.Search
      else ToolAction.CodeSearch
    isLocalCodeSearch() -> ToolAction.CodeSearch
    changedFiles.isNotEmpty() && itemType == "file_change" -> ToolAction.Edit
    isToolLike() -> ToolAction.Other
    else -> ToolAction.Update
  }
}

private fun WorkLogEntry.isLocalCodeSearch(): Boolean = itemType == "file_search" ||
  (itemType == "web_search" &&
    Regex("\\bgrep\\b", RegexOption.IGNORE_CASE).containsMatchIn(toolTitle ?: label))

/** `toolGroupActionCount`: edits count unique paths. */
private fun actionCount(action: ToolAction, entries: List<WorkLogEntry>): Int {
  if (action != ToolAction.Edit) return entries.size
  val paths: Set<String> = entries.flatMap { it.changedFiles }.toSet()
  return paths.size + entries.count { it.changedFiles.isEmpty() }
}

/** `toolGroupActionLabel`. */
private fun actionLabel(action: ToolAction, count: Int): String {
  fun plural(one: String, many: String): String = if (count == 1) one else many
  return when (action) {
    ToolAction.Read -> "Read $count ${plural("file", "files")}"
    ToolAction.Edit -> "Changed $count ${plural("file", "files")}"
    ToolAction.Command -> "Ran $count ${plural("command", "commands")}"
    ToolAction.ThreadCreate -> "Created $count ${plural("thread", "threads")}"
    ToolAction.Search -> "Searched the web $count ${plural("time", "times")}"
    ToolAction.CodeSearch -> "Searched code $count ${plural("time", "times")}"
    ToolAction.Other -> "Used $count ${plural("tool", "tools")}"
    ToolAction.Update -> "Received $count ${plural("update", "updates")}"
  }
}

/** `summaryActionPriority`: the actions that change things first, catch-alls last. */
private fun priority(action: ToolAction): Int = when (action) {
  ToolAction.Command, ToolAction.Edit, ToolAction.ThreadCreate -> 0
  ToolAction.Other, ToolAction.Update -> 2
  else -> 1
}

data class ToolGroupSummary(val summary: String, val hasFailure: Boolean)

/** `summarizeToolGroup`, presentation.ts:631. */
fun summarizeToolGroup(entries: List<WorkLogEntry>): ToolGroupSummary {
  val tools: List<WorkLogEntry> = entries.filter { it.itemType != "reasoning" }
  if (entries.isNotEmpty() && tools.isEmpty()) {
    val summary: String = if (entries.size == 1) "Thought" else "Thought (×${entries.size})"
    return ToolGroupSummary(summary, hasFailure = false)
  }
  val groups: Map<ToolAction, List<WorkLogEntry>> = tools.groupBy { it.action() }
  data class Bucket(val index: Int, val count: Int, val priority: Int, val label: String)
  val buckets: List<Bucket> = groups.entries.mapIndexed { index, (action, group) ->
    Bucket(index, group.size, priority(action), actionLabel(action, actionCount(action, group)))
  }
  val selected: List<Bucket> = buckets
    .sortedWith(compareBy({ it.priority }, { it.index }))
    .take(2)
    .sortedBy { it.index }
  val labels: MutableList<String> = selected.map { it.label }.toMutableList()
  val remaining: Int = tools.size - selected.sumOf { it.count }
  if (remaining > 0) {
    labels += "Performed $remaining other ${if (remaining == 1) "action" else "actions"}"
  }
  val sentence: List<String> = labels.mapIndexed { index, label ->
    if (index == 0) label else label.replaceFirstChar { it.lowercase() }
  }
  val summary: String =
    if (sentence.size < 3) sentence.joinToString(" and ")
    else "${sentence.dropLast(1).joinToString(", ")}, and ${sentence.last()}"
  return ToolGroupSummary(summary, hasFailure = tools.any { it.indicatesFailure() })
}

/** Upstream's `ToolGroupSummaryKind`, which picks the group header's icon. */
enum class ToolGroupKind { Action, DynamicTool, Reasoning, AgentTool, ToneTool, Other, Mixed }

/** `toolGroupSummaryKind`, presentation.ts:717. With [ToolGroupKind.Action], see [action]. */
fun toolGroupSummaryKind(entries: List<WorkLogEntry>): Pair<ToolGroupKind, ToolAction?> {
  val tools: List<WorkLogEntry> = entries.filter { it.itemType != "reasoning" }
  if (entries.isNotEmpty() && tools.isEmpty()) return ToolGroupKind.Reasoning to null
  val actions: Set<ToolAction> = tools.map { it.action() }.toSet()
  if (actions.size != 1) return ToolGroupKind.Mixed to null
  val action: ToolAction = actions.single()
  if (action != ToolAction.Other) return ToolGroupKind.Action to action
  val kinds: Set<ToolGroupKind> = tools.map { entry ->
    when {
      entry.itemType == "dynamic_tool" -> ToolGroupKind.DynamicTool
      entry.itemType == "subagent" || entry.tone == WorkTone.Thinking -> ToolGroupKind.AgentTool
      entry.tone == WorkTone.Tool -> ToolGroupKind.ToneTool
      else -> ToolGroupKind.Other
    }
  }.toSet()
  return (kinds.singleOrNull() ?: ToolGroupKind.Mixed) to null
}

private val WHITESPACE = Regex("\\s+")

/** `normalizeCompactToolLabel`. */
private fun normalizeCompactToolLabel(value: String): String =
  value.replace(Regex("\\s+(?:complete|completed)\\s*$", RegexOption.IGNORE_CASE), "").trim()

/** `workEntryDisplayLabel`, MessagesTimeline.logic.ts:131. */
fun WorkLogEntry.displayLabel(): String {
  if (itemType == "system_notice") return label
  if (itemType == "reasoning" || tone == WorkTone.Thinking) {
    return detail?.trim()?.replace(WHITESPACE, " ")?.ifEmpty { null } ?: label
  }
  if (!command.isNullOrBlank()) return commandDisplayText(command)
  val action: ToolAction = action()
  if ((action == ToolAction.CodeSearch || action == ToolAction.Search) &&
    itemType == "file_search"
  ) return label
  if (action == ToolAction.Read && changedFiles.isNotEmpty()) {
    return formatReadToolLabel(changedFiles.first(), changedFiles.size - 1)
  }
  val retry: Boolean = (item as? TurnItem.Error)?.retry != null
  val compactDetail: String? = detail?.trim()?.ifEmpty { null }
  val searchOutput: Boolean = (action == ToolAction.CodeSearch || action == ToolAction.Search) &&
    compactDetail.orEmpty().contains(Regex("[\\r\\n]"))
  if (compactDetail != null && !retry && action != ToolAction.Read && !searchOutput) {
    return compactDetail
  }
  changedFiles.firstOrNull()?.let { path ->
    return if (changedFiles.size == 1) path else "$path +${changedFiles.size - 1} more"
  }
  if (action == ToolAction.Read) return "Read file"
  val heading: String = normalizeCompactToolLabel(toolTitle?.ifEmpty { null } ?: label)
  return heading.replaceFirstChar { it.uppercase() }
}

/** `singleToolCallLabel`, MessagesTimeline.logic.ts:94. */
fun WorkLogEntry.singleToolCallLabel(): String {
  if (itemType == "reasoning") {
    return detail?.trim()?.replace(WHITESPACE, " ")?.ifEmpty { null } ?: "Thought"
  }
  if (itemType == "web_search") return toolTitle ?: "Web search"
  return displayLabel()
}

/** `liveActivityToolStatus`, presentation.ts:187. */
private fun liveStatus(status: ToolStatus?, active: Boolean): ToolStatus = when {
  status == ToolStatus.Failed || status == ToolStatus.Declined || status == ToolStatus.Stopped ||
    status == ToolStatus.Idle -> status
  active || status == ToolStatus.InProgress -> ToolStatus.InProgress
  else -> ToolStatus.Completed
}

/** `liveWorkEntryLabel`, MessagesTimeline.logic.ts:231. */
fun WorkLogEntry.liveLabel(active: Boolean): String {
  val status: ToolStatus = liveStatus(status, active)
  if (itemType == "reasoning") {
    return detail?.trim()?.replace(WHITESPACE, " ")?.ifEmpty { null }
      ?: if (status == ToolStatus.InProgress) "Thinking" else "Thought"
  }
  val command: String? = command?.trim()?.ifEmpty { null }
  if (command != null) {
    val verb: String = when (status) {
      ToolStatus.InProgress -> "Running"
      ToolStatus.Failed -> "Failed"
      ToolStatus.Declined -> "Declined"
      ToolStatus.Stopped -> "Stopped"
      else -> "Ran"
    }
    return "$verb ${commandProgramName(command) ?: "command"}"
  }
  return displayLabel()
}

private val SHELLS: Set<String> = setOf("bash", "sh", "zsh", "dash", "fish")

/**
 * `commandDisplayText`, commandLabel.ts:1367: a `bash -lc '<script>'` wrapper comes off. Upstream
 * tokenizes the shell properly; this takes the common shapes only and keeps anything else as is.
 */
fun commandDisplayText(command: String): String {
  val trimmed: String = command.trim()
  val match: MatchResult = WRAPPER.matchEntire(trimmed) ?: return trimmed
  val program: String = match.groupValues[1].substringAfterLast('/')
  if (program !in SHELLS) return trimmed
  val script: String = match.groupValues[3]
  return script.trim().ifEmpty { trimmed }
}

private val WRAPPER = Regex("""^(\S+)\s+-l?c\s+(['"])([\s\S]*)\2$""")

/** `commandProgramName`, simplified: the first word of the displayed command, without a path. */
fun commandProgramName(command: String): String? = commandDisplayText(command)
  .trim()
  .split(WHITESPACE)
  .firstOrNull { !it.contains('=') }
  ?.substringAfterLast('/')
  ?.ifEmpty { null }

/** `formatDuration`, packages/shared/src/orchestrationTiming.ts:34. */
fun formatDuration(durationMs: Long): String {
  if (durationMs < 0) return "0ms"
  if (durationMs < 1_000) return "${maxOf(1, durationMs)}ms"
  if (durationMs < 10_000) {
    val tenths: Double = (durationMs / 100.0).roundToLong() / 10.0
    return if (tenths >= 10) "10s" else "%.1fs".format(tenths)
  }
  if (durationMs < 60_000) return "${(durationMs / 1_000.0).roundToLong()}s"
  val total: Long = (durationMs / 1_000.0).roundToLong()
  val hours: Long = total / 3_600
  val minutes: Long = (total % 3_600) / 60
  val seconds: Long = total % 60
  return listOfNotNull(
    hours.takeIf { it > 0 }?.let { "${it}h" },
    minutes.takeIf { it > 0 }?.let { "${it}m" },
    seconds.takeIf { it > 0 }?.let { "${it}s" },
  ).joinToString(" ")
}

/** `formatWorkingTimer`, MessagesTimeline.tsx:3311. */
fun formatWorkingTimer(start: Instant, now: Instant): String {
  val seconds: Long = maxOf(0, Duration.between(start, now).seconds)
  return if (seconds < 60) "${seconds}s" else formatDuration(seconds * 1_000)
}

/** One row of the timeline, upstream's `MessagesTimelineRow`. */
sealed interface TimelineRow {
  val id: String

  /** "Working for 12s", right after the prompt the running turn answers. */
  data class Working(val startedAt: String?) : TimelineRow {
    override val id: String = "working-indicator-row"
  }

  /** "Worked for 24s": a settled turn's work, folded away. */
  data class TurnFold(
    val runId: String,
    val label: String,
    val expanded: Boolean,
  ) : TimelineRow {
    override val id: String = "turn-fold:$runId"
  }

  /** Work log lines: one entry, or the entries of an expanded group. */
  data class Work(
    override val id: String,
    val entries: List<WorkLogEntry>,
    val expandedGroup: Boolean = false,
    val displayLabel: String? = null,
  ) : TimelineRow

  /** A group's one line while its run is still going: the latest entry's live label. */
  data class WorkLive(
    override val id: String,
    val entry: WorkLogEntry,
    val groupId: String,
    val expanded: Boolean,
    val active: Boolean,
  ) : TimelineRow

  /** A group's one line: "Ran 3 commands and read 2 files". */
  data class WorkToggle(
    override val id: String,
    val runId: String?,
    val groupId: String,
    val summary: String,
    val kind: Pair<ToolGroupKind, ToolAction?>,
    val hasFailure: Boolean,
    val expanded: Boolean,
  ) : TimelineRow

  /** "Thinking", when the running turn shows nothing else. */
  data object Thinking : TimelineRow {
    override val id: String = "thinking-row"
  }

  data class ContextCompaction(
    override val id: String,
    val label: String,
    val active: Boolean,
  ) : TimelineRow

  data class Message(
    val entry: TimelineEntry.Message,
    val showMeta: Boolean, // the terminal reply of a settled turn
    val changedFiles: ChangedFiles? = null, // under the terminal reply, from its checkpoint
    val revertTo: Run? = null, // on a prompt: the run whose checkpoint is the state before it
  ) : TimelineRow {
    override val id: String = entry.id
  }

  /** The terminal reply's meta, moved under the tool rows that trailed it. */
  data class AssistantMeta(val entry: TimelineEntry.Message) : TimelineRow {
    override val id: String = "assistant-meta:${entry.id}"
  }

  data class Event(val item: TurnItem) : TimelineRow {
    override val id: String = item.id
  }

  data class Plan(val item: TurnItem.ProposedPlan) : TimelineRow {
    override val id: String = item.id
  }

  /**
   * The worktree being prepared for the thread's first turn, upstream's `WorktreeSetupCard`.
   * [embedded] once the agent has the worktree: the card folds to one line under the work.
   */
  data class WorktreeSetup(val snapshot: WorktreeSetupSnapshot, val embedded: Boolean) :
    TimelineRow {
    override val id: String = WORKTREE_SETUP_ROW_ID
  }
}

/** `WORKTREE_SETUP_ROW_ID`, MessagesTimeline.logic.ts:1968. */
const val WORKTREE_SETUP_ROW_ID: String = "worktree-setup-row"

/** Upstream's `TurnDiffSummary`, as far as the changed files card needs it. */
data class ChangedFiles(val run: Run, val files: List<CheckpointFileSummary>)

/** `workGroupId`. */
fun workGroupId(entryId: String): String = "work-group:$entryId"

/** `timelineEntryStartsResponse`. */
private fun TimelineEntry.startsResponse(): Boolean =
  (this is TimelineEntry.Message && user && inputIntent != "steer" &&
    inputIntent != "promoted_queued_to_steer") ||
    (this is TimelineEntry.Work && entry.itemType == "notification")

/** `deriveTerminalAssistantMessageIds`: the last reply per run, or per prompt when runless. */
private fun terminalAssistantMessageIds(entries: List<TimelineEntry>): Set<String> {
  val byResponse: MutableMap<String, String> = mutableMapOf()
  var runless = 0
  entries.filterIsInstance<TimelineEntry.Message>().forEach { message ->
    if (message.user) runless += 1
    else byResponse[message.runId?.let { "turn:$it" } ?: "unkeyed:$runless"] = message.id
  }
  return byResponse.values.toSet()
}

private fun TimelineEntry.isPersistentResourceCard(): Boolean = this is TimelineEntry.Event &&
  item.type in setOf("fork", "thread_created", "secret_request")

/** `timelineEntryFoldRunId`. */
private fun TimelineEntry.foldRunId(runlessKey: String?): String? = when {
  this is TimelineEntry.Work && entry.itemType == "system_notice" -> null
  this is TimelineEntry.Message && !user -> runId ?: runlessKey
  this is TimelineEntry.Work -> runId ?: runlessKey
  this is TimelineEntry.Event && (isPersistentResourceCard() || item.type == "subagent") ->
    runId ?: runlessKey
  else -> null
}

private fun TimelineEntry.failedErrorItem(): TurnItem.Error? {
  val item: TurnItem? = when (this) {
    is TimelineEntry.Event -> item
    is TimelineEntry.Work -> entry.item
    else -> null
  }
  return (item as? TurnItem.Error)?.takeIf { it.status == "failed" && it.parentItemId == null }
}

private class TurnFold(
  val runId: String,
  val anchorEntryId: String,
  val hiddenEntryIds: Set<String>,
  val label: String,
)

private fun String.instantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private fun elapsedMs(start: String?, end: String?): Long? {
  val from: Instant = start?.instantOrNull() ?: return null
  val to: Instant = end?.instantOrNull() ?: return null
  return Duration.between(from, to).toMillis().takeIf { it >= 0 }
}

private fun TimelineEntry.endedAt(): String =
  if (this is TimelineEntry.Message) item.updatedAt else createdAt

/** `deriveTurnFolds`, MessagesTimeline.logic.ts:954. */
private fun deriveTurnFolds(
  entries: List<TimelineEntry>,
  terminalIds: Set<String>,
  latestRun: Run?,
  unfoldedRunIds: Set<String>,
): Map<String, TurnFold> {
  val interrupted: Set<String> = entries
    .filter { it is TimelineEntry.Event && it.item.type.startsWith("run_interrupt_") }
    .mapNotNull { it.runId }
    .toSet()
  val promptIndexByRunId: MutableMap<String, Int> = mutableMapOf()
  entries.forEachIndexed { index, entry ->
    val runId: String? = entry.runId
    if (entry.startsResponse() && runId != null) promptIndexByRunId.putIfAbsent(runId, index)
  }

  class Group(val startBoundary: String?, val anchorEntryId: String) {
    val entries: MutableList<TimelineEntry> = mutableListOf()
    var terminal: TimelineEntry.Message? = null
    var streaming: Boolean = false
  }

  val groups: LinkedHashMap<String, Group> = linkedMapOf()
  val runlessFailed: MutableSet<String> = mutableSetOf()
  var runlessKey: String? = null
  var responseKey: String? = null
  var pending: Pair<String, String>? = null // the prompt's time, the entry after it
  entries.forEachIndexed { index, entry ->
    if (entry.startsResponse()) {
      pending = entries.getOrNull(index + 1)?.let { next -> entry.createdAt to next.id }
      runlessKey = if (entry.runId == null) "runless:${entry.id}" else null
      responseKey = entry.runId ?: runlessKey
      return@forEachIndexed
    }
    val own: String = entry.foldRunId(runlessKey) ?: return@forEachIndexed
    val key: String? = responseKey
    val runId: String =
      if (key != null && (promptIndexByRunId[own] ?: -1) > index) key else own
    if (runId == runlessKey && entry.failedErrorItem() != null) runlessFailed += runId
    val group: Group = groups.getOrPut(runId) {
      Group(pending?.first, pending?.second ?: entry.id).also { pending = null }
    }
    group.entries += entry
    if (entry is TimelineEntry.Message) {
      if (entry.id in terminalIds) group.terminal = entry
      if (entry.streaming) group.streaming = true
    }
  }

  val folds: MutableMap<String, TurnFold> = mutableMapOf()
  for ((runId, group) in groups) {
    if (runId in unfoldedRunIds || runId in interrupted || runId in runlessFailed) continue
    if (group.streaming) continue
    val terminalIndex: Int = group.terminal
      ?.let { terminal -> group.entries.indexOfFirst { it.id == terminal.id } }
      ?: group.entries.size
    val hidden: MutableSet<String> = mutableSetOf()
    group.entries.forEachIndexed { index, entry ->
      if (entry.id == group.terminal?.id) return@forEachIndexed
      val compaction: Boolean =
        entry is TimelineEntry.Work && entry.entry.sourceActivityKind == CONTEXT_COMPACTION
      val foldableTrailing: Boolean = entry is TimelineEntry.Work &&
        entry.entry.status != ToolStatus.InProgress && !entry.entry.indicatesFailure()
      if (!compaction && index > terminalIndex && !foldableTrailing) return@forEachIndexed
      if (entry.isPersistentResourceCard()) return@forEachIndexed
      if (entry is TimelineEntry.Work && entry.entry.itemType == "notification") {
        return@forEachIndexed
      }
      hidden += entry.id
    }
    val hidesWork: Boolean = group.entries.any { entry ->
      entry.id in hidden &&
        !(entry is TimelineEntry.Work && entry.entry.sourceActivityKind == CONTEXT_COMPACTION)
    }
    if (!hidesWork) continue
    val first: TimelineEntry = group.entries.first()
    val last: TimelineEntry = group.entries.last()
    val lastEnd: String = last.endedAt()
    val terminalEnd: String? = group.terminal?.item?.updatedAt
    val end: String = listOfNotNull(terminalEnd, lastEnd)
      .maxByOrNull { it.instantOrNull() ?: Instant.MIN } ?: lastEnd
    val latest: Run? = latestRun?.takeIf { it.id == runId }
    val elapsed: Long? =
      if (latest?.startedAt != null && latest.completedAt != null) {
        elapsedMs(latest.startedAt, latest.completedAt)
      } else {
        elapsedMs(group.startBoundary ?: first.createdAt, end)
      }
    val duration: String? = elapsed?.let(::formatDuration)
    val stopped: Boolean = latest?.status == "interrupted"
    val label: String = when {
      stopped && duration != null -> "You stopped after $duration"
      stopped -> "You stopped this response"
      duration != null -> "Worked for $duration"
      else -> "Worked"
    }
    folds[group.anchorEntryId] = TurnFold(runId, group.anchorEntryId, hidden, label)
  }
  return folds
}

/** `deriveUnsettledRunId`. */
private fun unsettledRunId(latestRun: Run?, runningRunId: String?): String? {
  if (runningRunId != null) return runningRunId
  latestRun ?: return null
  val settled: Boolean = latestRun.completedAt != null &&
    latestRun.status !in setOf("running", "starting", "waiting")
  return if (settled) null else latestRun.id
}

/** Upstream's expand state, kept by the view: which folds and groups are open. */
data class TimelineExpansion(
  val runIds: Set<String> = emptySet(),
  val workGroupIds: Set<String> = emptySet(),
)

/**
 * `deriveMessagesTimelineRows`, MessagesTimeline.logic.ts:1316. [runningRun] is the run in
 * flight, if any; [checkpointFiles] finds a run's ready checkpoint files, and [revertTarget] the
 * run a prompt's "revert" goes back to.
 */
fun deriveMessagesTimelineRows(
  timelineEntries: List<TimelineEntry>,
  runs: List<Run>,
  runningRun: Run?,
  expansion: TimelineExpansion = TimelineExpansion(),
  checkpointFiles: (Run) -> List<CheckpointFileSummary>? = { null },
  revertTarget: (Run) -> Run? = { null },
  worktreeSetup: WorktreeSetupSnapshot? = null,
): List<TimelineRow> {
  val entries: List<TimelineEntry> = settleSupersededReasoning(timelineEntries)
  val isWorking: Boolean = runningRun != null
  val latestRun: Run? = runs.maxByOrNull { it.ordinal }
  val runsById: Map<String, Run> = runs.associateBy { it.id }
  val terminalIds: Set<String> = terminalAssistantMessageIds(entries)
  val unsettled: String? = unsettledRunId(latestRun, runningRun?.id)
  val failedRunIds: Set<String> = buildSet {
    if (latestRun?.status == "failed") add(latestRun.id)
    entries.forEach { entry -> entry.failedErrorItem()?.runId?.let(::add) }
  }
  val lastBoundary: Int = entries.indexOfLast { it.startsResponse() }
  val activeRunIds: Set<String> = buildSet {
    if (unsettled == null) return@buildSet
    add(unsettled)
    if (!isWorking) return@buildSet
    entries.drop(lastBoundary + 1).mapNotNull { it.runId }.forEach(::add)
  }
  val folds: Map<String, TurnFold> =
    deriveTurnFolds(entries, terminalIds, latestRun, activeRunIds + failedRunIds)
  val collapsed: Set<String> = folds.values
    .filter { it.runId !in expansion.runIds }
    .flatMap { it.hiddenEntryIds }
    .toSet()

  fun WorkLogEntry.inActiveRun(): Boolean =
    isWorking && status == ToolStatus.InProgress && runId != null && runId == unsettled

  val headerIndex: Int = if (isWorking) lastBoundary + 1 else entries.size

  // The live tail: contiguous running work at the end of the active response
  val activeTools: MutableList<TimelineEntry.Work> = mutableListOf()
  if (isWorking && unsettled != null) {
    for (index in entries.indices.reversed()) {
      if (index < headerIndex) break
      val entry: TimelineEntry = entries[index]
      if (entry !is TimelineEntry.Work || entry.entry.tone == WorkTone.Error ||
        entry.entry.sourceActivityKind == RUNTIME_ERROR ||
        entry.entry.itemType == "system_notice" || entry.entry.itemType == "notification" ||
        entry.runId !in activeRunIds ||
        entry.entry.sourceActivityKind == CONTEXT_COMPACTION ||
        entry.id in collapsed || entry.id in folds
      ) break
      activeTools += entry
    }
  }
  activeTools.reverse()
  val visibleActive: List<TimelineEntry.Work> =
    activeTools.filter { it.entry.visibleInGroup(expandedToolGroupEntry = true) }
  val anchor: TimelineEntry.Work? = activeTools.firstOrNull()
  val latestVisible: TimelineEntry.Work? = visibleActive.lastOrNull()
  val latestRunning: TimelineEntry.Work? = visibleActive.lastOrNull {
    it.entry.status == ToolStatus.InProgress ||
      (it.entry.status == null && it.entry.isToolLike())
  }
  val keepsLive: Boolean = latestRunning != null || latestVisible?.entry?.indicatesSuccess() == true
  val latestFailed: Boolean = latestRunning == null && latestVisible != null &&
    latestVisible.entry.status != ToolStatus.Declined && latestVisible.entry.indicatesFailure()
  val activeWorkRows: List<TimelineRow> =
    if (anchor != null && latestVisible != null && !latestFailed) {
      val groupId: String = workGroupId(anchor.id)
      val expanded: Boolean = groupId in expansion.workGroupIds
      val live = TimelineRow.WorkLive(
        id = if (keepsLive) "live-activity-row" else "work-live:${anchor.id}",
        entry = (latestRunning ?: latestVisible).entry,
        groupId = groupId,
        expanded = expanded,
        active = keepsLive,
      )
      val details: TimelineRow.Work? = if (expanded) {
        expandedWorkGroupRow(groupId, visibleActive.map { it.entry })
      } else null
      listOfNotNull(live, details)
    } else emptyList()
  val activeIds: Set<String> =
    if (activeWorkRows.isNotEmpty() || latestFailed) activeTools.map { it.id }.toSet()
    else emptySet()

  val rows: MutableList<TimelineRow> = mutableListOf()
  var hasActivityRow = false
  var hasActiveCompaction = false
  var index = 0
  while (index < entries.size) {
    val entry: TimelineEntry = entries[index]
    if (isWorking && index == headerIndex) rows += TimelineRow.Working(runningRun?.workStart())
    if (entry.id == latestVisible?.id && activeWorkRows.isNotEmpty()) {
      rows += activeWorkRows
      hasActivityRow = hasActivityRow || keepsLive
    }
    index += 1
    if (entry is TimelineEntry.Event && entry.item.type == "run_interrupt_request") continue
    folds[entry.id]?.let { fold ->
      rows += TimelineRow.TurnFold(fold.runId, fold.label, fold.runId in expansion.runIds)
    }
    if (entry.id in collapsed || entry.id in activeIds) continue

    when (entry) {
      is TimelineEntry.Work -> {
        val work: WorkLogEntry = entry.entry
        if (work.sourceActivityKind == CONTEXT_COMPACTION) {
          val active: Boolean = work.inActiveRun()
          hasActiveCompaction = hasActiveCompaction || active
          rows += TimelineRow.ContextCompaction(entry.id, work.label, active)
          continue
        }
        if ((work.runId != null && work.runId in failedRunIds) ||
          (work.itemType == "error" && work.status == ToolStatus.Failed) ||
          work.standalone()
        ) {
          rows += TimelineRow.Work(entry.id, listOf(work))
          continue
        }
        val grouped: MutableList<WorkLogEntry> = mutableListOf(work)
        while (index < entries.size) {
          val next: TimelineEntry.Work = entries[index] as? TimelineEntry.Work ?: break
          if (next.entry.sourceActivityKind == CONTEXT_COMPACTION || next.entry.standalone() ||
            next.id in activeIds || next.id in collapsed || next.id in folds ||
            next.runId != work.runId
          ) break
          grouped += next.entry
          index += 1
        }
        val visible: List<WorkLogEntry> = grouped.filter { it.visibleInGroup(it.inActiveRun()) }
        if (visible.isEmpty()) continue
        val groupId: String = workGroupId(entry.id)
        val expanded: Boolean = groupId in expansion.workGroupIds
        val running: List<WorkLogEntry> = visible.filter { it.inActiveRun() }
        val single: WorkLogEntry? = visible.singleOrNull()
        when {
          running.isNotEmpty() -> {
            rows += TimelineRow.WorkLive(
              id = "work-live:${entry.id}",
              entry = running.last(),
              groupId = groupId,
              expanded = expanded,
              active = true,
            )
            hasActivityRow = true
            if (expanded) rows += expandedWorkGroupRow(groupId, visible)
          }
          single != null && single.isToolLike() -> rows += TimelineRow.Work(
            id = entry.id,
            entries = visible,
            displayLabel = if (single.action() == ToolAction.Edit) {
              summarizeToolGroup(visible).summary
            } else {
              single.singleToolCallLabel()
            },
          )
          else -> {
            val summary: String = when {
              single != null && single.isToolLike() -> single.singleToolCallLabel()
              single != null -> single.label
              else -> summarizeToolGroup(visible).summary
            }
            rows += TimelineRow.WorkToggle(
              id = "work-toggle:${entry.id}",
              runId = work.runId,
              groupId = groupId,
              summary = summary,
              kind = toolGroupSummaryKind(visible),
              hasFailure = visible.lastOrNull { it.isToolLike() }?.indicatesFailure() == true,
              expanded = expanded,
            )
            if (expanded) rows += expandedWorkGroupRow(groupId, visible)
          }
        }
      }

      is TimelineEntry.Message -> {
        val run: Run? = entry.runId?.let(runsById::get)
        val active: Boolean = entry.runId in activeRunIds
        val terminal: Boolean = !entry.user && entry.id in terminalIds
        rows += TimelineRow.Message(
          entry = entry,
          showMeta = terminal && !active,
          changedFiles = if (terminal && run != null) {
            checkpointFiles(run)?.takeIf { it.isNotEmpty() }?.let { ChangedFiles(run, it) }
          } else null,
          revertTo = if (entry.user && run != null &&
            entry.inputIntent in setOf("turn_start", "queued_turn")
          ) revertTarget(run) else null,
        )
      }

      is TimelineEntry.Plan -> rows += TimelineRow.Plan(entry.item)
      is TimelineEntry.Event -> rows += TimelineRow.Event(entry.item)
    }
  }
  if (isWorking && headerIndex >= entries.size) rows += TimelineRow.Working(runningRun?.workStart())
  if (isWorking && !hasActivityRow && !hasActiveCompaction) rows += TimelineRow.Thinking
  placeWorktreeSetup(rows, worktreeSetup, latestRun)
  return attachTrailingToolGroupsToAssistant(rows)
}

/**
 * MessagesTimeline.logic.ts:1818: the setup card goes after the working row while the setup
 * owns it, else right after the first prompt (or last, without one). Once the agent has the
 * worktree and the turn runs, the card is embedded; a running setup then isn't shown at all.
 */
private fun placeWorktreeSetup(
  rows: MutableList<TimelineRow>,
  setup: WorktreeSetupSnapshot?,
  latestRun: Run?,
) {
  if (setup == null) return
  val handedOff: Boolean = worktreeSetupAgentStarted(setup) && latestRun?.startedAt != null
  val ownsWorkingSlot: Boolean = !handedOff && (setup.phase == "running" || setup.phase == "done")
  if (handedOff && setup.phase == "running") return
  // Nothing thinks before the agent has the worktree
  if (ownsWorkingSlot) rows.remove(TimelineRow.Thinking)
  val setupRow = TimelineRow.WorktreeSetup(setup, embedded = handedOff)
  val firstUserRowIndex: Int =
    rows.indexOfFirst { it is TimelineRow.Message && it.entry.user }
  val workingRowIndex: Int =
    if (ownsWorkingSlot) rows.indexOfFirst { it is TimelineRow.Working } else -1
  if (workingRowIndex >= 0) {
    rows.add(workingRowIndex + 1, setupRow)
    return
  }
  val insertAt: Int = if (firstUserRowIndex >= 0) firstUserRowIndex + 1 else rows.size
  if (ownsWorkingSlot) {
    rows.addAll(insertAt, listOf(TimelineRow.Working(setup.startedAt), setupRow))
  } else {
    rows.add(insertAt, setupRow)
  }
}

/** `expandedWorkGroupRow`: an open group's entries, one line each. */
private fun expandedWorkGroupRow(groupId: String, entries: List<WorkLogEntry>): TimelineRow.Work =
  TimelineRow.Work("$groupId:details", entries, expandedGroup = true)

/** Upstream's live work start: `startedAt ?? requestedAt`. */
private fun Run.workStart(): String = startedAt ?: requestedAt

/** Entries that never group: failures, notices and notifications. */
private fun WorkLogEntry.standalone(): Boolean = tone == WorkTone.Error ||
  sourceActivityKind == RUNTIME_ERROR || itemType == "system_notice" ||
  itemType == "notification"

/** `settleSupersededReasoning`: only the last entry can still be thinking. */
private fun settleSupersededReasoning(entries: List<TimelineEntry>): List<TimelineEntry> =
  entries.mapIndexed { index, entry ->
    val work: WorkLogEntry? = (entry as? TimelineEntry.Work)?.entry
    if (work?.itemType == "reasoning" && work.status == ToolStatus.InProgress &&
      index != entries.lastIndex
    ) TimelineEntry.Work(work.copy(status = ToolStatus.Completed)) else entry
  }

/**
 * `attachTrailingToolGroupsToAssistant`, MessagesTimeline.logic.ts:1141: when a settled reply is
 * followed by tool rows of its own run, its meta moves under the last of them.
 */
private fun attachTrailingToolGroupsToAssistant(rows: List<TimelineRow>): List<TimelineRow> {
  val result: MutableList<TimelineRow> = rows.toMutableList()
  var index = 0
  while (index < result.size) {
    val row: TimelineRow = result[index]
    if (row !is TimelineRow.Message || row.entry.user || !row.showMeta ||
      row.entry.runId == null
    ) {
      index += 1
      continue
    }
    var last: Int = index
    var cursor: Int = index + 1
    while (cursor < result.size) {
      val next: TimelineRow = result[cursor]
      val runId: String? = row.entry.runId
      val trailing: Boolean = (next is TimelineRow.WorkToggle && next.runId == runId) ||
        (next is TimelineRow.Work && !next.expandedGroup && next.entries.all { work ->
          work.runId == runId && (work.isToolLike() || work.item is TurnItem.Error)
        })
      if (!trailing) break
      last = cursor
      cursor += 1
    }
    if (last != index) {
      result[index] = row.copy(showMeta = false)
      result.add(last + 1, TimelineRow.AssistantMeta(row.entry))
      index = last + 2
    } else {
      index += 1
    }
  }
  return result
}

private const val MAX_COLLAPSED_USER_MESSAGE_LINES = 8
private const val MAX_COLLAPSED_USER_MESSAGE_LENGTH = 600
private val MARKDOWN_LINK = Regex("""!?\[([^\]\n]*)\]\((?:<[^>\n]*>|[^\s)]*)\)""")

/** `shouldCollapseUserMessage`, MessagesTimeline.logic.ts:2259. */
fun shouldCollapseUserMessage(text: String): Boolean {
  val visible: String = MARKDOWN_LINK.replace(text) { it.groupValues[1] }
  if (visible.isBlank()) return false
  return visible.length > MAX_COLLAPSED_USER_MESSAGE_LENGTH ||
    visible.lines().size > MAX_COLLAPSED_USER_MESSAGE_LINES
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * `formatDayAwareTimestamp`, timestampFormat.ts:156: the time today, `yesterday at` the time,
 * else the numeric date (with the year when it isn't this one) and the time.
 */
fun formatDayAwareTimestamp(
  at: Instant,
  now: Instant,
  zone: ZoneId = ZoneId.systemDefault(),
): String {
  val then: ZonedDateTime = at.atZone(zone)
  val today: LocalDate = now.atZone(zone).toLocalDate()
  val time: String = TIME.format(then)
  val day: LocalDate = then.toLocalDate()
  return when {
    day == today -> time
    day == today.minusDays(1) -> "yesterday at $time"
    day.year == today.year -> "${day.monthValue}/${day.dayOfMonth} $time"
    else -> "${day.monthValue}/${day.dayOfMonth}/${day.year} $time"
  }
}
