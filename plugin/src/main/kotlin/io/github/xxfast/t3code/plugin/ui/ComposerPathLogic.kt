package io.github.xxfast.t3code.plugin.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The composer's `@` menu: files and folders from `projects.searchEntries`, threads from the
// shell. Ported from upstream's `packages/shared/src/composerTrigger.ts`,
// `apps/web/src/composer-logic.ts`, `apps/web/src/state/queries.ts`,
// `packages/client-runtime/src/composerThreadItems.ts` and
// `apps/web/src/lib/composerContextRecords.ts`.

/** `PROJECT_PATH_SEARCH_DEBOUNCE_MS`, apps/web/src/state/queries.ts:35. */
const val PROJECT_PATH_SEARCH_DEBOUNCE_MS: Long = 120

/** `COMPOSER_PATH_SEARCH_LIMIT`, apps/web/src/state/queries.ts:36. */
const val COMPOSER_PATH_SEARCH_LIMIT: Int = 80

/** `COMPOSER_THREAD_RESULT_LIMIT`, composerThreadItems.ts:3. */
const val COMPOSER_THREAD_RESULT_LIMIT: Int = 5

/** The `kind` of a `ProjectEntry` that's a folder. */
const val PROJECT_ENTRY_DIRECTORY: String = "directory"

/**
 * `ComposerPathSearchTarget`: what a search was asked for. The query is trimmed, as
 * `useProjectPathSearch` normalises it.
 */
data class ComposerPathSearchTarget(
  val environmentId: String?,
  val cwd: String?,
  val query: String,
)

/** `basenameOfPath`, apps/web/src/pierre-icons.ts:147: after the last `/` only. */
fun basenameOfPath(path: String): String {
  val slash: Int = path.lastIndexOf('/')
  return if (slash == -1) path else path.substring(slash + 1)
}

/** A path row's description, ChatComposer.tsx:2673: everything before the last `/`. */
fun pathEntryDescription(path: String): String = path.substring(0, maxOf(0, path.lastIndexOf('/')))

/**
 * `isPathSuggestionCurrent`, apps/web/src/composer-logic.ts:31: a path row only applies while
 * the trigger is still `@` with the query it was searched for, on the same server and cwd.
 */
fun isPathSuggestionCurrent(
  searchTarget: ComposerPathSearchTarget,
  trigger: ComposerTrigger?,
  environmentId: String?,
  cwd: String?,
): Boolean = trigger?.kind == ComposerTriggerKind.PATH &&
  searchTarget.environmentId == environmentId &&
  searchTarget.cwd == cwd &&
  searchTarget.query == trigger.query.trim()

/** `ComposerThreadCandidate`, composerThreadItems.ts:5. */
data class ComposerThreadCandidate(
  val environmentId: String,
  val id: String,
  val title: String,
  val updatedAt: String,
  val archivedAt: String?,
)

/** `ComposerThreadItem`, composerThreadItems.ts:13, `thread` flattened. */
data class ComposerThreadItem(
  val id: String,
  val environmentId: String,
  val threadId: String,
  val label: String,
  val description: String,
)

/**
 * `matchComposerThreadItems`, composerThreadItems.ts:25: up to five unarchived threads on the
 * composer's own server whose title contains the query, newest first, never the thread itself.
 * The agent can only read threads on its own server.
 */
fun matchComposerThreadItems(
  shells: List<ComposerThreadCandidate>,
  environmentId: String,
  excludeThreadId: String?,
  query: String,
): List<ComposerThreadItem> {
  val needle: String = query.trim().lowercase()
  if (needle.isEmpty()) return emptyList()
  return shells
    .filter { shell ->
      shell.environmentId == environmentId &&
        shell.id != excludeThreadId &&
        shell.archivedAt == null &&
        shell.title.lowercase().contains(needle)
    }
    // `right.updatedAt.localeCompare(left.updatedAt)`: ISO strings, so code order agrees
    .sortedByDescending { it.updatedAt }
    .take(COMPOSER_THREAD_RESULT_LIMIT)
    .map { shell ->
      ComposerThreadItem(
        id = "thread:${shell.environmentId}:${shell.id}",
        environmentId = shell.environmentId,
        threadId = shell.id,
        label = shell.title,
        description = "Thread",
      )
    }
}

private const val THREAD_KIND: String = "thread"

/** Upstream's `ThreadContextRecord` (packages/contracts/src/composerContext.ts:207). */
data class ThreadContextRecord(
  val contextId: String,
  val label: String,
  val environmentId: String,
  val threadId: String,
  val title: String,
)

/** `threadContextRecord`, apps/web/src/lib/composerContextRecords.ts:161. */
fun threadContextRecord(
  environmentId: String,
  threadId: String,
  title: String,
): ThreadContextRecord {
  val label: String = sanitizeComposerContextLabel(title, THREAD_KIND)
  return ThreadContextRecord(
    // `threadContextId`: `toKindScopedComposerContextId("thread", threadId)`
    contextId = toComposerContextId("${THREAD_KIND}_$threadId"),
    label = label,
    environmentId = environmentId,
    threadId = threadId,
    title = label,
  )
}

/**
 * `threadContextReference` (composerContextRecords.ts:157) formatted by
 * `formatComposerContextReference` (packages/shared/src/composerContextReferences.ts:80).
 */
fun threadContextReference(record: ThreadContextRecord): String =
  contextReference(THREAD_KIND, record.contextId, record.label)

/** The record as it goes in `message.dispatch.context`, field for field. */
fun ThreadContextRecord.toRecord(): JsonObject = buildJsonObject {
  put("version", 1)
  put("kind", THREAD_KIND)
  put("contextId", contextId)
  put("label", label)
  put("environmentId", environmentId)
  put("threadId", threadId)
  put("title", title)
}
