package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** One scenario file under `harness/scenarios`, as written. */
@Serializable
class ScenarioFile(
  val connection: ScenarioConnection = ScenarioConnection(),
  val projects: List<Project> = emptyList(),
  val threads: List<Thread> = emptyList(),
  val runs: Map<String, List<Run>> = emptyMap(),
  val items: Map<String, List<JsonObject>> = emptyMap(),
)

@Serializable
class ScenarioConnection(
  val state: String = "connected",
  val message: String? = null,
  val session: ServerSession? = null,
)

/** A turn item and how long to wait before it shows up, in milliseconds. */
class FixtureItem(val item: TurnItem, val after: Long)

/** A scenario file, decoded all the way down so a bad file fails at load, not mid-replay. */
class Scenario(
  val name: String,
  val connection: ConnectionState,
  val projects: List<Project>,
  val threads: List<Thread>,
  val runs: Map<String, List<Run>>,
  val items: Map<String, List<FixtureItem>>,
) {
  companion object {
    fun decode(name: String, text: String): Scenario {
      val file: ScenarioFile = T3Json.decodeFromString(ScenarioFile.serializer(), text)
      return Scenario(
        name = name,
        connection = file.connection.toState(),
        projects = file.projects,
        threads = file.threads,
        runs = file.runs,
        items = file.items.mapValues { (_, entries) -> entries.map(::fixtureItem) },
      )
    }

    fun failed(name: String, message: String): Scenario = Scenario(
      name = name,
      connection = ConnectionState.Error("scenario $name: $message"),
      projects = emptyList(),
      threads = emptyList(),
      runs = emptyMap(),
      items = emptyMap(),
    )
  }
}

private val defaultSession: ServerSession = ServerSession(
  authenticated = true,
  auth = ServerAuthDescriptor(
    policy = "desktop-managed-local",
    bootstrapMethods = listOf("desktop-bootstrap"),
    sessionMethods = listOf("bearer-access-token"),
    sessionCookieName = "t3_session_3773",
  ),
  permissions = listOf("orchestration:read", "filesystem:read"),
  scopes = listOf("orchestration:read", "filesystem:read"),
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
