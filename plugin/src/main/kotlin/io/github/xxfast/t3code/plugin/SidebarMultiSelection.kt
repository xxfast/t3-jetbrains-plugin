package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ui.EMPTY_SELECTION
import io.github.xxfast.t3code.plugin.ui.ThreadSelectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Sidebar's multi-select, upstream's `useThreadSelectionStore` (threadSelectionStore.ts).
 * Not [ThreadSelection], the one open thread. Here so the bulk actions can clear it once
 * they've run, as upstream's menu does. Not persisted.
 */
@Service(Service.Level.PROJECT)
class SidebarMultiSelection {
  private val state: MutableStateFlow<ThreadSelectionState> = MutableStateFlow(EMPTY_SELECTION)
  val selection: StateFlow<ThreadSelectionState> = state.asStateFlow()

  fun change(selection: ThreadSelectionState) {
    state.value = selection
  }

  fun clear() {
    state.value = EMPTY_SELECTION
  }

  fun remove(threadIds: Collection<String>) {
    state.update { it.removeFromSelection(threadIds) }
  }

  companion object {
    fun getInstance(project: Project): SidebarMultiSelection = project.service()
  }
}
