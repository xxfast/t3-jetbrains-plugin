package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.generated.ProjectEntry
import io.github.xxfast.t3code.protocol.generated.ProjectSearchEntriesInput
import io.github.xxfast.t3code.protocol.generated.ProjectSearchEntriesResult

/**
 * A stand-in for the server's `projects.searchEntries` over the scenario's [entries]: a name
 * that starts with the query first, then one that contains it, then a path that does, then a
 * path that has its letters in order. Not upstream's ranking; just enough to design the menu.
 */
fun fixtureSearchEntries(
  entries: List<ProjectEntry>,
  input: ProjectSearchEntriesInput,
): ProjectSearchEntriesResult {
  val query: String = input.query.trim().lowercase()
  val ranked: List<ProjectEntry> = entries
    .mapNotNull { entry -> rank(entry.path.lowercase(), query)?.let { entry to it } }
    .sortedWith(compareBy({ it.second }, { it.first.path.length }, { it.first.path }))
    .map { it.first }
  val limit: Int = input.limit.toInt()
  return ProjectSearchEntriesResult(entries = ranked.take(limit), truncated = ranked.size > limit)
}

private fun rank(path: String, query: String): Int? {
  val name: String = path.substringAfterLast('/')
  return when {
    query.isEmpty() -> 0
    name.startsWith(query) -> 0
    name.contains(query) -> 1
    path.contains(query) -> 2
    isSubsequence(query, path) -> 3
    else -> null
  }
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
  var at = 0
  for (char in haystack) {
    if (at < needle.length && needle[at] == char) at += 1
  }
  return at == needle.length
}
