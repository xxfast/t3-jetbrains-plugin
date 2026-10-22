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
import io.github.xxfast.t3code.protocol.generated.PullRequestDetail
import io.github.xxfast.t3code.protocol.generated.PullRequestListInput
import io.github.xxfast.t3code.protocol.generated.PullRequestListResult
import io.github.xxfast.t3code.protocol.generated.PullRequestRef
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestLink
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
  /**
   * `pullRequestProjectId`: the thread's project while the server does pull requests
   * (ChatView.tsx:8540), else null and `#` has nothing to list.
   */
  val pullRequestProjectId: String? = null,
  /** `pullRequestRepository`: the project's `sourceControlRepositorySelector`, same gate. */
  val pullRequestRepository: String? = null,
  /** The thread's own linked pull requests, `threadPullRequests`. */
  val linkedPullRequests: List<ThreadPullRequestLink> = emptyList(),
  /** `pullRequests.list`. */
  val listPullRequests: (suspend (PullRequestListInput) -> PullRequestListResult)? = null,
  /** `pullRequests.detail`. */
  val pullRequestDetail: (suspend (PullRequestRef) -> PullRequestDetail)? = null,
  /** A pull request was referenced: its review-comment record joins the draft. */
  val onPullRequestContext: (ReviewCommentContext) -> Unit = {},
)

/** A finished lookup and the input it answers, so a newer input reads as pending. */
private class Lookup<I, R>(val input: I, val result: R?, val failed: Boolean)

/**
 * One suspend call per distinct [input], the last answer kept with the input it was for:
 * upstream's `useEnvironmentQuery`. A null input asks nothing.
 */
@Composable
private fun <I : Any, R : Any> rememberLookup(input: I?, call: (suspend (I) -> R)?): Lookup<I, R>? {
  var lookup: Lookup<I, R>? by remember { mutableStateOf(null) }
  val latest by rememberUpdatedState(call)
  LaunchedEffect(input) {
    val asked: I = input ?: return@LaunchedEffect
    val fetch = latest ?: return@LaunchedEffect
    lookup = try {
      Lookup(asked, fetch(asked), failed = false)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      Lookup(asked, null, failed = true)
    }
  }
  return lookup
}

/** `useDebouncedValue`: [value] once it has held still for [delayMillis]. */
@Composable
private fun <T> rememberDebouncedValue(value: T, delayMillis: Long): T {
  var debounced: T by remember { mutableStateOf(value) }
  LaunchedEffect(value) {
    delay(delayMillis)
    debounced = value
  }
  return debounced
}

/** What the `#` lookups have answered, [rememberComposerPullRequestLookup]. */
class ComposerPullRequestLookup(
  val list: PullRequestListResult?,
  val exact: ComposerPullRequest?,
  val isLoading: Boolean,
  val failed: Boolean,
)

/**
 * The `#` lookups, ChatComposer.tsx:2566 to 2650: `pullRequests.list {state: "all", projectId,
 * limit: 99}` with a `query` only for non-numeric input, both settling for 180 ms. A settled
 * number the list doesn't have is asked of `pullRequests.detail` too.
 */
@Composable
fun rememberComposerPullRequestLookup(
  trigger: ComposerTrigger?,
  mentions: ComposerMentions,
): ComposerPullRequestLookup {
  val projectId: String? = mentions.pullRequestProjectId
  val repository: String? = mentions.pullRequestRepository
  val isPullRequestTrigger: Boolean = trigger?.kind == ComposerTriggerKind.PULL_REQUEST
  val textQuery: String? = composerPullRequestTextQuery(trigger)
  val debouncedTextQuery: String? =
    rememberDebouncedValue(textQuery, COMPOSER_PULL_REQUEST_DEBOUNCE_MS)
  val settledTextQuery: String? = textQuery.takeIf { it == debouncedTextQuery }
  val textUnsettled: Boolean = textQuery != null && settledTextQuery == null
  val listInput: PullRequestListInput? =
    if (!isPullRequestTrigger || projectId == null || textUnsettled) {
      null
    } else {
      PullRequestListInput(
        state = "all",
        projectId = projectId,
        limit = COMPOSER_PULL_REQUEST_LIST_LIMIT.toLong(),
        query = settledTextQuery,
      )
    }
  val list: Lookup<PullRequestListInput, PullRequestListResult>? =
    rememberLookup(listInput, mentions.listPullRequests)
  val number: Long? = composerPullRequestNumber(trigger)
  val debouncedNumber: Long? = rememberDebouncedValue(number, COMPOSER_PULL_REQUEST_DEBOUNCE_MS)
  val settledNumber: Long? = number.takeIf { it == debouncedNumber }
  val listed: PullRequestListResult? = list?.takeIf { it.input == listInput }?.result
  val recentHasExact: Boolean = settledNumber != null &&
    listed?.entries?.any { entry ->
      entry.projectId == projectId &&
        entry.repository.trim().lowercase() == repository?.trim()?.lowercase() &&
        entry.number == settledNumber
    } == true
  val detailInput: PullRequestRef? =
    if (settledNumber == null || projectId == null || repository == null || recentHasExact) {
      null
    } else {
      PullRequestRef(projectId = projectId, repository = repository, number = settledNumber)
    }
  val detail: Lookup<PullRequestRef, PullRequestDetail>? =
    rememberLookup(detailInput, mentions.pullRequestDetail)
  val listPending: Boolean = listInput != null && list?.input != listInput
  val detailPending: Boolean = detailInput != null && detail?.input != detailInput
  // `exactPullRequestLookup.data?.number === pullRequestTriggerNumber`
  val exact: ComposerPullRequest? = detail?.result
    ?.takeIf { detailInput != null && it.number == number }
    ?.toComposerPullRequest()
  return ComposerPullRequestLookup(
    list = listed,
    exact = exact,
    // `isComposerMenuLoading` for `#`
    isLoading = isPullRequestTrigger && projectId != null && repository != null && (
      listPending ||
        textQuery != debouncedTextQuery ||
        number != debouncedNumber ||
        detailPending
      ),
    failed = list?.failed == true ||
      listed?.errors?.any { it.projectId == projectId } == true,
  )
}

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
  private val pullRequests: ComposerPullRequestLookup,
) {
  /**
   * `#`, ChatComposer.tsx:2750: the matches of [composerPullRequestMatches] as `#<number>` rows.
   * A pick inserts the reference of `buildPullRequestReferenceContext` and a space, and adds it
   * to the draft as a review-comment record (`addComposerDraftReviewComment`).
   */
  fun pullRequestMenu(
    trigger: ComposerTrigger,
    pick: (ComposerTrigger, replacement: String) -> Boolean,
  ): ComposerMenu {
    val projectId: String? = mentions.pullRequestProjectId
    val repository: String? = mentions.pullRequestRepository
    val available: Boolean = projectId != null && repository != null
    val items: List<ComposerMenuItem> = if (projectId == null || repository == null) {
      emptyList()
    } else {
      composerPullRequestMatches(
        query = trigger.query,
        list = pullRequests.list,
        exact = pullRequests.exact,
        linked = composerPullRequestsFromLinks(mentions.linkedPullRequests, projectId),
        projectId = projectId,
        repository = repository,
      ).map { pullRequest ->
        ComposerMenuItem(
          id = "pull-request:${pullRequest.projectId}:${pullRequest.repository}:" +
            pullRequest.number,
          label = "#${pullRequest.number}",
          description = composerPullRequestDescription(pullRequest, repository),
          icon = if (pullRequest.state == "merged") MERGED_ICON else PULL_REQUEST_ICON,
          onPick = {
            val comment: ReviewCommentContext =
              buildPullRequestReferenceContext(pullRequest.toContextMetadata())
            if (pick(trigger, "${reviewCommentReference(comment)} ")) {
              mentions.onPullRequestContext(comment)
            }
          },
        )
      }
    }
    return ComposerMenu(
      kind = trigger.kind,
      items = items,
      isLoading = pullRequests.isLoading,
      emptyStateText = composerPullRequestEmptyState(available, pullRequests.failed, trigger.query),
    )
  }

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
  val pullRequests: ComposerPullRequestLookup = rememberComposerPullRequestLookup(trigger, mentions)
  val entries: List<ProjectEntry> = pathSearch.entries
  return remember(
    mentions,
    cwd,
    entries,
    pathSearch.searchedTarget,
    pathSearch.isPending,
    pullRequests.list,
    pullRequests.exact,
    pullRequests.isLoading,
    pullRequests.failed,
  ) {
    ComposerMentionMenus(mentions, cwd, pathSearch, pullRequests)
  }
}

// Upstream's `PierreEntryIcon` picks one per file type; the IDE's generic file and folder here
private val FILE_ICON: IconKey = AllIconsKeys.FileTypes.Any_type
private val FOLDER_ICON: IconKey = AllIconsKeys.Nodes.Folder

// Upstream's `MessagesSquareIcon`
private val THREAD_ICON: IconKey = AllIconsKeys.Toolwindows.ToolWindowMessages

// Upstream's `pullRequestPresentation` icons per state, the IDE's branch and merge here
private val PULL_REQUEST_ICON: IconKey = AllIconsKeys.Vcs.Branch
private val MERGED_ICON: IconKey = AllIconsKeys.Vcs.Merge
