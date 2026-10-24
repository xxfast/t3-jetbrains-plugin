// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ProjectShell(
  val id: String,
  val title: String,
  val workspaceRoot: String,
  val repositoryIdentity: RepositoryIdentity? = null,
  val defaultModelSelection: ModelSelection?,
  val defaultThreadEnvMode: String? = null,
  val autoPull: Boolean? = null,
  val faviconPath: String? = null,
  val projectIcon: StoredProjectIcon? = null,
  val scripts: List<ProjectScript>,
  val createdAt: String,
  val updatedAt: String,
)
