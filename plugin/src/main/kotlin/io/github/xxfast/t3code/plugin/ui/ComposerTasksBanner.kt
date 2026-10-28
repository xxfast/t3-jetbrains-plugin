package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Maximum number of segments shown in the progress bar. */
private const val MAX_TASK_SEGMENTS: Int = 10

/** Upstream's `text-success`, from the theme. */
private fun success(): Color =
  JBColor.namedColor("Label.successForeground", JBColor(0x208A3C, 0x5FAD65)).toComposeColor()

/**
 * Task segments progress bar for 2 to 10 steps, upstream's `TaskSegments`.
 */
@Composable
fun TaskSegments(
  steps: List<ComposerTaskStep>,
  modifier: Modifier = Modifier,
) {
  if (steps.size !in 2..MAX_TASK_SEGMENTS) return
  Row(
    modifier = modifier.width(40.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (step in steps) {
      val color: Color = when (step.status) {
        ComposerTaskStatus.COMPLETED -> success()
        ComposerTaskStatus.IN_PROGRESS -> accent()
        ComposerTaskStatus.PENDING -> muted().copy(alpha = 0.25f)
      }
      Box(
        modifier = Modifier
          .weight(1f)
          .height(3.dp)
          .clip(CircleShape)
          .background(color),
      )
    }
  }
}

/**
 * The collapsed tasks badge row above the composer, upstream's `ComposerTasksBadge`.
 */
@Composable
fun ComposerTasksBadge(
  progress: ComposerTasksProgress,
  steps: List<ComposerTaskStep>,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (progress.totalSteps <= 0) return
  val countColor: Color =
    if (progress.completedSteps >= progress.totalSteps) success() else muted()

  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .padding(vertical = 4.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(
      key = AllIconsKeys.General.TodoDefault,
      contentDescription = "Tasks",
      tint = muted(),
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = "Tasks",
      style = small(),
      color = muted(),
    )
    Text(
      text = progress.step,
      style = small().copy(fontWeight = FontWeight.Medium),
      color = JewelTheme.globalColors.text.normal.copy(alpha = 0.8f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "${progress.completedSteps}/${progress.totalSteps}",
      style = small(),
      color = countColor,
    )
    TaskSegments(steps = steps)
    Icon(
      key = if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown,
      contentDescription = if (expanded) "Collapse tasks" else "Expand tasks",
      tint = muted(),
      modifier = Modifier.size(12.dp),
    )
  }
}

/**
 * The full tasks content above the composer: the collapsed badge header, plus the scrolling
 * list of steps when [expanded], upstream's `ComposerTasksContent`.
 */
@Composable
fun ComposerTasksContent(
  progress: ComposerTasksProgress,
  steps: List<ComposerTaskStep>,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val dark: Boolean = JewelTheme.isDark
  val outline: Color =
    if (dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f)
  val shape: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = DRAWER_INSET)
      .clip(shape)
      .background(composerGlassSurface())
      .border(1.dp, outline, shape)
      .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = DRAWER_OVERLAP + 4.dp),
  ) {
    ComposerTasksBadge(
      progress = progress,
      steps = steps,
      expanded = expanded,
      onToggle = onToggle,
    )
    if (expanded) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(outline),
      )
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 160.dp)
          .verticalScroll(rememberScrollState())
          .padding(top = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        for (step in steps) {
          TaskStepRow(step = step, live = progress.live)
        }
      }
    }
  }
}

@Composable
private fun TaskStepRow(
  step: ComposerTaskStep,
  live: Boolean,
  modifier: Modifier = Modifier,
) {
  val textColor: Color = when (step.status) {
    ComposerTaskStatus.COMPLETED -> muted().copy(alpha = 0.55f)
    ComposerTaskStatus.IN_PROGRESS -> JewelTheme.globalColors.text.normal.copy(alpha = 0.9f)
    ComposerTaskStatus.PENDING -> muted().copy(alpha = 0.7f)
  }
  val durationText: String? = when {
    step.durationMs != null -> formatDuration(step.durationMs)
    step.status == ComposerTaskStatus.IN_PROGRESS && live -> "now"
    else -> null
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier.size(14.dp),
      contentAlignment = Alignment.Center,
    ) {
      when (step.status) {
        ComposerTaskStatus.COMPLETED -> {
          Icon(
            key = AllIconsKeys.Actions.Checked,
            contentDescription = "Completed",
            tint = success(),
            modifier = Modifier.size(12.dp),
          )
        }

        ComposerTaskStatus.IN_PROGRESS -> {
          Box(
            modifier = Modifier
              .size(7.dp)
              .background(accent(), CircleShape),
          )
        }

        ComposerTaskStatus.PENDING -> {
          Box(
            modifier = Modifier
              .size(7.dp)
              .border(1.dp, muted().copy(alpha = 0.4f), CircleShape),
          )
        }
      }
    }
    Text(
      text = step.step,
      style = small(),
      color = textColor,
      modifier = Modifier.weight(1f),
    )
    if (durationText != null) {
      Text(
        text = durationText,
        style = small(),
        color = muted().copy(alpha = 0.45f),
      )
    }
  }
}

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val DRAWER_INSET: Dp = 22.dp

/** `--chat-composer-attachment-overlap`, `calc(1rem+1px)`: the part behind the box. */
private val DRAWER_OVERLAP: Dp = 17.dp
