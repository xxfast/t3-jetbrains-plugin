package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.generated.PullRequestCapabilities
import io.github.xxfast.t3code.protocol.generated.PullRequestDetail
import io.github.xxfast.t3code.protocol.generated.PullRequestListEntry
import io.github.xxfast.t3code.protocol.generated.PullRequestListInput
import io.github.xxfast.t3code.protocol.generated.PullRequestListResult
import io.github.xxfast.t3code.protocol.generated.PullRequestMergeCapabilities
import io.github.xxfast.t3code.protocol.generated.PullRequestProviderSummary
import io.github.xxfast.t3code.protocol.generated.PullRequestRef
import io.github.xxfast.t3code.protocol.generated.PullRequestReviewCapabilities
import io.github.xxfast.t3code.protocol.generated.PullRequestReviewerCapabilities
import io.github.xxfast.t3code.protocol.generated.PullRequestViewerPermissions

/**
 * A stand-in for `pullRequests.list` over the scenario's [listed] pull requests: the project's,
 * in the asked state, newest first, the `query` searched on the "host" (title, branch, number,
 * author), cut at `limit`. The provider says it searches on the host, as GitHub's does.
 */
fun fixtureListPullRequests(
  listed: List<PullRequestListEntry>,
  input: PullRequestListInput,
): PullRequestListResult {
  val projects: Set<String> = (listOfNotNull(input.projectId) + input.projectIds.orEmpty()).toSet()
  val query: String = input.query.orEmpty().trim().lowercase()
  val matches: List<PullRequestListEntry> = listed
    .filter { projects.isEmpty() || it.projectId in projects }
    .filter { input.state == "all" || it.state == input.state }
    .filter { entry ->
      query.isEmpty() ||
        "#${entry.number} ${entry.title} ${entry.headBranch} ${entry.author?.login.orEmpty()}"
          .lowercase()
          .contains(query)
    }
    .sortedByDescending { it.updatedAt }
  val limit: Int = input.limit?.toInt() ?: matches.size
  return PullRequestListResult(
    viewers = mapOf(FIXTURE_HOST to "xxfast"),
    providers = listOf(
      PullRequestProviderSummary(
        host = FIXTURE_HOST,
        kind = "github",
        searchesOnHost = true,
        projectCount = 1,
        configured = true,
        detail = null,
      ),
    ),
    entries = matches.take(limit),
    errors = emptyList(),
    truncated = matches.size > limit,
    nextCursors = emptyMap(),
  )
}

/**
 * A stand-in for `pullRequests.detail`: [ref]'s pull request out of [known], the listed ones
 * and the older ones the list never shows, so a number past the list still finds one.
 */
fun fixturePullRequestDetail(
  known: List<PullRequestListEntry>,
  ref: PullRequestRef,
): PullRequestDetail {
  val entry: PullRequestListEntry = known.find { entry ->
    entry.projectId == ref.projectId &&
      entry.repository.equals(ref.repository, ignoreCase = true) &&
      entry.number == ref.number
  } ?: throw T3Exception.Rpc("PullRequestNotFoundError", "No pull request #${ref.number}")
  return PullRequestDetail(
    provider = entry.provider,
    capabilities = PullRequestCapabilities(
      diff = true,
      comment = true,
      actions = emptyList(),
      mergeMethods = emptyList(),
      search = true,
      review = PullRequestReviewCapabilities(
        inlineComment = true,
        reply = true,
        resolve = true,
        verdicts = emptyList(),
      ),
      reviewers = PullRequestReviewerCapabilities(request = false, listCandidates = false),
    ),
    viewerPermissions = PullRequestViewerPermissions(
      actions = emptyList(),
      comment = true,
      resolve = true,
      verdicts = emptyList(),
      requestReviewers = false,
    ),
    projectId = entry.projectId,
    projectTitle = entry.projectTitle,
    workspaceRoot = "/fixture/${entry.projectTitle}",
    repository = entry.repository,
    number = entry.number,
    title = entry.title,
    body = "",
    url = entry.url,
    author = entry.author,
    state = entry.state,
    isDraft = entry.isDraft,
    mergeability = entry.mergeability,
    additions = entry.additions,
    deletions = entry.deletions,
    changedFiles = 0,
    headBranch = entry.headBranch,
    baseBranch = entry.baseBranch,
    createdAt = entry.createdAt,
    updatedAt = entry.updatedAt,
    mergedAt = null,
    closedAt = null,
    reviewers = emptyList(),
    labels = entry.labels,
    checks = emptyList(),
    mergeCapabilities = PullRequestMergeCapabilities(merge = true, squash = true, rebase = true),
  )
}

private const val FIXTURE_HOST: String = "github.com"
