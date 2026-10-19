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
  }

  var serverUrl: String
    get() = state.serverUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
    set(value) {
      state.serverUrl = value.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER_URL }
    }

  companion object {
    const val DEFAULT_SERVER_URL: String = "http://127.0.0.1:3773"

    fun get(): T3Settings = service()
  }
}
