package io.github.xxfast.t3code.plugin

import com.intellij.openapi.application.PathManager
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
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.WebSocketT3Client
import java.nio.file.Path
import java.time.Instant
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
  private val cache: ShellSnapshotCache

  /** The picked client behind [ShellSnapshotCache], so a dropped server still lists threads. */
  val client: T3Client

  /** When the listed shell was last live, or null while it is. See [ShellSnapshotCache]. */
  val stale: StateFlow<Instant?>

  /** The listed threads while live, null while [stale]. See [ShellSnapshotCache.liveThreads]. */
  val liveThreads: StateFlow<List<Thread>?>

  private val fixtures: Boolean

  private val pairingError: MutableStateFlow<String?> = MutableStateFlow(null)

  init {
    val directory: Path? = System.getProperty("t3code.fixtures")
      ?.let { Path.of(it) }
      ?.takeIf { it.isDirectory() }
    fixtures = directory != null
    val picked: T3Client = directory?.let { FixtureClient(it, scope) } ?: PairingAwareClient(
      real = WebSocketT3Client(
        baseUrl = { T3Settings.get().serverUrl },
        credential = { T3Credentials.load(T3Settings.get().serverUrl) },
        scope = scope,
      ),
      pairingError = pairingError,
      scope = scope,
    )
    // A scenario is its own server, so the harness never writes over a real one's file
    val fixtureUrl: String? = directory?.toUri()?.toString()
    cache = ShellSnapshotCache(
      inner = picked,
      serverUrl = { fixtureUrl ?: T3Settings.get().serverUrl },
      store = ShellSnapshotStore(Path.of(PathManager.getSystemPath(), "t3code")),
      scope = scope,
    )
    client = cache
    stale = cache.stale
    liveThreads = cache.liveThreads
    val named: T3Client = (picked as? PairingAwareClient)?.real ?: picked
    LOG.info("T3 Code client: ${named.javaClass.simpleName}")
    if (!fixtures) client.connect()
  }

  fun reconnect() {
    pairingError.value = null
    client.connect()
  }

  /** Exchanges [input] for a credential, stores it, and reconnects. Failures land in the state. */
  fun pair(input: String) {
    if (fixtures) return
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
    if (fixtures) return
    scope.launch {
      val url: String = T3Settings.get().serverUrl
      T3Credentials.clear(url)
      cache.forget(url)
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
