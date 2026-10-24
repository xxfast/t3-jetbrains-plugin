package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.ide.isUnder
import io.github.xxfast.t3code.protocol.generated.MessageContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// Composer context, the structured records that ride on a user message. Ported from upstream's
// packages/contracts/src/composerContext.ts, packages/shared/src/composerContextReferences.ts,
// apps/web/src/reviewCommentContext.ts and apps/web/src/lib/composerContextRecords.ts.

/** `COMPOSER_CONTEXT_MAX_RECORDS`. */
const val COMPOSER_CONTEXT_MAX_RECORDS: Int = 200

/** `COMPOSER_CONTEXT_LABEL_MAX_CHARS`. */
const val COMPOSER_CONTEXT_LABEL_MAX_CHARS: Int = 200

/** `COMPOSER_CONTEXT_REVIEW_TEXT_MAX_CHARS`. */
const val COMPOSER_CONTEXT_REVIEW_TEXT_MAX_CHARS: Int = 16_000

/** `COMPOSER_CONTEXT_REVIEW_DIFF_MAX_CHARS`. */
const val COMPOSER_CONTEXT_REVIEW_DIFF_MAX_CHARS: Int = 32_000

/** `ATTACHMENT_ONLY_BOOTSTRAP_PROMPT`, the text of a message that is only attachments. */
const val ATTACHMENT_ONLY_BOOTSTRAP_PROMPT: String = "[User attached one or more files without " +
  "additional text. Respond using the conversation context and the attached files.]"

private const val REVIEW_COMMENT_KIND: String = "review-comment"
private const val CONTEXT_HREF_PREFIX: String = "t3-context://v1/"
private const val TRUNCATION_MARKER: String = "\n… truncated …"
private const val MAX_LINK_LABEL_LENGTH: Int = 512

private val CONTEXT_ID_PATTERN: Regex = Regex("^[a-z0-9_-]{1,128}$", RegexOption.IGNORE_CASE)
private val CONTEXT_KIND_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{0,39}$")
private val CONTEXT_LINK: Regex = Regex(
  "(!?)\\[([^\\]\\n]{0,$MAX_LINK_LABEL_LENGTH})\\]\\((${Regex.escape(CONTEXT_HREF_PREFIX)}" +
    "[^\\s)]{1,200})\\)",
)
// A link and the one space after it, as `withContextReferences` leaves them
private val CONTEXT_LINK_AND_SPACE: Regex = Regex("${CONTEXT_LINK.pattern} ?")
private val DIFF_RANGE: Regex = Regex("^([+-])(\\d+)(?: to \\1(\\d+))?$")

/** Upstream's `ReviewCommentContext`, apps/web/src/reviewCommentContext.ts. */
@Serializable
data class ReviewCommentContext(
  val id: String,
  val sectionId: String,
  val sectionTitle: String,
  val filePath: String,
  val startIndex: Int,
  val endIndex: Int,
  val rangeLabel: String,
  val text: String,
  val diff: String,
  val fenceLanguage: String? = null,
  /** The pull request a `#` reference or a hand-off is about. */
  val pullRequest: PullRequestContextMetadata? = null,
)

/**
 * `buildFileReviewComment`: lines [startLine] to [endLine] of [contents], 1-based and in either
 * order, as a comment on [filePath]. [text] is the user's note.
 */
fun fileReviewComment(
  id: String,
  filePath: String,
  startLine: Int,
  endLine: Int,
  text: String,
  contents: String,
): ReviewCommentContext {
  val start: Int = maxOf(1, minOf(startLine, endLine))
  val end: Int = maxOf(start, maxOf(startLine, endLine))
  val lines: List<String> = contents.split("\n")
  val selected: List<String> = lines.subList(minOf(start - 1, lines.size), minOf(end, lines.size))
  return fileReviewComment(id, filePath, start, end, text, selected)
}

private fun fileReviewComment(
  id: String,
  filePath: String,
  start: Int,
  end: Int,
  text: String,
  selected: List<String>,
): ReviewCommentContext =
  ReviewCommentContext(
    id = id,
    sectionId = "file:$filePath",
    sectionTitle = "File comment",
    filePath = filePath,
    startIndex = start - 1,
    endIndex = end - 1,
    rangeLabel = if (start == end) "L$start" else "L$start to L$end",
    text = text.trim(),
    diff = selected.joinToString("\n"),
    fenceLanguage = inferReviewCommentFenceLanguage(filePath),
  )

/**
 * The editor's last selection: [path]'s 1-based [lines] and their [text]. [id] is new each time
 * the selection changes, so its comment is a new context.
 */
data class EditorSelection(val id: String, val path: Path, val lines: IntRange, val text: String)

/**
 * [selection] as the file comment a send carries for a thread running in [cwd], or null when the
 * file is under neither [cwd] nor the project's [workspaceRoot]. A thread in a worktree still
 * gets the main checkout's files, by the same relative path. Ours, not upstream's: the web app
 * has no editor, so this is Claude Code's IDE selection on upstream's `buildFileReviewComment`.
 */
fun selectionComment(
  selection: EditorSelection,
  cwd: String?,
  workspaceRoot: String?,
): ReviewCommentContext? {
  val root: String = listOfNotNull(cwd, workspaceRoot).firstOrNull { root ->
    val path: Path? = runCatching { Path.of(root) }.getOrNull()
    path != null && isUnder(path, selection.path)
  } ?: return null
  return fileReviewComment(
    id = selection.id,
    filePath = commentFilePath(root, selection.path),
    start = selection.lines.first,
    end = selection.lines.last,
    text = "",
    selected = selection.text.split("\n"),
  )
}

/**
 * The path a comment names [file] by: relative to the thread's [cwd], with `/`, when the file is
 * under it, else absolute. The agent reads paths relative to where it runs.
 */
fun commentFilePath(cwd: String?, file: Path): String {
  val root: Path = cwd?.let { runCatching { Path.of(it) }.getOrNull() } ?: return file.toString()
  if (!isUnder(root, file) || file.normalize() == root.normalize()) return file.toString()
  return root.normalize().relativize(file.normalize()).joinToString("/")
}

/**
 * The 1-based lines a selection covers, from its 0-based start and end lines. A selection that
 * ends at the very start of a line, as a whole-line selection does, stops on the line before.
 */
fun selectedLines(startLine: Int, endLine: Int, endsAtLineStart: Boolean): IntRange {
  val last: Int = if (endsAtLineStart && endLine > startLine) endLine - 1 else endLine
  return (startLine + 1)..(last + 1)
}

/** `inferReviewCommentFenceLanguage`: the extension, a dotfile's name, else `text`. */
fun inferReviewCommentFenceLanguage(filePath: String): String {
  val normalized: String = filePath.replace("\\", "/")
  val fileName: String = normalized.substring(normalized.lastIndexOf('/') + 1).lowercase()
  val dot: Int = fileName.lastIndexOf('.')
  if (dot > 0 && dot < fileName.length - 1) return fileName.substring(dot + 1)
  if (fileName.startsWith(".") && fileName.length > 1) return fileName.substring(1)
  return "text"
}

private val fileCommentSequence: AtomicLong = AtomicLong()

/** `nextFileCommentId` in apps/web/src/components/files/fileCommentAnnotations.ts. */
fun nextFileCommentId(now: Long = System.currentTimeMillis()): String =
  "file-comment-$now-${fileCommentSequence.incrementAndGet()}"

/** `reviewCommentContextLabel`: `#<number>` for a pull request summary, else file and range. */
fun reviewCommentContextLabel(comment: ReviewCommentContext): String {
  val pullRequestNumber: Long? = pullRequestContextNumber(comment)
  if (isPullRequestSummaryContext(comment) && pullRequestNumber != null) {
    return "#$pullRequestNumber"
  }
  val range: MatchResult? = DIFF_RANGE.find(comment.rangeLabel)
  val label: String = if (range == null) {
    comment.rangeLabel
  } else {
    val (sign, from, to) = range.destructured
    val upTo: String = if (to.isNotEmpty()) " to L$to" else ""
    val before: String = if (sign == "-") " (before)" else ""
    "L$from$upTo$before"
  }
  return "${basename(comment.filePath)} $label"
}

private val LEGACY_PULL_REQUEST_PATH: Regex = Regex("^PR #(\\d+)$")

/** `isPullRequestSummaryContext`, apps/web/src/lib/composerContextRecords.ts:77. */
fun isPullRequestSummaryContext(comment: ReviewCommentContext): Boolean {
  if (comment.pullRequest != null) return true
  return comment.sectionId.startsWith("pull-request:") &&
    comment.diff.trim().isEmpty() &&
    LEGACY_PULL_REQUEST_PATH.matches(comment.filePath)
}

/** `pullRequestContextNumber`, composerContextRecords.ts:86. */
private fun pullRequestContextNumber(comment: ReviewCommentContext): Long? {
  comment.pullRequest?.let { return it.number }
  return LEGACY_PULL_REQUEST_PATH.find(comment.filePath)?.groupValues?.get(1)?.toLongOrNull()
}

private fun basename(filePath: String): String = filePath.split('/', '\\').last()

/** `reviewCommentContextId`: `review-comment_<id>`, made safe by [toComposerContextId]. */
fun reviewCommentContextId(commentId: String): String =
  toComposerContextId("${REVIEW_COMMENT_KIND}_$commentId")

/** `toComposerContextId`: [producerId] when it's a valid id, else a slug and its digest. */
fun toComposerContextId(producerId: String): String {
  if (CONTEXT_ID_PATTERN.matches(producerId)) return producerId
  val slug: String = producerId
    .replace(Regex("[^a-z0-9_-]+", RegexOption.IGNORE_CASE), "-")
    .trim('-')
    .take(48)
  return "${slug.ifEmpty { "ctx" }}-${fnv1a64(producerId)}"
}

// Upstream's `fnv1a64`: two 32-bit FNV-1a passes, forwards and backwards, over UTF-16 units
internal fun fnv1a64(value: String): String {
  var forward: Int = 0x811c9dc5.toInt()
  var reverse: Int = 0x9dc5811c.toInt()
  for (index in value.indices) {
    forward = (forward xor value[index].code) * 0x01000193
    reverse = (reverse xor value[value.length - 1 - index].code) * 0x01000193
  }
  fun hex(word: Int): String = word.toUInt().toString(16).padStart(8, '0')
  return hex(forward) + hex(reverse)
}

/** `sanitizeComposerContextLabel`: one line, no brackets or backslashes, bounded. */
fun sanitizeComposerContextLabel(label: String, kind: String): String {
  val cleaned: String = label
    .replace(Regex("[\\[\\]\\\\\\r\\n]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(COMPOSER_CONTEXT_LABEL_MAX_CHARS)
  return cleaned.ifEmpty { kind }
}

private fun clampContextText(value: String, max: Int): String {
  if (value.length <= max) return value
  return value.take(maxOf(0, max - TRUNCATION_MARKER.length)) + TRUNCATION_MARKER
}

/** This comment's context id, what its reference in the text and its record share. */
val ReviewCommentContext.contextId: String get() = reviewCommentContextId(id)

/** The chip's and the reference's label. */
val ReviewCommentContext.label: String
  get() = sanitizeComposerContextLabel(reviewCommentContextLabel(this), REVIEW_COMMENT_KIND)

/** `reviewCommentContextRecord`, field for field. */
fun ReviewCommentContext.toRecord(): JsonObject = buildJsonObject {
  put("version", 1)
  put("contextId", contextId)
  put("kind", REVIEW_COMMENT_KIND)
  put("label", label)
  put("sectionId", sectionId)
  put("sectionTitle", sectionTitle)
  put("filePath", filePath)
  put("startIndex", startIndex)
  put("endIndex", endIndex)
  put("rangeLabel", rangeLabel)
  put("text", clampContextText(text, COMPOSER_CONTEXT_REVIEW_TEXT_MAX_CHARS))
  put("diff", clampContextText(diff, COMPOSER_CONTEXT_REVIEW_DIFF_MAX_CHARS))
  if (fenceLanguage != null) put("fenceLanguage", fenceLanguage)
  if (pullRequest != null) put("pullRequest", pullRequest.toJson())
}

/** `buildMessageContext`'s envelope: null when there's nothing to carry. */
fun messageContext(records: List<JsonObject>): MessageContext? =
  if (records.isEmpty()) null else MessageContext(version = 1, records = records)

/** The labels of a message's context records, in order. Undecodable records are skipped. */
fun contextLabels(context: MessageContext?): List<String> = context?.records.orEmpty()
  .mapNotNull { record -> ((record as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull }

/** A message's context records by `contextId`, the first one when two share an id. */
fun messageContextRecords(context: MessageContext?): Map<String, JsonElement> {
  val records: MutableMap<String, JsonElement> = linkedMapOf()
  for (record in context?.records.orEmpty()) {
    val id: String = ((record as? JsonObject)?.get("contextId") as? JsonPrimitive)
      ?.contentOrNull ?: continue
    records.putIfAbsent(id, record)
  }
  return records
}

/**
 * The labels of the records [text] doesn't cite with a context link. Upstream shows a record
 * only where its link is; one the text lost would be invisible, so its label is listed.
 */
fun uncitedContextLabels(context: MessageContext?, text: String): List<String> {
  val cited: Set<String> = inlineContextIds(text).toSet()
  return context?.records.orEmpty()
    .mapNotNull { record -> record as? JsonObject }
    .filter { record -> (record["contextId"] as? JsonPrimitive)?.contentOrNull !in cited }
    .mapNotNull { record -> (record["label"] as? JsonPrimitive)?.contentOrNull }
}

/** `formatComposerContextReference`: the markdown link a message's text cites a record by. */
fun contextReference(kind: String, contextId: String, label: String): String {
  val clean: String = sanitizeComposerContextLabel(label, kind)
  return "${if (kind == "image") "!" else ""}[$clean]($CONTEXT_HREF_PREFIX$kind/$contextId)"
}

/** [comment]'s reference, see [contextReference]. */
fun reviewCommentReference(comment: ReviewCommentContext): String =
  contextReference(REVIEW_COMMENT_KIND, comment.contextId, comment.label)

/**
 * [text] citing each of [references] it doesn't cite yet, at its end:
 * `ensureInlineContextReferences` (apps/web/src/lib/composerContextReferences.ts:104), which
 * `addComposerDraftReviewComment` (ChatComposer.tsx:2981) relies on, with
 * `inlineContextReferenceReplacement`'s spacing: a space before unless the text already ends in
 * whitespace, one between, one after. Upstream appends
 * when the comment is added; the plugin's plain text field can't show the link as a chip, so it
 * goes in at send time instead. The provider only sees records the text cites
 * (`projectComposerContextForProvider`).
 */
fun withContextReferences(text: String, references: List<String>): String {
  // By context id, like collectInlineContextIds: a link already there with another label counts
  val cited: MutableSet<String> = inlineContextIds(text).toMutableSet()
  val missing: List<String> = references.filter { reference ->
    cited.add(inlineContextIds(reference).singleOrNull() ?: reference)
  }
  if (missing.isEmpty()) return text
  val boundary: Boolean = text.isEmpty() || text.last() in " \n\t\r"
  return "$text${if (boundary) "" else " "}${missing.joinToString(" ")} "
}

/** `stripInlineContextReferences`, composerContextReferences.ts:36: [text] without its links. */
fun stripInlineContextReferences(text: String): String {
  if (!text.contains("](t3-context:")) return text
  return CONTEXT_LINK.replace(text) { match ->
    val parts: List<String> = match.groupValues[3].removePrefix(CONTEXT_HREF_PREFIX).split('/')
    val valid: Boolean = parts.size == 2 &&
      CONTEXT_KIND_PATTERN.matches(parts[0]) && CONTEXT_ID_PATTERN.matches(parts[1])
    if (valid) "" else match.value
  }
}

/** `collectInlineContextIds`: the context ids [text]'s links cite, in order, once each. */
fun inlineContextIds(text: String): List<String> {
  if (!text.contains("](t3-context:")) return emptyList()
  return CONTEXT_LINK.findAll(text)
    .mapNotNull { match ->
      val parts: List<String> = match.groupValues[3].removePrefix(CONTEXT_HREF_PREFIX).split('/')
      parts.takeIf { it.size == 2 }
        ?.takeIf { CONTEXT_KIND_PATTERN.matches(it[0]) && CONTEXT_ID_PATTERN.matches(it[1]) }
        ?.get(1)
    }
    .distinct()
    .toList()
}

/** `toKindScopedComposerContextId`: `<kind>_<producerId>`, made safe. */
fun toKindScopedComposerContextId(kind: String, producerId: String): String =
  toComposerContextId("${kind}_$producerId")

/** `parseComposerContextHref`: the kind and context id of a `t3-context://v1/` href, or null. */
fun parseComposerContextHref(href: String): Pair<String, String>? {
  if (!href.startsWith(CONTEXT_HREF_PREFIX)) return null
  val parts: List<String> = href.removePrefix(CONTEXT_HREF_PREFIX).split('/')
  if (parts.size != 2) return null
  val (kind: String, contextId: String) = parts
  if (!CONTEXT_KIND_PATTERN.matches(kind) || !CONTEXT_ID_PATTERN.matches(contextId)) return null
  return kind to contextId
}

/** `ComposerContextReferenceOccurrence`: one context link in a text, with its source range. */
data class ComposerContextReferenceOccurrence(
  val kind: String,
  val contextId: String,
  val label: String,
  val image: Boolean,
  val source: String,
  val start: Int,
  val end: Int,
)

/** `collectComposerContextReferences`, composerContextReferences.ts: [text]'s valid links. */
fun collectComposerContextReferences(text: String): List<ComposerContextReferenceOccurrence> {
  if (!text.contains("](t3-context:")) return emptyList()
  return CONTEXT_LINK.findAll(text).mapNotNull { match ->
    val (kind: String, contextId: String) =
      parseComposerContextHref(match.groupValues[3]) ?: return@mapNotNull null
    ComposerContextReferenceOccurrence(
      kind = kind,
      contextId = contextId,
      label = sanitizeComposerContextLabel(match.groupValues[2], kind),
      image = match.groupValues[1] == "!",
      source = match.value,
      start = match.range.first,
      end = match.range.last + 1,
    )
  }.toList()
}

/** [text] without its context links, for showing a sent message. Upstream draws them as chips. */
fun stripContextReferences(text: String): String {
  if (!text.contains("](t3-context:")) return text
  return CONTEXT_LINK_AND_SPACE
    .replace(text) { match ->
      val kind: String? = match.groupValues[3]
        .removePrefix(CONTEXT_HREF_PREFIX)
        .split('/')
        .takeIf { it.size == 2 && CONTEXT_ID_PATTERN.matches(it[1]) }
        ?.first()
      if (kind != null && CONTEXT_KIND_PATTERN.matches(kind)) "" else match.value
    }
    .trim()
}
