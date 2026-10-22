package io.github.xxfast.t3code.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

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

    // Upstream's `favorites` (`ModelFavorites`, settings.ts:277), as `instanceId:slug` keys
    var modelFavorites: MutableList<String> by list()
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

  /** The favourite models in the picker, keyed by `providerModelKey`. */
  var modelFavorites: Set<String>
    get() = state.modelFavorites.toSet()
    set(value) {
      state.modelFavorites = value.toMutableList()
    }

  companion object {
    const val DEFAULT_SERVER_URL: String = "http://127.0.0.1:3773"

    fun get(): T3Settings = service()
  }
}
