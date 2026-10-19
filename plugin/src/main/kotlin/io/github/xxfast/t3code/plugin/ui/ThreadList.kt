package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.time.Instant
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.typography

@Composable
fun ThreadList(
  projects: List<Project>,
  threads: List<Thread>,
  selectedProjectId: String?,
  onOpenThread: (String) -> Unit,
) {
  val groups: List<ThreadGroup> = groupThreads(projects, threads, selectedProjectId)
  when {
    groups.isNotEmpty() -> ThreadGroups(groups, onOpenThread)
    projects.isEmpty() -> Hint("No projects yet. Open the T3 Code desktop app to add one.")
    else -> Hint("No threads")
  }
}

@Composable
private fun ThreadGroups(groups: List<ThreadGroup>, onOpenThread: (String) -> Unit) {
  val now: Instant by rememberNow()
  val state: LazyListState = rememberLazyListState()
  VerticallyScrollableContainer(scrollState = state, modifier = Modifier.fillMaxSize()) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
      groups.forEach { group ->
        item(key = "project:${group.project.id}") {
          GroupHeader(
            text = group.project.title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        items(group.threads, key = { it.id }) { thread ->
          ThreadRow(thread, now, onClick = { onOpenThread(thread.id) })
        }
      }
    }
  }
}

@Composable
private fun ThreadRow(thread: Thread, now: Instant, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .alpha(if (thread.archived) 0.5f else 1f),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = thread.title,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    StatusBadge(thread.badge())
    Text(
      text = relativeTime(thread.updatedAt.toInstant(), now),
      color = JewelTheme.globalColors.text.info,
      style = JewelTheme.typography.small,
    )
  }
}

@Composable
internal fun StatusBadge(badge: ThreadBadge) {
  val tint: Color = when (badge) {
    ThreadBadge.Running -> JewelTheme.globalColors.borders.focused
    ThreadBadge.WaitingOnYou -> JewelTheme.globalColors.text.warning
    ThreadBadge.Idle, is ThreadBadge.Other -> JewelTheme.globalColors.text.info
    ThreadBadge.Archived -> JewelTheme.globalColors.text.disabled
  }
  Text(
    text = badge.label,
    color = tint,
    style = JewelTheme.typography.small,
    maxLines = 1,
    modifier = Modifier
      .background(tint.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

@Composable
private fun Hint(text: String) {
  Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
    Text(text, color = JewelTheme.globalColors.text.info, textAlign = TextAlign.Center)
  }
}

@Composable
private fun rememberNow(): State<Instant> = produceState(Instant.now()) {
  while (true) {
    delay(30_000)
    value = Instant.now()
  }
}
