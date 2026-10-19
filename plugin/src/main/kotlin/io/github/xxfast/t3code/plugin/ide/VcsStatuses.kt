package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project as IdeProject
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ui.VcsStatus
import io.github.xxfast.t3code.plugin.ui.VcsSummary
import io.github.xxfast.t3code.plugin.ui.applyGitStatusStreamEvent
import io.github.xxfast.t3code.plugin.ui.vcsSummary
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * The thread header's git line, one `subscribeVcsStatus` per checkout however many tabs show it.
 * A subscription lives while something collects it, plus a grace period for tab switches. `null`
 * while loading, outside a repository, or when the stream fails: the IDE's own git integration is
 * the source of truth, this is a glance.
 */
@Service(Service.Level.PROJECT)
class VcsStatuses(private val scope: CoroutineScope) {
  private val shared: ConcurrentHashMap<String, StateFlow<VcsSummary?>> = ConcurrentHashMap()

  /** The summary for [cwd], or always `null` without one. */
  fun summary(cwd: String?): StateFlow<VcsSummary?> {
    if (cwd == null) return NONE
    return shared.computeIfAbsent(cwd) {
      T3.get().client.vcsStatus(cwd)
        .runningFold(null as VcsStatus?, ::applyGitStatusStreamEvent)
        .map { status -> status?.let(::vcsSummary) }
        .catch { emit(null) }
        .stateIn(scope, SharingStarted.WhileSubscribed(GRACE_MS), null)
    }
  }

  companion object {
    private const val GRACE_MS: Long = 5_000
    private val NONE: StateFlow<VcsSummary?> = MutableStateFlow<VcsSummary?>(null).asStateFlow()

    fun getInstance(project: IdeProject): VcsStatuses = project.service()
  }
}

/** Where [thread] runs: its worktree, else its project's root. `null` when neither is known. */
fun threadCwd(thread: Thread?, projects: List<Project>): String? {
  if (thread == null) return null
  val root: String? = projects.find { it.id == thread.projectId }?.workspaceRoot
  return if (root != null) cwdOf(thread.worktreePath, root) else thread.worktreePath
}
