// Generated from v0.1.5 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class DiscoveredLocalServerList(
  val servers: List<DiscoveredLocalServer>,
  val scannedAt: String,
  val configuredUrlProbing: Boolean? = null,
)
