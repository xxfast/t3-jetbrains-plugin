package io.github.xxfast.t3code.plugin.ui

// Upstream's search ranking, `packages/shared/src/searchRanking.ts`, shared by the model picker
// and the composer's trigger menu. Lower scores rank first; null is no match.

/**
 * `normalizeSearchQuery`: trimmed and lowercased, with [trimLeadingPattern] stripped from the
 * start first when there is one.
 */
fun normalizeSearchQuery(input: String, trimLeadingPattern: Regex? = null): String {
  val trimmed: String = input.trim()
  if (trimmed.isEmpty()) return ""
  return if (trimLeadingPattern != null) {
    trimmed.replaceFirst(trimLeadingPattern, "").lowercase()
  } else {
    trimmed.lowercase()
  }
}

/** `scoreSubsequenceMatch`: the query's characters in order, penalised for the gaps between. */
internal fun scoreSubsequenceMatch(value: String, query: String, ignorePosition: Boolean): Int? {
  if (query.isEmpty()) return 0
  var queryIndex = 0
  var firstMatch = -1
  var previousMatch = -1
  var gapPenalty = 0
  value.forEachIndexed { index, char ->
    if (char != query[queryIndex]) return@forEachIndexed
    if (firstMatch == -1) firstMatch = index
    if (previousMatch != -1) gapPenalty += index - previousMatch - 1
    previousMatch = index
    queryIndex++
    if (queryIndex == query.length) {
      val spanPenalty: Int = index - firstMatch + 1 - query.length
      if (ignorePosition) return gapPenalty * 3 + spanPenalty
      val lengthPenalty: Int = minOf(64, value.length - query.length)
      return firstMatch * 2 + gapPenalty * 3 + spanPenalty + lengthPenalty
    }
  }
  return null
}

private val DEFAULT_BOUNDARY_MARKERS: List<String> = listOf(" ", "-", "_", "/")

/**
 * `scoreQueryMatch` on normalised inputs: exact, then prefix, word boundary, substring, fuzzy.
 * A null base skips that step, as an absent one does upstream.
 */
internal fun scoreQueryMatch(
  value: String,
  query: String,
  exactBase: Int,
  prefixBase: Int? = null,
  boundaryBase: Int? = null,
  includesBase: Int? = null,
  fuzzyBase: Int? = null,
  boundaryMarkers: List<String> = DEFAULT_BOUNDARY_MARKERS,
  ignorePosition: Boolean = false,
): Int? {
  val penalty: Int = if (ignorePosition) 0 else minOf(64, maxOf(0, value.length - query.length))
  if (value.isEmpty() || query.isEmpty()) return null
  if (value == query) return exactBase
  if (prefixBase != null && value.startsWith(query)) return prefixBase + penalty
  if (boundaryBase != null) {
    val boundary: Int? = boundaryMarkers
      .mapNotNull { marker ->
        value.indexOf("$marker$query").takeIf { it != -1 }?.plus(marker.length)
      }
      .minOrNull()
    if (boundary != null) {
      return boundaryBase + (if (ignorePosition) 0 else boundary * 2) + penalty
    }
  }
  if (includesBase != null) {
    val includes: Int = value.indexOf(query)
    if (includes != -1) return includesBase + (if (ignorePosition) 0 else includes * 2) + penalty
  }
  if (fuzzyBase != null) {
    scoreSubsequenceMatch(value, query, ignorePosition)?.let { return fuzzyBase + it }
  }
  return null
}

/** `RankedSearchResult`. */
class RankedSearchResult<T>(val item: T, val score: Int, val tieBreaker: String)

/**
 * `compareRankedSearchResults`: by score, then the tie-breaker. Upstream's `localeCompare` is
 * a plain comparison here; the tie-breakers are lowercased names, where the two agree.
 */
private fun <T> compareRankedSearchResults(
  left: RankedSearchResult<T>,
  right: RankedSearchResult<T>,
): Int {
  val scoreDelta: Int = left.score - right.score
  if (scoreDelta != 0) return scoreDelta
  return left.tieBreaker.compareTo(right.tieBreaker)
}

/** `insertRankedSearchResult`: [candidate] into the sorted [ranked], keeping at most [limit]. */
fun <T> insertRankedSearchResult(
  ranked: MutableList<RankedSearchResult<T>>,
  candidate: RankedSearchResult<T>,
  limit: Int,
) {
  if (limit <= 0) return
  // `findInsertionIndex`: after every entry that doesn't rank below the candidate
  var low = 0
  var high: Int = ranked.size
  while (low < high) {
    val middle: Int = low + (high - low) / 2
    val before: Boolean = compareRankedSearchResults(candidate, ranked[middle]) < 0
    if (before) high = middle else low = middle + 1
  }
  if (ranked.size < limit) {
    ranked.add(low, candidate)
    return
  }
  if (low >= limit) return
  ranked.add(low, candidate)
  ranked.removeAt(ranked.lastIndex)
}
