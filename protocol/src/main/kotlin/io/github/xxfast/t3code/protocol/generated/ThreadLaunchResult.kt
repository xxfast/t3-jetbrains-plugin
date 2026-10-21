// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ThreadLaunchResult(
  val threadId: String,
  val projection: ThreadProjection,
  val resumed: Boolean,
)
