package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import kotlinx.coroutines.flow.MutableStateFlow

/** Connection and shell set by hand, the rest from [rest], to drive a [ShellSnapshotCache]. */
class FakeShellClient(
  rest: T3Client,
  initial: ConnectionState = ConnectionState.Disconnected,
) : T3Client by rest {
  override val connection: MutableStateFlow<ConnectionState> = MutableStateFlow(initial)
  override val projects: MutableStateFlow<List<Project>> = MutableStateFlow(emptyList())
  override val threads: MutableStateFlow<List<Thread>> = MutableStateFlow(emptyList())
}

fun connected(): ConnectionState = ConnectionState.Connected(
  ServerSession(
    authenticated = true,
    auth = ServerAuthDescriptor(
      policy = "desktop-managed-local",
      bootstrapMethods = listOf("desktop-bootstrap"),
      sessionMethods = listOf("bearer-access-token"),
      sessionCookieName = "t3_session_3773",
    ),
  ),
)
