package io.github.xxfast.t3code.plugin.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Ports apps/web/src/hooks/threadUndo.ts (claims) and showThreadUndoNotice.ts (the notice) at the
// pinned tag. Upstream undoes Settled, Snoozed, Unpinned, Archived and a discarded draft. Not a
// delete: there's no command that brings a deleted thread back.

/** What a notice says was done, `UndoOptions["action"]`. Drafts aren't ours yet. */
enum class UndoAction { Settled, Snoozed, Unpinned, Archived }

/** The claim kinds upstream's actions take and invalidate, useThreadActions.ts. */
object UndoKind {
  const val ARCHIVE: String = "archive"
  const val PIN: String = "pin"
  const val SETTLE: String = "settle"
  const val SNOOZE: String = "snooze"
}

/** Upstream's notice after 5 s without another action, showThreadUndoNotice.ts:104. */
const val UNDO_WINDOW_MS: Long = 5_000

/** What the notice shows: the latest action and how many in a row did the same. */
data class UndoNotice(val action: UndoAction, val count: Int)

/** "Archived 2 threads", SidebarThreadUndoNotice.tsx:15. */
fun noticeText(notice: UndoNotice): String =
  "${notice.action} ${notice.count} thread${if (notice.count == 1) "" else "s"}"

/** The notice's button, "⌘Z to undo" with a shortcut and "Undo" without, as upstream's. */
fun undoText(shortcut: String?): String =
  if (shortcut.isNullOrEmpty()) "Undo" else "$shortcut to undo"

/**
 * `ThreadUndo.begin`'s claim: current until a newer action of the same kind on the same thread
 * begins, something invalidates it, or it's finished.
 */
class UndoClaim internal constructor(private val owner: ThreadUndo, private val key: List<String>) {
  fun isCurrent(): Boolean = owner.isCurrent(key, this)

  fun finish() = owner.finish(key, this)
}

/** One undoable action, `UndoOptions`: [undo] puts it back, [failureTitle] if that fails. */
class UndoEntry(
  val action: UndoAction,
  val claim: UndoClaim,
  val failureTitle: String,
  val undo: suspend () -> Unit,
)

/**
 * The claims and the live undos, threadUndo.ts and showThreadUndoNotice.ts in one. The timer
 * isn't here: whoever holds this calls [expire] [UNDO_WINDOW_MS] after the latest [show].
 */
class ThreadUndo {
  private val current: MutableMap<List<String>, UndoClaim> = mutableMapOf()
  private var live: List<UndoEntry> = emptyList()
  private val shown: MutableStateFlow<UndoNotice?> = MutableStateFlow(null)

  val notice: StateFlow<UndoNotice?> = shown.asStateFlow()

  @Synchronized
  fun begin(kind: String, threadId: String): UndoClaim {
    val key: List<String> = listOf(kind, threadId)
    val claim = UndoClaim(this, key)
    current[key] = claim
    refresh()
    return claim
  }

  /** `ThreadUndo.invalidate`: the opposite action ran, so an undo would now do the wrong thing. */
  @Synchronized
  fun invalidate(kind: String, threadId: String) {
    current.remove(listOf(kind, threadId))
    refresh()
  }

  /** `showThreadUndoNotice`: false when [entry]'s claim went stale while its command was out. */
  @Synchronized
  fun show(entry: UndoEntry): Boolean {
    if (!entry.claim.isCurrent()) return false
    live = live + entry
    refresh()
    return true
  }

  /**
   * The notice's undo: takes the latest run of same-action entries that are still current,
   * finishing their claims first so a second click or shortcut can't restore them twice. The
   * caller runs their [UndoEntry.undo]. Empty when there's no notice.
   */
  @Synchronized
  fun takeLatest(): List<UndoEntry> {
    val group: List<UndoEntry> = latestGroup()
    val taken: List<UndoEntry> = group.filter { it.claim.isCurrent() }
    live = live - group.toSet()
    taken.forEach { it.claim.finish() }
    refresh()
    return taken
  }

  /** The window closed: every live undo is dropped and its claim finished. */
  @Synchronized
  fun expire() {
    val expired: List<UndoEntry> = live
    live = emptyList()
    expired.forEach { it.claim.finish() }
    refresh()
  }

  @Synchronized
  internal fun isCurrent(key: List<String>, claim: UndoClaim): Boolean = current[key] === claim

  @Synchronized
  internal fun finish(key: List<String>, claim: UndoClaim) {
    if (current[key] !== claim) return
    current.remove(key)
    refresh()
  }

  /** `refreshNotice`: drop stale entries, then count back from the latest while it's the same. */
  private fun refresh() {
    live = live.filter { it.claim.isCurrent() }
    val group: List<UndoEntry> = latestGroup()
    shown.value = group.firstOrNull()?.let { UndoNotice(it.action, group.size) }
  }

  private fun latestGroup(): List<UndoEntry> {
    val latest: UndoAction = live.lastOrNull()?.action ?: return emptyList()
    return live.takeLastWhile { it.action == latest }
  }
}
