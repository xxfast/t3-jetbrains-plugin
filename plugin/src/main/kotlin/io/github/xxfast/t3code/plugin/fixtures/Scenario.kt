package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** One file in a scenario directory under `harness/scenarios`, as written. */
@Serializable
class ScenarioFile(
  val connection: ScenarioConnection? = null,
  val projects: List<Project> = emptyList(),
  val threads: List<Thread> = emptyList(),
  val runs: Map<String, List<Run>> = emptyMap(),
  val items: Map<String, List<JsonObject>> = emptyMap(),
  val runtimeRequests: Map<String, List<RuntimeRequest>> = emptyMap(),
  val providerSessions: Map<String, List<ProviderSession>> = emptyMap(),
  val checkpoints: Map<String, List<Checkpoint>> = emptyMap(),
)

@Serializable
class ScenarioConnection(
  val state: String = "connected",
  val message: String? = null,
  val session: ServerSession? = null,
)

/** A turn item and how long to wait before it shows up, in milliseconds. */
class FixtureItem(val item: TurnItem, val after: Long)

/**
 * A scenario file, decoded all the way down so a bad file fails at load, not mid-replay.
 * [connection] is `null` when the file doesn't set one.
 */
class Scenario(
  val connection: ConnectionState?,
  val projects: List<Project>,
  val threads: List<Thread>,
  val runs: Map<String, List<Run>>,
  val items: Map<String, List<FixtureItem>>,
  val runtimeRequests: Map<String, List<RuntimeRequest>> = emptyMap(),
  val providerSessions: Map<String, List<ProviderSession>> = emptyMap(),
  val checkpoints: Map<String, List<Checkpoint>> = emptyMap(),
) {
  companion object {
    fun decode(text: String): Scenario {
      val file: ScenarioFile = T3Json.decodeFromString(ScenarioFile.serializer(), text)
      return Scenario(
        connection = file.connection?.toState(),
        projects = file.projects,
        threads = file.threads,
        runs = file.runs,
        items = file.items.mapValues { (_, entries) -> entries.map(::fixtureItem) },
        runtimeRequests = file.runtimeRequests,
        providerSessions = file.providerSessions,
        checkpoints = file.checkpoints,
      )
    }

    /**
     * One scenario out of a directory's files, in order. Projects and threads dedupe by id, first
     * wins. Runs, items, requests, sessions and checkpoints append per thread. The last file that
     * sets a connection wins, and a directory that sets none is connected.
     */
    fun merge(parts: List<Scenario>): Scenario = Scenario(
      connection = parts.lastOrNull { it.connection != null }?.connection
        ?: ConnectionState.Connected(defaultSession),
      projects = parts.flatMap { it.projects }.distinctBy { it.id },
      threads = parts.flatMap { it.threads }.distinctBy { it.id },
      runs = parts.map { it.runs }.fold(emptyMap(), ::append),
      items = parts.map { it.items }.fold(emptyMap(), ::append),
      runtimeRequests = parts.map { it.runtimeRequests }.fold(emptyMap(), ::append),
      providerSessions = parts.map { it.providerSessions }.fold(emptyMap(), ::append),
      checkpoints = parts.map { it.checkpoints }.fold(emptyMap(), ::append),
    )

    fun failed(name: String, message: String): Scenario = Scenario(
      connection = ConnectionState.Error("scenario $name: $message"),
      projects = emptyList(),
      threads = emptyList(),
      runs = emptyMap(),
      items = emptyMap(),
    )
  }
}

private fun <T> append(
  into: Map<String, List<T>>,
  from: Map<String, List<T>>,
): Map<String, List<T>> = (into.keys + from.keys)
  .associateWith { key -> into[key].orEmpty() + from[key].orEmpty() }

private val defaultSession: ServerSession = ServerSession(
  authenticated = true,
  auth = ServerAuthDescriptor(
    policy = "desktop-managed-local",
    bootstrapMethods = listOf("desktop-bootstrap"),
    sessionMethods = listOf("bearer-access-token"),
    sessionCookieName = "t3_session_3773",
  ),
  permissions = listOf("orchestration:read", "orchestration:operate", "filesystem:read"),
  scopes = listOf("orchestration:read", "orchestration:operate", "filesystem:read"),
  sessionMethod = "bearer-access-token",
)

private fun ScenarioConnection.toState(): ConnectionState = when (state) {
  "disconnected" -> ConnectionState.Disconnected
  "unpaired" -> ConnectionState.Unpaired
  "connecting" -> ConnectionState.Connecting
  "connected" -> ConnectionState.Connected(session ?: defaultSession)
  "authError" -> ConnectionState.AuthError(message ?: "credential rejected")
  "error" -> ConnectionState.Error(message ?: "connection failed")
  else -> throw IllegalArgumentException("unknown connection state '$state'")
}

private fun fixtureItem(entry: JsonObject): FixtureItem {
  val after: Long = (entry["after"] as? JsonPrimitive)?.long ?: 0
  val item: TurnItem = T3Json.decodeFromJsonElement(
    TurnItem.serializer(),
    JsonObject(entry - "after"),
  )
  return FixtureItem(item, after)
}
