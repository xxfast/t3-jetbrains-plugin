// Generated from v0.1.5 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class SubscribeThreadInput(
  val threadId: String,
  val afterSequence: Long? = null,
  val requestCompletionMarker: Boolean? = null,
  val acceptBoundedSnapshot: Boolean? = null,
  val acceptDeferredWork: Boolean? = null,
  val acceptCompactTurnItems: Boolean? = null,
  val acceptDedupedBodies: Boolean? = null,
  val acceptCompactIds: Boolean? = null,
)
