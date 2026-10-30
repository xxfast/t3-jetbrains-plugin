package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.LimitRecoveryUpdate
import io.github.xxfast.t3code.protocol.generated.ThreadShell
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// Cases from upstream's ChatView.logic.test.ts, ThreadErrorBanner.test.tsx,
// ComposerBannerStack.tsx and UsageLimitRecoveryBanner.tsx
class ChatBannersLogicTest {
  private val mismatch = BranchMismatch(threadBranch = "feature", currentBranch = "main")

  @Test
  fun `a mismatch is keyed by thread and both branches`() {
    assertEquals("t1:feature:main", branchMismatchKey("t1", mismatch))
    assertNull(branchMismatchKey("t1", null))
    assertNull(branchMismatchKey(null, mismatch))
  }

  @Test
  fun `the notice waits for draft content, then sticks until dismissed`() {
    assertFalse(shouldShowBranchMismatchBanner(true, false, false, false))
    assertTrue(shouldShowBranchMismatchBanner(true, false, true, false))
    assertTrue(shouldShowBranchMismatchBanner(true, false, false, true))
    assertFalse(shouldShowBranchMismatchBanner(true, true, true, true))
    assertFalse(shouldShowBranchMismatchBanner(false, false, true, true))
  }

  @Test
  fun `the reveal latch holds its mismatch and lets go of another`() {
    assertEquals("k", nextRevealedBranchMismatchKey(show = true, activeKey = "k", revealed = null))
    assertEquals("k", nextRevealedBranchMismatchKey(show = false, activeKey = "k", revealed = "k"))
    assertNull(nextRevealedBranchMismatchKey(show = false, activeKey = "j", revealed = "k"))
    assertNull(nextRevealedBranchMismatchKey(show = false, activeKey = null, revealed = "k"))
  }

  @Test
  fun `draft content is text that isn't blank or something attached`() {
    assertFalse(composerDraftHasUserContent("  \n", DraftExtras()))
    assertTrue(composerDraftHasUserContent("hi", DraftExtras()))
    assertFalse(composerDraftHasUserContent("", DraftExtras(notice = "dropped a file")))
  }

  @Test
  fun `activity sorts ahead of a notice, and equals keep their order`() {
    val work = ComposerBannerItem.BackgroundWork(BackgroundWorkState(tasks = emptyList()))
    val branch = ComposerBannerItem.BranchMismatch(BranchMismatchBannerState(mismatch))
    assertEquals(listOf(work, branch), orderComposerBanners(listOf(branch, work)))
    assertEquals(1, bannerPriority(work))
    assertEquals(2, bannerPriority(branch))
    val other = ComposerBannerItem.BranchMismatch(BranchMismatchBannerState(mismatch))
    assertEquals(listOf(branch, other), orderComposerBanners(listOf(branch, other)))
  }

  @Test
  fun `the usage limit is urgent, in front of everything`() {
    val limit =
      ComposerBannerItem.LimitRecovery(UsageLimitRecoveryState("r", null, false, false, false))
    val work = ComposerBannerItem.BackgroundWork(BackgroundWorkState(tasks = emptyList()))
    val branch = ComposerBannerItem.BranchMismatch(BranchMismatchBannerState(mismatch))
    assertEquals(listOf(limit, work, branch), orderComposerBanners(listOf(branch, work, limit)))
  }

  @Test
  fun `an error banner is keyed by thread and exact text`() {
    assertNull(threadErrorBannerKey("t1", null))
    assertEquals("t1\u0000boom", threadErrorBannerKey("t1", "boom"))
    assertTrue(shouldShowThreadErrorBanner("t1", "boom", isDismissed = false))
    assertFalse(shouldShowThreadErrorBanner("t1", "boom", isDismissed = true))
    assertFalse(shouldShowThreadErrorBanner("t1", null, isDismissed = false))
  }

  @Test
  fun `the error banner shows the last error until dismissed or the limit banner takes it`() {
    val failed: Thread =
      thread(""""status":"failed","lastError":"boom","lastErrorClass":"provider_error"""")
    assertEquals(
      ThreadErrorBannerState("boom", "provider_error"),
      resolveThreadErrorBanner(failed, emptySet(), limitRecoveryShown = false),
    )
    assertEquals(ComposerBannerVariant.ERROR, ThreadErrorBannerState("boom").variant)
    assertNull(resolveThreadErrorBanner(failed, setOf("t1\u0000boom"), limitRecoveryShown = false))
    assertNull(resolveThreadErrorBanner(failed, emptySet(), limitRecoveryShown = true))
    // A new error is a new key, so it shows again after a dismissal
    val again: Thread = thread(""""status":"failed","lastError":"bang"""")
    assertEquals("bang", resolveThreadErrorBanner(again, setOf("t1\u0000boom"), false)?.error)
    // Cleared by the server, gone
    assertNull(resolveThreadErrorBanner(thread(""""status":"idle""""), emptySet(), false))
  }

  @Test
  fun `a usage limit or a ChatGPT code warns`() {
    assertEquals(
      ComposerBannerVariant.WARNING,
      ThreadErrorBannerState("x", errorClass = "usage_limit").variant,
    )
    val workspace = ThreadErrorBannerState("x", errorCode = CHATGPT_WORKSPACE_USAGE_LIMIT_CODE)
    assertTrue(workspace.workspaceLimit)
    assertTrue(workspace.chatGptLimit)
    assertEquals(ComposerBannerVariant.WARNING, workspace.variant)
    val plan = ThreadErrorBannerState("x", errorCode = CHATGPT_USAGE_LIMIT_CODE)
    assertFalse(plan.workspaceLimit)
    assertTrue(plan.chatGptLimit)
  }

  @Test
  fun `a JSON error body collapses to its message`() {
    assertEquals("Too many", getThreadErrorBannerMessage("""{"error":{"message":"Too many"}}"""))
    assertEquals("plain", getThreadErrorBannerMessage("plain"))
    val blank = """{"error":{"message":"  "}}"""
    assertEquals(blank, getThreadErrorBannerMessage(blank))
    val number = """{"error":{"message":3}}"""
    assertEquals(number, getThreadErrorBannerMessage(number))
    assertEquals("[1]", getThreadErrorBannerMessage("[1]"))
  }

  @Test
  fun `text expands when collapsed, long or on several lines`() {
    assertFalse(canExpandText("short", "short"))
    assertTrue(canExpandText("full", "short"))
    assertTrue(canExpandText("x".repeat(181), "x".repeat(181)))
    assertTrue(canExpandText("a\nb", "a\nb"))
  }

  @Test
  fun `usage limit recovery needs a failed thread with the class and a run`() {
    val failed: Thread = thread(
      """
      "status":"failed","lastError":"Rate limited","lastErrorClass":"usage_limit",
      "latestRunId":"run1","latestRunCompletedAt":"2026-10-26T01:00:00.000Z",
      "usageLimitResetAt":"2026-10-26T03:00:00.000Z",
      "limitRecovery":{"runId":"run1","resetAt":"2026-10-26T03:00:00.000Z","autoResume":true}
      """,
    )
    assertEquals(
      UsageLimitRecoveryState(
        runId = "run1",
        resetAt = "2026-10-26T03:00:00.000Z",
        canSchedule = true,
        scheduled = true,
        snoozed = false,
      ),
      usageLimitRecovery(failed),
    )
    val otherClass = """"status":"failed","lastErrorClass":"provider_error""""
    assertNull(usageLimitRecovery(thread(otherClass)))
    val completed = """"status":"completed","lastErrorClass":"usage_limit""""
    assertNull(usageLimitRecovery(thread(completed)))
    assertNull(usageLimitRecovery(thread(""""status":"failed","lastErrorClass":"usage_limit"""")))
    val noReset: UsageLimitRecoveryState? = usageLimitRecovery(
      thread(""""status":"failed","lastErrorClass":"usage_limit","latestRunId":"run1""""),
    )
    assertEquals(UsageLimitRecoveryState("run1", null, false, false, false), noReset)
  }

  @Test
  fun `a reset before the stop can't be scheduled, and another run's recovery doesn't count`() {
    val early: UsageLimitRecoveryState? = usageLimitRecovery(
      thread(
        """
        "status":"failed","lastErrorClass":"usage_limit","latestRunId":"run2",
        "latestRunCompletedAt":"2026-10-26T04:00:00.000Z",
        "usageLimitResetAt":"2026-10-26T03:00:00.000Z",
        "limitRecovery":{"runId":"run1","resetAt":"2026-10-26T03:00:00.000Z","autoResume":true}
        """,
      ),
    )
    assertEquals(false, early?.canSchedule)
    assertEquals(false, early?.scheduled)
  }

  @Test
  fun `snoozed means a snooze for this reset with the thread asleep until it`() {
    val base = """
      "status":"failed","lastErrorClass":"usage_limit","latestRunId":"run1",
      "usageLimitResetAt":"2026-10-26T03:00:00.000Z",
      "limitRecovery":{"runId":"run1","resetAt":"2026-10-26T03:00:00.000Z","autoResume":false,
        "snooze":true}
    """
    assertEquals(
      true,
      usageLimitRecovery(thread("""$base,"snoozedUntil":"2026-10-26T03:00:00Z""""))?.snoozed,
    )
    assertEquals(false, usageLimitRecovery(thread(base))?.snoozed)
    assertEquals(
      false,
      usageLimitRecovery(thread("""$base,"snoozedUntil":"2026-10-27T00:00:00Z""""))?.snoozed,
    )
  }

  @Test
  fun `a toggle flips only its own field`() {
    val state = UsageLimitRecoveryState("run1", "2026-10-26T03:00:00.000Z", true, true, false)
    assertEquals(
      LimitRecoveryUpdate("run1", "2026-10-26T03:00:00.000Z", autoResume = false),
      limitRecoveryUpdate(state, LimitRecoveryAction.RESUME),
    )
    assertEquals(
      LimitRecoveryUpdate("run1", "2026-10-26T03:00:00.000Z", snooze = true),
      limitRecoveryUpdate(state, LimitRecoveryAction.SNOOZE),
    )
    assertNull(limitRecoveryUpdate(state.copy(resetAt = null), LimitRecoveryAction.RESUME))
  }

  @Test
  fun `the reset passes at its instant`() {
    val reset = "2026-10-26T03:00:00Z"
    assertFalse(limitResetPassed(reset, Instant.parse("2026-10-26T02:59:59Z")))
    assertTrue(limitResetPassed(reset, Instant.parse(reset)))
    assertFalse(limitResetPassed(null, Instant.parse(reset)))
  }

  @Test
  fun `the description says when the limit resets, in local time`() {
    assertEquals(
      "Resets 10/26/26, 2:00:00 PM",
      usageLimitResetDescription("2026-10-26T03:00:00Z", MELBOURNE, Locale.US)
        .replace(' ', ' '),
    )
    assertEquals("Reset time unavailable; retry manually", usageLimitResetDescription(null))
  }

  private companion object {
    val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
  }

  // [extra] carries the status and whatever else the case is about, over the base fields
  private fun thread(extra: String): Thread {
    val base: JsonObject = T3Json.parseToJsonElement(
      """
      {"id":"t1","projectId":"p","title":"t","createdBy":"user","creationSource":"web",
       "providerInstanceId":"codex","modelSelection":{"instanceId":"codex","model":"gpt-5"},
       "runtimeMode":"approval-required","interactionMode":"default",
       "lineage":{"rootThreadId":"t1"},"branch":null,"worktreePath":null,"latestRunId":null,
       "itemCount":0,"visibleItemCount":0,"hasActionableProposedPlan":false,
       "createdAt":"2026-10-26T00:00:00.000Z","updatedAt":"2026-10-26T02:00:00.000Z"}
      """.trimIndent(),
    ).jsonObject
    val over: JsonObject = T3Json.parseToJsonElement("{$extra}").jsonObject
    return T3Json.decodeFromJsonElement(ThreadShell.serializer(), JsonObject(base + over))
  }
}
