package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Chips in a sent message, what upstream's `ChatMarkdown` draws in place of a context link
// (`UserMessageContextChips.tsx`), a `$skill` token (`SkillInlineText.tsx`) and a file link or
// an inline code path (`MarkdownFileLink.tsx`). The rules that pick a chip's kind, label and icon
// live here, IDE-free; `MessageChips.kt` draws them. The kinds, states and icons are the
// composer's too, `ContextChip.kt` draws all of them.

/**
 * Upstream's `ContextChipKind`, `contextChipVariants`' `kind` (`ContextChip.tsx`): the chip's
 * [accent], its key in [CONTEXT_CHIP_ACCENTS], whose ink `contextChipVariables` solves per
 * theme. Neutral has none, upstream's `currentColor`.
 */
enum class ContextChipKind(val accent: String?) {
  NEUTRAL(null),
  IMAGE("image"),
  VIDEO("video"),
  FILE("file"),
  MENTION("mention"),
  TERMINAL("terminal"),
  ELEMENT("element"),
  PREVIEW_ANNOTATION("preview-annotation"),
  REVIEW_COMMENT("review-comment"),
  PULL_REQUEST("pull-request"),
  PR_OPEN("pr-open"),
  PR_DRAFT("pr-draft"),
  PR_MERGED("pr-merged"),
  PR_CLOSED("pr-closed"),
  SKILL("skill"),
  THREAD("thread"),
  CITATION("citation"),
  DOC_PDF("doc-pdf"),
  DOC_WORD("doc-word"),
  DOC_SPREADSHEET("doc-spreadsheet"),
  DOC_SLIDES("doc-slides"),
  DOC_EPUB("doc-epub"),
}

/**
 * One of the `doc-*` kinds, whose icon upstream strokes in the chip's ink
 * (`--document-icon-stroke` in `ContextChip.tsx`).
 */
val ContextChipKind.isDocument: Boolean
  get() = accent?.startsWith("doc-") == true

// `T3_PIERRE_ICONS.byFileExtension`'s document families (apps/web/src/pierre-icons.ts), through
// `DOCUMENT_CHIP_KINDS` to the chip kind each one takes
private val DOCUMENT_CHIP_KINDS: Map<String, ContextChipKind> = mapOf(
  "pdf" to ContextChipKind.DOC_PDF,
  "doc" to ContextChipKind.DOC_WORD,
  "docx" to ContextChipKind.DOC_WORD,
  "odt" to ContextChipKind.DOC_WORD,
  "rtf" to ContextChipKind.DOC_WORD,
  "xls" to ContextChipKind.DOC_SPREADSHEET,
  "xlsx" to ContextChipKind.DOC_SPREADSHEET,
  "ods" to ContextChipKind.DOC_SPREADSHEET,
  "csv" to ContextChipKind.DOC_SPREADSHEET,
  "tsv" to ContextChipKind.DOC_SPREADSHEET,
  "ppt" to ContextChipKind.DOC_SLIDES,
  "pptx" to ContextChipKind.DOC_SLIDES,
  "odp" to ContextChipKind.DOC_SLIDES,
  "epub" to ContextChipKind.DOC_EPUB,
)

/**
 * `documentChipKindForFileName` (apps/web/src/pierre-icons.ts): the document kind for a file
 * whose icon is one of the document families, by its extension in any case, else null.
 */
fun documentChipKindForFileName(fileName: String): ContextChipKind? {
  val basename: String = fileBasename(fileName)
  if ('.' !in basename) return null
  return DOCUMENT_CHIP_KINDS[basename.substringAfterLast('.').lowercase()]
}

/**
 * `contextChipVariants`' `state`: unresolved inks in the current text colour (a record that's
 * gone), invalid in the error one (a failure).
 */
enum class ContextChipState { NORMAL, UNRESOLVED, INVALID }

/** What a chip's leading icon shows; a file's is its type's, see [MessageChip.iconPath]. */
enum class ContextChipIcon {
  SKILL,
  FILE,
  FOLDER,
  IMAGE,
  VIDEO,
  THREAD,
  TERMINAL,
  ELEMENT,
  PULL_REQUEST,
  PULL_REQUEST_DRAFT,
  PULL_REQUEST_MERGED,
  PULL_REQUEST_CLOSED,
  REVIEW_COMMENT,
  UNAVAILABLE,
}

/** What a click on a chip does. Null when it only shows. */
sealed interface MessageChipAction {
  /** Open [path], relative to the thread's cwd or absolute, at 1-based [line]. */
  data class OpenFile(val path: String, val line: Int? = null) : MessageChipAction

  data class OpenUrl(val url: String) : MessageChipAction

  data class OpenThread(val threadId: String) : MessageChipAction
}

/**
 * One chip: upstream's [kind] for its colours, the [label], the [icon] ([iconPath] names the
 * file whose type icon a [ContextChipIcon.FILE] shows), a smaller [detail] after the label
 * (an attachment's size), the [tooltip], and what a click does. A [ContextChipState.UNRESOLVED]
 * [state] is a record the message no longer carries, inked in the text colour.
 */
data class MessageChip(
  val kind: ContextChipKind,
  val label: String,
  val icon: ContextChipIcon,
  val iconPath: String? = null,
  val detail: String? = null,
  val tooltip: String? = null,
  val action: MessageChipAction? = null,
  val state: ContextChipState = ContextChipState.NORMAL,
)

private val lenientJson: Json = Json { ignoreUnknownKeys = true }

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * `userMessageContextPresentationRegistry` (`UserMessageContextChips.tsx`): the chip for a
 * context link of [kind] and [contextId] labelled [label], given the message's [record] for
 * it. A record of another kind, or none, is `UnavailableUserMessageContextChip`.
 */
fun messageContextChip(
  kind: String,
  contextId: String,
  label: String,
  record: JsonElement?,
): MessageChip {
  val fields: JsonObject? = (record as? JsonObject)?.takeIf { it.string("kind") == kind }
  val unavailable = MessageChip(
    kind = ContextChipKind.NEUTRAL,
    label = label,
    icon = ContextChipIcon.UNAVAILABLE,
    tooltip = "This context is no longer available",
    state = ContextChipState.UNRESOLVED,
  )
  if (fields == null) return unavailable
  return when (kind) {
    "mention" -> {
      val path: String = fields.string("path") ?: return unavailable
      MessageChip(
        kind = documentChipKindForFileName(path) ?: ContextChipKind.MENTION,
        label = fields.string("label") ?: label,
        icon = if (path.endsWith('/')) ContextChipIcon.FOLDER else ContextChipIcon.FILE,
        iconPath = path,
        tooltip = path,
        action = MessageChipAction.OpenFile(path),
      )
    }

    "skill" -> {
      val name: String = fields.string("name") ?: return unavailable
      MessageChip(
        kind = ContextChipKind.SKILL,
        label = fields.string("label")?.ifEmpty { null } ?: name,
        icon = ContextChipIcon.SKILL,
        tooltip = "$$name",
      )
    }

    "thread" -> {
      val title: String = fields.string("title") ?: return unavailable
      val threadId: String? = fields.string("threadId")
      MessageChip(
        kind = ContextChipKind.THREAD,
        label = title,
        icon = ContextChipIcon.THREAD,
        tooltip = title,
        action = threadId?.let { MessageChipAction.OpenThread(it) },
      )
    }

    "image", "file" -> {
      val name: String = fields.string("name") ?: return unavailable
      val size: String? = (fields["sizeBytes"] as? JsonPrimitive)
        ?.longOrNull
        ?.let(::formatAttachmentSize)
      val mimeType: String = fields.string("mimeType").orEmpty()
      // `contextChipPresentation`: a file that's an image looks like one, a video has its own
      val image: Boolean = kind == "image" || isSupportedImageMimeType(mimeType)
      val video: Boolean = !image && mimeType.startsWith("video/")
      MessageChip(
        kind = when {
          image -> ContextChipKind.IMAGE
          video -> ContextChipKind.VIDEO
          else -> documentChipKindForFileName(name) ?: ContextChipKind.FILE
        },
        label = middleTruncateAttachmentName(name),
        icon = when {
          image -> ContextChipIcon.IMAGE
          video -> ContextChipIcon.VIDEO
          else -> ContextChipIcon.FILE
        },
        iconPath = name,
        detail = size,
        tooltip = if (size == null) name else "$name\n$size",
      )
    }

    "terminal" -> MessageChip(
      kind = ContextChipKind.TERMINAL,
      label = fields.string("label") ?: label,
      icon = ContextChipIcon.TERMINAL,
      tooltip = fields.string("terminalLabel"),
    )

    "element" -> MessageChip(
      kind = ContextChipKind.ELEMENT,
      label = fields.string("label") ?: label,
      icon = ContextChipIcon.ELEMENT,
      tooltip = fields.string("pageTitle")?.ifBlank { null } ?: fields.string("pageUrl"),
    )

    "preview-annotation" -> MessageChip(
      kind = ContextChipKind.PREVIEW_ANNOTATION,
      label = fields.string("label") ?: label,
      icon = ContextChipIcon.ELEMENT,
      tooltip = fields.string("pageTitle")?.ifBlank { null } ?: fields.string("pageUrl"),
    )

    "review-comment" -> {
      // A record carries `contextId`, not the comment's `id`; the context id stands in
      val withId: JsonObject = JsonObject(fields + ("id" to JsonPrimitive(contextId)))
      val comment: ReviewCommentContext = runCatching {
        lenientJson.decodeFromJsonElement(ReviewCommentContext.serializer(), withId)
      }.getOrNull() ?: return unavailable
      reviewCommentChip(comment)
    }

    else -> unavailable
  }
}

/** `pullRequestContextDisplayState` (`composerContextRecords.ts`): draft is an open draft. */
fun pullRequestContextDisplayState(comment: ReviewCommentContext): String? =
  comment.pullRequest?.let { if (it.state == "open" && it.isDraft) "draft" else it.state }

/** `PULL_REQUEST_CHIP_KINDS` (`contextChipParts.tsx`) for [comment]'s display state. */
fun pullRequestChipKind(comment: ReviewCommentContext): ContextChipKind =
  when (pullRequestContextDisplayState(comment)) {
    "open" -> ContextChipKind.PR_OPEN
    "draft" -> ContextChipKind.PR_DRAFT
    "merged" -> ContextChipKind.PR_MERGED
    "closed" -> ContextChipKind.PR_CLOSED
    else -> ContextChipKind.PULL_REQUEST
  }

/**
 * The review-comment handler's chip: a comment's, or a pull request's by its state through
 * [pullRequestChipKind] and `PULL_REQUEST_STATE_PRESENTATION`'s icons.
 */
fun reviewCommentChip(comment: ReviewCommentContext): MessageChip {
  val label: String = reviewCommentContextLabel(comment)
  val pullRequest: PullRequestContextMetadata? = comment.pullRequest
  if (!isPullRequestSummaryContext(comment)) {
    return MessageChip(
      kind = ContextChipKind.REVIEW_COMMENT,
      label = label,
      icon = ContextChipIcon.REVIEW_COMMENT,
      tooltip = listOf(comment.filePath, comment.rangeLabel, comment.text.trim())
        .filter { it.isNotEmpty() }
        .joinToString("\n"),
    )
  }
  val state: String? = pullRequestContextDisplayState(comment)
  return MessageChip(
    kind = pullRequestChipKind(comment),
    label = label,
    icon = when (state) {
      "draft" -> ContextChipIcon.PULL_REQUEST_DRAFT
      "merged" -> ContextChipIcon.PULL_REQUEST_MERGED
      "closed" -> ContextChipIcon.PULL_REQUEST_CLOSED
      else -> ContextChipIcon.PULL_REQUEST
    },
    tooltip = pullRequest?.let {
      "#${it.number} ${it.title}\n${it.headBranch} → ${it.baseBranch}"
    },
    action = pullRequest?.let { MessageChipAction.OpenUrl(it.url) },
  )
}

/** `middleTruncateAttachmentName` (`composerInlineChip.ts`): the ends of a long name. */
fun middleTruncateAttachmentName(name: String, maxCharacters: Int = 36): String {
  val characters: List<Int> = name.codePoints().toArray().toList()
  if (characters.size <= maxCharacters) return name
  if (maxCharacters <= 0) return ""
  if (maxCharacters == 1) return "…"
  val available: Int = maxCharacters - 1
  val suffixLength: Int =
    minOf(available - 1, if (available >= 18) 14 else (available + 1) / 2)
  val prefixLength: Int = available - suffixLength
  fun join(points: List<Int>): String = buildString { points.forEach { appendCodePoint(it) } }
  val suffix: String = if (suffixLength == 0) "" else join(characters.takeLast(suffixLength))
  return "${join(characters.take(prefixLength))}…$suffix"
}

/** A `$skill` token in a text: its range and the skill. */
data class InlineSkillMatch(val start: Int, val end: Int, val skill: ServerProviderSkill)

// `SKILL_TOKEN_REGEX` (`packages/shared/src/inlineSkills.ts`): the composer's token form with
// the end of the text as a boundary too. `(?U)` makes `\s` Unicode like JavaScript's.
private val INLINE_SKILL_REGEX: Regex = Regex(
  "(?U)(^|\\s)\\p{Sc}(?![0-9][0-9_]*(?:[kKmMbBtT]|[eE][0-9]+)?(?:\\s|$))" +
    "(?=[a-zA-Z0-9:_-]*[a-zA-Z])([a-zA-Z0-9][a-zA-Z0-9:_-]*)(?=\\s|$)",
)

/** `matchInlineSkills`: the `$name` tokens in [text] that name one of [skills], in order. */
fun matchInlineSkills(text: String, skills: List<ServerProviderSkill>): List<InlineSkillMatch> {
  if (skills.isEmpty() || !text.any { it == '$' }) return emptyList()
  return INLINE_SKILL_REGEX.findAll(text).mapNotNull { match ->
    val name: String = match.groupValues[2]
    val skill: ServerProviderSkill = skills.find { it.name == name } ?: return@mapNotNull null
    InlineSkillMatch(match.range.first + match.groupValues[1].length, match.range.last + 1, skill)
  }.toList()
}

/** `SkillChip`: the skill's display name, upstream's `formatProviderSkillDisplayName`. */
fun skillChip(skill: ServerProviderSkill): MessageChip = MessageChip(
  kind = ContextChipKind.SKILL,
  label = formatProviderSkillDisplayName(skill),
  icon = ContextChipIcon.SKILL,
  tooltip = "$${skill.name}",
)

/** `FilePathPosition` (`packages/shared/src/fileLinks.ts`). */
data class FilePathPosition(val path: String, val line: Int? = null, val column: Int? = null)

private val POSITION_SUFFIX_CAPTURE: Regex = Regex(":(\\d+)(?::(\\d+))?$")
private val POSITION_HASH: Regex = Regex("^#L(\\d+)(?:C(\\d+))?$", RegexOption.IGNORE_CASE)
private val RELATIVE_PATH_PREFIX: Regex = Regex("^(~/|\\.{1,2}/)")
private val RELATIVE_FILE_PATH: Regex = Regex(
  "^(?:[A-Za-z0-9._-]+(?: +[A-Za-z0-9._-]+)*/)+" +
    "(?:[A-Za-z0-9._-]+(?: +[A-Za-z0-9._-]+)*(?::\\d+){0,2})?$",
)
private val RELATIVE_FILE_NAME: Regex =
  Regex("^[A-Za-z0-9._-]+(?: +[A-Za-z0-9._-]+)*\\.[A-Za-z0-9_-]+(?::\\d+){0,2}$")
private val EXTERNAL_SCHEME: Regex = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$")
private val POSITION_SUFFIX: Regex = Regex(":\\d+(?::\\d+)?$")
private val POSITION_ONLY: Regex = Regex("^\\d+(?::\\d+)?$")
private val INLINE_CODE_DISQUALIFIER: Regex = Regex("[\\s`]")
private val PATH_SEPARATOR: Regex = Regex("[\\\\/]")
private val FILE_EXTENSION: Regex = Regex("\\.[A-Za-z0-9_-]+$")
private val VERSION_SUFFIX: Regex = Regex("\\d\\.\\d[^.]*$")
private val NUMERIC_DOTTED: Regex = Regex("^\\d+(?:\\.\\d+)+$")
private val WINDOWS_DRIVE: Regex = Regex("^[A-Za-z]:[\\\\/]")
private val UNC_PATH: Regex = Regex("^\\\\\\\\[^\\\\]")
private val SLASH_PREFIXED_WINDOWS_DRIVE: Regex = Regex("^/[A-Za-z]:[\\\\/]")

private val POSIX_FILE_ROOT_PREFIXES: List<String> = listOf(
  "/Users/", "/home/", "/tmp/", "/var/", "/etc/", "/opt/", "/mnt/", "/Volumes/", "/private/",
  "/root/", "/usr/", "/bin/", "/sbin/", "/lib/", "/lib64/", "/srv/", "/dev/", "/proc/", "/sys/",
  "/run/", "/boot/", "/media/", "/workspace/", "/workspaces/",
)

private val EXTENSIONLESS_FILE_NAMES: Set<String> = setOf(
  "Makefile", "makefile", "GNUmakefile", "Dockerfile", "Containerfile", "Justfile", "justfile",
  "Rakefile", "Gemfile", "Procfile", "Brewfile", "Caddyfile", "Vagrantfile", "Jenkinsfile",
  "Podfile", "Fastfile", "BUILD", "WORKSPACE", "LICENSE", "LICENCE", "COPYING", "NOTICE",
  "AUTHORS", "CONTRIBUTORS", "CHANGELOG", "README", "CODEOWNERS",
)

private val SINGLE_LABEL_HOSTNAMES: Set<String> = setOf("localhost")

private val GENERIC_HOSTNAME_TLDS: Set<String> = setOf(
  "com", "net", "org", "io", "dev", "app", "ai", "co", "edu", "gov", "mil", "info", "biz",
  "xyz", "me", "tv", "cc", "gg", "chat", "cloud", "site", "online", "tech", "store", "link",
)

private val COUNTRY_HOSTNAME_TLDS: Set<String> = setOf(
  "uk", "de", "fr", "nl", "se", "no", "fi", "dk", "pl", "ch", "at", "be", "es", "it", "pt",
  "eu", "us", "ca", "au", "nz", "jp", "kr", "cn", "br", "ru", "mx", "ie", "cz", "tr", "sg", "hk",
)

private fun isWindowsAbsolutePath(value: String): Boolean =
  UNC_PATH.containsMatchIn(value) || WINDOWS_DRIVE.containsMatchIn(value)

private fun stripSlashPrefixedWindowsDrive(path: String): String =
  if (SLASH_PREFIXED_WINDOWS_DRIVE.containsMatchIn(path)) path.substring(1) else path

/** `fileBasename`: the last segment, trailing separators ignored. */
fun fileBasename(path: String): String {
  val trimmed: String = path.trimEnd('/', '\\')
  if (trimmed.isEmpty()) return path
  val separator: Int = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  return if (separator >= 0) trimmed.substring(separator + 1) else trimmed
}

/** `splitFilePathPosition`: a `:line[:column]` suffix, else a `#Lline[Ccolumn]` hash. */
fun splitFilePathPosition(path: String, hash: String = ""): FilePathPosition {
  val suffix: MatchResult? = POSITION_SUFFIX_CAPTURE.find(path)
  val match: MatchResult = suffix ?: POSITION_HASH.find(hash) ?: return FilePathPosition(path)
  val line: Int = match.groupValues[1].toIntOrNull() ?: return FilePathPosition(path)
  val column: Int? = match.groups[2]?.value?.toIntOrNull()
  return FilePathPosition(
    path = if (suffix != null) path.substring(0, path.length - suffix.value.length) else path,
    line = line.takeIf { it > 0 },
    column = column?.takeIf { it > 0 },
  )
}

private fun safeDecodeUriComponent(value: String): String =
  runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8) }
    .getOrDefault(value)

/** `normalizeMarkdownLinkDestination`: trimmed, without `<` and `>`. */
fun normalizeMarkdownLinkDestination(value: String): String {
  val trimmed: String = value.trim()
  return if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
    trimmed.substring(1, trimmed.length - 1)
  } else {
    trimmed
  }
}

private fun looksLikeHostname(segment: String, hasPosition: Boolean): Boolean {
  if (segment.startsWith(".")) return false
  val lowered: String = segment.lowercase()
  if (lowered in SINGLE_LABEL_HOSTNAMES) return true
  if (NUMERIC_DOTTED.matches(segment)) return true
  val labels: List<String> = lowered.split(".")
  if (labels.size < 2) return false
  val last: String = labels.last()
  if (last in GENERIC_HOSTNAME_TLDS) return true
  return !hasPosition && last in COUNTRY_HOSTNAME_TLDS
}

/**
 * `inlineCodeFilePathCandidate` (`packages/shared/src/markdownLinks.ts`): the path an inline
 * code span names, or null when it reads as code. A bare name needs a separator or a position;
 * a hostname, a version and a trailing slash stay code.
 */
fun inlineCodeFilePathCandidate(codeText: String): String? {
  val trimmed: String = codeText.trim()
  if (trimmed.isEmpty() || INLINE_CODE_DISQUALIFIER.containsMatchIn(trimmed)) return null
  val candidate: String =
    if (isWindowsAbsolutePath(trimmed)) trimmed else trimmed.replace("\\", "/")
  val hasPosition: Boolean = POSITION_SUFFIX.containsMatchIn(candidate)
  if (!hasPosition && !PATH_SEPARATOR.containsMatchIn(candidate)) return null
  val explicitShape: Boolean = RELATIVE_PATH_PREFIX.containsMatchIn(candidate) ||
    candidate.startsWith("/") ||
    isWindowsAbsolutePath(candidate)
  if (!explicitShape) {
    val withoutPosition: String = POSITION_SUFFIX.replace(candidate, "")
    if (withoutPosition.endsWith("/")) return null
    val firstSegment: String = withoutPosition.substringBefore("/")
    if (looksLikeHostname(firstSegment, hasPosition)) return null
    val basename: String = withoutPosition.trimEnd('/', '\\').split('/', '\\').last()
    if (VERSION_SUFFIX.containsMatchIn(basename)) return null
    if (!hasPosition && !FILE_EXTENSION.containsMatchIn(basename)) return null
  }
  return candidate
}

private fun looksLikePosixFilesystemPath(path: String): Boolean {
  if (!path.startsWith("/")) return false
  if (POSIX_FILE_ROOT_PREFIXES.any { path.startsWith(it) }) return true
  if (POSITION_SUFFIX.containsMatchIn(path)) return true
  val basename: String = path.substring(path.lastIndexOf('/') + 1)
  return basename in EXTENSIONLESS_FILE_NAMES || FILE_EXTENSION.containsMatchIn(basename)
}

private fun looksLikeFilePath(path: String, authoredPath: String): Boolean {
  if (isWindowsAbsolutePath(path) || RELATIVE_PATH_PREFIX.containsMatchIn(path)) return true
  if (path.startsWith("/")) return looksLikePosixFilesystemPath(authoredPath)
  if (path in EXTENSIONLESS_FILE_NAMES) return true
  return RELATIVE_FILE_PATH.matches(authoredPath) || RELATIVE_FILE_NAME.matches(path)
}

private fun hasExternalScheme(path: String): Boolean {
  if (isWindowsAbsolutePath(path)) return false
  val match: MatchResult = EXTERNAL_SCHEME.find(path) ?: return false
  val rest: String = match.groupValues[2]
  if (rest.startsWith("//")) return true
  return !POSITION_ONLY.matches(rest)
}

/**
 * `parseMarkdownFileLink`: the file a link destination names, with its position, or null for
 * anything else (a URL, a fragment, a word). `file:` URLs become paths.
 */
fun parseMarkdownFileLink(href: String): FilePathPosition? {
  val normalized: String = normalizeMarkdownLinkDestination(href)
  if (normalized.isEmpty() || normalized.startsWith("#") || normalized.startsWith("//")) {
    return null
  }
  // `parseFileUrlHref`: a `file:` URL is a host path, still percent-encoded; a non-localhost
  // authority is a UNC share
  val fileUrl: Pair<String, String>? = normalized
    .takeIf { it.lowercase().startsWith("file:") }
    ?.let { runCatching { URI(it) }.getOrNull() }
    ?.let { uri ->
      val host: String =
        uri.host?.takeUnless { it.equals("localhost", ignoreCase = true) }.orEmpty()
      val rawPath: String = uri.rawPath.orEmpty()
      val path: String =
        if (host.isEmpty()) rawPath else "\\\\$host${rawPath.replace("/", "\\")}"
      if (path.isEmpty()) return null
      val fragment: String = uri.rawFragment?.let { "#$it" }.orEmpty()
      stripSlashPrefixedWindowsDrive(path) to fragment
    }
  // `splitMarkdownLinkSearchAndHash`
  val (sourcePath: String, sourceHash: String) = fileUrl ?: run {
    val hashIndex: Int = normalized.indexOf('#')
    val pathWithSearch: String =
      if (hashIndex >= 0) normalized.substring(0, hashIndex) else normalized
    val hash: String = if (hashIndex >= 0) normalized.substring(hashIndex) else ""
    pathWithSearch.substringBefore('?') to hash
  }
  val path: String = stripSlashPrefixedWindowsDrive(safeDecodeUriComponent(sourcePath.trim()))
  val hash: String = safeDecodeUriComponent(sourceHash.trim())
  if (path.isEmpty() || hasExternalScheme(path)) return null
  val position: FilePathPosition = splitFilePathPosition(path, hash)
  return if (looksLikeFilePath(position.path, path)) position else null
}

/**
 * `isMarkdownFileLinkLabel`: [label] names the file [href] points at, so the chip replaces it;
 * otherwise the label stays text and the chip follows it.
 */
fun isMarkdownFileLinkLabel(label: String, href: String): Boolean {
  val destination: FilePathPosition = parseMarkdownFileLink(href) ?: return false
  fun normalize(path: String): String =
    path.replace("\\", "/").removePrefix("./").trimEnd('/')
  val labelPosition: FilePathPosition = splitFilePathPosition(label.trim())
  if (labelPosition.line != null && labelPosition.line != destination.line) return false
  if (labelPosition.column != null && labelPosition.column != destination.column) return false
  var labelPath: String = normalize(labelPosition.path)
  var destinationPath: String = normalize(destination.path)
  if (labelPath.isEmpty()) return true
  if (isWindowsAbsolutePath(destination.path)) {
    labelPath = labelPath.lowercase()
    destinationPath = destinationPath.lowercase()
  }
  return destinationPath == labelPath || destinationPath.endsWith("/$labelPath")
}

/** `fileLinkLabel`: the basename and `L<line>[:C<column>]`, dot-separated. */
fun fileLinkLabel(file: FilePathPosition): String = listOfNotNull(
  fileBasename(file.path),
  file.line?.let { line -> "L$line${file.column?.let { ":C$it" }.orEmpty()}" },
).joinToString(" · ")

/** `MarkdownFileLink`: a chip that opens [file] in the IDE. */
fun fileLinkChip(file: FilePathPosition): MessageChip = MessageChip(
  kind = documentChipKindForFileName(file.path) ?: ContextChipKind.MENTION,
  label = fileLinkLabel(file),
  icon = if (file.path.endsWith('/')) ContextChipIcon.FOLDER else ContextChipIcon.FILE,
  iconPath = file.path,
  tooltip = file.path,
  action = MessageChipAction.OpenFile(file.path, file.line),
)
