// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class PullRequestListInput(
  val state: String,
  val involvement: String? = null,
  val filters: PullRequestListFilters? = null,
  val projectId: String? = null,
  val projectIds: List<String>? = null,
  val host: String? = null,
  val limit: Long? = null,
  val cursors: Map<String, String>? = null,
  val query: String? = null,
)
