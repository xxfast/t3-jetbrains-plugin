package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.ThreadVirtualFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The thread this IDE window is looking at, and one editor tab file per thread. Not persisted. */
@Service(Service.Level.PROJECT)
class ThreadSelection {
  private val selection: MutableStateFlow<String?> = MutableStateFlow(null)
  val selected: StateFlow<String?> = selection.asStateFlow()

  // Same file for the same thread, so reopening focuses the existing tab
  private val files: MutableMap<String, ThreadVirtualFile> = ConcurrentHashMap()

  fun select(threadId: String?) {
    selection.value = threadId
  }

  fun file(threadId: String, title: String): ThreadVirtualFile =
    files.getOrPut(threadId) { ThreadVirtualFile(threadId, title) }

  companion object {
    fun getInstance(project: Project): ThreadSelection = project.service()
  }
}
