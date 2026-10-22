package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ServerInfo

// `environment.capabilities` flags the Sidebar reads, packages/contracts/src/environment.ts

const val THREAD_SETTLEMENT: String = "threadSettlement"
const val THREAD_AUTO_SETTLE_OPT_OUT: String = "threadAutoSettleOptOut"
const val THREAD_SNOOZE: String = "threadSnooze"
const val THREAD_TITLE_REGENERATION: String = "threadTitleRegeneration"

/** `thread.stop` is a client command, sent by `interruptThreadTurn` without a run id. */
const val THREAD_STOP: String = "threadStop"

/**
 * Whether the server says it does [capability]. Upstream reads an unknown config as unsupported;
 * the plugin fetches `server.getConfig` after the shell, so a null [ServerInfo] is "not yet"
 * and [unknown] says what to assume meanwhile.
 */
fun ServerInfo?.supports(capability: String, unknown: Boolean = false): Boolean =
  this?.capabilities?.contains(capability) ?: unknown
