package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.generated.SearchThreadsInput
import io.github.xxfast.t3code.protocol.generated.SearchThreadsResult
import io.github.xxfast.t3code.protocol.generated.ThreadSearchMatch
import io.github.xxfast.t3code.protocol.generated.TurnItem

/**
 * `orchestration.searchThreads` over the scenario, as the server's query has it
 * (apps/server/src/orchestration-v2/ThreadSearch.ts): finished user and assistant messages of
 * threads that aren't archived, containing the query case-insensitively. One match a thread,
 * its newest user message ahead of any assistant one; user matches first, then threads by
 * `updatedAt`, newest first. [itemsOf] answers a thread's items.
 */
fun fixtureSearchThreads(
  input: SearchThreadsInput,
  threads: List<Thread>,
  itemsOf: (threadId: String) -> List<TurnItem>,
): SearchThreadsResult {
  val query: String = input.query.trim()
  val messageOrder: Comparator<TurnItem> = compareBy<TurnItem> { it !is TurnItem.UserMessage }
    .thenByDescending { it.createdAt() }
    .thenBy { it.id }
  val matches: List<Pair<Thread, ThreadSearchMatch>> = threads
    .filter { !it.archived }
    .mapNotNull { thread ->
      val item: TurnItem = itemsOf(thread.id)
        .filter { it.messageText()?.contains(query, ignoreCase = true) == true }
        .minWithOrNull(messageOrder)
        ?: return@mapNotNull null
      thread to ThreadSearchMatch(
        threadId = thread.id,
        projectId = thread.projectId,
        source = if (item is TurnItem.UserMessage) "user" else "assistant",
        snippet = buildSearchSnippet(item.messageText().orEmpty(), query),
        messageCreatedAt = item.createdAt(),
      )
    }
  return SearchThreadsResult(
    matches
      .sortedWith(
        compareBy<Pair<Thread, ThreadSearchMatch>> { (_, match) -> match.source != "user" }
          .thenByDescending { (thread, _) -> thread.updatedAt }
          .thenBy { (thread, _) -> thread.id },
      )
      .take((input.limit ?: 50).toInt())
      .map { (_, match) -> match },
  )
}

/** `buildSearchSnippet`, ThreadSearch.ts: the text around the match, at most 240 characters. */
fun buildSearchSnippet(text: String, query: String): String {
  val normalizedText: String = text.replace(WHITESPACE, " ").trim()
  if (normalizedText.length <= 240) return normalizedText
  val normalizedQuery: String = foldAsciiCase(query.replace(WHITESPACE, " ").trim())
  val matchIndex: Int = foldAsciiCase(normalizedText).indexOf(normalizedQuery)
  val bodyLength = 236
  val idealStart: Int = maxOf(0, matchIndex - 72)
  val start: Int = minOf(idealStart, normalizedText.length - bodyLength)
  val end: Int = minOf(normalizedText.length, start + bodyLength)
  val head: String = if (start > 0) "…" else ""
  val tail: String = if (end < normalizedText.length) "…" else ""
  return head + normalizedText.substring(start, end) + tail
}

/** What the server searches: a user message, or an assistant one that's done streaming. */
private fun TurnItem.messageText(): String? = when (this) {
  is TurnItem.UserMessage -> text
  is TurnItem.AssistantMessage -> if (streaming) null else text
  else -> null
}

/** The message's `created_at`; items carry when they started instead. */
private fun TurnItem.createdAt(): String = startedAt ?: updatedAt

/** `foldAsciiCase`, ThreadSearch.ts: only A to Z, so indices line up with the original. */
private fun foldAsciiCase(value: String): String =
  String(CharArray(value.length) { i -> value[i].let { if (it in 'A'..'Z') it + 32 else it } })

private val WHITESPACE: Regex = Regex("\\s+")
