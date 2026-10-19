package io.github.xxfast.t3code.plugin.fixtures

import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.FileContents
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A [T3Client] that replays one scenario instead of talking to a server: every `*.json` in
 * [directory], merged by [Scenario.merge] in filename order. Polls the directory, so editing,
 * adding or removing a file updates whoever is collecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixtureClient(
  private val directory: Path,
  scope: CoroutineScope,
  private val pollInterval: Duration = 1.seconds,
) : T3Client {
  private val log: Logger = Logger.getInstance(FixtureClient::class.java)

  private val loaded: MutableStateFlow<Scenario?> = MutableStateFlow(null)
  private val online: MutableStateFlow<Boolean> = MutableStateFlow(true)
  private var loadedStamp: List<Pair<String, Long>>? = null

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
        delay(pollInterval)
      }
    }
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
    return FileContents(path, contents, byteLength = contents.length.toLong(), truncated = false)
  }

  override suspend fun turnDiff(threadId: String, fromTurn: Long, toTurn: Long): String = PATCH

  override suspend fun fullThreadDiff(threadId: String, toTurn: Long): String = PATCH

  override fun connect() {
    online.value = true
  }

  override fun disconnect() {
    online.value = false
  }

  private fun poll() {
    val files: List<Path>
    val stamp: List<Pair<String, Long>>
    try {
      files = Files.list(directory).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "json" }.toList().sortedBy { it.name }
      }
      stamp = files.map { it.name to it.getLastModifiedTime().toMillis() }
    } catch (e: Exception) {
      log.warn("Can't list fixtures in $directory", e)
      return
    }
    if (stamp == loadedStamp) return
    loadedStamp = stamp
    loaded.value = load(files)
  }

  private fun load(files: List<Path>): Scenario {
    val parts: List<Scenario> = files.map { file ->
      try {
        Scenario.decode(file.readText())
      } catch (e: Exception) {
        log.warn("Can't decode fixture ${file.name}", e)
        return Scenario.failed(file.name, e.message ?: e.javaClass.simpleName)
      }
    }
    return Scenario.merge(parts)
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
