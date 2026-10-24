package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.xxfast.t3code.plugin.ui.DraftThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * New threads nobody has sent to yet, upstream's `draftThreadsByThreadKey` in
 * composerDraftStore.ts, keyed by thread id. App level like that store, so a draft follows
 * its tab into any IDE project. Kept in memory: a thread tab doesn't outlive the IDE either.
 */
@Service(Service.Level.APP)
class DraftThreads {
  private val _drafts: MutableStateFlow<Map<String, DraftThread>> = MutableStateFlow(emptyMap())

  val drafts: StateFlow<Map<String, DraftThread>> = _drafts

  /** [threadId]'s draft while it is one. */
  fun draft(threadId: String): Flow<DraftThread?> =
    _drafts.map { it[threadId] }.distinctUntilChanged()

  /** [projectId]'s draft, upstream's `getDraftSessionByLogicalProjectKey`. */
  fun forProject(projectId: String): DraftThread? =
    _drafts.value.values.find { it.projectId == projectId }

  /** Makes [draft] its project's draft, replacing the one it had. */
  fun put(draft: DraftThread) {
    _drafts.update { all ->
      all.filterValues { it.projectId != draft.projectId } + (draft.threadId to draft)
    }
  }

  fun update(threadId: String, change: (DraftThread) -> DraftThread) {
    _drafts.update { all -> all[threadId]?.let { all + (threadId to change(it)) } ?: all }
  }

  /** The server has [threadId] now, upstream's `markPromotedDraftThreadByRef`. */
  fun promoted(threadId: String) {
    _drafts.update { all -> all - threadId }
  }

  companion object {
    fun get(): DraftThreads = service()
  }
}
