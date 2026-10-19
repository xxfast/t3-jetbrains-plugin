package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ServerInfo

// `environment.capabilities` flags the Sidebar reads, packages/contracts/src/environment.ts

const val THREAD_SETTLEMENT: String = "threadSettlement"
const val THREAD_AUTO_SETTLE_OPT_OUT: String = "threadAutoSettleOptOut"
const val THREAD_SNOOZE: String = "threadSnooze"

/**
 * Whether the server says it does [capability]. Upstream reads an unknown config as unsupported;
 * the plugin fetches `server.getConfig` after the shell, so a null [ServerInfo] is "not yet"
 * and [unknown] says what to assume meanwhile.
 */
fun ServerInfo?.supports(capability: String, unknown: Boolean = false): Boolean =
  this?.capabilities?.contains(capability) ?: unknown
