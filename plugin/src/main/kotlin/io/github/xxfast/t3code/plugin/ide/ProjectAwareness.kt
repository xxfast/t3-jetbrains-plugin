package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.settings.T3ProjectState
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which T3 projects and thread this IDE window is about, from its content roots: the Sidebar's
 * default filter, and on open, the thread whose worktree this window is. Rules in
 * [matchProjects] and [threadForWorkspace]. Content roots are re-read when they change.
 */
@Service(Service.Level.PROJECT)
class ProjectAwareness(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  private val client: T3Client get() = T3.get().client
  private val state: T3ProjectState get() = T3ProjectState.getInstance(project)

  private val roots: MutableStateFlow<List<Path>?> = MutableStateFlow(null)
  private val chosen: MutableStateFlow<List<String>?> = MutableStateFlow(state.filter)
  private val matched: MutableStateFlow<ProjectFilter> = MutableStateFlow(ProjectFilter.All)

  /** What the Sidebar lists. */
  val filter: StateFlow<ProjectFilter> = matched.asStateFlow()

  init {
    project.messageBus.connect(this).subscribe(
      ModuleRootListener.TOPIC,
      object : ModuleRootListener {
        override fun rootsChanged(event: ModuleRootEvent) {
          scope.launch { refreshRoots() }
        }
      },
    )
    scope.launch { refreshRoots() }
    scope.launch {
      combine(roots, chosen, client.projects) { roots, chosen, projects ->
        Triple(roots, chosen, projects)
      }.collectLatest { (roots, chosen, projects) ->
        if (roots == null) return@collectLatest
        // gitCommonDir reads files, so off the read action and off the EDT
        matched.value = withContext(Dispatchers.IO) {
          matchProjects(chosen, comparable(roots), projects)
        }
      }
    }
    scope.launch { selectWorkspaceThread() }
  }

  /** The user picked [filter] in the Sidebar. It sticks for this IDE project. */
  fun choose(filter: ProjectFilter) {
    val ids: List<String> = when (filter) {
      ProjectFilter.All -> emptyList()
      is ProjectFilter.Only -> filter.ids.toList()
    }
    state.filter = ids
    chosen.value = ids
  }

  /**
   * Once per session, and only while nothing is selected: the thread whose worktree this window
   * has open. It selects, it doesn't open a tab; the Sidebar marks it, T3 Thread Details shows it.
   */
  private suspend fun selectWorkspaceThread() {
    val roots: List<Path> = this.roots.filter { it != null }.first().orEmpty()
    val threads: List<Thread> = client.threads.filter { it.isNotEmpty() }.first()
    val found: Thread = threadForWorkspace(comparable(roots), threads) ?: return
    // Checked here, after the wait and right before selecting: an editor tab restored on startup
    // selects its thread in selectNotify, and that wins over the guess
    val selection: ThreadSelection = ThreadSelection.getInstance(project)
    if (selection.selected.value == null) selection.select(found.id)
  }

  /** A remote server's paths aren't this disk's, so there's nothing to match them against. */
  private fun comparable(roots: List<Path>): List<Path> =
    if (T3.serverIsLocal()) roots else emptyList()

  private suspend fun refreshRoots() {
    roots.value = readAction { localRoots(project) }
  }

  override fun dispose() = Unit

  companion object {
    fun getInstance(project: Project): ProjectAwareness = project.service()
  }
}
