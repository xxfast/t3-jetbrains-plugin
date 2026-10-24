// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ProjectSearchEntriesInput(
  val cwd: String,
  val query: String,
  val limit: Long,
  val kind: String? = null,
  val imageOnly: Boolean? = null,
)
