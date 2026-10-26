package io.github.xxfast.t3code.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Cases from upstream's ChatView.logic.test.ts and ComposerBannerStack.tsx
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
}
