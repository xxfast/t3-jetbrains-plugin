package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.terminal.TERMINAL_READ
import io.github.xxfast.t3code.plugin.ui.applyTerminalMetadata
import io.github.xxfast.t3code.plugin.ui.runningTerminals
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.TerminalSummary
import io.github.xxfast.t3code.protocol.has
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * The running terminals of every thread, by thread id, upstream's `useThreadRunningTerminalIds`
 * for the whole Sidebar at once: one `subscribeTerminalMetadata` while something collects, and
 * only on a pairing with `terminal:read`, as `useKnownTerminalSessions` checks. Empty otherwise,
 * and when the stream fails: the row's icon is a glance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Service(Service.Level.APP)
class RunningTerminals(scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  val byThread: StateFlow<Map<String, List<String>>> = client.connection
    .map { connection ->
      connection is ConnectionState.Connected && connection.session.has(TERMINAL_READ)
    }
    .distinctUntilChanged()
    .flatMapLatest { readable ->
      if (!readable) return@flatMapLatest flowOf(emptyMap())
      client.terminalMetadata()
        .runningFold(emptyList<TerminalSummary>(), ::applyTerminalMetadata)
        .map(::runningTerminals)
        .catch { emit(emptyMap()) }
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(GRACE_MS), emptyMap())

  companion object {
    private const val GRACE_MS: Long = 5_000

    fun get(): RunningTerminals = service()
  }
}
