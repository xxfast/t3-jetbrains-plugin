package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import io.github.xxfast.t3code.protocol.generated.TerminalMetadataStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalSummary

// What a Sidebar row shows besides its three lines, Sidebar.tsx's `SidebarThreadTooltip` (929 to
// 1066) and `ThreadTerminalStatusButton` (ThreadStatusIndicators.tsx:728) at the pinned tag.
// Upstream's diff stat reads `latestRunDiff`, which returns null at this tag, so there's none.

/**
 * The server's terminals after [event], sorted by id the way `terminalMetadataIndex`
 * (apps/web/src/state/terminalSessions.ts:68) sorts them, numbers by value.
 */
fun applyTerminalMetadata(
  current: List<TerminalSummary>,
  event: TerminalMetadataStreamEvent,
): List<TerminalSummary> {
  val next: List<TerminalSummary> = when (event) {
    is TerminalMetadataStreamEvent.Snapshot -> event.terminals
    is TerminalMetadataStreamEvent.Upsert -> current.filterNot { it.same(event.terminal) } +
      event.terminal
    is TerminalMetadataStreamEvent.Remove -> current.filterNot { summary ->
      summary.threadId == event.threadId && summary.terminalId == event.terminalId
    }
    is TerminalMetadataStreamEvent.Unknown -> current
  }
  return next.sortedWith(compareBy(NaturalOrder) { it.terminalId })
}

private fun TerminalSummary.same(other: TerminalSummary): Boolean =
  threadId == other.threadId && terminalId == other.terminalId

/** `selectRunningSubprocessTerminalIds` (client-runtime terminalSession.ts:58), per thread. */
fun runningTerminals(terminals: List<TerminalSummary>): Map<String, List<String>> = terminals
  .filter { it.hasRunningSubprocess }
  .groupBy(keySelector = { it.threadId }, valueTransform = { it.terminalId })

/** `terminalProcessLabel`, Sidebar.tsx:877: the hover card's line. */
fun terminalProcessLabel(count: Int): String =
  "$count terminal ${if (count == 1) "process" else "processes"} running"

/** The row's terminal button, ThreadStatusIndicators.tsx:741. */
fun terminalButtonLabel(count: Int): String = "${terminalProcessLabel(count)}. Open terminal"

/** `resolveLocalCheckoutBranchMismatch`, BranchToolbar.logic.ts:234. */
data class BranchMismatch(val threadBranch: String, val currentBranch: String)

/**
 * Only for a thread working in the project's own checkout: a worktree has its own branch. Null
 * when either branch is unknown or they agree.
 */
fun branchMismatch(thread: Thread, currentBranch: String?): BranchMismatch? {
  if (thread.worktreePath != null) return null
  val branch: String = thread.branch?.takeIf { it.isNotEmpty() } ?: return null
  if (currentBranch.isNullOrEmpty() || branch == currentBranch) return null
  return BranchMismatch(branch, currentBranch)
}

/** The instance the row's model belongs to, the current one in [providerStack]. */
fun Thread.modelInstanceId(): String = providerStack().last()

/**
 * The hover card's model line (Sidebar.tsx:1737 and 1006): the model's short name, or its slug
 * when the catalog doesn't know it, and the instance's name after it when the row wears a badge.
 */
fun hoverModelLabel(thread: Thread, providers: Map<String, ServerProvider>): String {
  val entry: ServerProvider? = providers[thread.modelInstanceId()]
  val slug: String = thread.modelSelection.slug.orEmpty()
  val model: ServerProviderModel? = entry?.models?.find { it.slug == slug }
  val label: String = model?.let(::triggerDisplayModelLabel) ?: slug
  val badged: Boolean = entry != null && showInstanceBadge(entry, providers.values)
  return if (badged) "$label · ${resolveProviderInstanceDisplayName(entry!!)}" else label
}

/**
 * `getTriggerDisplayModelLabel` (apps/web/src/components/chat/providerIconUtils.ts:54): the short
 * name when there is one, without a leading sub-provider qualifier.
 */
fun triggerDisplayModelLabel(model: ServerProviderModel): String {
  val name: String = model.shortName?.takeIf { it.isNotEmpty() } ?: model.name
  return stripLeadingQualifier(name, model.subProvider)
}

/** `stripLeadingQualifier`, providerIconUtils.ts:17: "Claude Opus" under "Claude" is "Opus". */
fun stripLeadingQualifier(value: String, qualifier: String?): String {
  val trimmed: String = qualifier?.trim()?.takeIf { it.isNotEmpty() } ?: return value
  val pattern = Regex("^${Regex.escape(trimmed)}(?:\\s*[.:/-]\\s*|\\s+)", RegexOption.IGNORE_CASE)
  return value.replace(pattern, "").trim().ifEmpty { value }
}

/** "Handed off from", Sidebar.tsx:961: every earlier instance, by name where the catalog has it. */
fun handedOffFrom(thread: Thread, providers: Map<String, ServerProvider>): List<String> {
  val current: String = thread.modelInstanceId()
  return thread.providerInstanceHistory.orEmpty()
    .filter { it != current }
    .map { id -> providers[id]?.let(::resolveProviderInstanceDisplayName) ?: id }
}

/** The hover card's last line (Sidebar.tsx:1048): a usage limit warns, anything else errors. */
data class RowError(val text: String, val limited: Boolean)

fun Thread.rowError(): RowError? {
  if (lastError == null) return null
  val limited: Boolean = lastErrorClass == "usage_limit"
  return RowError(if (limited) "Usage limit reached" else "Error occurred", limited)
}

/**
 * The glyph `EnvironmentMachineIcon` draws for [machine], its `ICON_BY_KIND`. Linux, Mac mini
 * and Mac Studio are upstream's own drawings, copied into `icons/lucide` alongside.
 */
fun machineGlyph(machine: String): String = when (machine) {
  "cloud", "linux", "laptop", "mac-mini", "mac-studio" -> machine
  "desktop" -> "monitor"
  else -> "server"
}

/** `Intl.Collator(undefined, { numeric: true })`: digit runs compare by value. */
private object NaturalOrder : Comparator<String> {
  private val parts = Regex("\\d+|\\D+")

  override fun compare(a: String, b: String): Int {
    val left: List<String> = parts.findAll(a).map { it.value }.toList()
    val right: List<String> = parts.findAll(b).map { it.value }.toList()
    for (index in 0 until minOf(left.size, right.size)) {
      val l: String = left[index]
      val r: String = right[index]
      val numbers: Boolean = l[0].isDigit() && r[0].isDigit()
      val order: Int = if (numbers) l.toBigInteger().compareTo(r.toBigInteger()) else l.compareTo(r)
      if (order != 0) return order
    }
    return left.size.compareTo(right.size)
  }
}
