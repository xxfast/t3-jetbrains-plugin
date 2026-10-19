package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.DelegatedFrom
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import io.github.xxfast.t3code.protocol.generated.ThreadLinkedPullRequest
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestLink
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestSnapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SidebarLogicTest {
  @Test
  fun `active is a run in flight or a running status`() {
    assertTrue(thread(activeRunId = "r").active)
    assertTrue(thread(status = "queued").active)
    assertFalse(thread(status = "completed").active)
    assertFalse(thread(status = "waiting").active)
  }

  @Test
  fun `waiting on you`() {
    assertTrue(thread(status = "waiting").waitingOnYou)
    assertTrue(thread(status = "running", pending = true).waitingOnYou)
    assertFalse(thread(status = "running").waitingOnYou)
  }

  @Test
  fun `settled is the server's override, a settledAt stamp alone isn't`() {
    assertFalse(thread().settled, "An idle thread that just finished a turn stays in the inbox")
    assertFalse(thread(settledAt = EPOCH).settled)
    assertTrue(thread(settledAt = EPOCH, settledOverride = "settled").settled)
    assertFalse(thread(settledAt = EPOCH, settledOverride = "active").settled)
  }

  @Test
  fun `subagents and delegated children with a listed parent are hidden`() {
    val threads: List<Thread> = listOf(
      thread("parent"),
      thread("sub", relationship = "subagent"),
      thread("task", delegatedFrom = "parent"),
      thread("orphan", delegatedFrom = "elsewhere"),
    )
    assertEquals(listOf("orphan", "parent"), sections(threads, "").inbox.map { it.id })
  }

  @Test
  fun `sections split settled from the inbox and drop archived`() {
    val threads: List<Thread> = listOf(
      thread("idle"),
      thread("done", settledOverride = "settled"),
      thread("running", status = "running"),
      thread("archived", archivedAt = "2026-10-19T00:00:00Z"),
      thread("busy", activeRunId = "r"),
    )
    val sections: ThreadSections = sections(threads, "")
    assertEquals(setOf("idle", "running", "busy"), sections.inbox.map { it.id }.toSet())
    assertEquals(listOf("done"), sections.settled.map { it.id })
  }

  @Test
  fun `inbox is pinned by order key, then active newest first`() {
    val threads: List<Thread> = listOf(
      thread("old", createdAt = "2026-10-19T08:00:00Z"),
      thread("new", createdAt = "2026-10-19T09:00:00Z"),
      thread("woken", createdAt = "2026-10-01T00:00:00Z", unsettledAt = "2026-10-19T10:00:00Z"),
      thread("keyed", createdAt = "2026-10-19T11:00:00Z", activeOrderKey = "a"),
      thread("pin-b", pinnedAt = EPOCH, pinOrderKey = "b"),
      thread("pin-a", pinnedAt = EPOCH, pinOrderKey = "a"),
      thread("pin-keyless", pinnedAt = EPOCH, createdAt = "2026-10-19T12:00:00Z"),
    )
    assertEquals(
      listOf("pin-a", "pin-b", "pin-keyless", "woken", "new", "old", "keyed"),
      sections(threads, "").inbox.map { it.id },
    )
  }

  @Test
  fun `settled newest settled first, falling back to last activity`() {
    val threads: List<Thread> = listOf(
      thread("old", settledOverride = "settled", settledAt = "2026-10-01T00:00:00Z"),
      thread("new", settledOverride = "settled", settledAt = "2026-10-18T00:00:00Z"),
      thread(
        "active-later", settledOverride = "settled", updatedAt = "2026-09-01T00:00:00Z",
        latestUserMessageAt = "2026-10-19T00:00:00Z",
      ),
    )
    assertEquals(
      listOf("active-later", "new", "old"),
      sections(threads, "").settled.map { it.id },
    )
  }

  @Test
  fun `row times`() {
    val user: String = "2026-10-19T08:00:00Z"
    val updated: String = "2026-10-19T09:00:00Z"
    val spoke: Thread = thread(updatedAt = updated, latestUserMessageAt = user)
    assertEquals(Instant.parse(user), spoke.inboxAt())
    assertEquals(Instant.parse(updated), thread(updatedAt = updated).inboxAt())
    assertEquals(Instant.parse(user), spoke.settledAt())
    assertEquals(Instant.parse(EPOCH), thread(updatedAt = updated, settledAt = EPOCH).settledAt())
  }

  @Test
  fun `search matches title case-insensitively`() {
    val threads: List<Thread> = listOf(
      thread("a", title = "Fix Predictive Back", settledOverride = "settled"),
      thread("b", title = "Release notes"),
      thread("c", title = "Back to basics", status = "running"),
    )
    val sections: ThreadSections = sections(threads, "  back ")
    assertEquals(listOf("c"), sections.inbox.map { it.id })
    assertEquals(listOf("a"), sections.settled.map { it.id })
  }

  @Test
  fun `show more reveals a page at a time`() {
    assertEquals(0, moreCount(total = 8, shown = 10))
    assertEquals(4, moreCount(total = 14, shown = 10))
    assertEquals(25, moreCount(total = 100, shown = 10))
    assertEquals(0, moreCount(total = 35, shown = 35))
  }

  @Test
  fun `run start falls back to updatedAt`() {
    val started: String = "2026-10-19T08:00:00Z"
    assertEquals(Instant.parse(started), thread(runStartedAt = started).runStartedAt())
    val updated: String = "2026-10-19T09:00:00Z"
    assertEquals(Instant.parse(updated), thread(updatedAt = updated).runStartedAt())
  }

  @Test
  fun `durations`() {
    assertEquals("0s", duration(Duration.ofSeconds(-5)))
    assertEquals("22s", duration(Duration.ofSeconds(22)))
    assertEquals("1m 12s", duration(Duration.ofSeconds(72)))
    assertEquals("2m", duration(Duration.ofMinutes(2)))
    assertEquals("2h 5m", duration(Duration.ofMinutes(125)))
    assertEquals("3h", duration(Duration.ofHours(3).plusSeconds(20)))
  }

  @Test
  fun `pull request badge picks the open link, a stack, or the latest`() {
    assertEquals(null, thread().pullRequestBadge())
    val one: Thread = thread(pullRequests = listOf(link(564, "open")))
    assertEquals(PullRequestBadge("open", "564", "https://pr/564"), one.pullRequestBadge())
    val draft: Thread = thread(pullRequests = listOf(link(7, "open", draft = true)))
    assertEquals("draft", draft.pullRequestBadge()?.state)
    val stack: Thread = thread(pullRequests = listOf(link(1, "open"), link(2, "open")))
    assertEquals(PullRequestBadge("stack", "2", "https://pr/1"), stack.pullRequestBadge())
    val done: Thread = thread(
      pullRequests = listOf(
        link(3, "merged", updatedAt = "2026-10-01T00:00:00Z"),
        link(4, "closed", updatedAt = "2026-10-02T00:00:00Z"),
        link(5, "merged", updatedAt = "2026-09-01T00:00:00Z", source = "stack-dismissed"),
      ),
    )
    assertEquals(PullRequestBadge("closed", "4", "https://pr/4"), done.pullRequestBadge())
    val legacy: Thread =
      BASE_THREAD.copy(linkedPullRequest = ThreadLinkedPullRequest("p", "r", 9, "u"))
    assertEquals(PullRequestBadge("pending", "9", "u"), legacy.pullRequestBadge())
  }

  @Test
  fun `relative time`() {
    val now: Instant = Instant.parse("2026-10-19T12:00:00Z")
    fun ago(at: String): String = relativeTime(Instant.parse(at), now)
    assertEquals("just now", ago("2026-10-19T11:59:30Z"))
    assertEquals("just now", ago("2026-10-19T12:05:00Z"))
    assertEquals("5m", ago("2026-10-19T11:55:00Z"))
    assertEquals("3h", ago("2026-10-19T09:00:00Z"))
    assertEquals("2d", ago("2026-10-17T11:00:00Z"))
    assertEquals("16d", ago("2026-10-03T08:00:00Z"))
  }

  @Test
  fun `unread is a run completed after the last visit`() {
    assertTrue(thread(completedAt = "2026-10-19T12:00:00Z", visitedAt = TEN_TO).unread)
    assertFalse(thread(completedAt = TEN_TO, visitedAt = "2026-10-19T12:00:00Z").unread)
    assertFalse(thread(completedAt = TEN_TO, visitedAt = TEN_TO).unread, "Equal is seen")
  }

  @Test
  fun `unread without a visit, a completion, or a parsable stamp`() {
    assertFalse(thread(completedAt = TEN_TO).unread, "No visit on record reads as seen")
    assertFalse(thread(visitedAt = TEN_TO).unread, "Nothing completed yet")
    assertTrue(thread(completedAt = TEN_TO, visitedAt = "garbage").unread)
    assertFalse(thread(completedAt = "garbage", visitedAt = TEN_TO).unread)
    assertFalse(thread(completedAt = "garbage", visitedAt = "garbage").unread)
  }

  @Test
  fun `visit when the row moved past the last visit`() {
    val behind: Thread = thread(updatedAt = "2026-10-19T12:00:00Z", visitedAt = TEN_TO)
    assertTrue(shouldVisit(behind, lastDispatched = null))
    assertTrue(shouldVisit(behind, lastDispatched = "t:2026-10-19T11:00:00Z"))
    assertFalse(shouldVisit(behind, lastDispatched = behind.visitKey), "Once per watermark")
    val caughtUp: Thread = thread(updatedAt = TEN_TO, visitedAt = TEN_TO)
    assertFalse(shouldVisit(caughtUp, lastDispatched = null))
    assertTrue(shouldVisit(thread(updatedAt = TEN_TO, visitedAt = "garbage"), null))
  }

  @Test
  fun `a never visited thread gets a visit, a row without a time doesn't`() {
    val never: Thread = thread(updatedAt = TEN_TO)
    assertTrue(shouldVisit(never, lastDispatched = null))
    assertFalse(shouldVisit(never, lastDispatched = never.visitKey))
    assertFalse(shouldVisit(thread(updatedAt = "garbage", visitedAt = EPOCH), null))
  }

  @Test
  fun `the watermark reads a missing or unparsable visit as unseen`() {
    assertTrue(unseenCompletion(thread(completedAt = TEN_TO)))
    assertTrue(unseenCompletion(thread(completedAt = TEN_TO, visitedAt = "garbage")))
    assertTrue(unseenCompletion(thread(completedAt = TEN_TO, visitedAt = EPOCH)))
    assertFalse(unseenCompletion(thread(completedAt = TEN_TO, visitedAt = TEN_TO)))
    assertFalse(unseenCompletion(thread(visitedAt = EPOCH)), "Nothing completed")
    assertFalse(unseenCompletion(thread(completedAt = "garbage")))
    assertFalse(thread(completedAt = TEN_TO).unread, "The Sidebar's rule differs on purpose")
  }

  @Test
  fun `visits wait out the throttle unless a completion is unseen`() {
    val behind: Thread = thread(updatedAt = "2026-10-19T12:00:00Z", visitedAt = TEN_TO)
    assertEquals(Duration.ofSeconds(7), visitDelay(behind, Duration.ofSeconds(3)))
    assertEquals(Duration.ZERO, visitDelay(behind, Duration.ofSeconds(10)))
    val unseen: Thread = behind.copy(latestRunCompletedAt = "2026-10-19T12:00:00Z")
    assertEquals(Duration.ZERO, visitDelay(unseen, Duration.ZERO))
    val neverVisited: Thread = thread(updatedAt = TEN_TO, completedAt = TEN_TO)
    assertEquals(Duration.ZERO, visitDelay(neverVisited, Duration.ZERO))
  }

  @Test
  fun `card status puts limited and failed ahead of done`() {
    val unread: Thread = thread(completedAt = "2026-10-19T12:00:00Z", visitedAt = TEN_TO)
    assertEquals(CardStatus.Done, unread.cardStatus())
    assertEquals(CardStatus.Failed, unread.copy(status = "failed").cardStatus())
    val limited: Thread = unread.copy(status = "failed", lastErrorClass = "usage_limit")
    assertEquals(CardStatus.Limited, limited.cardStatus())
    assertEquals(null, thread().cardStatus())
  }

  private fun thread(
    id: String = "t",
    title: String = id,
    status: String = "idle",
    activeRunId: String? = null,
    runStartedAt: String? = null,
    createdAt: String = EPOCH,
    updatedAt: String = EPOCH,
    archivedAt: String? = null,
    pinnedAt: String? = null,
    pinOrderKey: String? = null,
    activeOrderKey: String? = null,
    unsettledAt: String? = null,
    settledAt: String? = null,
    settledOverride: String? = null,
    latestUserMessageAt: String? = null,
    relationship: String? = null,
    delegatedFrom: String? = null,
    pending: Boolean = false,
    pullRequests: List<ThreadPullRequestLink>? = null,
    completedAt: String? = null,
    visitedAt: String? = null,
  ): Thread = BASE_THREAD.copy(
    latestRunCompletedAt = completedAt,
    lastVisitedAt = visitedAt,
    id = id,
    title = title,
    status = status,
    activeRunId = activeRunId,
    activityRunStartedAt = runStartedAt,
    archivedAt = archivedAt,
    pinnedAt = pinnedAt,
    pinOrderKey = pinOrderKey,
    activeOrderKey = activeOrderKey,
    unsettledAt = unsettledAt,
    settledAt = settledAt,
    settledOverride = settledOverride,
    latestUserMessageAt = latestUserMessageAt,
    lineage = BASE_THREAD.lineage.copy(relationshipToParent = relationship),
    delegatedFrom = delegatedFrom?.let { DelegatedFrom("env", it, "parent") },
    createdAt = createdAt,
    pullRequests = pullRequests,
    pendingRuntimeRequest =
      if (pending) PendingRuntimeRequestSummary("r", "command", EPOCH) else null,
    updatedAt = updatedAt,
  )
}

private fun link(
  number: Long,
  state: String,
  draft: Boolean = false,
  updatedAt: String = EPOCH,
  source: String = "manual",
): ThreadPullRequestLink = ThreadPullRequestLink(
  host = "github.com",
  repository = "xxfast/t3-jetbrains-plugin",
  number = number,
  url = "https://pr/$number",
  source = source,
  linkedAt = EPOCH,
  snapshot = ThreadPullRequestSnapshot(
    state = state, title = "PR $number", headBranch = "h", baseBranch = "main", isDraft = draft,
    updatedAt = updatedAt, syncedAt = EPOCH,
  ),
  stack = null,
)

private const val EPOCH: String = "1970-01-01T00:00:00Z"
private const val TEN_TO: String = "2026-10-19T11:50:00Z"

/** A thread row with only what upstream requires. Tests copy it with the fields they care about. */
private val BASE_THREAD: Thread = T3Json.decodeFromString(
  Thread.serializer(),
  """
    {
      "id": "t", "projectId": "kstore", "title": "t", "status": "idle",
      "createdBy": "user", "creationSource": "web", "providerInstanceId": "codex",
      "modelSelection": { "model": "gpt-5" }, "runtimeMode": "full-access",
      "interactionMode": "default", "lineage": { "rootThreadId": "t" },
      "hasActionableProposedPlan": false, "itemCount": 0, "visibleItemCount": 0,
      "createdAt": "$EPOCH", "updatedAt": "$EPOCH"
    }
  """,
)
