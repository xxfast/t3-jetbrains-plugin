package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import io.github.xxfast.t3code.protocol.generated.ThreadLinkedPullRequest
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestLink
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestSnapshot
import io.github.xxfast.t3code.protocol.generated.ThreadSearchMatch
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
  fun `subagents are hidden`() {
    val threads: List<Thread> = listOf(thread("parent"), thread("sub", relationship = "subagent"))
    assertEquals(listOf("parent"), sections(threads).inbox.map { it.id })
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
    val sections: ThreadSections = sections(threads)
    assertEquals(setOf("idle", "running", "busy"), sections.inbox.map { it.id }.toSet())
    assertEquals(listOf("done"), sections.settled.map { it.id })
  }

  @Test
  fun `without settlement on the server settled rows stay in the inbox`() {
    val threads: List<Thread> = listOf(thread("idle"), thread("done", settledOverride = "settled"))
    val sections: ThreadSections = sections(threads, settlement = false)
    assertEquals(setOf("idle", "done"), sections.inbox.map { it.id }.toSet())
    assertEquals(emptyList(), sections.settled)
  }

  @Test
  fun `a snooze outranks settled and pinned, soonest to wake first`() {
    val now: Instant = Instant.parse("2026-10-19T12:00:00Z")
    val threads: List<Thread> = listOf(
      thread("later").copy(snoozedUntil = "2026-10-21T09:00:00Z"),
      thread("sooner", settledOverride = "settled", pinnedAt = EPOCH)
        .copy(snoozedUntil = "2026-10-20T09:00:00Z"),
      thread("woken").copy(snoozedUntil = "2026-10-19T09:00:00Z"),
    )
    val sections: ThreadSections = sections(threads, now = now)
    assertEquals(listOf("sooner", "later"), sections.snoozed.map { it.id })
    assertEquals(listOf("woken"), sections.inbox.map { it.id })
    val unsupported: ThreadSections = sections(threads, snooze = false, now = now)
    assertEquals(emptyList(), unsupported.snoozed)
    assertEquals(listOf("sooner"), unsupported.settled.map { it.id })
  }

  @Test
  fun `woke sits after failed and before done, and keeps the row from receding`() {
    assertEquals(TopStatus.Woke, thread().topStatus(woke = true))
    val failed: Thread = thread(status = "failed", latestRunId = "r")
    assertEquals(TopStatus.Failed, failed.topStatus(woke = true))
    assertTrue(thread().recedes(open = false))
    assertFalse(thread().recedes(open = false, woke = true))
  }

  @Test
  fun `settling the open card moves to the next one, wrapping round`() {
    val inbox: List<Thread> = listOf(thread("a"), thread("b"), thread("c"))
    assertEquals("c", nextCard(inbox, "b"))
    assertEquals("a", nextCard(inbox, "c"))
    assertNull(nextCard(inbox, "settled"), "Not a card, nowhere to go")
    assertNull(nextCard(listOf(thread("a")), "a"), "The only card")
  }

  @Test
  fun `previous and next step one row and stop at the ends`() {
    val ids: List<String> = listOf("a", "b", "c")
    val previous: ThreadTraversalDirection = ThreadTraversalDirection.Previous
    val next: ThreadTraversalDirection = ThreadTraversalDirection.Next
    assertEquals("a", resolveAdjacentThreadId(ids, "b", previous))
    assertEquals("c", resolveAdjacentThreadId(ids, "b", next))
    assertNull(resolveAdjacentThreadId(ids, "a", previous), "No wrap at the top")
    assertNull(resolveAdjacentThreadId(ids, "c", next), "No wrap at the bottom")
    assertEquals("c", resolveAdjacentThreadId(ids, null, previous), "Nothing open: the last")
    assertEquals("a", resolveAdjacentThreadId(ids, null, next), "Nothing open: the first")
    assertNull(resolveAdjacentThreadId(ids, "gone", next), "The open thread isn't a row")
    assertNull(resolveAdjacentThreadId(emptyList(), null, next))
  }

  @Test
  fun `an open subagent stands for its nearest row`() {
    fun child(id: String, parent: String?): Thread = thread(id, relationship = "subagent")
      .let { it.copy(lineage = it.lineage.copy(parentThreadId = parent)) }
    val threads: List<Thread> =
      listOf(thread("root"), child("sub", "root"), child("subsub", "sub"), child("orphan", null))
    assertEquals("root", resolveSidebarRowThreadId("subsub", threads))
    assertEquals("root", resolveSidebarRowThreadId("root", threads))
    assertEquals("orphan", resolveSidebarRowThreadId("orphan", threads), "Broken chain: itself")
    assertEquals("unknown", resolveSidebarRowThreadId("unknown", threads))
    val cycle: List<Thread> = listOf(child("x", "y"), child("y", "x"))
    assertEquals("x", resolveSidebarRowThreadId("x", cycle), "A cycle: itself")
  }

  @Test
  fun `navigation walks the rows on screen, a closed shelf only keeping the open one`() {
    val sections = ThreadSections(
      inbox = listOf(thread("p"), thread("a")),
      settled = listOf(thread("s1"), thread("s2"), thread("s3")),
      snoozed = listOf(thread("z1"), thread("z2")),
    )
    val open = Shelves(settled = true, snoozed = true)
    assertEquals(
      listOf("p", "a", "z1", "z2", "s1", "s2", "s3"),
      orderedThreadIds(sections, open, settledShown = 10, routeRowThreadId = null),
    )
    assertEquals(
      listOf("p", "a", "z1", "z2", "s1"),
      orderedThreadIds(sections, open, settledShown = 1, routeRowThreadId = null),
      "Only the settled page",
    )
    assertEquals(
      listOf("p", "a", "z1", "z2", "s1", "s3"),
      orderedThreadIds(sections, open, settledShown = 1, routeRowThreadId = "s3"),
      "The open row past the page comes along",
    )
    assertEquals(
      listOf("p", "a", "z2"),
      orderedThreadIds(sections, Shelves(), settledShown = 10, routeRowThreadId = "z2"),
      "Closed shelves keep only the open row",
    )
    assertEquals(
      listOf("p", "a", "s3"),
      orderedThreadIds(sections, Shelves(), settledShown = 1, routeRowThreadId = "s3"),
      "Even past the page",
    )
  }

  @Test
  fun `jump hints want exactly a jump shortcut's modifiers`() {
    val ctrlAlt = ShortcutModifiers(ctrl = true, alt = true)
    val bound: List<ShortcutModifiers> = listOf(ctrlAlt, ShortcutModifiers(meta = true))
    assertTrue(shouldShowThreadJumpHints(ctrlAlt, bound))
    assertTrue(shouldShowThreadJumpHints(ShortcutModifiers(meta = true), bound))
    assertFalse(shouldShowThreadJumpHints(ShortcutModifiers(ctrl = true), bound), "Not all held")
    assertFalse(
      shouldShowThreadJumpHints(ctrlAlt.copy(shift = true), bound),
      "One too many held",
    )
    assertFalse(shouldShowThreadJumpHints(ctrlAlt, emptyList()), "No jump bound")
  }

  @Test
  fun `jump hints show after a hold and hide at once`() = runTest {
    val hints = ThreadJumpHintVisibility(this)
    hints.sync(true)
    advanceTimeBy(THREAD_JUMP_HINT_SHOW_DELAY_MS - 1)
    assertFalse(hints.visible.value, "Not yet")
    hints.sync(true)
    advanceTimeBy(2)
    assertTrue(hints.visible.value, "A second sync doesn't restart the wait")
    hints.sync(false)
    assertFalse(hints.visible.value)
    hints.sync(true)
    advanceTimeBy(THREAD_JUMP_HINT_SHOW_DELAY_MS / 2)
    hints.sync(false)
    advanceTimeBy(THREAD_JUMP_HINT_SHOW_DELAY_MS)
    assertFalse(hints.visible.value, "Let go before the delay")
  }

  @Test
  fun `a bulk settle moves on past the cards settling with it`() {
    val inbox: List<Thread> = listOf(thread("a"), thread("b"), thread("c"), thread("d"))
    assertEquals("d", nextCard(inbox, "b", coParking = setOf("b", "c")))
    assertNull(nextCard(inbox, "a", coParking = setOf("a", "b", "c", "d")))
  }

  @Test
  fun `a closed shelf counts its rows`() {
    assertEquals("Settled", shelfLabel("Settled", open = true, count = 3))
    assertEquals("Settled (3)", shelfLabel("Settled", open = false, count = 3))
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
      sections(threads).inbox.map { it.id },
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
      sections(threads).settled.map { it.id },
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
  fun `search matches title case-insensitively, in the list's order`() {
    val threads: List<Thread> = listOf(
      thread("c", title = "Back to basics", status = "running"),
      thread("b", title = "Release notes"),
      thread("a", title = "Fix Predictive Back", settledOverride = "settled"),
    )
    assertEquals(listOf("c", "a"), searchSidebarThreads(threads, "  back ").map { it.id })
    assertTrue(searchSidebarThreads(threads, "  ").isEmpty())
  }

  @Test
  fun `search matches a pull request's number, repository and title`() {
    val threads: List<Thread> = listOf(
      thread("a", title = "Release", pullRequests = listOf(link(212, "open"))),
      thread("b", title = "Other"),
    )
    assertEquals(listOf("a"), searchSidebarThreads(threads, "#212").map { it.id })
    assertEquals(listOf("a"), searchSidebarThreads(threads, "pr 212").map { it.id })
    assertEquals(
      listOf("a"),
      searchSidebarThreads(threads, "t3-jetbrains-plugin#212").map { it.id },
    )
    assertTrue(searchSidebarThreads(threads, "#213").isEmpty())
  }

  @Test
  fun `a dismissed stack member isn't searched, a legacy link is without links`() {
    val dismissed: Thread =
      thread("a", pullRequests = listOf(link(7, "open", source = "stack-dismissed")))
    assertTrue(searchSidebarThreads(listOf(dismissed), "#7").isEmpty())
    val legacy: Thread = thread("b", pullRequests = emptyList())
      .copy(linkedPullRequest = ThreadLinkedPullRequest("kstore", "xxfast/KStore", 9, "u"))
    assertEquals(listOf("#9", "xxfast/KStore#9", "u"), threadPullRequestSearchTerms(legacy))
    val branch: Thread = thread("c", pullRequests = listOf(link(3, "open")))
      .copy(branchPullRequest = ThreadLinkedPullRequest("kstore", "xxfast/KStore", 2, "v"))
    assertEquals("#2", threadPullRequestSearchTerms(branch).first())
    assertTrue("PR 3" in threadPullRequestSearchTerms(branch))
  }

  @Test
  fun `message matches follow title matches, once each`() {
    val threads: List<Thread> = listOf(
      thread("a", title = "Nothing here"),
      thread("b", title = "Codec docs"),
      thread("c", title = "Also nothing"),
    )
    val results: List<Thread> = searchSidebarThreads(threads, "codec", setOf("c", "b", "x"))
    assertEquals(listOf("b", "c"), results.map { it.id })
  }

  @Test
  fun `the pull request badge shows when every word matches, accents aside`() {
    val row: Thread = thread("a", pullRequests = listOf(link(212, "open")))
    assertTrue(pullRequestMatchesSearchQuery(row, "#212"))
    assertTrue(pullRequestMatchesSearchQuery(row, "pr  212"))
    assertFalse(pullRequestMatchesSearchQuery(row, "pr 213"))
    assertFalse(pullRequestMatchesSearchQuery(row, " "))
    assertEquals("cafe", normalizeSearchText(" Café "))
  }

  @Test
  fun `the highlight wraps and resets when the results reorder`() {
    assertEquals(1, moveSearchHighlight(0, 3, down = true))
    assertEquals(0, moveSearchHighlight(2, 3, down = true))
    assertEquals(2, moveSearchHighlight(0, 3, down = false))
    assertNull(moveSearchHighlight(0, 0, down = true))
    val highlight = SearchHighlight(orderKey = "a b", index = 1)
    assertEquals(1, highlight.activeIn("a b"))
    assertEquals(0, highlight.activeIn("b a"))
  }

  @Test
  fun `an excerpt highlights every match, folding only ASCII case`() {
    assertEquals(
      listOf("Use " to false, "Codec" to true, " and " to false, "codec" to true),
      highlightSearchText("Use Codec and codec", " codec "),
    )
    assertEquals(listOf("plain" to false), highlightSearchText("plain", ""))
  }

  @Test
  fun `message search debounces, needs two characters and swallows failures`() = runTest {
    val asked: MutableList<String> = mutableListOf()
    val queries = MutableStateFlow("")
    val states: MutableList<ThreadSearch> = mutableListOf()
    val job = launch {
      threadSearch(queries) { query ->
        asked += query
        if (query == "boom") error("down")
        listOf(ThreadSearchMatch("t", "p", "user", "a $query b", null))
      }.toList(states)
    }
    runCurrent()
    queries.value = "c"
    runCurrent()
    queries.value = "co"
    runCurrent()
    advanceTimeBy(THREAD_SEARCH_DEBOUNCE_MS / 2)
    queries.value = " cod "
    runCurrent()
    assertEquals(ThreadSearch(pending = true), states.last())
    advanceTimeBy(THREAD_SEARCH_DEBOUNCE_MS + 1)
    assertEquals(listOf("cod"), asked, "The keystroke before it never went out")
    assertEquals("a cod b", states.last().matches.single().snippet)
    assertFalse(states.last().pending)
    queries.value = "boom"
    advanceTimeBy(THREAD_SEARCH_DEBOUNCE_MS + 1)
    assertEquals(ThreadSearch(), states.last())
    queries.value = ""
    runCurrent()
    assertEquals(ThreadSearch(), states.last())
    job.cancel()
  }

  @Test
  fun `show more reveals a page at a time`() {
    assertEquals(0, moreCount(total = 8, shown = 10))
    assertEquals(4, moreCount(total = 14, shown = 10))
    assertEquals(25, moreCount(total = 100, shown = 10))
    assertEquals(0, moreCount(total = 35, shown = 35))
  }

  @Test
  fun `working clock starts at the activity run, else the unfinished active run`() {
    val started: String = "2026-10-19T08:00:00Z"
    assertEquals(Instant.parse(started), thread(runStartedAt = started).workingStartedAt())
    val active: Thread = thread(activeRunId = "r", latestRunId = "r")
      .copy(latestRunStartedAt = started)
    assertEquals(Instant.parse(started), active.workingStartedAt())
    assertEquals(null, active.copy(latestRunCompletedAt = started).workingStartedAt())
    assertEquals(null, thread(updatedAt = started).workingStartedAt())
  }

  @Test
  fun `waiting clock starts when the last run completed, else at updatedAt`() {
    val done: String = "2026-10-19T08:00:00Z"
    val updated: String = "2026-10-19T09:00:00Z"
    assertEquals(null, thread(updatedAt = updated).waitingSince())
    val idle: Thread = thread(latestRunId = "r", updatedAt = updated)
    assertEquals(Instant.parse(updated), idle.waitingSince())
    assertEquals(Instant.parse(done), idle.copy(latestRunCompletedAt = done).waitingSince())
  }

  @Test
  fun `card clock is seconds, then minutes, then hours and minutes`() {
    assertEquals("0s", workingDuration(Duration.ofSeconds(-5)))
    assertEquals("22s", workingDuration(Duration.ofSeconds(22)))
    assertEquals("1m", workingDuration(Duration.ofSeconds(72)))
    assertEquals("2h 5m", workingDuration(Duration.ofMinutes(125)))
    assertEquals("3h 0m", workingDuration(Duration.ofHours(3).plusSeconds(20)))
  }

  @Test
  fun `sidebar status mirrors upstream's precedence`() {
    assertEquals(ThreadStatus.Ready, thread().sidebarStatus(), "no run, no runtime")
    assertEquals(ThreadStatus.Approval, thread(status = "running", pending = true).sidebarStatus())
    val input: Thread = thread().copy(
      pendingRuntimeRequest = PendingRuntimeRequestSummary("r", "user_input", EPOCH),
    )
    assertEquals(ThreadStatus.Input, input.sidebarStatus())
    val auth: Thread = thread().copy(
      pendingRuntimeRequest = PendingRuntimeRequestSummary("r", "auth_refresh", EPOCH),
    )
    assertEquals(ThreadStatus.Ready, auth.sidebarStatus(), "auth refresh is the provider's own")
    fun ran(status: String): Thread = thread(status = status, latestRunId = "r")
    assertEquals(ThreadStatus.Working, ran("running").sidebarStatus())
    assertEquals(ThreadStatus.Working, ran("waiting").sidebarStatus())
    assertEquals(ThreadStatus.Waiting, ran("idle").sidebarStatus())
    assertEquals(ThreadStatus.Ready, ran("completed").sidebarStatus())
    assertEquals(ThreadStatus.Failed, ran("failed").sidebarStatus())
    val limited: Thread = ran("failed").copy(lastErrorClass = "usage_limit")
    assertEquals(ThreadStatus.Limited, limited.sidebarStatus())
    assertEquals(ThreadStatus.Ready, thread(status = "failed").sidebarStatus(), "no runtime")
  }

  @Test
  fun `rows recede unless open, asking, failed or unread`() {
    assertFalse(thread().recedes(open = true))
    assertTrue(thread().recedes(open = false), "a quiet ready row")
    assertTrue(thread(status = "running", latestRunId = "r").recedes(open = false))
    assertFalse(thread(status = "failed", latestRunId = "r").recedes(open = false))
    val unread: Thread = thread(completedAt = "2026-10-19T12:00:00Z", visitedAt = TEN_TO)
    assertFalse(unread.recedes(open = false))
  }

  @Test
  fun `provider stack is the last two earlier instances then the current`() {
    val hopped: Thread = thread().copy(providerInstanceHistory = listOf("a", "b", "codex", "c"))
    assertEquals(listOf("b", "c", "codex"), hopped.providerStack())
    assertEquals(listOf("codex"), thread().providerStack())
    assertEquals("CL", providerInitials("Claude"))
    assertEquals("CA", providerInitials("claude_agent"))
    assertEquals("", providerInitials("  "))
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
    assertEquals("now", compactTime(Instant.parse("2026-10-19T11:59:30Z"), now))
    assertEquals("5m", ago("2026-10-19T11:55:00Z"))
    assertEquals("3h", ago("2026-10-19T09:00:00Z"))
    assertEquals("2d", ago("2026-10-17T11:00:00Z"))
    assertEquals("16d", ago("2026-10-03T08:00:00Z"))
  }

  @Test
  fun `the project filter drops rows from other projects`() {
    val threads: List<Thread> = listOf(
      thread("a").copy(projectId = "kstore"),
      thread("b").copy(projectId = "router"),
      thread("c").copy(projectId = "other"),
    )
    fun shown(filter: ProjectFilter): List<String> =
      sections(threads, filter).inbox.map { it.id }
    assertEquals(listOf("a", "b", "c"), shown(ProjectFilter.All).sorted())
    assertEquals(listOf("b"), shown(ProjectFilter.Only(setOf("router"))))
    assertEquals(listOf("a", "b"), shown(ProjectFilter.Only(setOf("kstore", "router"))).sorted())
  }

  @Test
  fun `an empty list says when the filter is why, naming the project`() {
    val projects: List<Project> = listOf(project("kstore", "KStore"), project("router", "Router"))
    val one: ProjectFilter = ProjectFilter.Only(setOf("kstore"))
    val two: ProjectFilter = ProjectFilter.Only(setOf("kstore", "router"))
    assertEquals("No threads in KStore yet", emptyHint(projects, one))
    assertEquals("No threads in these projects yet", emptyHint(projects, two))
    assertEquals("No threads yet", emptyHint(projects, ProjectFilter.All))
    assertEquals("No threads yet", emptyHint(projects, ProjectFilter.Only(setOf("gone"))))
    assertEquals("No projects yet", emptyHint(emptyList(), one))
  }

  @Test
  fun `searching the picker keeps the matching projects only, in order`() {
    val projects: List<Project> = listOf(
      project("kstore", "KStore"), project("router", "Decompose-Router"), project("nuget", "NuGet"),
    )
    val both: ProjectFilter = ProjectFilter.Only(setOf("kstore", "router"))
    val matched: FilterChoices = filterChoices(projects, both, setOf("nuget"), query = " U ")
    assertEquals(listOf("NuGet", "Decompose-Router"), matched.labels)
    assertEquals(1, matched.divider)
    assertEquals(-1, matched.selected, "The several-project match isn't listed")
    val router: ProjectFilter = ProjectFilter.Only(setOf("router"))
    assertEquals(0, filterChoices(projects, router, query = "route").selected)
    assertEquals(emptyList(), filterChoices(projects, ProjectFilter.All, query = "zzz").labels)
  }

  @Test
  fun `a picker match ignores case and accents`() {
    assertTrue(projectScopeMatches("Café Router", "cafe"))
    assertTrue(projectScopeMatches("KStore", "sto"))
    assertFalse(projectScopeMatches("KStore", "router"))
  }

  @Test
  fun `the picker lists all, a several-project match, then each project`() {
    val projects: List<Project> = listOf(project("kstore", "KStore"), project("router", "Router"))
    val all: FilterChoices = filterChoices(projects, ProjectFilter.All)
    assertEquals(listOf("All projects", "KStore", "Router"), all.labels)
    assertEquals(0, all.selected)
    assertEquals(emptyList(), all.names)
    val one: FilterChoices = filterChoices(projects, ProjectFilter.Only(setOf("router")))
    assertEquals(2, one.selected)
    assertEquals(listOf("Router"), one.names)
    val both: ProjectFilter = ProjectFilter.Only(setOf("kstore", "router"))
    val several: FilterChoices = filterChoices(projects, both)
    assertEquals(listOf("All projects", "2 projects", "KStore", "Router"), several.labels)
    assertEquals(1, several.selected)
    assertEquals(both, several.filters[several.selected])
    assertEquals(ProjectFilter.Only(setOf("kstore")), several.filters[2])
    assertEquals(listOf("KStore", "Router"), several.names)
  }

  @Test
  fun `the picker puts this window's projects first, a divider before the rest`() {
    val projects: List<Project> = listOf(
      project("kstore", "KStore"), project("router", "Router"), project("nuget", "NuGet"),
    )
    val open: FilterChoices = filterChoices(projects, ProjectFilter.All, setOf("nuget"))
    assertEquals(listOf("All projects", "NuGet", "KStore", "Router"), open.labels)
    assertEquals(ProjectFilter.Only(setOf("nuget")), open.filters[1])
    assertEquals(2, open.divider)
    val router: ProjectFilter = ProjectFilter.Only(setOf("router"))
    assertEquals(3, filterChoices(projects, router, setOf("nuget")).selected)
    assertNull(filterChoices(projects, ProjectFilter.All).divider, "Nothing open, no divider")
    val everything: Set<String> = setOf("kstore", "router", "nuget")
    assertNull(filterChoices(projects, ProjectFilter.All, everything).divider, "Nothing else")
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
  fun `top status puts limited and failed ahead of done`() {
    // Idle with a run reads as waiting upstream; done is a completed run nobody has looked at
    val unread: Thread = thread(
      completedAt = "2026-10-19T12:00:00Z", visitedAt = TEN_TO, latestRunId = "r",
      status = "completed",
    )
    assertEquals(TopStatus.Done, unread.topStatus())
    assertEquals(TopStatus.Waiting, unread.copy(status = "idle").topStatus())
    assertEquals(TopStatus.Failed, unread.copy(status = "failed").topStatus())
    val limited: Thread = unread.copy(status = "failed", lastErrorClass = "usage_limit")
    assertEquals(TopStatus.Limited, limited.topStatus())
    assertEquals(null, thread().topStatus())
    assertEquals("Working", unread.topStatusLabel(TopStatus.Working))
  }

  private fun thread(
    id: String = "t",
    title: String = id,
    status: String = "idle",
    activeRunId: String? = null,
    latestRunId: String? = null,
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
    latestRunId = latestRunId,
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

private fun project(id: String, title: String): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "$id", "title": "$title", "workspaceRoot": "/code/$id", "scripts": [],
      "createdAt": "$EPOCH", "updatedAt": "$EPOCH"
    }
  """,
)

private const val EPOCH: String = "1970-01-01T00:00:00Z"
private const val TEN_TO: String = "2026-10-19T11:50:00Z"

/** A thread row with only what upstream requires. Tests copy it with the fields they care about. */
internal val BASE_THREAD: Thread = T3Json.decodeFromString(
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
