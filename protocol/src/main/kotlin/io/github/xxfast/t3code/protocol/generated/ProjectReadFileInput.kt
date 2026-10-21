// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ProjectReadFileInput(
  val cwd: String,
  val relativePath: String,
  val allowMissing: Boolean? = null,
)
