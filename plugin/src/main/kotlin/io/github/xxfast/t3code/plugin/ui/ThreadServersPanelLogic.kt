package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.DiscoveredLocalServer

// The rules behind T3 Thread Details' Servers section, upstream's ThreadServersPanel.tsx
// (apps/web/src/components/chat/ThreadServersPanel.tsx) and the thread filter it reads from
// portDiscoveryState.ts (apps/web/src/portDiscoveryState.ts).

/**
 * `useThreadDiscoveredPorts`: the servers [threadId]'s terminals or agent started, out of every
 * server `subscribeDiscoveredLocalServers` found. None without a thread.
 */
fun useThreadDiscoveredPorts(
  ports: List<DiscoveredLocalServer>,
  threadId: String?,
): List<DiscoveredLocalServer> = if (threadId != null) {
  ports.filter { port -> port.terminal?.threadId == threadId || port.agent?.threadId == threadId }
} else {
  emptyList()
}

/** `serverKey`: a row's identity, and what [ThreadServersState.stoppingKey] names. */
fun serverKey(server: DiscoveredLocalServer): String = "${server.pid}:${server.port}"

/**
 * `stoppable`: the row gets a stop button when the pairing may stop servers ([canStop],
 * `terminal:operate`), the server says it can ([supportsStop], `localServerStop`) and the
 * process is known.
 */
fun stoppable(server: DiscoveredLocalServer, canStop: Boolean, supportsStop: Boolean): Boolean =
  canStop && supportsStop && server.pid != null

/** The row's second line: `Agent` or `Terminal`, then the process name, joined with " · ". */
fun serverDetail(server: DiscoveredLocalServer): String =
  listOfNotNull(if (server.terminal == null) "Agent" else "Terminal", server.processName)
    .filter { it.isNotEmpty() }
    .joinToString(" · ")
