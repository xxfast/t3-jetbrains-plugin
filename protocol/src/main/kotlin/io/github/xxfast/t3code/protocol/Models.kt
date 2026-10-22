package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.AuthSessionState
import io.github.xxfast.t3code.protocol.generated.ProjectReadFileResult
import io.github.xxfast.t3code.protocol.generated.ProjectShell
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ThreadShell
import kotlinx.serialization.Serializable

// The wire shapes are generated from upstream into `generated`, see contracts/README.md. These
// aliases are the names the plugin uses, so a regenerated class name only moves one line.

/** What `GET /api/auth/session` answers. */
public typealias ServerSession = AuthSessionState

/** `OrchestrationProjectShell`. */
public typealias Project = ProjectShell

/** `OrchestrationV2ThreadShell`, the list row. */
public typealias Thread = ThreadShell

/** A `Run` is upstream's "turn". `ordinal` is what the diff RPCs call a turn count. */
public typealias Run = io.github.xxfast.t3code.protocol.generated.Run

public typealias TurnItem = io.github.xxfast.t3code.protocol.generated.TurnItem

/**
 * What `orchestration.dispatchCommand` takes. Typealiases don't reach nested classes, so code
 * that builds a branch (`Command.ThreadPin`) imports `generated.Command` directly.
 */
public typealias OrchestrationV2Command = io.github.xxfast.t3code.protocol.generated.Command

/** An approval or question the provider is waiting on. */
public typealias RuntimeRequest = io.github.xxfast.t3code.protocol.generated.RuntimeRequest

public typealias ProviderSession = io.github.xxfast.t3code.protocol.generated.ProviderSession

public typealias ProviderThread = io.github.xxfast.t3code.protocol.generated.ProviderThread
public typealias ProviderTurn = io.github.xxfast.t3code.protocol.generated.ProviderTurn

/** A message in the conversation, queued ones included. `id` is the dispatched `messageId`. */
public typealias ConversationMessage =
  io.github.xxfast.t3code.protocol.generated.ConversationMessage

public typealias Checkpoint = io.github.xxfast.t3code.protocol.generated.Checkpoint

/** A subagent the thread started, `OrchestrationV2Subagent`. */
public typealias Subagent = io.github.xxfast.t3code.protocol.generated.Subagent

/** Context carried from one thread to another, `OrchestrationV2ContextTransfer`. */
public typealias ContextTransfer = io.github.xxfast.t3code.protocol.generated.ContextTransfer

/** What `projects.readFile` answers. */
public typealias FileContents = ProjectReadFileResult

public val Thread.archived: Boolean get() = archivedAt != null

public val Thread.pinned: Boolean get() = pinnedAt != null

/**
 * Whether the session grants [scope]. `permissions` is the exact grant and wins when present;
 * older servers send only the legacy `scopes`.
 */
public fun ServerSession.has(scope: String): Boolean =
  if (permissions.isNotEmpty()) scope in permissions else scope in scopes

/** A bearer session minted by `POST /oauth/token`. Stored by the plugin in PasswordSafe. */
@Serializable
public data class Credential(
  val accessToken: String,
  val tokenType: String = "Bearer",
  val scopes: List<String> = emptyList(),
  val expiresInSeconds: Long? = null,
)

/**
 * One open thread: the row, its runs and the items to render, in order, plus the projection's
 * pending requests, attached provider sessions and threads, captured checkpoints, its
 * conversation messages (queued ones have a message long before they have an item), and its
 * subagents and context transfers.
 */
public data class ThreadView(
  val thread: Thread,
  val runs: List<Run>,
  val items: List<TurnItem>,
  val runtimeRequests: List<RuntimeRequest> = emptyList(),
  val providerSessions: List<ProviderSession> = emptyList(),
  val checkpoints: List<Checkpoint> = emptyList(),
  val providerThreads: List<ProviderThread> = emptyList(),
  /** Each provider turn, carrying the live context window usage while one runs. */
  val providerTurns: List<ProviderTurn> = emptyList(),
  val messages: List<ConversationMessage> = emptyList(),
  /** The subagents this thread started, what its Lineage lists. */
  val subagents: List<Subagent> = emptyList(),
  /** Context transfers into or out of this thread. */
  val contextTransfers: List<ContextTransfer> = emptyList(),
  /**
   * How far into the server's command log this view is: the last event's `sequence`, or a
   * snapshot's `snapshotSequence`, whichever is later. Compare with what `dispatch` returned.
   */
  val sequence: Long = 0,
)

/** `server.getConfig`, the parts the plugin uses. */
public data class ServerInfo(
  val providers: List<ServerProvider>,
  /**
   * `environment.capabilities.serverResolvedCommandContext`: the server picks queue vs steer
   * itself.
   */
  val serverResolvedCommandContext: Boolean,
  /**
   * The `environment.capabilities` flags that are `true` (`threadSettlement`, `threadSnooze`,
   * ...). Upstream reads an absent flag as unsupported, so this only holds the ones that are on.
   */
  val capabilities: Set<String> = emptySet(),
  /** `environment.label`, what the Sidebar's hover card calls the machine. */
  val environmentLabel: String? = null,
  /**
   * `environment.environmentId`, the server's own id. A thread context record names the thread
   * by it (`threadContextRecord`, apps/web/src/lib/composerContextRecords.ts:161).
   */
  val environmentId: String? = null,
  /**
   * `resolveEnvironmentMachineKind`, packages/contracts/src/server.ts:629: the user's
   * `settings.environmentIcon`, else `environment.platform.machine`, else `server`.
   */
  val machine: String = "server",
)
