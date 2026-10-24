// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class PullRequestListResult(
  val viewers: Map<String, String>,
  val providers: List<PullRequestProviderSummary>,
  val entries: List<PullRequestListEntry>,
  val errors: List<PullRequestListProjectError>,
  val truncated: Boolean,
  val nextCursors: Map<String, String>,
)
