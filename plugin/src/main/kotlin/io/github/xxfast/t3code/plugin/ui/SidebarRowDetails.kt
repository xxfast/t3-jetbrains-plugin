package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.TerminalMetadataStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalSummary

// What a Sidebar row shows besides its three lines: `ThreadTerminalStatusButton`
// (ThreadStatusIndicators.tsx:728) at the pinned tag. Upstream's `SidebarThreadTooltip` hover card
// is left out on purpose: IntelliJ doesn't put hover cards on list rows, and the ChatView already
// shows all of it. Upstream's diff stat reads `latestRunDiff`, which returns null at this tag.

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

/** The row's terminal button, ThreadStatusIndicators.tsx:741, with `terminalProcessLabel`. */
fun terminalButtonLabel(count: Int): String =
  "$count terminal ${if (count == 1) "process" else "processes"} running. Open terminal"

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
