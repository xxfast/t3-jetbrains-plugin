package io.github.xxfast.t3code.plugin.review

import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.plugin.ui.activeRun
import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ThreadView

// Review comments on a diff, ported from upstream's `apps/web/src/reviewCommentContext.ts`. The
// IDE's diff viewer shows both sides whole, so the rows are upstream's `!isPartial` rows: every
// line of the file, context, then removals, then additions, in order.

/** `DiffReviewLine`. Line numbers are 1-based; content has no newline. */
data class DiffReviewLine(
  val change: Change,
  val oldLineNumber: Int?,
  val newLineNumber: Int?,
  val content: String,
)

enum class Change { CONTEXT, ADD, DELETE }

/** Upstream's `SelectionSide`: the before side is `deletions`, the after side `additions`. */
enum class SelectionSide { ADDITIONS, DELETIONS }

/** A changed block: before lines `[start1, end1)`, after lines `[start2, end2)`, zero-based. */
data class LineChange(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

/** Upstream's `SelectedLineRange`: 1-based file lines on their sides. */
data class SelectedLineRange(
  val start: Int,
  val side: SelectionSide,
  val end: Int,
  val endSide: SelectionSide = side,
)

/**
 * `buildDiffReviewLines` with expanded context: the whole file as diff rows. Unchanged lines
 * between [changes] are context, read from the after side like upstream's `pushContextGap`.
 */
fun diffReviewLines(
  before: List<String>,
  after: List<String>,
  changes: List<LineChange>,
): List<DiffReviewLine> {
  val rows: MutableList<DiffReviewLine> = mutableListOf()
  var old = 0
  var new = 0

  fun context(until: Int) {
    while (new < until && old < before.size) {
      rows += DiffReviewLine(Change.CONTEXT, old + 1, new + 1, after[new])
      old += 1
      new += 1
    }
  }

  for (change in changes.sortedBy { it.start2 }) {
    context(change.start2)
    old = change.start1
    new = change.start2
    // Clamped: an empty side has no lines, whatever the fragment says
    for (line in change.start1 until minOf(change.end1, before.size)) {
      rows += DiffReviewLine(Change.DELETE, line + 1, null, before[line])
    }
    for (line in change.start2 until minOf(change.end2, after.size)) {
      rows += DiffReviewLine(Change.ADD, null, line + 1, after[line])
    }
    old = change.end1
    new = change.end2
  }
  context(after.size)
  return rows
}

/**
 * `findDiffReviewLineIndex`: the row of [lineNumber] on [side], else on the other side, else -1.
 * Context rows sit on both sides.
 */
fun findDiffReviewLineIndex(
  rows: List<DiffReviewLine>,
  lineNumber: Int,
  side: SelectionSide,
): Int {
  fun on(selected: SelectionSide): Int = rows.indexOfFirst { row ->
    if (selected == SelectionSide.DELETIONS) row.oldLineNumber == lineNumber
    else row.newLineNumber == lineNumber
  }

  val preferred: Int = on(side)
  if (preferred >= 0) return preferred
  val other: SelectionSide =
    if (side == SelectionSide.DELETIONS) SelectionSide.ADDITIONS else SelectionSide.DELETIONS
  return on(other)
}

private fun Change.marker(): String = when (this) {
  Change.ADD -> "+"
  Change.DELETE -> "-"
  Change.CONTEXT -> " "
}

/** `getDiffRange`: where the selected rows start on one side, and how many there are. */
private fun diffRange(lines: List<DiffReviewLine>, number: (DiffReviewLine) -> Int?): String {
  val numbered: List<Int> = lines.mapNotNull(number)
  return "${numbered.firstOrNull() ?: 0},${numbered.size}"
}

/**
 * `formatDiffReviewRangeLabel`: the after line (else the before line) of the first and last
 * rows, prefixed `+` or `-` when every row is that change.
 */
fun formatDiffReviewRangeLabel(lines: List<DiffReviewLine>): String {
  val first: DiffReviewLine = lines.firstOrNull() ?: return "line"
  val last: DiffReviewLine = lines.last()
  val firstNumber: Int? = first.newLineNumber ?: first.oldLineNumber
  val lastNumber: Int? = last.newLineNumber ?: last.oldLineNumber
  if (firstNumber == null || lastNumber == null) {
    return if (lines.size == 1) "line" else "${lines.size} lines"
  }

  val firstMarker: String = first.change.marker().trim()
  val marker: String =
    if (firstMarker.isNotEmpty() && lines.all { it.change == first.change }) firstMarker else ""
  return if (firstNumber == lastNumber) "$marker$firstNumber"
  else "$marker$firstNumber to $marker$lastNumber"
}

/**
 * `buildDiffReviewComment`: the rows [range] covers, a hunk header and the rows with their
 * markers as the `diff`, fenced as `diff`. Indices are zero-based rows. Upstream also keeps the
 * `selection` to restore the range in its own diff view; it isn't sent, so it isn't kept here.
 */
fun buildDiffReviewComment(
  id: String,
  sectionId: String,
  sectionTitle: String,
  filePath: String,
  rows: List<DiffReviewLine>,
  range: SelectedLineRange,
  text: String,
): ReviewCommentContext? {
  val startIndex: Int = findDiffReviewLineIndex(rows, range.start, range.side)
  val endIndex: Int = findDiffReviewLineIndex(rows, range.end, range.endSide)
  if (startIndex < 0 || endIndex < 0) return null

  val from: Int = minOf(startIndex, endIndex)
  val to: Int = maxOf(startIndex, endIndex)
  val selected: List<DiffReviewLine> = rows.subList(from, to + 1)
  val header = "@@ -${diffRange(selected) { it.oldLineNumber }} " +
    "+${diffRange(selected) { it.newLineNumber }} @@"
  return ReviewCommentContext(
    id = id,
    sectionId = sectionId,
    sectionTitle = sectionTitle,
    filePath = filePath,
    startIndex = from,
    endIndex = to,
    rangeLabel = formatDiffReviewRangeLabel(selected),
    text = text.trim(),
    diff = (listOf(header) + selected.map { "${it.change.marker()}${it.content}" })
      .joinToString("\n"),
    fenceLanguage = "diff",
  )
}

/** A comment's section: `DiffPanel.tsx:299`, a turn's diff, else upstream's non-turn title. */
data class ReviewSection(val id: String, val title: String)

fun reviewSection(runId: String?, runOrdinal: Long?): ReviewSection =
  if (runId != null && runOrdinal != null) ReviewSection("turn:$runId", "Turn $runOrdinal")
  else ReviewSection("changes", "Changes")

/**
 * The checkpoint a turn rolls back to, as `revertThreadCheckpoint` finds it
 * (`packages/client-runtime/src/operations/commands.ts:873`): the last one captured for the
 * run's ordinal, and only when it's `ready`.
 */
fun checkpointForRun(view: ThreadView, run: Run): Checkpoint? =
  view.checkpoints.lastOrNull { it.appRunOrdinal == run.ordinal }?.takeIf { it.status == "ready" }

/** Upstream's refusal while a turn runs, `ChatView.tsx:5802`. */
const val INTERRUPT_FIRST: String = "Interrupt the current turn before reverting checkpoints."

/**
 * Why [view]'s turns can't roll back now: [operateReason] (not connected, read only), else a run
 * still going. The server doesn't guard that: `CheckpointRollbackService.ts` rolls back settled
 * runs while `restoreFiles` would snap the worktree under the running one.
 */
fun rollbackBlockReason(operateReason: String?, view: ThreadView): String? =
  operateReason ?: INTERRUPT_FIRST.takeIf { activeRun(view.runs) != null }

/**
 * What the revert on the prompt after [run] says it does. Turn N's checkpoint is the state
 * after turn N: rolling back keeps N and undoes what came after.
 */
fun rollbackTooltip(run: Run, reason: String?): String =
  reason ?: "Roll back to the state after turn ${run.ordinal}"
