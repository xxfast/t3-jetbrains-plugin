package io.github.xxfast.t3code.plugin.review

import com.intellij.openapi.util.Key

/**
 * Where a diff in the IDE's viewer came from, so review actions can tell a T3 diff from any
 * other: the thread, the file's path as T3 names it, and the run when the diff is a turn's.
 * [excerpt] means the sides are hunks or an edit's fragment, not whole files, so their line
 * numbers aren't the file's.
 */
data class T3DiffSource(
  val threadId: String,
  val path: String,
  val runId: String?,
  val runOrdinal: Long?,
  val excerpt: Boolean,
)

/** On every [com.intellij.diff.requests.DiffRequest] `T3Opener` shows. */
val T3_DIFF_SOURCE: Key<T3DiffSource> = Key.create("t3code.diffSource")
