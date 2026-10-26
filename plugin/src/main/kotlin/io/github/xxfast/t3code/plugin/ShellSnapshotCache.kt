package io.github.xxfast.t3code.plugin

import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The shell as last seen live from one server, what [ShellSnapshotStore] keeps on disk. */
@Serializable
data class ShellSnapshot(
  val serverUrl: String,
  val savedAt: String,
  val projects: List<Project>,
  val threads: List<Thread>,
)

/** One file per server under [directory], `shell-<sha256 of the url>.json`. */
class ShellSnapshotStore(private val directory: Path) {
  fun load(serverUrl: String): ShellSnapshot? = try {
    T3Json.decodeFromString(ShellSnapshot.serializer(), file(serverUrl).readText())
      .takeIf { it.serverUrl == serverUrl }
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  fun save(snapshot: ShellSnapshot) {
    directory.createDirectories()
    val target: Path = file(snapshot.serverUrl)
    val temp: Path = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
    temp.writeText(T3Json.encodeToString(ShellSnapshot.serializer(), snapshot))
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  fun delete(serverUrl: String) {
    file(serverUrl).deleteIfExists()
  }

  private fun file(serverUrl: String): Path {
    val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(serverUrl.toByteArray())
    return directory.resolve("shell-${digest.joinToString("") { "%02x".format(it) }}.json")
  }
}

/**
 * [inner] with its last shell kept on disk and shown, marked [stale], while it isn't connected.
 * Plugin only: upstream's desktop app is local-first and has no offline mode.
 *
 * The rule, per change of [inner]: connected is live, saved (debounced) when it has anything.
 * Not connected with threads in hand (a dropped socket keeps its list, a fixture scenario
 * ships one) is that list, stale since the connection left. Not connected with nothing is the
 * file for [serverUrl], stale since it was saved. Unpaired never shows the file.
 */
@OptIn(FlowPreview::class)
class ShellSnapshotCache(
  private val inner: T3Client,
  private val serverUrl: () -> String,
  private val store: ShellSnapshotStore,
  scope: CoroutineScope,
  private val clock: () -> Instant = Instant::now,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  debounce: Duration = 1.seconds,
) : T3Client by inner {
  private val log: Logger = Logger.getInstance(ShellSnapshotCache::class.java)

  private val _projects: MutableStateFlow<List<Project>> = MutableStateFlow(emptyList())
  private val _threads: MutableStateFlow<List<Thread>> = MutableStateFlow(emptyList())
  private val _stale: MutableStateFlow<Instant?> = MutableStateFlow(null)
  private val _liveThreads: MutableStateFlow<List<Thread>?> = MutableStateFlow(emptyList())
  private val cached: MutableStateFlow<ShellSnapshot?> = MutableStateFlow(null)
  private val saves: MutableStateFlow<ShellSnapshot?> = MutableStateFlow(null)

  override val projects: StateFlow<List<Project>> = _projects.asStateFlow()
  override val threads: StateFlow<List<Thread>> = _threads.asStateFlow()

  /** When what [projects] and [threads] show was last live, or null while it is. */
  val stale: StateFlow<Instant?> = _stale.asStateFlow()

  /**
   * [threads] while live, null while [stale]. One flow, so a reader never pairs a cached list
   * with a live state between [show]'s writes.
   */
  val liveThreads: StateFlow<List<Thread>?> = _liveThreads.asStateFlow()

  init {
    // A drop or a server change rereads the file, so it's always the current server's
    scope.launch {
      inner.connection.map { it is ConnectionState.Connected }.distinctUntilChanged()
        .collect { live -> if (!live) cached.value = withContext(io) { store.load(serverUrl()) } }
    }
    scope.launch {
      saves.filterNotNull().debounce(debounce).collect { snapshot ->
        try {
          withContext(io) { store.save(snapshot) }
        } catch (e: IOException) {
          log.info("Couldn't cache the shell snapshot", e)
        }
      }
    }
    scope.launch {
      var offlineSince: Instant? = null
      var live: ShellSnapshot? = null
      combine(inner.connection, inner.projects, inner.threads, cached) { c, p, t, f ->
        Inputs(c, p, t, f)
      }.collect { (connection, projects, threads, file) ->
        if (connection is ConnectionState.Connected) {
          offlineSince = null
          show(projects, threads, stale = null)
          if (projects.isEmpty() && threads.isEmpty()) return@collect
          live = ShellSnapshot(serverUrl(), clock().toString(), projects, threads)
          saves.value = live
          return@collect
        }
        val since: Instant = offlineSince ?: clock().also { offlineSince = it }
        // Saved again as the connection drops, so a cold start says how old it really is
        live?.let { saves.value = it.copy(savedAt = since.toString()) }
        live = null
        when {
          threads.isNotEmpty() -> show(projects, threads, stale = since)
          file != null && connection !is ConnectionState.Unpaired ->
            show(file.projects, file.threads, stale = Instant.parse(file.savedAt))
          else -> show(projects, threads, stale = null)
        }
      }
    }
  }

  /** Drops [serverUrl]'s file, for a forgotten credential. */
  suspend fun forget(serverUrl: String) {
    cached.value = null
    withContext(io) { store.delete(serverUrl) }
  }

  private fun show(projects: List<Project>, threads: List<Thread>, stale: Instant?) {
    _projects.value = projects
    _threads.value = threads
    _stale.value = stale
    _liveThreads.value = threads.takeIf { stale == null }
  }

  private data class Inputs(
    val connection: ConnectionState,
    val projects: List<Project>,
    val threads: List<Thread>,
    val file: ShellSnapshot?,
  )
}
