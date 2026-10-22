package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.text.TextRange
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

// Chips in the composer prompt. The draft stays the markdown that gets sent; chips are drawn over
// the references in it. Tokens are ported from upstream's
// packages/shared/src/composerInlineTokens.ts. Which tokens become chips follows the mobile
// editor's `composerContextEditorTokens` (apps/mobile/src/lib/composerContext.ts), which keeps
// plain text and draws chips over it as this field does. Labels follow the web editor's node
// views (ComposerPromptEditorTiptap.tsx, composerContextPresentation.tsx).

/** `ComposerInlineToken`'s `type`. */
enum class ComposerInlineTokenType { MENTION, SKILL }

/** `ComposerInlineToken`: a file mention or a `$skill`, with its source range. */
data class ComposerInlineToken(
  val type: ComposerInlineTokenType,
  val value: String,
  val source: String,
  val start: Int,
  val end: Int,
)

// `SKILL_MENTION_SOURCE` and its `(?=\s)` token form. `(?U)` makes `\s` Unicode like JavaScript's
private const val SKILL_MENTION_SOURCE: String = "(^|\\s)\\p{Sc}" +
  "(?![0-9][0-9_]*(?:[kKmMbBtT]|[eE][0-9]+)?(?:\\s|$))" +
  "(?=[a-zA-Z0-9:_-]*[a-zA-Z])([a-zA-Z0-9][a-zA-Z0-9:_-]*)"
private val SKILL_TOKEN_REGEX: Regex = Regex("(?U)$SKILL_MENTION_SOURCE(?=\\s)")
private val MENTION_TOKEN_REGEX: Regex =
  Regex("(?U)(^|\\s)@(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^\\s@\"]+))(?=\\s)")

/** `MAX_FILE_LINK_LABEL_LENGTH`: bounds the label so a run of `[` can't go quadratic. */
private const val MAX_FILE_LINK_LABEL_LENGTH: Int = 512
private val FILE_LINK_TOKEN_REGEX: Regex = Regex(
  "(?U)(^|\\s)\\[((?:\\\\.|[^\\]\\\\]){0,$MAX_FILE_LINK_LABEL_LENGTH})\\]\\(([^)\\s]+)\\)(?=\\s)",
)
private val URI_SCHEME_REGEX: Regex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val WINDOWS_DRIVE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/]")
private val SCOPED_PACKAGE_REFERENCE_REGEX: Regex =
  Regex("[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*(?:/[^\\s@\"]+)*")

// JavaScript's ID_Start and ID_Continue as the JDK knows them
private val DECORATOR_CALL_REFERENCE_REGEX: Regex = Regex(
  "^[\\p{javaUnicodeIdentifierStart}_$][\\p{javaUnicodeIdentifierPart}.$\\u200c\\u200d]*\\(",
)
private val ESCAPED_CHAR: Regex = Regex("\\\\(.)")

private fun collectMentionTokens(text: String): MutableList<ComposerInlineToken> {
  val matches: MutableList<ComposerInlineToken> = mutableListOf()

  for (match in FILE_LINK_TOKEN_REGEX.findAll(text)) {
    val prefix: String = match.groupValues[1]
    val label: String = ESCAPED_CHAR.replace(match.groupValues[2], "$1")
    val encodedPath: String = match.groupValues[3]
    val path: String = decodeUriComponent(encodedPath) ?: encodedPath
    val separator: Int = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val basename: String = if (separator >= 0) path.substring(separator + 1) else path
    val hasExternalScheme: Boolean =
      URI_SCHEME_REGEX.containsMatchIn(path) && !WINDOWS_DRIVE_PATH_REGEX.containsMatchIn(path)
    if (path.isEmpty() || hasExternalScheme || label != basename) continue
    val start: Int = match.range.first + prefix.length
    val end: Int = match.range.last + 1
    matches += ComposerInlineToken(
      ComposerInlineTokenType.MENTION,
      path,
      text.substring(start, end),
      start,
      end,
    )
  }

  for (match in MENTION_TOKEN_REGEX.findAll(text)) {
    val prefix: String = match.groupValues[1]
    val quotedPath: String? = match.groups[2]?.value
    val path: String = quotedPath?.let { ESCAPED_CHAR.replace(it, "$1") } ?: match.groupValues[3]
    val ambiguous: Boolean = quotedPath == null &&
      (SCOPED_PACKAGE_REFERENCE_REGEX.matches(path) ||
        DECORATOR_CALL_REFERENCE_REGEX.containsMatchIn(path))
    if (path.isEmpty() || ambiguous) continue
    val start: Int = match.range.first + prefix.length
    val end: Int = match.range.last + 1
    matches += ComposerInlineToken(
      ComposerInlineTokenType.MENTION,
      path,
      text.substring(start, end),
      start,
      end,
    )
  }

  return matches
}

/**
 * `collectComposerInlineTokens`: file links (`[name](path)`, what an `@` pick inserts), bare
 * `@path` mentions and `$skill`s, each only once whitespace follows it, so a token still being
 * typed stays text. A token from [preserveTrailingFrom] that still ends the text unchanged stays
 * a token after its trailing whitespace is deleted. Upstream also keeps tokens inside TeX
 * formulas out (`markdownMathRanges`); that needs remark-math, which the plugin doesn't have.
 */
fun collectComposerInlineTokens(
  text: String,
  preserveTrailingFrom: List<ComposerInlineToken> = emptyList(),
): List<ComposerInlineToken> {
  val matches: MutableList<ComposerInlineToken> = collectMentionTokens(text)

  for (match in SKILL_TOKEN_REGEX.findAll(text)) {
    val prefix: String = match.groupValues[1]
    val value: String = match.groupValues[2]
    if (value.isEmpty()) continue
    val start: Int = match.range.first + prefix.length
    val end: Int = match.range.last + 1
    matches += ComposerInlineToken(
      ComposerInlineTokenType.SKILL,
      value,
      text.substring(start, end),
      start,
      end,
    )
  }

  for (token in preserveTrailingFrom) {
    val stillThere: Boolean = token.end == text.length &&
      token.start in 0..text.length &&
      text.substring(token.start, token.end) == token.source
    val known: Boolean = matches.any {
      it.type == token.type && it.start == token.start && it.end == token.end
    }
    if (stillThere && !known) matches += token
  }

  return matches.sortedBy { it.start }
}

/** JavaScript's `decodeURIComponent`, or null where it throws. */
private fun decodeUriComponent(encoded: String): String? {
  if ('%' !in encoded) return encoded
  val decoded = StringBuilder()
  val bytes = ByteArrayOutputStream()

  fun flush(): Boolean {
    if (bytes.size() == 0) return true
    val chars: String = try {
      Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (_: CharacterCodingException) {
      return false
    }
    decoded.append(chars)
    bytes.reset()
    return true
  }

  var index = 0
  while (index < encoded.length) {
    val char: Char = encoded[index]
    if (char != '%') {
      if (!flush()) return null
      decoded.append(char)
      index++
      continue
    }
    if (index + 3 > encoded.length) return null
    val byte: Int = encoded.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
    bytes.write(byte)
    index += 3
  }
  return if (flush()) decoded.toString() else null
}

/** A token that becomes a chip, `composerContextEditorTokens`' union. */
sealed interface ComposerEditorToken {
  val start: Int
  val end: Int

  data class Inline(val token: ComposerInlineToken) : ComposerEditorToken {
    override val start: Int get() = token.start
    override val end: Int get() = token.end
  }

  data class Context(val reference: ComposerContextReferenceOccurrence) : ComposerEditorToken {
    override val start: Int get() = reference.start
    override val end: Int get() = reference.end
  }
}

/**
 * `composerContextEditorTokens` (apps/mobile/src/lib/composerContext.ts:164): [tokens] that
 * aren't inside a context link, a `$name` only when it's one of the provider's skills (`$HOME`
 * stays prose), then the context links, in order. A token starting inside the one before it is
 * dropped, as `splitPromptTextIntoComposerSegments` skips it on the web.
 */
fun composerContextEditorTokens(
  text: String,
  tokens: List<ComposerInlineToken>,
  isKnownSkill: (String) -> Boolean,
): List<ComposerEditorToken> {
  val references: List<ComposerContextReferenceOccurrence> = collectComposerContextReferences(text)
  val inline: List<ComposerEditorToken> = tokens
    .filter { token ->
      (token.type != ComposerInlineTokenType.SKILL || isKnownSkill(token.value)) &&
        references.none { ref -> token.start < ref.end && token.end > ref.start }
    }
    .map { ComposerEditorToken.Inline(it) }
  val sorted: List<ComposerEditorToken> =
    (inline + references.map { ComposerEditorToken.Context(it) }).sortedBy { it.start }
  var cursor = 0
  return sorted.filter { token -> (token.start >= cursor).also { if (it) cursor = token.end } }
}

/** `SkillMeta`. */
data class ComposerSkillMeta(val label: String, val description: String?)

/**
 * `composerSkillMeta` (ComposerPromptEditorTiptap.tsx:324): the chip's label for `$name`, or
 * null when the provider doesn't offer that skill to users.
 */
fun composerSkillMeta(skills: List<ServerProviderSkill>, name: String): ComposerSkillMeta? {
  val skill: ServerProviderSkill = skills
    .find { it.name == name && isProviderSkillUserInvocable(it) }
    ?: return null
  val shortDescription: String? = skill.shortDescription?.trim()?.ifEmpty { null }
  return ComposerSkillMeta(
    label = formatProviderSkillDisplayName(skill),
    description = shortDescription ?: skill.description?.trim()?.ifEmpty { null },
  )
}

/** `ComposerDraftContextRecord`: what a context link in the draft can point at. */
sealed interface ComposerDraftContextRecord {
  data class Image(val attachment: DraftAttachment) : ComposerDraftContextRecord

  data class File(val attachment: DraftAttachment) : ComposerDraftContextRecord

  data class ReviewComment(val comment: ReviewCommentContext) : ComposerDraftContextRecord

  data class Thread(val record: ThreadContextRecord) : ComposerDraftContextRecord
}

/**
 * `composerContextRecordsFromDraft` (composerContextPresentation.tsx:95): the draft's records
 * by context id. Images and files by `imageContextReference` and `fileContextReference`'s ids.
 */
fun composerContextRecordsFromDraft(extras: DraftExtras): Map<String, ComposerDraftContextRecord> {
  val records: MutableMap<String, ComposerDraftContextRecord> = linkedMapOf()
  for (attachment in extras.attachments) {
    when (attachment.kind) {
      AttachmentKind.IMAGE -> records[toKindScopedComposerContextId("image", attachment.id)] =
        ComposerDraftContextRecord.Image(attachment)

      AttachmentKind.FILE -> records[toKindScopedComposerContextId("file", attachment.id)] =
        ComposerDraftContextRecord.File(attachment)
    }
  }
  for (comment in extras.comments) {
    records[comment.contextId] = ComposerDraftContextRecord.ReviewComment(comment)
  }
  for (thread in extras.threads) {
    records[thread.contextId] = ComposerDraftContextRecord.Thread(thread)
  }
  return records
}

/**
 * Something drawn over a stretch of the prompt's text, [start] to [end] in the draft. Chips for
 * now; the markdown styling of #61 goes in as another kind.
 */
sealed interface ComposerPromptDecoration {
  val start: Int
  val end: Int
}

/** What a chip's leading icon shows. */
enum class ComposerPromptChipIcon {
  SKILL,
  FILE,
  FOLDER,
  IMAGE,
  THREAD,
  PULL_REQUEST,
  PULL_REQUEST_MERGED,
  REVIEW_COMMENT,
  UNAVAILABLE,
}

/**
 * A reference drawn as a chip: its source range in the draft, upstream's chip [kind] (`skill`,
 * `mention` or the context kind), the [label] and, for images and files, the size in [detail].
 * It edits as one unit, see [atomicComposerPromptEdit].
 */
data class ComposerPromptChip(
  override val start: Int,
  override val end: Int,
  val kind: String,
  val label: String,
  val icon: ComposerPromptChipIcon,
  val detail: String? = null,
) : ComposerPromptDecoration

/**
 * The chips in [text]: [tokens] (from [collectComposerInlineTokens]) through
 * [composerContextEditorTokens], each with the label its node view shows on the web.
 */
fun composerPromptChips(
  text: String,
  tokens: List<ComposerInlineToken>,
  skills: List<ServerProviderSkill>,
  records: Map<String, ComposerDraftContextRecord>,
): List<ComposerPromptChip> {
  val editorTokens: List<ComposerEditorToken> =
    composerContextEditorTokens(text, tokens) { name -> composerSkillMeta(skills, name) != null }
  return editorTokens.mapNotNull { token ->
    when (token) {
      is ComposerEditorToken.Inline -> inlineChip(token.token, skills)
      is ComposerEditorToken.Context -> contextReferenceChip(token.reference, records)
    }
  }
}

// `ComposerSkillNodeView` and `ComposerMentionNodeView`
private fun inlineChip(
  token: ComposerInlineToken,
  skills: List<ServerProviderSkill>,
): ComposerPromptChip? = when (token.type) {
  ComposerInlineTokenType.SKILL -> composerSkillMeta(skills, token.value)?.let { meta ->
    ComposerPromptChip(token.start, token.end, "skill", meta.label, ComposerPromptChipIcon.SKILL)
  }

  ComposerInlineTokenType.MENTION -> ComposerPromptChip(
    start = token.start,
    end = token.end,
    kind = "mention",
    label = basenameOfPath(token.value).ifEmpty { token.value },
    // Upstream's `FileTagChipContent` asks the workspace; a trailing `/` is all the text says
    icon = if (token.value.endsWith('/')) {
      ComposerPromptChipIcon.FOLDER
    } else {
      ComposerPromptChipIcon.FILE
    },
  )
}

// `ComposerContextReferenceChip`'s registry: the record for the link's kind, else unresolved
private fun contextReferenceChip(
  reference: ComposerContextReferenceOccurrence,
  records: Map<String, ComposerDraftContextRecord>,
): ComposerPromptChip {
  fun chip(label: String, icon: ComposerPromptChipIcon, detail: String? = null) =
    ComposerPromptChip(reference.start, reference.end, reference.kind, label, icon, detail)

  val record: ComposerDraftContextRecord? = records[reference.contextId]
  return when {
    reference.kind == "image" && record is ComposerDraftContextRecord.Image -> chip(
      label = record.attachment.name,
      icon = ComposerPromptChipIcon.IMAGE,
      detail = attachmentDetail(record.attachment),
    )

    reference.kind == "file" && record is ComposerDraftContextRecord.File -> chip(
      label = record.attachment.name,
      // `contextChipPresentation`: a file that's an image looks like one
      icon = if (isSupportedImageMimeType(record.attachment.mimeType)) {
        ComposerPromptChipIcon.IMAGE
      } else {
        ComposerPromptChipIcon.FILE
      },
      detail = attachmentDetail(record.attachment),
    )

    reference.kind == "review-comment" && record is ComposerDraftContextRecord.ReviewComment -> {
      val comment: ReviewCommentContext = record.comment
      val pullRequest: Boolean = isPullRequestSummaryContext(comment)
      val icon: ComposerPromptChipIcon = when {
        !pullRequest -> ComposerPromptChipIcon.REVIEW_COMMENT
        comment.pullRequest?.state == "merged" -> ComposerPromptChipIcon.PULL_REQUEST_MERGED
        else -> ComposerPromptChipIcon.PULL_REQUEST
      }
      chip(reviewCommentContextLabel(comment), icon)
    }

    reference.kind == "thread" && record is ComposerDraftContextRecord.Thread ->
      chip(record.record.title, ComposerPromptChipIcon.THREAD)

    // `UnresolvedContextChip`
    else -> chip(reference.label, ComposerPromptChipIcon.UNAVAILABLE)
  }
}

// `formatAttachmentSize`, then `uploadStatusSuffix` while it uploads or after it failed
private fun attachmentDetail(attachment: DraftAttachment): String {
  val size: String = formatAttachmentSize(attachment.sizeBytes)
  val suffix: String? = when (val upload: Upload = attachment.upload) {
    is Upload.Uploading -> formatAttachmentUploadProgress(upload.progress)
    is Upload.Failed -> "upload failed"
    is Upload.Ready -> null
  }
  return if (suffix == null) size else "$size · $suffix"
}

/** Longest label a chip shows before it ellipsises, upstream's `max-w` on the chip. */
const val COMPOSER_CHIP_LABEL_MAX_CHARS: Int = 40

// No-break spaces, so a chip never wraps at its own padding
private const val NBSP: Char = ' '

/**
 * What a chip shows in place of its source: a no-break space the icon sits in, the label, the
 * [detail] range (offsets into [text]) and a closing no-break space. [text] is what the field
 * lays out, so the caret steps over a chip as one wedge.
 */
data class ComposerChipDisplay(val text: String, val detail: IntRange?)

fun composerChipDisplay(chip: ComposerPromptChip): ComposerChipDisplay {
  val max: Int = COMPOSER_CHIP_LABEL_MAX_CHARS
  val label: String = chip.label
    .let { if (it.length > max) it.take(max - 1) + "…" else it }
    .replace(' ', NBSP)
  val detail: String? = chip.detail?.replace(' ', NBSP)
  val text: String = buildString {
    append(NBSP)
    append(label)
    if (detail != null) append(NBSP).append(detail)
    append(NBSP)
  }
  val detailStart: Int = label.length + 2
  val detailRange: IntRange? = detail?.let { detailStart until detailStart + it.length }
  return ComposerChipDisplay(text, detailRange)
}

/** Where each chip lands in the laid out text: [start] to [end], the icon's slot at [start]. */
data class ComposerChipDisplayRange(
  val chip: ComposerPromptChip,
  val display: ComposerChipDisplay,
  val start: Int,
  val end: Int,
)

/** [chips] (in order, not overlapping) as they land in the laid out text. */
fun composerChipDisplayRanges(chips: List<ComposerPromptChip>): List<ComposerChipDisplayRange> {
  var delta = 0
  return chips.map { chip ->
    val display: ComposerChipDisplay = composerChipDisplay(chip)
    val start: Int = chip.start + delta
    delta += display.text.length - (chip.end - chip.start)
    ComposerChipDisplayRange(chip, display, start, start + display.text.length)
  }
}

/** The laid out text's length for a draft of [length] with [chips]. */
fun composerPromptDisplayLength(length: Int, chips: List<ComposerPromptChip>): Int =
  length + chips.sumOf { composerChipDisplay(it).text.length - (it.end - it.start) }

/**
 * [offset] in the draft as an offset in the laid out text. An offset inside a chip lands on its
 * start, as the field's own mapping collapses it with its start affinity.
 */
fun composerPromptDisplayOffset(chips: List<ComposerPromptChip>, offset: Int): Int {
  var delta = 0
  for (chip in chips) {
    if (chip.start >= offset) break
    if (offset < chip.end) return chip.start + delta
    delta += composerChipDisplay(chip).text.length - (chip.end - chip.start)
  }
  return offset + delta
}

/**
 * An edit of [start] to [end] in the draft made atomic over [chips]: a deletion or replacement
 * that cuts into a chip takes the whole chip, and text typed inside one goes in at its nearer
 * edge. Upstream's chips are atom nodes, so neither can split one.
 */
fun atomicComposerPromptEdit(chips: List<ComposerPromptChip>, start: Int, end: Int): TextRange {
  if (start == end) {
    val chip: ComposerPromptChip =
      chips.find { it.start < start && start < it.end } ?: return TextRange(start)
    return TextRange(if (start - chip.start <= chip.end - start) chip.start else chip.end)
  }
  var from: Int = start
  var to: Int = end
  for (chip in chips) {
    if (chip.start < to && chip.end > from) {
      from = minOf(from, chip.start)
      to = maxOf(to, chip.end)
    }
  }
  return TextRange(from, to)
}

/** Text going in at an edit: what to insert and where the caret lands, from the edit's start. */
data class ComposerPromptInsertion(val text: String, val cursor: Int)

/**
 * [text] replacing [start] to [end], with a space added where it would touch a mention or skill
 * chip: a mention or `$skill` only parses with whitespace on both sides, so `X[a.kt](a.kt)`
 * would fall back to raw text. Upstream's chips are atoms that can't lose their parse, and its
 * paste handler pads pasted text that touches one for the same reason
 * (ComposerPromptEditorTiptap.tsx:1416). The caret stays before a space added after the text, so
 * typing on carries on in the same word.
 */
fun separateComposerPromptInsertion(
  chips: List<ComposerPromptChip>,
  start: Int,
  end: Int,
  text: String,
): ComposerPromptInsertion {
  if (text.isEmpty()) return ComposerPromptInsertion(text, 0)
  // `isChip`: context links parse without the whitespace
  val spaced: List<ComposerPromptChip> =
    chips.filter { it.kind == "skill" || it.kind == "mention" }
  val before: Boolean = !text.first().isWhitespace() && spaced.any { it.end == start }
  val after: Boolean = !text.last().isWhitespace() && spaced.any { it.start == end }
  val padded: String = "${if (before) " " else ""}$text${if (after) " " else ""}"
  return ComposerPromptInsertion(padded, if (before) text.length + 1 else text.length)
}

/**
 * [next] with no end inside a chip. Clicking or arrowing into a chip lands the field's caret
 * inside its source; here a caret that came from one edge steps to the other, a click selects
 * the chip, and a selection's ends move out to take the chips they cut. [textChanged]: [previous]
 * is in the text before an edit, so it says nothing about the direction.
 */
fun snapComposerPromptSelection(
  chips: List<ComposerPromptChip>,
  previous: TextRange,
  next: TextRange,
  textChanged: Boolean = false,
): TextRange {
  fun inside(offset: Int): ComposerPromptChip? = chips.find { it.start < offset && offset < it.end }
  val atMin: ComposerPromptChip? = inside(next.min)
  val atMax: ComposerPromptChip? = inside(next.max)
  if (atMin == null && atMax == null) return next
  if (atMin != null && atMin == atMax) {
    val chip: ComposerPromptChip = atMin
    if (!textChanged && previous.collapsed && previous.start == chip.start) {
      return TextRange(chip.end)
    }
    if (!textChanged && previous.collapsed && previous.start == chip.end) {
      return TextRange(chip.start)
    }
    if (next.collapsed) {
      val offset: Int = next.start
      return TextRange(if (offset - chip.start <= chip.end - offset) chip.start else chip.end)
    }
    return if (next.reversed) TextRange(chip.end, chip.start) else TextRange(chip.start, chip.end)
  }
  val min: Int = atMin?.start ?: next.min
  val max: Int = atMax?.end ?: next.max
  return if (next.reversed) TextRange(max, min) else TextRange(min, max)
}
