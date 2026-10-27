package io.github.xxfast.t3code.plugin.web

import io.github.xxfast.t3code.plugin.actions.NO_THREAD
import io.github.xxfast.t3code.protocol.generated.PreviewListResult
import io.github.xxfast.t3code.protocol.generated.PreviewSessionSnapshot

const val NO_PREVIEW: String = "No preview is open for this thread"

/**
 * [threadId]'s tabs in a `preview.list` answer. The server already lists one thread's; a
 * snapshot's `threadId` is what ties it to one (`PreviewSessionSnapshot` in preview.ts).
 */
fun previewSessionsFor(
  result: PreviewListResult,
  threadId: String,
): List<PreviewSessionSnapshot> = result.sessions.filter { it.threadId == threadId }

/**
 * Why "Open preview" is off, or null when it's on. [sessions] is null when `preview.list`
 * hasn't answered for the thread yet; then it's on, and the click asks again.
 */
fun openPreviewDisabledReason(
  hasThread: Boolean,
  environmentId: String?,
  sessions: List<PreviewSessionSnapshot>?,
): String? = when {
  !hasThread -> NO_THREAD
  environmentId == null -> "Waiting for the server's config"
  sessions != null && sessions.isEmpty() -> NO_PREVIEW
  else -> null
}
