package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.plugin.ide.serverIsLocal
import io.github.xxfast.t3code.plugin.settings.T3Configurable
import io.github.xxfast.t3code.plugin.settings.T3Credentials
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Credential
import io.github.xxfast.t3code.protocol.T3Auth
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.WebSocketT3Client
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.isDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The plugin's container. Holds the one [T3Client], picked once at startup. */
@Service(Service.Level.APP)
class T3(private val scope: CoroutineScope) {
  val client: T3Client

  private val pairingError: MutableStateFlow<String?> = MutableStateFlow(null)

  init {
    val directory: Path? = System.getProperty("t3code.fixtures")
      ?.let { Path.of(it) }
      ?.takeIf { it.isDirectory() }
    client = directory?.let { FixtureClient(it, scope) } ?: PairingAwareClient(
      real = WebSocketT3Client(
        baseUrl = { T3Settings.get().serverUrl },
        credential = { T3Credentials.load(T3Settings.get().serverUrl) },
        scope = scope,
      ),
      pairingError = pairingError,
      scope = scope,
    )
    val picked: T3Client = (client as? PairingAwareClient)?.real ?: client
    LOG.info("T3 Code client: ${picked.javaClass.simpleName}")
    if (client !is FixtureClient) client.connect()
  }

  fun reconnect() {
    pairingError.value = null
    client.connect()
  }

  /** Exchanges [input] for a credential, stores it, and reconnects. Failures land in the state. */
  fun pair(input: String) {
    if (client is FixtureClient) return
    pairingError.value = null
    scope.launch {
      try {
        val url: String = T3Settings.get().serverUrl
        val credential: Credential = T3Auth(url).pair(input.trim())
        T3Credentials.save(url, credential)
        client.connect()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Pairing failed", e)
        pairingError.value = e.message ?: "Pairing failed"
      }
    }
  }

  fun forget() {
    if (client is FixtureClient) return
    scope.launch {
      T3Credentials.clear(T3Settings.get().serverUrl)
      reconnect()
    }
  }

  /** Runs [block] on the service scope for every connection change, for Swing UI to follow. */
  fun watch(block: suspend (ConnectionState) -> Unit): Job =
    scope.launch { client.connection.collectLatest(block) }

  companion object {
    private val LOG: Logger = Logger.getInstance(T3::class.java)

    fun get(): T3 = service()

    /** Whether the server's paths are this machine's paths, see [serverIsLocal]. */
    fun serverIsLocal(): Boolean = serverIsLocal(T3Settings.get().serverUrl)

    fun openSettings(project: Project) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, T3Configurable::class.java)
    }
  }
}

/** The real client, with a failed pairing shown as an auth error until the next attempt. */
private class PairingAwareClient(
  val real: T3Client,
  pairingError: StateFlow<String?>,
  scope: CoroutineScope,
) : T3Client by real {
  override val connection: StateFlow<ConnectionState> =
    combine(real.connection, pairingError) { connection, error ->
      if (error != null && connection !is ConnectionState.Connected) {
        ConnectionState.AuthError(error)
      } else {
        connection
      }
    }.stateIn(scope, SharingStarted.Eagerly, real.connection.value)
}
