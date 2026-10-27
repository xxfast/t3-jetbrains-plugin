package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ScheduledTask
import java.time.Instant
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What a scheduled task row can do, each wired by the host. */
class ScheduledTaskActions(
  val onRunNow: (ScheduledTask) -> Unit = {},
  val onEnabledChange: (ScheduledTask, Boolean) -> Unit = { _, _ -> },
)

/** What `scheduledTasks.subscribe` gave so far: null [tasks] while loading. */
class ScheduledTasksLoad(
  val tasks: List<ScheduledTask>? = null,
  val error: String? = null,
)

/**
 * The "Scheduled" tab in T3 Sidebar, upstream's `ScheduledTaskEnvironmentSection`
 * (apps/web/src/components/settings/ScheduledTasksSettings.tsx:585) for the one server: not
 * connected, the error, loading, empty, else a row per task. Read-only past Run now and the
 * toggle; [onEdit] opens wherever editing lives, null when nothing in this IDE can.
 * [blocked] is why nothing can change, null when it can. [busy] are the tasks with a call out.
 */
@Composable
fun ScheduledTasks(
  connected: Boolean,
  load: ScheduledTasksLoad,
  blocked: String?,
  busy: Set<String>,
  actions: ScheduledTaskActions,
  onEdit: (() -> Unit)?,
) {
  val now: Instant = rememberNow(60_000).value
  val tasks: List<ScheduledTask>? = load.tasks
  VerticallyScrollableContainer(
    scrollState = rememberScrollState(),
    modifier = Modifier.fillMaxSize(),
  ) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
      when {
        !connected -> StateRow(
          title = "Server disconnected",
          description = "Reconnect to view its scheduled tasks.",
        )

        load.error != null -> StateRow("Could not load scheduled tasks", load.error)
        tasks == null -> StateRow("Loading scheduled tasks…")
        tasks.isEmpty() -> StateRow("No scheduled tasks")
        else -> tasks.forEach { task ->
          key(task.id) {
            ScheduledTaskRow(
              task = task,
              line = scheduledTaskStatusLine(task, now),
              blocked = blocked,
              busy = task.id in busy,
              actions = actions,
              detailed = true,
            )
          }
        }
      }
      EditHint(onEdit)
    }
  }
}

/** Upstream's `SettingsRow` with only a title and a description. */
@Composable
private fun StateRow(title: String, description: String? = null) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
    Text(title, fontWeight = FontWeight.Medium)
    description?.let { Text(it, style = small(), color = muted()) }
  }
}

/** Editing isn't the plugin's: where it lives, and a way there when this IDE has one. */
@Composable
private fun EditHint(onEdit: (() -> Unit)?) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Edit in the T3 Code desktop app", style = small(), color = muted())
    onEdit?.let { edit -> Link("Open in web view", onClick = edit, textStyle = small()) }
  }
}

/**
 * One scheduled task, upstream's Automations row (ThreadAutomationsPanel.tsx:166): the
 * calendar clock with the last run's dot, the title over [line], Run now unless it's a webhook
 * task, then the enabled switch. [detailed] adds what the settings row has
 * (`ScheduledTaskRow`, ScheduledTasksSettings.tsx:668): the prompt, the last run's status and its
 * error. Both controls are off while [busy] or [blocked], with [blocked] as the hint.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip sits on Compose's TooltipArea
@Composable
fun ScheduledTaskRow(
  task: ScheduledTask,
  line: String,
  blocked: String?,
  busy: Boolean,
  actions: ScheduledTaskActions,
  detailed: Boolean = false,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(if (hovered) subtle(0.08f) else Color.Transparent)
      .hoverable(interactions)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TaskIcon(task.lastRunStatus)
    Tooltip(
      tooltip = {
        Column {
          Text(task.title, fontWeight = FontWeight.Medium)
          Text(line, color = muted())
        }
      },
      modifier = Modifier.weight(1f),
    ) {
      Column(Modifier.fillMaxWidth()) {
        Text(
          text = task.title,
          color = JewelTheme.globalColors.text.normal.copy(alpha = 0.85f),
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (detailed) {
          Text(
            text = task.prompt,
            color = muted(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = line,
            modifier = Modifier.weight(1f, fill = false),
            style = small(),
            color = muted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (detailed && task.lastRunStatus != "never") {
            Text(
              text = task.lastRunStatus,
              style = small(),
              color = statusColor(scheduledTaskStatusTone(task.lastRunStatus)),
            )
          }
        }
        if (detailed) {
          task.lastRunError?.let { error ->
            Text(error, style = small(), color = DESTRUCTIVE, maxLines = 2)
          }
        }
      }
    }
    val enabled: Boolean = blocked == null && !busy
    if (task.canRunNow) {
      IconActionButton(
        key = AllIconsKeys.Actions.Execute,
        contentDescription = "Run ${task.title} now",
        onClick = { actions.onRunNow(task) },
        enabled = enabled && task.lastRunStatus != "running",
        tooltip = { Text(blocked ?: "Run now") },
      )
    }
    val toggleHint: String = if (task.enabled) "Pause ${task.title}" else "Resume ${task.title}"
    Tooltip(tooltip = { Text(blocked ?: toggleHint) }) {
      Checkbox(
        checked = task.enabled,
        onCheckedChange = { on -> actions.onEnabledChange(task, on) },
        enabled = enabled,
      )
    }
  }
}

/** `CalendarClockIcon` with the status dot at its top right. */
@Composable
private fun TaskIcon(status: String) {
  Box(Modifier.size(16.dp)) {
    Glyph("calendar-clock", muted(), 16.dp)
    val panel: Color = JewelTheme.globalColors.panelBackground
    Box(
      Modifier
        .align(Alignment.TopEnd)
        .offset(x = 3.dp, y = (-3).dp)
        .size(7.dp)
        .clip(CircleShape)
        .background(statusColor(scheduledTaskStatusTone(status)))
        .border(1.dp, panel, CircleShape),
    )
  }
}

/** `bg-sky-500`, `bg-emerald-500`, `bg-destructive`, `bg-muted-foreground/40`. */
@Composable
private fun statusColor(tone: ThreadRelationshipTone): Color = when (tone) {
  ThreadRelationshipTone.Info -> Color(0xFF0EA5E9)
  ThreadRelationshipTone.Success -> Color(0xFF10B981)
  ThreadRelationshipTone.Destructive -> DESTRUCTIVE
  ThreadRelationshipTone.Muted -> muted().copy(alpha = 0.4f)
}

/** Upstream's `--destructive`, as the composer's stop button has it. */
private val DESTRUCTIVE: Color = Color(0xFFEF4444)
