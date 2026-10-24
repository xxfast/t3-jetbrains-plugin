// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ThreadLaunchResult(
  val threadId: String,
  val projection: ThreadProjection,
  val resumed: Boolean,
)
