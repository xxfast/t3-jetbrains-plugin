package io.github.xxfast.t3code.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the server is. Nothing secret lives here; the credential is in [T3Credentials]. */
@Service(Service.Level.APP)
@State(
  name = "T3CodeSettings",
  storages = [Storage("t3code.xml", roamingType = RoamingType.DISABLED)],
)
class T3Settings : SimplePersistentStateComponent<T3Settings.State>(State()) {
  class State : BaseState() {
    var serverUrl: String? by string(DEFAULT_SERVER_URL)

    // Upstream's client settings of the same names and defaults, packages/contracts/src/settings.ts
    var confirmThreadArchive: Boolean by property(false)
    var confirmThreadDelete: Boolean by property(true)
    var showSkillsInSlashMenu: Boolean by property(true)
    var composerRichTextEnabled: Boolean by property(true)
    var followUpBehavior: String? by string(FOLLOW_UP_STEER)
    var planModeEnabled: Boolean by property(false)

    // Upstream's `favorites` (`ModelFavorites`, settings.ts:277), as `instanceId:slug` keys
    var modelFavorites: MutableList<String> by list()

    // Upstream's `threadDetailsItems` (settings.ts:484): sparse show and hide overrides for T3
    // Thread Details. Missing ids use the default; ids the plugin doesn't know are kept
    var threadDetailsItems: MutableMap<String, Boolean> by map()
  }

  private val _threadDetailsItems: MutableStateFlow<Map<String, Boolean>> =
    MutableStateFlow(emptyMap())

  /** [threadDetailsItems] as it changes, for T3 Thread Details to follow. */
  val threadDetailsItemsFlow: StateFlow<Map<String, Boolean>> = _threadDetailsItems.asStateFlow()

  override fun loadState(state: State) {
    super.loadState(state)
    _threadDetailsItems.value = state.threadDetailsItems.toMap()
  }

  var serverUrl: String
    get() = state.serverUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
    set(value) {
      state.serverUrl = value.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER_URL }
    }

  var confirmThreadArchive: Boolean
    get() = state.confirmThreadArchive
    set(value) {
      state.confirmThreadArchive = value
    }

  var confirmThreadDelete: Boolean
    get() = state.confirmThreadDelete
    set(value) {
      state.confirmThreadDelete = value
    }

  var showSkillsInSlashMenu: Boolean
    get() = state.showSkillsInSlashMenu
    set(value) {
      state.showSkillsInSlashMenu = value
    }

  var composerRichTextEnabled: Boolean
    get() = state.composerRichTextEnabled
    set(value) {
      state.composerRichTextEnabled = value
    }

  /** Upstream's `followUpBehavior`: [FOLLOW_UP_STEER] unless [FOLLOW_UP_QUEUE] was picked. */
  var followUpBehavior: String
    get() = state.followUpBehavior.takeIf { it == FOLLOW_UP_QUEUE } ?: FOLLOW_UP_STEER
    set(value) {
      state.followUpBehavior = value
    }

  /** Upstream's "Plan mode (legacy)": the Plan/Build toggle, `/plan`, `/default` and Shift+Tab. */
  var planModeEnabled: Boolean
    get() = state.planModeEnabled
    set(value) {
      state.planModeEnabled = value
    }

  /** The favourite models in the picker, keyed by `providerModelKey`. */
  var modelFavorites: Set<String>
    get() = state.modelFavorites.toSet()
    set(value) {
      state.modelFavorites = value.toMutableList()
    }

  /** See `resolveThreadDetailsVisibility` and `setThreadDetailsItem` for what goes in. */
  var threadDetailsItems: Map<String, Boolean>
    get() = state.threadDetailsItems.toMap()
    set(value) {
      state.threadDetailsItems = value.toMutableMap()
      _threadDetailsItems.value = value.toMap()
    }

  companion object {
    const val DEFAULT_SERVER_URL: String = "http://127.0.0.1:3773"
    const val FOLLOW_UP_QUEUE: String = "queue"
    const val FOLLOW_UP_STEER: String = "steer"

    fun get(): T3Settings = service()
  }
}
