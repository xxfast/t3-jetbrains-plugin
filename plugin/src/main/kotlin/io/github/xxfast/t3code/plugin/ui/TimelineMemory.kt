package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * Where a thread's timeline was, upstream's `rememberTimelinePosition`
 * (timelineScrollAnchoring.ts): the first row on screen and how far into it, whether it followed
 * the end, and which turns, groups and details were open. The thread's editor tab holds it, so it
 * outlives the chat view's composition, and hands it to the IDE as its editor state: switching
 * tabs, reopening one and restarting the IDE come back to the same place, as a text editor does.
 */
class TimelineMemory {
  var index: Int = 0
  var offset: Int = 0

  /** `atEnd`: following the end, or within its band. A thread opens at its end when it is. */
  var atEnd: Boolean = true

  var expansion: TimelineExpansion by mutableStateOf(TimelineExpansion())

  /** Work entries with their details open, by id. */
  val open: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

  /** `rememberTimelinePosition`: `atEnd` is the follow or the list's own end, either one. */
  fun record(index: Int, offset: Int, following: Boolean, atEnd: Boolean) {
    this.index = index
    this.offset = offset
    this.atEnd = following || atEnd
  }

  /** The ids of the open details, for the editor state. */
  fun openIds(): Set<String> = open.filterValues { it }.keys.toSet()
}
