package io.github.xxfast.t3code.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.xxfast.t3code.plugin.ui.COMPOSER_DRAFT_STORAGE_VERSION
import io.github.xxfast.t3code.plugin.ui.PersistedComposerDraft
import io.github.xxfast.t3code.plugin.ui.PromptStashEntry
import io.github.xxfast.t3code.plugin.ui.StickyModelSelection
import io.github.xxfast.t3code.plugin.ui.decodeDraft
import io.github.xxfast.t3code.plugin.ui.decodeStash
import io.github.xxfast.t3code.plugin.ui.decodeSticky
import io.github.xxfast.t3code.plugin.ui.encodeDraft
import io.github.xxfast.t3code.plugin.ui.encodeStash
import io.github.xxfast.t3code.plugin.ui.encodeSticky
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Composer drafts, the prompt stash and the last model picked across IDE restarts, upstream's
 * `composerDraftStore` and `promptStashStore` persisted to `localStorage`. App level because
 * upstream's store is one per origin, not per window: a thread's draft follows it into any IDE
 * project, the stash is one list and the sticky model is one pick. Drafts are keyed by
 * `composerDraftKey`, server and thread.
 *
 * Each value is the JSON of a `:protocol`-style type rather than XML-mapped beans, so a field
 * added later decodes leniently and a broken entry drops instead of failing the whole file.
 */
@Service(Service.Level.APP)
@State(
  name = "T3CodeComposerDrafts",
  storages = [Storage("t3code-drafts.xml", roamingType = RoamingType.DISABLED)],
)
class ComposerDrafts : SimplePersistentStateComponent<ComposerDrafts.State>(State()) {
  class State : BaseState() {
    var version: Int by property(COMPOSER_DRAFT_STORAGE_VERSION)
    var drafts: MutableMap<String, String> by map()
    var stash: String? by string()
    var stickyModel: String? by string()
  }

  private val _stash: MutableStateFlow<List<PromptStashEntry>> = MutableStateFlow(emptyList())

  /** The stash, newest first. */
  val stash: StateFlow<List<PromptStashEntry>> = _stash

  override fun loadState(state: State) {
    super.loadState(state)
    _stash.value = decodeStash(state.stash)
  }

  fun draft(key: String): PersistedComposerDraft? = state.drafts[key]?.let(::decodeDraft)

  /** Keeps [draft] under [key], or forgets the key for null, as upstream skips empty drafts. */
  @Synchronized
  fun put(key: String, draft: PersistedComposerDraft?) {
    // A copy, so BaseState sees the change and counts a modification
    val next: MutableMap<String, String> = LinkedHashMap(state.drafts)
    if (draft == null) next.remove(key) else next[key] = encodeDraft(draft)
    if (next == state.drafts) return
    state.drafts = next
  }

  /** The last model picked per provider, and the provider it was picked on. */
  fun stickyModel(): StickyModelSelection = decodeSticky(state.stickyModel)

  /** A model was picked in a composer: `setStickyModelSelection`. */
  @Synchronized
  fun rememberModel(selection: ModelSelection) {
    state.stickyModel = encodeSticky(stickyModel().picked(selection))
  }

  /** Traits were picked in a composer: `setProviderModelOptions` with `persistSticky`. */
  @Synchronized
  fun rememberModelOptions(selection: ModelSelection) {
    state.stickyModel = encodeSticky(stickyModel().optionsPicked(selection))
  }

  /** Replaces the stash at once, as upstream writes it immediately rather than debounced. */
  @Synchronized
  fun putStash(entries: List<PromptStashEntry>) {
    _stash.value = entries
    state.stash = if (entries.isEmpty()) null else encodeStash(entries)
  }

  companion object {
    fun get(): ComposerDrafts = service()

    /** Which server drafts belong to: the fixture directory in the harness, else the URL. */
    fun server(): String =
      System.getProperty("t3code.fixtures")?.let { "fixtures:$it" } ?: T3Settings.get().serverUrl
  }
}
