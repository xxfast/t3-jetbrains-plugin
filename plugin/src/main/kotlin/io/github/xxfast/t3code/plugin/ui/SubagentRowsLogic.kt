package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Subagent
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import java.time.format.DateTimeParseException

/*
 * The timeline's subagent rows, drawn by SubagentRows.kt: `SubagentTimelineLink`
 * (apps/web/src/components/chat/V2LifecycleRow.tsx) for one, `V2SubagentGroup`
 * (TimelineV2EventRow.tsx) for a run of them. Display rules from
 * packages/client-runtime/src/state/subagentDisplay.ts.
 */

/** A subagent status' dot, `dotClass` in `STATUS_VISUALS`. */
enum class SubagentStatusTone { Info, Idle, Success, Destructive, Stopped }

data class SubagentStatusVisual(val tone: SubagentStatusTone, val label: String)

/** `STATUS_VISUALS`, V2LifecycleRow.tsx:214. A status the tag doesn't know reads as itself. */
fun subagentStatusVisual(status: String): SubagentStatusVisual = when (status) {
  "pending" -> SubagentStatusVisual(SubagentStatusTone.Info, "Queued")
  "running" -> SubagentStatusVisual(SubagentStatusTone.Info, "Running")
  "waiting" -> SubagentStatusVisual(SubagentStatusTone.Info, "Waiting")
  "idle" -> SubagentStatusVisual(SubagentStatusTone.Idle, "Idle · resumable")
  "completed" -> SubagentStatusVisual(SubagentStatusTone.Success, "Completed")
  "failed" -> SubagentStatusVisual(SubagentStatusTone.Destructive, "Failed")
  "cancelled", "interrupted" -> SubagentStatusVisual(SubagentStatusTone.Stopped, "Stopped")
  else -> SubagentStatusVisual(
    SubagentStatusTone.Stopped,
    status.replace("_", " ").replaceFirstChar { it.uppercase() },
  )
}

/** `SETTLED_SUBAGENT_STATUSES`, V2LifecycleRow.tsx:233, subagentRuntime.ts' `TERMINAL_STATUSES`. */
private val SETTLED_SUBAGENT_STATUSES: Set<String> =
  setOf("completed", "failed", "cancelled", "interrupted")

/** `isTerminalSubagentStatus`, subagentRuntime.ts:86. */
fun isTerminalSubagentStatus(status: String): Boolean = status in SETTLED_SUBAGENT_STATUSES

/** `isLiveSubagentTurnItem`, packages/client-runtime/src/state/subagentRuntime.ts:94. */
fun isLiveSubagentTurnItem(item: TurnItem): Boolean =
  item is TurnItem.TurnItemSubagent && isOrchestrationV2WorkActive(item.status)

private val GENERIC_CHILD_END: Regex =
  Regex("^Child task ended with status\\b", RegexOption.IGNORE_CASE)
private val MARKDOWN_LINK: Regex = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
private val LIST_MARKER: Regex = Regex("^[ \\t]*[-*][ \\t]+", RegexOption.MULTILINE)
private val WHITESPACE: Regex = Regex("\\s+")

/** `plainDetail`, V2LifecycleRow.tsx:242: links to their text, no code ticks or bullets. */
fun plainDetail(text: String): String = text
  .replace(MARKDOWN_LINK, "$1")
  .replace("`", "")
  .replace(LIST_MARKER, "")
  .replace(WHITESPACE, " ")
  .trim()

/**
 * `SubagentTimelineLink`'s detail line: a settled subagent's result, else its progress, the other
 * way round while it works. The server's "Child task ended with status..." says nothing the status
 * doesn't, so it's dropped.
 */
fun subagentTimelineDetail(status: String, result: String?, progress: String?): String? {
  val trimmedResult: String? = result?.trim()?.ifEmpty { null }
  val trimmedProgress: String? = progress?.trim()?.ifEmpty { null }
  val raw: String = (
    if (status in SETTLED_SUBAGENT_STATUSES) trimmedResult ?: trimmedProgress
    else trimmedProgress ?: trimmedResult
    ) ?: return null
  if (GENERIC_CHILD_END.containsMatchIn(raw)) return null
  return plainDetail(raw).ifEmpty { null }
}

/**
 * What `SubagentTimelineLink` draws for one `subagent` item. The parent's
 * `projection.subagents` entry ([live]) wins for the status, result, progress and timing; the
 * item has the rest.
 */
data class SubagentTimelineLinkState(
  val subagentId: String,
  val providerInstanceId: String,
  val driver: String,
  val title: String,
  val status: String,
  val statusLabel: String,
  val detail: String?,
  val result: String?,
  val progress: String?,
  val model: String?, // only the projection reports one
  val startedAt: String?,
  val completedAt: String?,
  val threadId: String?, // the child thread a click opens, when there's one
) {
  val failed: Boolean get() = status == "failed"

  /** The label next to the title, only when the detail line took the label's place. */
  val showsInlineStatus: Boolean get() = detail != null && status != "completed"

  /** `SubagentElapsed`, `AgentElapsed` without `compact`. */
  fun elapsed(nowMs: Long): String? =
    deriveSubagentElapsedMs(status, startedAt, completedAt, nowMs)
      ?.let { formatElapsedSeconds(it / 1_000) }
}

/** `SubagentTimelineLink`'s props, V2LifecycleRow.tsx:398. */
fun subagentTimelineLink(
  item: TurnItem.TurnItemSubagent,
  live: Subagent?,
): SubagentTimelineLinkState {
  val status: String = live?.status ?: item.status
  val result: String? = live?.result ?: item.result
  val progress: String? = live?.progress ?: item.progress
  return SubagentTimelineLinkState(
    subagentId = item.subagentId,
    providerInstanceId = item.providerInstanceId,
    driver = item.driver,
    title = formatSubagentDisplayTitle(item.title ?: "Subagent"),
    status = status,
    statusLabel = subagentStatusVisual(status).label,
    detail = subagentTimelineDetail(status, result, progress),
    result = result,
    progress = progress,
    model = live?.model,
    startedAt = live?.startedAt ?: item.startedAt,
    completedAt = live?.completedAt ?: item.completedAt,
    threadId = item.childThreadId,
  )
}

/** `formatElapsedSeconds`, apps/web/src/components/chat/AgentElapsed.tsx:6. */
fun formatElapsedSeconds(totalSeconds: Long): String {
  val seconds: Long = maxOf(0, totalSeconds)
  val minutes: Long = seconds / 60
  if (minutes == 0L) return "${seconds}s"
  val hours: Long = minutes / 60
  if (hours == 0L) return "${minutes}m ${(seconds % 60).toString().padStart(2, '0')}s"
  return "${hours}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

/** A `V2SubagentGroup` member: its item, with the projection's status and timing. */
data class SubagentGroupMember(
  val item: TurnItem.TurnItemSubagent,
  val status: String,
  val startedAt: String?,
  val completedAt: String?,
)

/** `V2SubagentGroup`'s `agents`, TimelineV2EventRow.tsx:229. */
fun subagentGroupMembers(
  items: List<TurnItem.TurnItemSubagent>,
  live: List<Subagent>,
): List<SubagentGroupMember> = items.map { item ->
  val agent: Subagent? = live.find { it.id == item.subagentId }
  SubagentGroupMember(
    item = item,
    status = agent?.status ?: item.status,
    startedAt = agent?.startedAt ?: item.startedAt,
    completedAt = agent?.completedAt ?: item.completedAt,
  )
}

data class SubagentGroupSummary(val label: String, val active: Boolean, val failed: Boolean)

/** `subagentGroupSummary`, subagentDisplay.ts:11. */
fun subagentGroupSummary(statuses: List<String>): SubagentGroupSummary {
  val active: Boolean = statuses.any(::isOrchestrationV2WorkActive)
  val noun: String = if (statuses.size == 1) "subagent" else "subagents"
  return SubagentGroupSummary(
    label = "${if (active) "Kicked off" else "Ran"} ${statuses.size} $noun",
    active = active,
    failed = statuses.any { it == "failed" },
  )
}

/** `summarizeSubagentStatuses`, subagentDisplay.ts:24: "2 working · 1 done". */
fun summarizeSubagentStatuses(statuses: List<String>): String {
  val counts: LinkedHashMap<String, Int> =
    linkedMapOf("working" to 0, "done" to 0, "failed" to 0, "stopped" to 0, "idle" to 0)
  statuses.forEach { status ->
    val key: String = when (status) {
      "pending", "running", "waiting" -> "working"
      "completed" -> "done"
      "failed" -> "failed"
      "idle" -> "idle"
      else -> "stopped"
    }
    counts[key] = counts.getValue(key) + 1
  }
  return counts.filter { it.value > 0 }.entries.joinToString(" · ") { "${it.value} ${it.key}" }
}

data class SubagentTiming(val status: String, val startedAt: String?, val completedAt: String?)

/**
 * `subagentGroupTiming`, TimelineV2EventRow.logic.ts:98: from the first start to the last end,
 * running while any member is, and without an end while any member lacks one.
 */
fun subagentGroupTiming(members: List<SubagentGroupMember>): SubagentTiming {
  var startMs: Long? = null
  var endMs: Long? = null
  var endUnknown = false
  members.forEach { member ->
    epochMillis(member.startedAt)?.let { ms -> startMs = startMs?.let { minOf(it, ms) } ?: ms }
    val completed: Long? = epochMillis(member.completedAt)
    if (member.completedAt != null && completed != null) {
      endMs = endMs?.let { maxOf(it, completed) } ?: completed
    } else {
      endUnknown = true
    }
  }
  val live: Boolean = members.any { isOrchestrationV2WorkActive(it.status) }
  return SubagentTiming(
    status = if (live) "running" else "completed",
    startedAt = startMs?.let { Instant.ofEpochMilli(it).toString() },
    completedAt = endMs
      .takeUnless { live || endUnknown }
      ?.let { Instant.ofEpochMilli(it).toString() },
  )
}

/** `subagentDetailPreview`, subagentDisplay.ts:131: the tooltip's last line, 280 characters. */
fun subagentDetailPreview(status: String, result: String?, progress: String?): String? {
  val trimmedResult: String? = result?.trim()?.ifEmpty { null }
  val trimmedProgress: String? = progress?.trim()?.ifEmpty { null }
  val detail: String = (
    if (isTerminalSubagentStatus(status)) trimmedResult ?: trimmedProgress
    else trimmedProgress ?: trimmedResult
    ).orEmpty()
  val compact: String = detail.replace(WHITESPACE, " ")
  return if (compact.length > 280) "${compact.take(280).trimEnd()}…" else compact.ifEmpty { null }
}

/** One of the tooltip's workspace lines: "Project", "Branch", "Worktree" or "Workspace". */
data class SubagentMetadataRow(val label: String, val value: String)

data class SubagentMetadata(val modelLabel: String, val workspace: List<SubagentMetadataRow>)

/**
 * `resolveSubagentMetadata`, subagentDisplay.ts:50: the model as the catalog names it, and where
 * the child works when that isn't where its parent does.
 */
fun resolveSubagentMetadata(
  model: String?,
  provider: ServerProvider?,
  parentThread: Thread?,
  childThread: Thread?,
  parentProject: Project?,
  childProject: Project?,
): SubagentMetadata {
  val trimmed: String? = model?.trim()
  val slug: String? =
    if (provider != null) resolveSelectableModel(trimmed, provider.models) else trimmed
  val catalogModel: ServerProviderModel? = provider?.models?.find { it.slug == slug }
  val reportedLabel: String = when {
    catalogModel != null -> catalogModel.shortName?.ifEmpty { null } ?: catalogModel.name
    !trimmed.isNullOrEmpty() -> formatModelSlugName(trimmed)
    else -> "Not reported"
  }
  val qualifier: String? = catalogModel?.subProvider?.trim()?.ifEmpty { null }
  val modelLabel: String = if (qualifier != null) {
    val prefix: Regex =
      Regex("^${Regex.escape(qualifier)}(?:\\s*[.:/-]\\s*|\\s+)", RegexOption.IGNORE_CASE)
    reportedLabel.replace(prefix, "").trim().ifEmpty { reportedLabel }
  } else {
    reportedLabel
  }
  val parentWorkspace: String? = parentThread?.worktreePath ?: parentProject?.workspaceRoot
  val childWorkspace: String? = childThread?.worktreePath ?: childProject?.workspaceRoot
  val workspace: List<SubagentMetadataRow> = buildList {
    if (parentThread != null && childProject != null &&
      childProject.id != parentThread.projectId
    ) {
      add(SubagentMetadataRow("Project", childProject.title))
    }
    if (parentWorkspace != null && childWorkspace != null && parentWorkspace != childWorkspace) {
      val label: String = when {
        childThread?.branch != null -> "Branch"
        childThread?.worktreePath != null -> "Worktree"
        else -> "Workspace"
      }
      add(SubagentMetadataRow(label, childThread?.branch ?: fileBasename(childWorkspace)))
    }
  }
  return SubagentMetadata(modelLabel, workspace)
}

/**
 * `resolveSelectableModel`, packages/shared/src/model.ts:428: the slug, the name or an alias.
 * Upstream's last resort, the per-driver alias table, isn't ported.
 */
private fun resolveSelectableModel(value: String?, options: List<ServerProviderModel>): String? {
  val trimmed: String = value?.trim()?.ifEmpty { null } ?: return null
  options.find { it.slug == trimmed }?.let { return it.slug }
  options.find { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.slug }
  return options
    .find { option -> option.aliases.orEmpty().any { it.equals(trimmed, ignoreCase = true) } }
    ?.slug
}

private fun epochMillis(iso: String?): Long? = iso?.let {
  try {
    Instant.parse(it).toEpochMilli()
  } catch (_: DateTimeParseException) {
    null
  }
}
