package io.github.xxfast.t3code.plugin.actions

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadUndoLogicTest {
  private val undo = ThreadUndo()

  private fun entry(action: UndoAction, kind: String, threadId: String): UndoEntry =
    UndoEntry(action, undo.begin(kind, threadId), "failed") {}

  @Test
  fun `the notice counts the latest run of the same action`() {
    undo.show(entry(UndoAction.Settled, UndoKind.SETTLE, "a"))
    undo.show(entry(UndoAction.Archived, UndoKind.ARCHIVE, "b"))
    undo.show(entry(UndoAction.Archived, UndoKind.ARCHIVE, "c"))
    assertEquals(UndoNotice(UndoAction.Archived, 2), undo.notice.value)
  }

  @Test
  fun `undo takes the latest group once, then the notice falls back to the one before`() {
    undo.show(entry(UndoAction.Settled, UndoKind.SETTLE, "a"))
    undo.show(entry(UndoAction.Archived, UndoKind.ARCHIVE, "b"))
    undo.show(entry(UndoAction.Archived, UndoKind.ARCHIVE, "c"))
    assertEquals(2, undo.takeLatest().size)
    assertEquals(UndoNotice(UndoAction.Settled, 1), undo.notice.value)
    assertEquals(1, undo.takeLatest().size)
    assertNull(undo.notice.value)
    assertEquals(emptyList(), undo.takeLatest())
  }

  @Test
  fun `an invalidated claim drops out of the notice`() {
    undo.show(entry(UndoAction.Unpinned, UndoKind.PIN, "a"))
    undo.show(entry(UndoAction.Unpinned, UndoKind.PIN, "b"))
    undo.invalidate(UndoKind.PIN, "a")
    assertEquals(UndoNotice(UndoAction.Unpinned, 1), undo.notice.value)
  }

  @Test
  fun `a newer action of the same kind supersedes the older claim`() {
    val older: UndoEntry = entry(UndoAction.Snoozed, UndoKind.SNOOZE, "a")
    undo.show(older)
    undo.begin(UndoKind.SNOOZE, "a")
    assertFalse(older.claim.isCurrent())
    assertNull(undo.notice.value)
  }

  @Test
  fun `a claim that went stale while its command was out isn't shown`() {
    val claimed: UndoEntry = entry(UndoAction.Archived, UndoKind.ARCHIVE, "a")
    undo.invalidate(UndoKind.ARCHIVE, "a")
    assertFalse(undo.show(claimed))
    assertNull(undo.notice.value)
  }

  @Test
  fun `expiring drops everything and finishes the claims`() {
    val shown: UndoEntry = entry(UndoAction.Settled, UndoKind.SETTLE, "a")
    assertTrue(undo.show(shown))
    undo.expire()
    assertNull(undo.notice.value)
    assertFalse(shown.claim.isCurrent())
  }

  @Test
  fun `texts follow SidebarThreadUndoNotice`() {
    assertEquals("Archived 1 thread", noticeText(UndoNotice(UndoAction.Archived, 1)))
    assertEquals("Settled 3 threads", noticeText(UndoNotice(UndoAction.Settled, 3)))
    assertEquals("⌘Z to undo", undoText("⌘Z"))
    assertEquals("Undo", undoText(""))
    assertEquals("Undo", undoText(null))
  }
}
