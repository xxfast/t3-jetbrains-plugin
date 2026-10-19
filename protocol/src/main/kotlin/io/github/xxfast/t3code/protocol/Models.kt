package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.AuthSessionState
import io.github.xxfast.t3code.protocol.generated.ProjectReadFileResult
import io.github.xxfast.t3code.protocol.generated.ProjectShell
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

/** What `projects.readFile` answers. */
public typealias FileContents = ProjectReadFileResult

public val Thread.archived: Boolean get() = archivedAt != null

public val Thread.pinned: Boolean get() = pinnedAt != null

/** A bearer session minted by `POST /oauth/token`. Stored by the plugin in PasswordSafe. */
@Serializable
public data class Credential(
  val accessToken: String,
  val tokenType: String = "Bearer",
  val scopes: List<String> = emptyList(),
  val expiresInSeconds: Long? = null,
)

/** One open thread: the row, its runs and the items to render, in order. */
public data class ThreadView(
  val thread: Thread,
  val runs: List<Run>,
  val items: List<TurnItem>,
)
