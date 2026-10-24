package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ContextTransfer
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.ProviderTurn
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.Subagent
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ProjectEntry
import io.github.xxfast.t3code.protocol.generated.PullRequestListEntry
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageLimits
import io.github.xxfast.t3code.protocol.generated.ServerProviderWorkspaceSnapshot
import io.github.xxfast.t3code.protocol.generated.TerminalSummary
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
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
  val providerThreads: Map<String, List<ProviderThread>> = emptyMap(),
  val terminals: List<TerminalSummary> = emptyList(),
  /** A worktree setup in flight per thread, what `subscribeWorktreeSetup` answers at once. */
  val worktreeSetups: Map<String, WorktreeSetupSnapshot> = emptyMap(),
  /** The machine's skills per provider instance, `ServerProvider.skills`. */
  val providerSkills: Map<String, List<ServerProviderSkill>> = emptyMap(),
  /** Workspace snapshots per provider instance, `ServerProvider.workspaceSnapshots`. */
  val providerWorkspaceSnapshots: Map<String, List<ServerProviderWorkspaceSnapshot>> = emptyMap(),
  /**
   * Usage limits per provider instance, `ServerProvider.usageLimits`. A window whose `resetsAt`
   * has passed rolls forward by its `windowDurationMins`, so the file doesn't go stale.
   */
  val providerUsageLimits: Map<String, ServerProviderUsageLimits> = emptyMap(),
  /** The projection's conversation messages per thread, what a queued run's text comes from. */
  val messages: Map<String, List<ConversationMessage>> = emptyMap(),
  /** The projection's provider turns per thread, what says a run can be steered. */
  val providerTurns: Map<String, List<ProviderTurn>> = emptyMap(),
  /** The projection's subagents per thread, what its Lineage lists. */
  val subagents: Map<String, List<Subagent>> = emptyMap(),
  /** The projection's context transfers per thread. */
  val contextTransfers: Map<String, List<ContextTransfer>> = emptyMap(),
  /** Files and folders `projects.searchEntries` searches, relative to any cwd. */
  val projectEntries: List<ProjectEntry> = emptyList(),
  /** Pull requests `pullRequests.list` answers with. */
  val pullRequests: List<PullRequestListEntry> = emptyList(),
  /** Pull requests only `pullRequests.detail` finds, past what the list shows. */
  val olderPullRequests: List<PullRequestListEntry> = emptyList(),
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
  // Each thread's provider threads, for the composer's context window meter
  val providerThreads: Map<String, List<ProviderThread>> = emptyMap(),
  val terminals: List<TerminalSummary> = emptyList(), // what `subscribeTerminalMetadata` lists
  val worktreeSetups: Map<String, WorktreeSetupSnapshot> = emptyMap(),
  val providerSkills: Map<String, List<ServerProviderSkill>> = emptyMap(),
  val providerWorkspaceSnapshots: Map<String, List<ServerProviderWorkspaceSnapshot>> = emptyMap(),
  val providerUsageLimits: Map<String, ServerProviderUsageLimits> = emptyMap(),
  val messages: Map<String, List<ConversationMessage>> = emptyMap(),
  val providerTurns: Map<String, List<ProviderTurn>> = emptyMap(),
  val subagents: Map<String, List<Subagent>> = emptyMap(),
  val contextTransfers: Map<String, List<ContextTransfer>> = emptyMap(),
  val projectEntries: List<ProjectEntry> = emptyList(),
  val pullRequests: List<PullRequestListEntry> = emptyList(),
  val olderPullRequests: List<PullRequestListEntry> = emptyList(),
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
        providerThreads = file.providerThreads,
        terminals = file.terminals,
        worktreeSetups = file.worktreeSetups,
        providerSkills = file.providerSkills,
        providerWorkspaceSnapshots = file.providerWorkspaceSnapshots,
        providerUsageLimits = file.providerUsageLimits,
        messages = file.messages,
        providerTurns = file.providerTurns,
        subagents = file.subagents,
        contextTransfers = file.contextTransfers,
        projectEntries = file.projectEntries,
        pullRequests = file.pullRequests,
        olderPullRequests = file.olderPullRequests,
      )
    }

    /**
     * One scenario out of a directory's files, in order. Projects, threads and terminals dedupe
     * by id, first wins. Runs, items, requests, sessions, checkpoints and provider threads append
     * per thread. The
     * last file that sets a connection wins, and a directory that sets none is connected.
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
      providerThreads = parts.map { it.providerThreads }.fold(emptyMap(), ::append),
      terminals = parts.flatMap { it.terminals }.distinctBy { it.threadId to it.terminalId },
      worktreeSetups = parts.map { it.worktreeSetups }.fold(emptyMap()) { into, from -> from + into },
      providerSkills = parts.map { it.providerSkills }.fold(emptyMap(), ::append),
      providerWorkspaceSnapshots =
        parts.map { it.providerWorkspaceSnapshots }.fold(emptyMap(), ::append),
      providerUsageLimits = parts.map { it.providerUsageLimits }.fold(emptyMap()) { into, from ->
        from + into
      },
      messages = parts.map { it.messages }.fold(emptyMap(), ::append),
      providerTurns = parts.map { it.providerTurns }.fold(emptyMap(), ::append),
      subagents = parts.map { it.subagents }.fold(emptyMap(), ::append),
      contextTransfers = parts.map { it.contextTransfers }.fold(emptyMap(), ::append),
      projectEntries = parts.flatMap { it.projectEntries }.distinctBy { it.path },
      pullRequests = parts.flatMap { it.pullRequests }.distinctBy { it.url },
      olderPullRequests = parts.flatMap { it.olderPullRequests }.distinctBy { it.url },
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
  permissions = listOf(
    "orchestration:read",
    "orchestration:operate",
    "terminal:read",
    "terminal:operate",
    "filesystem:read",
  ),
  scopes = listOf(
    "orchestration:read",
    "orchestration:operate",
    "terminal:read",
    "terminal:operate",
    "filesystem:read",
  ),
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
