package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.PullRequestListEntry
import io.github.xxfast.t3code.protocol.generated.PullRequestListResult
import io.github.xxfast.t3code.protocol.generated.PullRequestProviderSummary
import io.github.xxfast.t3code.protocol.generated.RepositoryIdentity
import io.github.xxfast.t3code.protocol.generated.RepositoryIdentityLocator
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestLink
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ComposerPullRequestLogicTest {
  private fun entry(
    number: Long,
    updatedAt: String,
    repository: String = "owner/repo",
    title: String = "Pull request $number",
  ) = ComposerPullRequest(
    projectId = "p1",
    repository = repository,
    number = number,
    url = "https://github.com/$repository/pull/$number",
    title = title,
    headBranch = "feature",
    baseBranch = "main",
    state = "open",
    isDraft = false,
    updatedAt = updatedAt,
  )

  private fun link(
    repository: String,
    number: Long,
    source: String = "agent",
  ) = ThreadPullRequestLink(
    host = "github.com",
    repository = repository,
    number = number,
    url = "https://github.com/$repository/pull/$number",
    source = source,
    linkedAt = "2026-01-01T00:00:00.000Z",
    snapshot = ThreadPullRequestSnapshot(
      state = "open",
      title = "$repository $number",
      headBranch = "feature",
      baseBranch = "main",
      isDraft = false,
      updatedAt = "2026-01-02T00:00:00.000Z",
      syncedAt = "2026-01-02T00:00:00.000Z",
    ),
    stack = null,
  )

  // filterComposerPullRequestMatches, mirroring
  // packages/shared/src/composerPullRequestMatches.test.ts

  @Test
  fun `ranks the exact number above newer substring matches`() {
    val result: List<ComposerPullRequest> = filterComposerPullRequestMatches(
      entries = listOf(
        entry(1234, "2026-01-03"),
        entry(123, "2026-01-01"),
        entry(1230, "2026-01-02"),
      ),
      linked = emptyList(),
      projectId = "p1",
      repository = "owner/repo",
      query = "123",
      limit = 10,
    )
    assertEquals(listOf(123L, 1234L, 1230L), result.map { it.number })
  }

  @Test
  fun `offers a linked pull request from another repository above the project's own`() {
    val result: List<ComposerPullRequest> = filterComposerPullRequestMatches(
      entries = listOf(entry(4, "2026-01-03"), entry(4, "2026-01-04", repository = "owner/other")),
      linked = composerPullRequestsFromLinks(listOf(link("owner/repo-b", 4)), "p1"),
      projectId = "p1",
      repository = "owner/repo",
      query = "4",
      limit = 10,
    )
    assertEquals(
      listOf("owner/repo-b#4", "owner/repo#4"),
      result.map { "${it.repository}#${it.number}" },
    )
  }

  @Test
  fun `keeps one row for a linked pull request the project listing already has`() {
    val result: List<ComposerPullRequest> = filterComposerPullRequestMatches(
      entries = listOf(entry(4, "2026-01-03")),
      linked = composerPullRequestsFromLinks(listOf(link("Owner/Repo", 4)), "p1"),
      projectId = "p1",
      repository = "owner/repo",
      query = "",
      limit = 10,
    )
    assertEquals(listOf(entry(4, "2026-01-03")), result)
  }

  @Test
  fun `a dismissed stack link is not offered`() {
    val links: List<ThreadPullRequestLink> =
      listOf(link("owner/repo", 4, source = "stack-dismissed"), link("owner/repo", 5))
    assertEquals(listOf(5L), composerPullRequestsFromLinks(links, "p1").map { it.number })
  }

  // rankPullRequestMatches, mirroring pullRequestList.logic.test.ts:229

  @Test
  fun `puts the pull request whose title says it above one that merely mentions it`() {
    val titled = entry(1, "2026-07-01T00:00:00Z", title = "Add the welcome wizard")
    val mentioned = entry(2, "2026-08-01T00:00:00Z", title = "Unrelated work")
    assertEquals(
      listOf(1L, 2L),
      rankPullRequestMatches(listOf(mentioned, titled), "welcome wizard").map { it.number },
    )
  }

  private fun listEntry(
    number: Long,
    title: String,
    updatedAt: String,
    repository: String = "owner/repo",
    host: String = "github.com",
  ) = PullRequestListEntry(
    provider = "github",
    host = host,
    projectId = "p1",
    projectTitle = "Repo",
    repository = repository,
    number = number,
    title = title,
    url = "https://$host/$repository/pull/$number",
    author = null,
    headBranch = "feature/$number",
    baseBranch = "main",
    state = "open",
    isDraft = false,
    mergeability = "unknown",
    additions = 0,
    deletions = 0,
    createdAt = updatedAt,
    updatedAt = updatedAt,
    viewerReviewRequested = false,
    labels = emptyList(),
  )

  private fun listed(
    entries: List<PullRequestListEntry>,
    searchesOnHost: Boolean,
  ) = PullRequestListResult(
    viewers = emptyMap(),
    providers = listOf(
      PullRequestProviderSummary(
        host = "github.com",
        kind = "github",
        searchesOnHost = searchesOnHost,
        projectCount = 1,
        configured = true,
        detail = null,
      ),
    ),
    entries = entries,
    errors = emptyList(),
    truncated = false,
    nextCursors = emptyMap(),
  )

  @Test
  fun `a text query ranks this repository's matches, at most twelve`() {
    val entries: List<PullRequestListEntry> =
      (1L..20L).map { listEntry(it, "Wizard step $it", "2026-01-%02d".format(it)) } +
        listEntry(30, "Add the wizard", "2025-12-01") +
        listEntry(31, "Wizard elsewhere", "2026-02-01", repository = "owner/other")
    val ranked: List<ComposerPullRequest> = composerPullRequestMatches(
      query = "add the wizard",
      list = listed(entries, searchesOnHost = true),
      exact = null,
      linked = emptyList(),
      projectId = "p1",
      repository = "owner/repo",
    )
    assertEquals(COMPOSER_PULL_REQUEST_RESULT_LIMIT, ranked.size)
    assertEquals(30L, ranked.first().number)
    assertTrue(ranked.none { it.repository == "owner/other" })
  }

  @Test
  fun `a host that doesn't search needs the row to match`() {
    val entries: List<PullRequestListEntry> =
      listOf(listEntry(1, "Wizard", "2026-01-01"), listEntry(2, "Unrelated", "2026-01-02"))
    val matches: List<ComposerPullRequest> = composerPullRequestMatches(
      query = "wizard",
      list = listed(entries, searchesOnHost = false),
      exact = null,
      linked = emptyList(),
      projectId = "p1",
      repository = "owner/repo",
    )
    assertEquals(listOf(1L), matches.map { it.number })
  }

  @Test
  fun `a numeric query counts the exact lookup`() {
    val matches: List<ComposerPullRequest> = composerPullRequestMatches(
      query = "7",
      list = listed(listOf(listEntry(17, "Other", "2026-01-02")), searchesOnHost = true),
      exact = entry(7, "2025-01-01"),
      linked = emptyList(),
      projectId = "p1",
      repository = "owner/repo",
    )
    assertEquals(listOf(7L, 17L), matches.map { it.number })
  }

  @Test
  fun `only a non-numeric query is sent, and only a positive number is looked up`() {
    fun trigger(query: String) = ComposerTrigger(ComposerTriggerKind.PULL_REQUEST, query, 0, 1)
    assertEquals("fix", composerPullRequestTextQuery(trigger("fix")))
    assertNull(composerPullRequestTextQuery(trigger("12")))
    assertNull(composerPullRequestTextQuery(trigger("")))
    assertEquals(12L, composerPullRequestNumber(trigger("12")))
    assertNull(composerPullRequestNumber(trigger("0")))
    assertNull(composerPullRequestNumber(trigger("fix")))
    assertNull(composerPullRequestNumber(trigger("99999999999999999999")))
    assertNull(composerPullRequestNumber(ComposerTrigger(ComposerTriggerKind.PATH, "12", 0, 1)))
  }

  @Test
  fun `a row from another repository names it`() {
    assertEquals("Pull request 4", composerPullRequestDescription(entry(4, "x"), "Owner/Repo"))
    assertEquals(
      "owner/other · Pull request 4",
      composerPullRequestDescription(entry(4, "x", repository = "owner/other"), "owner/repo"),
    )
  }

  // sourceControlRepositorySelector, packages/shared/src/sourceControl.ts:287

  private fun identity(
    provider: String?,
    displayName: String?,
    owner: String? = null,
    name: String? = null,
  ) = RepositoryIdentity(
    canonicalKey = "key",
    locator = RepositoryIdentityLocator("git-remote", "origin", "url"),
    displayName = displayName,
    provider = provider,
    owner = owner,
    name = name,
  )

  @Test
  fun `the repository selector is the display name, owner and name, or azure's bare name`() {
    assertNull(sourceControlRepositorySelector(null))
    val github: RepositoryIdentity = identity("github", "xxfast/KStore")
    assertEquals("xxfast/KStore", sourceControlRepositorySelector(github))
    assertEquals("o/n", sourceControlRepositorySelector(identity("gitlab", null, "o", "n")))
    assertNull(sourceControlRepositorySelector(identity("gitlab", null, "o", null)))
    assertEquals(
      "repo",
      sourceControlRepositorySelector(identity("azure-devops", "org/project/_git/repo")),
    )
  }

  // buildPullRequestReferenceContext, pullRequestDetail.logic.ts:1095

  @Test
  fun `a pull request reference is a review comment labelled by its number`() {
    val metadata = PullRequestContextMetadata(
      number = 42,
      title = "Fix  the\nbug",
      url = "https://github.com/owner/repo/pull/42",
      headBranch = "fix/bug",
      baseBranch = "main",
      state = "open",
      isDraft = true,
    )
    val comment: ReviewCommentContext = buildPullRequestReferenceContext(metadata)
    assertEquals("pr-reference:https://github.com/owner/repo/pull/42", comment.id)
    assertEquals("pull-request:42", comment.sectionId)
    assertEquals("PR #42", comment.filePath)
    assertEquals("Fix the bug", comment.rangeLabel)
    assertEquals("Fix the bug", comment.pullRequest?.title)
    assertTrue(comment.text.startsWith("The pull request is #42, titled `Fix the bug`"))
    assertEquals("#42", comment.label)
    assertTrue(reviewCommentReference(comment).startsWith("[#42](t3-context://v1/review-comment/"))
    val record: JsonObject = comment.toRecord()
    val pullRequest: JsonObject = record.getValue("pullRequest").jsonObject
    assertEquals(JsonPrimitive(42), pullRequest["number"])
    assertEquals(JsonPrimitive(true), pullRequest["isDraft"])
    assertEquals(JsonPrimitive("#42"), record["label"])
  }

  @Test
  fun `the empty state says why there is nothing`() {
    assertEquals(
      "Pull requests are not available for this project.",
      composerPullRequestEmptyState(available = false, failed = false, query = ""),
    )
    assertEquals(
      "Pull requests could not be read for this project.",
      composerPullRequestEmptyState(available = true, failed = true, query = ""),
    )
    assertEquals("No pull request matches 12.", composerPullRequestEmptyState(true, false, "12"))
    assertEquals(
      "No pull requests found in this repository.",
      composerPullRequestEmptyState(true, false, ""),
    )
  }
}
