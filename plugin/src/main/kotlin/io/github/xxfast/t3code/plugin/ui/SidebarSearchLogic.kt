package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ThreadLinkedPullRequest
import io.github.xxfast.t3code.protocol.generated.ThreadSearchMatch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * What the server's message search has for the query, `useThreadSearch`'s answer
 * (apps/web/src/state/queries.ts): its [matches], one per thread, and whether it's [pending],
 * debouncing or loading. Matches are empty while it is.
 */
data class ThreadSearch(
  val matches: List<ThreadSearchMatch> = emptyList(),
  val pending: Boolean = false,
)

/** `THREAD_SEARCH_DEBOUNCE_MS`, queries.ts. */
const val THREAD_SEARCH_DEBOUNCE_MS: Long = 200

/** The shortest and longest query `OrchestrationSearchThreadsInput` takes, threadSearch.ts. */
private const val THREAD_SEARCH_MIN: Int = 2
private const val THREAD_SEARCH_MAX: Int = 200

/**
 * `useThreadSearch`, queries.ts, as a flow of [queries]: trimmed, debounced, and only asked of
 * [search] from two characters. A query the server would refuse, or one it fails, has no
 * matches, as a failed query's result has no value upstream. Upstream also keeps answers for
 * 30 seconds (`staleTimeMs`); this asks again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun threadSearch(
  queries: Flow<String>,
  search: suspend (query: String) -> List<ThreadSearchMatch>,
): Flow<ThreadSearch> = queries
  .map { it.trim() }
  .distinctUntilChanged()
  .transformLatest { query ->
    if (query.length !in THREAD_SEARCH_MIN..THREAD_SEARCH_MAX) {
      emit(ThreadSearch())
      return@transformLatest
    }
    emit(ThreadSearch(pending = true))
    delay(THREAD_SEARCH_DEBOUNCE_MS)
    val matches: List<ThreadSearchMatch> = try {
      search(query)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyList()
    }
    emit(ThreadSearch(matches))
  }

/**
 * `searchSidebarThreads`, apps/web/src/components/Sidebar.logic.ts: [threads] whose title or
 * pull requests contain [query], then the ones only the server's message search found
 * ([contentMatches], by thread id), each in [threads]' order. Empty without a query.
 */
fun searchSidebarThreads(
  threads: List<Thread>,
  query: String,
  contentMatches: Set<String> = emptySet(),
): List<Thread> {
  val normalizedQuery: String = query.trim().lowercase()
  if (normalizedQuery.isEmpty()) return emptyList()
  val titleMatches: MutableList<Thread> = mutableListOf()
  val bodyMatches: MutableList<Thread> = mutableListOf()
  for (thread in threads) {
    val matchesTitle: Boolean = (listOf(thread.title) + threadPullRequestSearchTerms(thread))
      .any { term -> term.lowercase().contains(normalizedQuery) }
    if (matchesTitle) {
      titleMatches += thread
    } else if (thread.id in contentMatches) {
      bodyMatches += thread
    }
  }
  return titleMatches + bodyMatches
}

/**
 * `threadPullRequestSearchTerms`, packages/shared/src/threadPullRequests.ts: `#N`,
 * `repository#N` and the URL of the branch's pull request and of every visible link, plus the
 * link's title. A thread without links falls back to its one legacy linked pull request.
 */
fun threadPullRequestSearchTerms(thread: Thread): List<String> {
  val branch: ThreadLinkedPullRequest? = thread.branchPullRequest
  val terms: List<String> = branch?.searchTerms().orEmpty()
  val links = thread.pullRequests
  if (!links.isNullOrEmpty()) {
    return terms + visibleThreadPullRequests(links).flatMap { link ->
      listOf(
        "#${link.number}",
        "${link.repository}#${link.number}",
        link.url,
        link.snapshot?.title.orEmpty(),
      )
    }
  }
  return terms + thread.linkedPullRequest?.searchTerms().orEmpty()
}

private fun ThreadLinkedPullRequest.searchTerms(): List<String> =
  listOf("#$number", "$repository#$number", url)

/**
 * `pullRequestMatchesSearchQuery`, Sidebar.logic.ts: every word of [query] is somewhere in the
 * thread's pull request terms, accents and case aside. A search result shows its badge then.
 */
fun pullRequestMatchesSearchQuery(thread: Thread, query: String): Boolean {
  val normalizedQuery: String = normalizeSearchText(query)
  if (normalizedQuery.isEmpty()) return false
  val haystack: String = normalizeSearchText(threadPullRequestSearchTerms(thread).joinToString(" "))
  return normalizedQuery.split(" ").all { token -> haystack.contains(token) }
}

/**
 * Which result the keyboard has, upstream's `searchHighlight`: an index into the results as
 * they were ordered ([orderKey]). Once they reorder it's back on the first.
 */
data class SearchHighlight(val orderKey: String = "", val index: Int = 0) {
  /** `activeSearchResultIndex`. */
  fun activeIn(orderKey: String): Int = if (this.orderKey == orderKey) index else 0
}

/** `threadSearchResultOrderKey`: the results' ids, in order. */
fun searchResultOrderKey(results: List<Thread>): String = results.joinToString("\n") { it.id }

/**
 * Up or Down from the search field, `handleThreadSearchKeyDown`: the next or previous result,
 * wrapping round. Null with no results.
 */
fun moveSearchHighlight(active: Int, count: Int, down: Boolean): Int? {
  if (count == 0) return null
  return if (down) (active + 1) % count else (active - 1 + count) % count
}

/**
 * `HighlightedSearchText`, apps/web/src/components/ThreadSearchMatch.tsx: [text] cut into runs,
 * true where it matches [query]. Case folds only A to Z, so the runs line up with [text].
 */
fun highlightSearchText(text: String, query: String): List<Pair<String, Boolean>> {
  val trimmed: String = query.trim()
  if (trimmed.isEmpty()) return listOf(text to false)
  val normalizedText: String = foldAsciiCase(text)
  val normalizedQuery: String = foldAsciiCase(trimmed)
  val parts: MutableList<Pair<String, Boolean>> = mutableListOf()
  var cursor = 0
  while (cursor < text.length) {
    val matchIndex: Int = normalizedText.indexOf(normalizedQuery, cursor)
    if (matchIndex == -1) {
      parts += text.substring(cursor) to false
      break
    }
    if (matchIndex > cursor) parts += text.substring(cursor, matchIndex) to false
    parts += text.substring(matchIndex, matchIndex + trimmed.length) to true
    cursor = matchIndex + trimmed.length
  }
  return parts
}

/** `foldAsciiCase`, ThreadSearchMatch.tsx. */
private fun foldAsciiCase(value: String): String =
  String(CharArray(value.length) { i -> value[i].let { if (it in 'A'..'Z') it + 32 else it } })
