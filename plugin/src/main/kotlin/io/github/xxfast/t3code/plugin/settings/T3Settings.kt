package io.github.xxfast.t3code.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Where a thread opens: an editor tab, or the "T3 Chat View" tool window. */
enum class ThreadHost(val label: String) {
  EDITOR("Editor tab"),
  TOOL_WINDOW("T3 Chat View tool window"),
}

/** Where the server is. Nothing secret lives here; the credential is in [T3Credentials]. */
@Service(Service.Level.APP)
@State(
  name = "T3CodeSettings",
  storages = [Storage("t3code.xml", roamingType = RoamingType.DISABLED)],
)
class T3Settings : SimplePersistentStateComponent<T3Settings.State>(State()) {
  class State : BaseState() {
    var serverUrl: String? by string(DEFAULT_SERVER_URL)
    var openThreadsIn: String? by string(ThreadHost.EDITOR.name)

    // Upstream's client settings of the same names and defaults, packages/contracts/src/settings.ts
    var confirmThreadArchive: Boolean by property(false)
    var confirmThreadDelete: Boolean by property(true)
  }

  var serverUrl: String
    get() = state.serverUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
    set(value) {
      state.serverUrl = value.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER_URL }
    }

  // A string on disk, so an unknown or renamed value falls back instead of failing to load
  var openThreadsIn: ThreadHost
    get() = ThreadHost.entries.find { it.name == state.openThreadsIn } ?: ThreadHost.EDITOR
    set(value) {
      state.openThreadsIn = value.name
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

  companion object {
    const val DEFAULT_SERVER_URL: String = "http://127.0.0.1:3773"

    fun get(): T3Settings = service()
  }
}
