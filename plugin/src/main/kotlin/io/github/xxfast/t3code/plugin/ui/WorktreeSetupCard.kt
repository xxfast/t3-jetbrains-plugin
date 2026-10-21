package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupStage
import java.time.Instant
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/**
 * Upstream's `WorktreeSetupCard` (`apps/web/src/components/chat/WorktreeSetupCard.tsx`): the
 * stages of a worktree being prepared, one work-log line each, the setup script's output tail
 * under its stage, and Details, Open terminal and Cancel underneath. [embedded] is the card
 * once the agent has the worktree: one summary line, no header. [onCancel] is null once the
 * setup can't be cancelled; [onOpenTerminal] gets the setup script's terminal id.
 */
@Composable
fun WorktreeSetupCard(
  snapshot: WorktreeSetupSnapshot,
  embedded: Boolean,
  now: Instant,
  onCancel: (() -> Unit)?,
  onOpenTerminal: ((terminalId: String) -> Unit)?,
) {
  val running: Boolean = snapshot.phase == "running"
  var detailsOpen: Boolean by remember { mutableStateOf(false) }
  val totalElapsed: Long? = worktreeSetupElapsedMs(snapshot, now)
  val setupStage: WorktreeSetupStage? = snapshot.stages.find { it.id == "setup-script" }
  val terminalId: String? = snapshot.setupScript?.terminalId
  val showTerminal: Boolean =
    onOpenTerminal != null && terminalId != null && setupStage != null &&
      setupStage.status != "pending"
  val collapsed: Boolean = embedded && !running
  val showHeader: Boolean = !embedded && !running && snapshot.phase != "done"
  val showTail: Boolean = worktreeSetupShowsTail(snapshot)

  Column(Modifier.fillMaxWidth()) {
    if (showHeader) SetupHeaderRow(snapshot, totalElapsed)
    if (collapsed) {
      CollapsedSummaryRow(snapshot, totalElapsed)
    } else {
      Column(Modifier.padding(top = if (showHeader) 6.dp else 0.dp)) {
        snapshot.stages.forEach { stage ->
          StageRow(stage, now, snapshot.setupScript?.name)
          if (stage.id == "setup-script" && showTail) {
            OutputTail(stage.tail, failed = stage.status == "failed")
          }
        }
      }
    }
    snapshot.error?.takeIf { snapshot.phase == "failed" }?.let { error ->
      Text(
        text = error,
        style = small(),
        color = muted(),
        modifier = Modifier.padding(start = 32.dp, top = 4.dp),
      )
    }
    if (detailsOpen) SetupDetails(snapshot)
    Row(
      modifier = Modifier.padding(start = 24.dp, top = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Link(if (detailsOpen) "Hide details" else "Details", onClick = { detailsOpen = !detailsOpen })
      if (showTerminal) Link("Open terminal", onClick = { onOpenTerminal?.invoke(terminalId!!) })
      if (onCancel != null && running) Link("Cancel", onClick = onCancel)
    }
  }
}

/** `SetupHeaderRow`: the phase's label over a hairline, red when it failed. */
@Composable
private fun SetupHeaderRow(snapshot: WorktreeSetupSnapshot, totalElapsed: Long?) {
  val failed: Boolean = snapshot.phase == "failed"
  val finishedWithFailedStage: Boolean =
    snapshot.phase == "done" && snapshot.stages.any { it.status == "failed" }
  val tone: Color = when {
    failed -> JewelTheme.globalColors.text.error
    finishedWithFailedStage -> JewelTheme.globalColors.text.warning
    else -> muted()
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = worktreeSetupHeaderLabel(snapshot),
      color = tone,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (totalElapsed != null) Text(formatDuration(totalElapsed), style = small(), color = muted())
  }
}

/** `StageRow`: the stage's icon, label, detail or percent, and elapsed time. */
@Composable
private fun StageRow(stage: WorktreeSetupStage, now: Instant, scriptName: String?) {
  val elapsed: Long? = worktreeSetupStageElapsedMs(stage, now)
  val trailing: String? = worktreeSetupStageTrailing(stage)
  val showElapsed: Boolean =
    elapsed != null && stage.status != "skipped" && stage.status != "pending"
  SetupLine(
    status = stage.status,
    label = worktreeSetupStageLabel(stage, scriptName),
    trailing = listOfNotNull(trailing, elapsed?.takeIf { showElapsed }?.let(::formatDuration)),
  )
}

/** `CollapsedSummaryRow`: the whole setup as one line. */
@Composable
private fun CollapsedSummaryRow(snapshot: WorktreeSetupSnapshot, totalElapsed: Long?) {
  SetupLine(
    status = worktreeSetupSummaryStatus(snapshot),
    label = worktreeSetupHeaderLabel(snapshot),
    trailing = listOfNotNull(totalElapsed?.let(::formatDuration)),
  )
}

/** A `WorkLogRow` with a stage's icon and tone (`StageIcon`, `stageRowClassName`). */
@Composable
private fun SetupLine(status: String, label: String, trailing: List<String>) {
  val color: Color = when (status) {
    "failed" -> JewelTheme.globalColors.text.error
    "warning" -> JewelTheme.globalColors.text.warning
    else -> muted()
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 24.dp)
      .alpha(if (status == "pending") 0.4f else 1f)
      .padding(horizontal = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
      when (status) {
        "running" -> CircularProgressIndicator(Modifier.size(14.dp))
        else -> Glyph(stageIcon(status), color, 16.dp)
      }
    }
    Text(
      text = label,
      color = color,
      fontWeight = if (status == "failed" || status == "warning") FontWeight.Medium else null,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    trailing.forEach { text ->
      Text(text, style = small(), color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** `StageIcon`: a lucide glyph per status. Running draws a spinner instead. */
private fun stageIcon(status: String): String = when (status) {
  "done" -> "check"
  "failed" -> "x"
  "warning" -> "circle-alert"
  "skipped" -> "minus"
  else -> "circle"
}

/** `OutputTail`: the last [WORKTREE_SETUP_TAIL_LINES] of the setup script, monospace. */
@Composable
private fun OutputTail(lines: List<String>, failed: Boolean) {
  val border: Color =
    if (failed) JewelTheme.globalColors.text.error.copy(alpha = 0.2f)
    else JewelTheme.globalColors.borders.normal
  val tail: List<String> = lines.takeLast(WORKTREE_SETUP_TAIL_LINES)
  val rows: List<String> = List(WORKTREE_SETUP_TAIL_LINES - tail.size) { "" } + tail
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 32.dp, bottom = 4.dp)
      .border(1.dp, border, RoundedCornerShape(6.dp))
      .background(subtle(0.04f), RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    rows.forEach { line ->
      Text(
        text = line.ifEmpty { " " },
        style = small().copy(fontFamily = FontFamily.Monospace),
        color = if (failed) JewelTheme.globalColors.text.error else muted(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** `SetupDetails`: the branch, base, path and setup command, when there are any. */
@Composable
private fun SetupDetails(snapshot: WorktreeSetupSnapshot) {
  val rows: List<Pair<String, String>> = listOfNotNull(
    snapshot.branch?.let { "Branch" to it },
    snapshot.baseRef?.let { "Base" to it },
    snapshot.worktreePath?.let { "Path" to it },
    snapshot.setupScript?.let { "Setup" to it.command },
  )
  Column(
    modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 6.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    rows.forEach { (label, value) ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = label,
          style = small(),
          color = JewelTheme.globalColors.text.normal.copy(alpha = 0.8f),
          modifier = Modifier.widthIn(min = 44.dp),
        )
        Text(
          text = value,
          style = small().copy(fontFamily = FontFamily.Monospace),
          color = muted(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
