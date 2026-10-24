// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class PullRequestDetail(
  val provider: String,
  val capabilities: PullRequestCapabilities,
  val viewerPermissions: PullRequestViewerPermissions,
  val projectId: String,
  val projectTitle: String,
  val workspaceRoot: String,
  val repository: String,
  val number: Long,
  val title: String,
  val body: String,
  val url: String,
  val author: PullRequestActor?,
  val state: String,
  val isDraft: Boolean,
  val mergeability: String,
  val additions: Long,
  val deletions: Long,
  val changedFiles: Long,
  val headBranch: String,
  val headRepositoryNameWithOwner: String? = null,
  val headSha: String? = null,
  val baseBranch: String,
  val createdAt: String,
  val updatedAt: String,
  val observedAt: Double? = null,
  val mergedAt: String?,
  val closedAt: String?,
  val reviewers: List<PullRequestActor>,
  val labels: List<PullRequestLabel>,
  val checks: List<PullRequestCheck>,
  val mergeCapabilities: PullRequestMergeCapabilities,
  val viewer: String? = null,
  val baseComparison: String? = null,
  val behindBy: Long? = null,
  val autoMergeEnabled: Boolean? = null,
  val autoMergeMethod: String? = null,
  val workflowApprovalsRequired: Long? = null,
)
