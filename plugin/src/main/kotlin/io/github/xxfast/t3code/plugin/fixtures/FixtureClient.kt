package io.github.xxfast.t3code.plugin.fixtures

import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.FileContents
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.TurnItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A [T3Client] that replays `*.json` scenarios from [directory] instead of talking to a server.
 * Polls the directory, so editing a file updates whoever is collecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixtureClient(
  private val directory: Path,
  scope: CoroutineScope,
  private val pollInterval: Duration = 1.seconds,
) : T3Client {
  private val log: Logger = Logger.getInstance(FixtureClient::class.java)

  private val _scenarios: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
  val scenarios: StateFlow<List<String>> = _scenarios.asStateFlow()

  private val _selected: MutableStateFlow<String?> = MutableStateFlow(null)
  val selected: StateFlow<String?> = _selected.asStateFlow()

  private val loaded: MutableStateFlow<Scenario?> = MutableStateFlow(null)
  private val online: MutableStateFlow<Boolean> = MutableStateFlow(true)
  private val wake: Channel<Unit> = Channel(Channel.CONFLATED)
  private var loadedStamp: Pair<String, Long>? = null

  override val connection: StateFlow<ConnectionState> = combine(loaded, online) { scenario, on ->
    if (!on) ConnectionState.Disconnected else scenario?.connection ?: ConnectionState.Connecting
  }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Connecting)

  override val projects: StateFlow<List<Project>> = loaded
    .map { scenario -> scenario?.projects.orEmpty() }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  override val threads: StateFlow<List<Thread>> = loaded
    .map { scenario -> scenario?.threads.orEmpty() }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    scope.launch(Dispatchers.IO) {
      while (isActive) {
        poll()
        withTimeoutOrNull(pollInterval) { wake.receive() }
      }
    }
  }

  fun select(name: String) {
    _selected.value = name
    wake.trySend(Unit)
  }

  override fun thread(id: String): Flow<ThreadView> = loaded
    .filterNotNull()
    .flatMapLatest { scenario ->
      val thread: Thread? = scenario.threads.find { it.id == id }
      if (thread == null) emptyFlow() else replay(thread, scenario)
    }

  private fun replay(thread: Thread, scenario: Scenario): Flow<ThreadView> = flow {
    val entries: List<FixtureItem> = scenario.items[thread.id].orEmpty()
    val snapshot: Int = entries.indexOfFirst { it.after > 0 }.takeIf { it >= 0 } ?: entries.size
    val items: LinkedHashMap<String, TurnItem> = LinkedHashMap()
    entries.take(snapshot).forEach { items[it.item.id] = it.item }
    fun view() = ThreadView(thread, scenario.runs[thread.id].orEmpty(), items.values.toList())
    emit(view())
    entries.drop(snapshot).forEach { entry ->
      delay(entry.after)
      items[entry.item.id] = entry.item
      emit(view())
    }
  }

  override suspend fun readFile(cwd: String, path: String): FileContents {
    val contents = "// fixture content for $path\n"
    return FileContents(path, contents, byteLength = contents.length.toLong())
  }

  override suspend fun turnDiff(threadId: String, fromTurn: Int, toTurn: Int): String = PATCH

  override suspend fun fullThreadDiff(threadId: String, toTurn: Int): String = PATCH

  override fun connect() {
    online.value = true
  }

  override fun disconnect() {
    online.value = false
  }

  private fun poll() {
    val files: Map<String, Path> = try {
      Files.list(directory).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "json" }
          .toList()
          .associateBy { it.nameWithoutExtension }
          .toSortedMap()
      }
    } catch (e: Exception) {
      log.warn("Can't list fixtures in $directory", e)
      return
    }
    _scenarios.value = files.keys.toList()

    val current: String? = _selected.value
    val name: String = current?.takeIf { it in files }
      ?: "connected-list".takeIf { it in files }
      ?: files.keys.firstOrNull()
      ?: return
    if (!_selected.compareAndSet(current, name)) return

    val path: Path = files.getValue(name)
    val stamp: Pair<String, Long> = name to path.getLastModifiedTime().toMillis()
    if (stamp == loadedStamp) return
    loadedStamp = stamp
    loaded.value = try {
      Scenario.decode(name, path.readText())
    } catch (e: Exception) {
      log.warn("Can't decode fixture scenario $name", e)
      Scenario.failed(name, e.message ?: e.javaClass.simpleName)
    }
  }
}

private val PATCH: String = """
  diff --git a/src/main/kotlin/App.kt b/src/main/kotlin/App.kt
  --- a/src/main/kotlin/App.kt
  +++ b/src/main/kotlin/App.kt
  @@ -1,3 +1,4 @@
   fun main() {
  -  println("hello")
  +  val name: String = "fixture"
  +  println("hello, ${'$'}name")
   }

""".trimIndent()
