package io.github.xxfast.t3code.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.xxfast.t3code.plugin.ui.Shelves
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which Sidebar shelves are open, kept across restarts under upstream's local storage keys
 * (Sidebar.tsx:366). One for the whole IDE, as upstream's is one per browser.
 */
@Service(Service.Level.APP)
class SidebarShelves {
  private val properties: PropertiesComponent = PropertiesComponent.getInstance()

  private val state: MutableStateFlow<Shelves> =
    MutableStateFlow(Shelves(settled = properties.getBoolean(SETTLED, false)))
  val shelves: StateFlow<Shelves> = state.asStateFlow()

  fun change(shelves: Shelves) {
    state.value = shelves
    properties.setValue(SETTLED, shelves.settled, false)
  }

  companion object {
    private const val SETTLED: String = "t3code:sidebar:settled-expanded"

    fun get(): SidebarShelves = service()
  }
}
