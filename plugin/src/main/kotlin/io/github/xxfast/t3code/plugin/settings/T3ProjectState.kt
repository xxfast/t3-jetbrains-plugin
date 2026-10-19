package io.github.xxfast.t3code.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Per IDE project, in the workspace file so it's never committed: the Sidebar's filter. */
@Service(Service.Level.PROJECT)
@State(name = "T3CodeProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class T3ProjectState : SimplePersistentStateComponent<T3ProjectState.State>(State()) {
  class State : BaseState() {
    var chosen: Boolean by property(false)
    var projectIds: MutableList<String> by list()
  }

  /**
   * The T3 project ids the user picked: `null` when they haven't (match automatically), empty
   * for an explicit "All projects".
   */
  var filter: List<String>?
    get() = if (state.chosen) state.projectIds.toList() else null
    set(value) {
      state.chosen = value != null
      state.projectIds = value.orEmpty().toMutableList()
    }

  companion object {
    fun getInstance(project: Project): T3ProjectState = project.service()
  }
}
