package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The thread list's project filter for one IDE window, by T3 project id. `null` is "All". */
@Service(Service.Level.PROJECT)
@State(name = "T3CodeProjectFilter", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectFilter : SimplePersistentStateComponent<ProjectFilter.State>(State()) {
  class State : BaseState() {
    var projectId: String? by string()
  }

  private val selection: MutableStateFlow<String?> = MutableStateFlow(null)
  val selected: StateFlow<String?> = selection.asStateFlow()

  var projectId: String?
    get() = state.projectId
    set(value) {
      state.projectId = value
      selection.value = value
    }

  override fun loadState(state: State) {
    super.loadState(state)
    selection.value = state.projectId
  }

  companion object {
    fun getInstance(project: Project): ProjectFilter = project.service()
  }
}
