package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.xxfast.t3code.protocol.generated.ProjectEntry
import io.github.xxfast.t3code.protocol.generated.ProjectSearchEntriesInput
import io.github.xxfast.t3code.protocol.generated.ProjectSearchEntriesResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * What the composer's `@` and `#` menus read from the host: the server's id, the thread on
 * screen, the threads it could mention, and the lookups behind the rows. The lookups are the
 * client's calls; composables never see the client.
 */
class ComposerMentions(
  /** `environment.environmentId` from `server.getConfig`, null until it loads. */
  val environmentId: String? = null,
  /** The thread the composer sends to, never offered as a mention of itself. */
  val activeThreadId: String? = null,
  /** The server's threads, `useThreadShells`. */
  val threads: List<ComposerThreadCandidate> = emptyList(),
  /** `projects.searchEntries`; null where the host can't search. */
  val searchEntries: (suspend (ProjectSearchEntriesInput) -> ProjectSearchEntriesResult)? = null,
  /** A thread was mentioned: its record joins the draft (`addComposerDraftThreadContexts`). */
  val onThreadContext: (ThreadContextRecord) -> Unit = {},
)

/** One finished `projects.searchEntries`, and what it was asked for. */
private class PathSearchResult(
  val target: ComposerPathSearchTarget,
  val entries: List<ProjectEntry>,
)

/**
 * `useComposerPathSearch`'s answer: the rows, what they were searched for, and whether more is
 * coming.
 */
class ComposerPathSearch(
  val entries: List<ProjectEntry>,
  val searchedTarget: ComposerPathSearchTarget?,
  val isPending: Boolean,
)

/**
 * `useComposerPathSearch` (apps/web/src/state/queries.ts:283) over `useProjectPathSearch`: the
 * target settles for [PROJECT_PATH_SEARCH_DEBOUNCE_MS], then `projects.searchEntries
 * {cwd, query, limit: 80}`. A bare `@` (an empty query) or no cwd sends nothing. The last
 * answer stays up while the next one is on its way; each row carries the target it came from,
 * so [isPathSuggestionCurrent] turns a stale pick down.
 */
@Composable
fun rememberComposerPathSearch(
  target: ComposerPathSearchTarget?,
  search: (suspend (ProjectSearchEntriesInput) -> ProjectSearchEntriesResult)?,
): ComposerPathSearch {
  var settled: ComposerPathSearchTarget? by remember { mutableStateOf(null) }
  var result: PathSearchResult? by remember { mutableStateOf(null) }
  var inFlight: Boolean by remember { mutableStateOf(false) }
  val lookup by rememberUpdatedState(search)
  LaunchedEffect(target) {
    delay(PROJECT_PATH_SEARCH_DEBOUNCE_MS)
    settled = target
    val cwd: String = target?.cwd ?: return@LaunchedEffect
    val call = lookup ?: return@LaunchedEffect
    if (target.query.isEmpty()) return@LaunchedEffect
    inFlight = true
    try {
      val input = ProjectSearchEntriesInput(
        cwd = cwd,
        query = target.query,
        limit = COMPOSER_PATH_SEARCH_LIMIT.toLong(),
      )
      result = PathSearchResult(target, call(input).entries)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Upstream's `result.error`; the menu has no line for it, so it reads as no matches
      result = PathSearchResult(target, emptyList())
    } finally {
      inFlight = false
    }
  }
  val hasTarget: Boolean = target?.cwd != null && target.query.isNotEmpty() && search != null
  val current: PathSearchResult? = result?.takeIf { hasTarget }
  return ComposerPathSearch(
    entries = current?.entries.orEmpty(),
    searchedTarget = current?.target,
    isPending = target != settled || (hasTarget && inFlight),
  )
}

/** The `@` and `#` halves of `composerMenuItems`, for [triggerMenuFor]'s branches. */
class ComposerMentionMenus(
  private val mentions: ComposerMentions,
  private val cwd: String?,
  private val pathSearch: ComposerPathSearch,
) {
  /**
   * `@`, ChatComposer.tsx:2655: up to five threads first (`matchComposerThreadItems`), then the
   * server's files and folders in its order. A file pick inserts `serializeComposerFileLink`
   * and a space, a thread pick its context reference and a record for the draft.
   */
  fun pathMenu(
    trigger: ComposerTrigger,
    pick: (ComposerTrigger, replacement: String) -> Boolean,
  ): ComposerMenu {
    val environmentId: String? = mentions.environmentId
    val threads: List<ComposerMenuItem> = environmentId
      ?.let { id ->
        matchComposerThreadItems(mentions.threads, id, mentions.activeThreadId, trigger.query)
      }
      .orEmpty()
      .map { item ->
        ComposerMenuItem(
          id = item.id,
          label = item.label,
          description = item.description,
          icon = THREAD_ICON,
          onPick = {
            val record: ThreadContextRecord =
              threadContextRecord(item.environmentId, item.threadId, item.label)
            if (pick(trigger, "${threadContextReference(record)} ")) {
              mentions.onThreadContext(record)
            }
          },
        )
      }
    val target: ComposerPathSearchTarget? = pathSearch.searchedTarget
    val paths: List<ComposerMenuItem> = if (target == null) {
      emptyList()
    } else {
      pathSearch.entries.map { entry ->
        ComposerMenuItem(
          id = "path:${entry.kind}:${entry.path}",
          label = basenameOfPath(entry.path),
          description = pathEntryDescription(entry.path),
          icon = if (entry.kind == PROJECT_ENTRY_DIRECTORY) FOLDER_ICON else FILE_ICON,
          onPick = {
            if (isPathSuggestionCurrent(target, trigger, environmentId, cwd)) {
              pick(trigger, "${serializeComposerFileLink(entry.path)} ")
            }
          },
        )
      }
    }
    return ComposerMenu(
      kind = trigger.kind,
      items = threads + paths,
      // `isComposerMenuLoading` for `@`: only with a query
      isLoading = trigger.query.isNotEmpty() && pathSearch.isPending,
    )
  }
}

/**
 * The `@` and `#` state for [trigger], recomputed as searches answer. Only an `@` trigger
 * searches; any other leaves the target empty.
 */
@Composable
fun rememberComposerMentionMenus(
  trigger: ComposerTrigger?,
  cwd: String?,
  mentions: ComposerMentions,
): ComposerMentionMenus {
  val isPathTrigger: Boolean = trigger?.kind == ComposerTriggerKind.PATH
  val target: ComposerPathSearchTarget? = if (isPathTrigger) {
    ComposerPathSearchTarget(mentions.environmentId, cwd, trigger.query.trim())
  } else {
    null
  }
  val pathSearch: ComposerPathSearch = rememberComposerPathSearch(target, mentions.searchEntries)
  val entries: List<ProjectEntry> = pathSearch.entries
  return remember(mentions, cwd, entries, pathSearch.searchedTarget, pathSearch.isPending) {
    ComposerMentionMenus(mentions, cwd, pathSearch)
  }
}

// Upstream's `PierreEntryIcon` picks one per file type; the IDE's generic file and folder here
private val FILE_ICON: IconKey = AllIconsKeys.FileTypes.Any_type
private val FOLDER_ICON: IconKey = AllIconsKeys.Nodes.Folder

// Upstream's `MessagesSquareIcon`
private val THREAD_ICON: IconKey = AllIconsKeys.Toolwindows.ToolWindowMessages
