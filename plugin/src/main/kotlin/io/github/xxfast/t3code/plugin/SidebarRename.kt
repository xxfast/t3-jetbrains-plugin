package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.actions.ThreadActionScope
import io.github.xxfast.t3code.plugin.actions.commitRename
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Sidebar row being renamed in place, upstream's `renamingThreadKey` (Sidebar.tsx:3157).
 * Here so "Rename thread" in the row's menu can start it.
 */
@Service(Service.Level.PROJECT)
class SidebarRename(private val project: Project) {
  private val state: MutableStateFlow<String?> = MutableStateFlow(null)
  val renaming: StateFlow<String?> = state.asStateFlow()

  fun start(threadId: String) {
    state.value = threadId
  }

  /** Ends renaming [threadId]: [typed] commits it, null cancels. */
  fun end(threadId: String, typed: String?) {
    state.compareAndSet(threadId, null)
    if (typed == null) return
    val client: T3Client = T3.get().client
    val row: Thread = client.threads.value.find { it.id == threadId } ?: return
    val scope: CoroutineScope = ThreadActionScope.getInstance(project).scope
    scope.launchReporting(project, "Couldn't rename thread") {
      commitRename(project, client, row, typed)
    }
  }

  companion object {
    fun getInstance(project: Project): SidebarRename = project.service()
  }
}
