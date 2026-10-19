// Generated from v0.1.1-preview.20261019.3339 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ProjectReadFileResult(
  val relativePath: String,
  val contents: String,
  val byteLength: Long,
  val truncated: Boolean,
)
