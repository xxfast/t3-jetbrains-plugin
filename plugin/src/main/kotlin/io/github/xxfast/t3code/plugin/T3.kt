package io.github.xxfast.t3code.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.FileContents
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** The plugin's container. Holds the one [T3Client], picked once at startup. */
@Service(Service.Level.APP)
class T3(private val scope: CoroutineScope) {
  /** Only for the tool window's scenario picker. Everything else reads [client]. */
  val fixtures: FixtureClient?

  val client: T3Client

  init {
    val directory: Path? = System.getProperty("t3code.fixtures")
      ?.let { Path.of(it) }
      ?.takeIf { it.isDirectory() }
    fixtures = directory?.let { FixtureClient(it, scope) }
    client = fixtures ?: StubClient
    Logger.getInstance(T3::class.java).info("T3 Code client: ${client.javaClass.simpleName}")
  }

  companion object {
    fun get(): T3 = service()
  }
}

/** Sits disconnected until #3 wires the real client in. */
private object StubClient : T3Client {
  override val connection: StateFlow<ConnectionState> =
    MutableStateFlow(ConnectionState.Disconnected)
  override val projects: StateFlow<List<Project>> = MutableStateFlow(emptyList())
  override val threads: StateFlow<List<Thread>> = MutableStateFlow(emptyList())
  override fun thread(id: String): Flow<ThreadView> = emptyFlow()
  override suspend fun readFile(cwd: String, path: String): FileContents = FileContents(path, "")
  override suspend fun turnDiff(threadId: String, fromTurn: Int, toTurn: Int): String = ""
  override suspend fun fullThreadDiff(threadId: String, toTurn: Int): String = ""
  override fun connect() = Unit
  override fun disconnect() = Unit
}
