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
// live here, IDE-free; `MessageChips.kt` draws them.

/** Upstream's `ContextChipKind` (`ContextChip.tsx`): the chip's accent. */
enum class MessageChipKind(val accent: Long) {
  NEUTRAL(0),
  IMAGE(0xD55665),
  VIDEO(0xD06217),
  FILE(0x0090CD),
  MENTION(0x0096AF),
  TERMINAL(0x009F6E),
  ELEMENT(0xB87501),
  PREVIEW_ANNOTATION(0xB87501),
  REVIEW_COMMENT(0x8A70DD),
  PULL_REQUEST(0x7079E4),
  PR_OPEN(0x009F6E),
  PR_DRAFT(0x7F8793),
  PR_MERGED(0x8A70DD),
  PR_CLOSED(0xD55665),
  SKILL(0xB261BE),
  THREAD(0x009C96),
}

/** What a chip's leading icon shows; a file's is its type's, see [MessageChip.iconPath]. */
enum class MessageChipIcon {
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
 * file whose type icon a [MessageChipIcon.FILE] shows), a smaller [detail] after the label
 * (an attachment's size), the [tooltip], and what a click does. [unresolved] draws the dashed
 * border of a record the message no longer carries.
 */
data class MessageChip(
  val kind: MessageChipKind,
  val label: String,
  val icon: MessageChipIcon,
  val iconPath: String? = null,
  val detail: String? = null,
  val tooltip: String? = null,
  val action: MessageChipAction? = null,
  val unresolved: Boolean = false,
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
    kind = MessageChipKind.NEUTRAL,
    label = label,
    icon = MessageChipIcon.UNAVAILABLE,
    tooltip = "This context is no longer available",
    unresolved = true,
  )
  if (fields == null) return unavailable
  return when (kind) {
    "mention" -> {
      val path: String = fields.string("path") ?: return unavailable
      MessageChip(
        kind = MessageChipKind.MENTION,
        label = fields.string("label") ?: label,
        icon = if (path.endsWith('/')) MessageChipIcon.FOLDER else MessageChipIcon.FILE,
        iconPath = path,
        tooltip = path,
        action = MessageChipAction.OpenFile(path),
      )
    }

    "skill" -> {
      val name: String = fields.string("name") ?: return unavailable
      MessageChip(
        kind = MessageChipKind.SKILL,
        label = fields.string("label")?.ifEmpty { null } ?: name,
        icon = MessageChipIcon.SKILL,
        tooltip = "$$name",
      )
    }

    "thread" -> {
      val title: String = fields.string("title") ?: return unavailable
      val threadId: String? = fields.string("threadId")
      MessageChip(
        kind = MessageChipKind.THREAD,
        label = title,
        icon = MessageChipIcon.THREAD,
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
          image -> MessageChipKind.IMAGE
          video -> MessageChipKind.VIDEO
          else -> MessageChipKind.FILE
        },
        label = middleTruncateAttachmentName(name),
        icon = when {
          image -> MessageChipIcon.IMAGE
          video -> MessageChipIcon.VIDEO
          else -> MessageChipIcon.FILE
        },
        iconPath = name,
        detail = size,
        tooltip = if (size == null) name else "$name\n$size",
      )
    }

    "terminal" -> MessageChip(
      kind = MessageChipKind.TERMINAL,
      label = fields.string("label") ?: label,
      icon = MessageChipIcon.TERMINAL,
      tooltip = fields.string("terminalLabel"),
    )

    "element" -> MessageChip(
      kind = MessageChipKind.ELEMENT,
      label = fields.string("label") ?: label,
      icon = MessageChipIcon.ELEMENT,
      tooltip = fields.string("pageTitle")?.ifBlank { null } ?: fields.string("pageUrl"),
    )

    "preview-annotation" -> MessageChip(
      kind = MessageChipKind.PREVIEW_ANNOTATION,
      label = fields.string("label") ?: label,
      icon = MessageChipIcon.ELEMENT,
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

// `PULL_REQUEST_CHIP_KINDS` and the review-comment handler's icons
private fun reviewCommentChip(comment: ReviewCommentContext): MessageChip {
  val label: String = reviewCommentContextLabel(comment)
  val pullRequest: PullRequestContextMetadata? = comment.pullRequest
  if (!isPullRequestSummaryContext(comment)) {
    return MessageChip(
      kind = MessageChipKind.REVIEW_COMMENT,
      label = label,
      icon = MessageChipIcon.REVIEW_COMMENT,
      tooltip = listOf(comment.filePath, comment.rangeLabel, comment.text.trim())
        .filter { it.isNotEmpty() }
        .joinToString("\n"),
    )
  }
  // `pullRequestContextDisplayState`
  val state: String? = pullRequest?.let {
    if (it.state == "open" && it.isDraft) "draft" else it.state
  }
  return MessageChip(
    kind = when (state) {
      "open" -> MessageChipKind.PR_OPEN
      "draft" -> MessageChipKind.PR_DRAFT
      "merged" -> MessageChipKind.PR_MERGED
      "closed" -> MessageChipKind.PR_CLOSED
      else -> MessageChipKind.PULL_REQUEST
    },
    label = label,
    icon = when (state) {
      "draft" -> MessageChipIcon.PULL_REQUEST_DRAFT
      "merged" -> MessageChipIcon.PULL_REQUEST_MERGED
      "closed" -> MessageChipIcon.PULL_REQUEST_CLOSED
      else -> MessageChipIcon.PULL_REQUEST
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
  kind = MessageChipKind.SKILL,
  label = formatProviderSkillDisplayName(skill),
  icon = MessageChipIcon.SKILL,
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
  kind = MessageChipKind.MENTION,
  label = fileLinkLabel(file),
  icon = if (file.path.endsWith('/')) MessageChipIcon.FOLDER else MessageChipIcon.FILE,
  iconPath = file.path,
  tooltip = file.path,
  action = MessageChipAction.OpenFile(file.path, file.line),
)
