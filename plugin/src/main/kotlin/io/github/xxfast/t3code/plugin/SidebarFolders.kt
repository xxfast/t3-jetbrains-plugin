package io.github.xxfast.t3code.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.xxfast.t3code.plugin.ui.resolveProjectExpanded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which boring sidebar folders are open, by project id, upstream's `projectExpandedById`
 * (apps/web/src/uiStateStore.ts). Kept across restarts, one for the whole IDE as
 * [SidebarShelves] is. A project with nothing stored is open.
 */
@Service(Service.Level.APP)
class SidebarFolders {
  private val properties: PropertiesComponent = PropertiesComponent.getInstance()

  private val state: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(load())
  val projectExpandedById: StateFlow<Map<String, Boolean>> = state.asStateFlow()

  /** `toggleProjectFolder`, Sidebar.tsx:571: opens a closed folder, closes an open one. */
  fun toggle(projectId: String) {
    state.update { expanded ->
      expanded + (projectId to !resolveProjectExpanded(expanded, listOf(projectId)))
    }
    properties.setList(KEY, state.value.map { (id, open) -> "$id=$open" })
  }

  private fun load(): Map<String, Boolean> = properties.getList(KEY).orEmpty()
    .mapNotNull { entry ->
      val id: String = entry.substringBeforeLast('=', missingDelimiterValue = "")
      val open: Boolean? = entry.substringAfterLast('=').toBooleanStrictOrNull()
      if (id.isEmpty() || open == null) null else id to open
    }
    .toMap()

  companion object {
    private const val KEY: String = "t3code:sidebar:project-expanded"

    fun get(): SidebarFolders = service()
  }
}
