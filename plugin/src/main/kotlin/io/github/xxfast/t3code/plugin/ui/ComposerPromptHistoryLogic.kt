package io.github.xxfast.t3code.plugin.ui

import androidx.compose.ui.text.TextRange
import io.github.xxfast.t3code.protocol.generated.TurnItem

// Up/Down recall of earlier prompts: upstream's
// `apps/web/src/components/chat/composerPromptHistory.ts`, `resolveUserMessagePresentation`
// from `packages/client-runtime/src/userMessage.ts`, and the guards of `navigatePromptHistory`
// and `isCaretOnVisualEdge` (ChatComposer.tsx:4170, ComposerPromptEditorTiptap.tsx:1749).

/** `ComposerPromptHistoryMessage`: what a timeline message brings to the history. */
data class ComposerPromptHistoryMessage(
  val id: String,
  val role: String,
  val text: String,
  val createdBy: String? = null,
  val creationSource: String? = null,
  val scheduledTaskId: String? = null,
)

/** A sent user message as a [ComposerPromptHistoryMessage], upstream's `timelineMessages`. */
fun TurnItem.UserMessage.toPromptHistoryMessage(): ComposerPromptHistoryMessage =
  ComposerPromptHistoryMessage(
    id = messageId,
    role = "user",
    text = text,
    createdBy = createdBy,
    creationSource = creationSource,
    scheduledTaskId = scheduledTaskId,
  )

/** `ComposerPromptHistoryEntry`. */
data class ComposerPromptHistoryEntry(val id: String, val prompt: String)

/** `ComposerPromptHistoryPosition`: the entry being shown, and the text it put in. */
data class ComposerPromptHistoryPosition(val entryId: String, val recalled: String)

/** `ComposerPromptHistoryStep`: where browsing is now, and the prompt to show. */
data class ComposerPromptHistoryStep(
  val position: ComposerPromptHistoryPosition?,
  val prompt: String,
)

/** `resolveUserMessagePresentation`'s answer, userMessage.ts:10. */
data class UserMessagePresentation(
  val text: String,
  /** `"automation"`, `"agent"`, `"t3code"`, or null for what the user typed. */
  val attribution: String?,
  val scheduledTaskId: String?,
)

private val LEGACY_AUTOMATION_PREFIX: Regex =
  Regex("^\\[Triggered by schedule task: [^\\r\\n]+\\]\\r?\\n\\r?\\n")
private val LEGACY_AUTOMATION_MESSAGE_ID: Regex =
  Regex("^scheduled-task-message:(.+):\\d+:(?:scheduled|manual)$")

/** `resolveUserMessagePresentation`, userMessage.ts:10: who a user-role message came from. */
fun resolveUserMessagePresentation(
  message: ComposerPromptHistoryMessage,
): UserMessagePresentation {
  if (message.role != "user") return UserMessagePresentation(message.text, null, null)
  if (message.scheduledTaskId != null) {
    return UserMessagePresentation(message.text, "automation", message.scheduledTaskId)
  }
  val legacyPrefix: MatchResult? = LEGACY_AUTOMATION_PREFIX.find(message.text)
  val legacyTaskId: String? = legacyPrefix
    ?.let { LEGACY_AUTOMATION_MESSAGE_ID.find(message.id)?.groupValues?.get(1) }
  if (legacyPrefix != null && (legacyTaskId != null || message.createdBy == "agent")) {
    return UserMessagePresentation(
      text = message.text.substring(legacyPrefix.value.length),
      attribution = "automation",
      scheduledTaskId = legacyTaskId,
    )
  }
  val attribution: String? = when {
    message.createdBy != "agent" -> null
    message.creationSource == "server" -> "t3code"
    else -> "agent"
  }
  return UserMessagePresentation(message.text, attribution, null)
}

private const val CLAUDE_ULTRATHINK_PREFIX: String = "Ultrathink:\n"

/** `PLAN_IMPLEMENTATION_PROMPT_PREFIX`, apps/web/src/proposedPlan.ts:54. */
const val PLAN_IMPLEMENTATION_PROMPT_PREFIX: String = "PLEASE IMPLEMENT THIS PLAN:\n"

private val REVIEW_COMMENT_BLOCK_PATTERN: Regex =
  Regex("<review_comment\\b[^>]*>[\\s\\S]*?</review_comment>")
private val TRAILING_LEGACY_CONTEXT: Regex = Regex(
  "\\n*<(terminal_context|element_context|preview_annotation)>\\n([\\s\\S]*?)\\n</\\1>\\s*\\z",
)
private val LEGACY_HEADER: Regex = Regex("^- (.+):$", RegexOption.MULTILINE)
private val BARE_ANNOTATION_OPENING: Regex =
  Regex("^<preview_annotation>\\s*$", RegexOption.MULTILINE)
private val ANNOTATION_FIELD: Regex =
  Regex("^(?:Id|Page|Comment|Targets): ", RegexOption.MULTILINE)
private val TERMINAL_HEADER: Regex = Regex("^(.+?) lines? (\\d+(?:-\\d+)?)$")
private val WHITESPACE_RUN: Regex = Regex("\\s+")

// `CONTEXT_LINK` and `parseComposerContextHref`, composerContextReferences.ts, for
// `collectComposerContextReferences`'s ranges
private const val CONTEXT_HREF_PREFIX: String = "t3-context://v1/"
private val CONTEXT_KIND_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{0,39}$")
private val CONTEXT_ID_PATTERN: Regex = Regex("^[a-z0-9_-]{1,128}$", RegexOption.IGNORE_CASE)
private val CONTEXT_LINK: Regex = Regex(
  "(!?)\\[([^\\]\\n]{0,512})\\]\\((${Regex.escape(CONTEXT_HREF_PREFIX)}[^\\s)]{1,200})\\)",
)

/** `collectComposerContextReferences`, composerContextReferences.ts:100, as ranges only. */
private fun collectComposerContextReferenceRanges(text: String): List<IntRange> {
  if (!text.contains("](t3-context:")) return emptyList()
  return CONTEXT_LINK.findAll(text)
    .filter { match ->
      val parts: List<String> = match.groupValues[3].removePrefix(CONTEXT_HREF_PREFIX).split('/')
      parts.size == 2 &&
        CONTEXT_KIND_PATTERN.matches(parts[0]) &&
        CONTEXT_ID_PATTERN.matches(parts[1])
    }
    .map { it.range }
    .toList()
}

/** `stripTrailingReviewComments`: the review comment blocks at the very end, not mid-prompt. */
private fun stripTrailingReviewComments(prompt: String): String {
  var cut: Int = prompt.length
  for (match in REVIEW_COMMENT_BLOCK_PATTERN.findAll(prompt).toList().asReversed()) {
    val blockEnd: Int = match.range.last + 1
    if (prompt.substring(blockEnd, cut).trim().isNotEmpty()) break
    cut = match.range.first
  }
  return if (cut == prompt.length) prompt else prompt.substring(0, cut).trimEnd()
}

/**
 * `stripInlineTerminalLabels`: inline terminal chips go out as `@terminal-1:12-13` labels with
 * their content in the trailing block. Once the block is stripped the label points at nothing,
 * so it goes too.
 */
private fun stripInlineTerminalLabels(prompt: String, headers: List<String>): String {
  var result: String = prompt
  for (header in headers) {
    val match: MatchResult = TERMINAL_HEADER.find(header) ?: continue
    val name: String = match.groupValues[1].trim().lowercase().replace(WHITESPACE_RUN, "-")
    val label = "@$name:${match.groupValues[2]}"
    val labelPattern = Regex("(?<![\\w@.-])${Regex.escape(label)}(?![\\d-])")
    val index: Int = labelPattern.find(result)?.range?.first ?: continue
    var end: Int = index + label.length
    var start: Int = index
    if (result.getOrNull(end) == ' ') {
      end += 1
    } else if (result.getOrNull(start - 1) == ' ') {
      start -= 1
    }
    result = result.substring(0, start) + result.substring(end)
  }
  return result
}

/**
 * `recallableComposerPrompt`, composerPromptHistory.ts:78: what the user typed, without the
 * Ultrathink prefix, the trailing review comments and legacy context blocks, and the context
 * links (recall is text only, so no dangling chips). A bootstrap or plan implementation prompt
 * recalls as nothing.
 */
fun recallableComposerPrompt(messageText: String): String {
  var prompt: String = messageText.trim()
  if (prompt.startsWith(CLAUDE_ULTRATHINK_PREFIX)) {
    prompt = prompt.substring(CLAUDE_ULTRATHINK_PREFIX.length)
  }

  while (prompt.isNotEmpty()) {
    val withoutReviewComments: String = stripTrailingReviewComments(prompt)
    if (withoutReviewComments != prompt) {
      prompt = withoutReviewComments
      continue
    }
    val legacy: MatchResult = TRAILING_LEGACY_CONTEXT.find(prompt) ?: break
    val kind: String = legacy.groupValues[1]
    val body: String = legacy.groupValues[2]
    if (
      kind == "preview_annotation" &&
      BARE_ANNOTATION_OPENING.containsMatchIn(body) &&
      !ANNOTATION_FIELD.containsMatchIn(body)
    ) {
      break
    }
    val headers: List<String> = LEGACY_HEADER.findAll(body).map { it.groupValues[1] }.toList()
    if (kind != "preview_annotation" && headers.isEmpty()) break
    prompt = prompt.substring(0, legacy.range.first).trimEnd()
    if (kind == "terminal_context") prompt = stripInlineTerminalLabels(prompt, headers)
  }

  for (reference in collectComposerContextReferenceRanges(prompt).asReversed()) {
    var start: Int = reference.first
    var end: Int = reference.last + 1
    if (prompt.getOrNull(end) == ' ') {
      end += 1
    } else if (prompt.getOrNull(start - 1) == ' ') {
      start -= 1
    }
    prompt = prompt.substring(0, start) + prompt.substring(end)
  }

  val trimmed: String = prompt.trim()
  if (
    trimmed == ATTACHMENT_ONLY_BOOTSTRAP_PROMPT ||
    trimmed.startsWith(PLAN_IMPLEMENTATION_PROMPT_PREFIX)
  ) {
    return ""
  }
  return trimmed
}

/**
 * `buildComposerPromptHistoryEntries`, composerPromptHistory.ts:125: the user's own prompts in
 * order, consecutive duplicates collapsed onto the newest message. Automations and other agents
 * send user-role messages too; recall only what the user typed.
 */
fun buildComposerPromptHistoryEntries(
  messages: List<ComposerPromptHistoryMessage>,
): List<ComposerPromptHistoryEntry> {
  val entries: MutableList<ComposerPromptHistoryEntry> = mutableListOf()
  for (message in messages) {
    if (message.role != "user") continue
    if (resolveUserMessagePresentation(message).attribution != null) continue
    val prompt: String = recallableComposerPrompt(message.text)
    if (prompt.isEmpty()) continue
    val previous: ComposerPromptHistoryEntry? = entries.lastOrNull()
    if (previous != null && previous.prompt == prompt) {
      entries[entries.lastIndex] = ComposerPromptHistoryEntry(message.id, prompt)
      continue
    }
    entries.add(ComposerPromptHistoryEntry(message.id, prompt))
  }
  return entries
}

/** `findActive`: the entry by id, else the last one with the recalled text. */
private fun findActive(
  entries: List<ComposerPromptHistoryEntry>,
  position: ComposerPromptHistoryPosition,
): Int {
  val byId: Int = entries.indexOfFirst { it.id == position.entryId }
  if (byId >= 0) return byId
  return entries.indexOfLast { it.prompt == position.recalled }
}

enum class ComposerPromptHistoryDirection { BACKWARD, FORWARD }

/**
 * `stepComposerPromptHistory`, composerPromptHistory.ts:145. Backward starts from the newest
 * entry only on an empty prompt; an edited recall is a fresh draft. Forward past the newest
 * entry empties the prompt and stops browsing.
 */
fun stepComposerPromptHistory(
  direction: ComposerPromptHistoryDirection,
  entries: List<ComposerPromptHistoryEntry>,
  position: ComposerPromptHistoryPosition?,
  currentPrompt: String,
): ComposerPromptHistoryStep? {
  val activeIndex: Int = if (position != null && position.recalled == currentPrompt) {
    findActive(entries, position)
  } else {
    -1
  }

  if (direction == ComposerPromptHistoryDirection.BACKWARD) {
    if (activeIndex < 0 && currentPrompt.isNotEmpty()) return null
    val entry: ComposerPromptHistoryEntry =
      entries.getOrNull(if (activeIndex < 0) entries.lastIndex else activeIndex - 1) ?: return null
    return ComposerPromptHistoryStep(
      ComposerPromptHistoryPosition(entry.id, entry.prompt),
      entry.prompt,
    )
  }

  if (activeIndex < 0) return null
  val entry: ComposerPromptHistoryEntry =
    entries.getOrNull(activeIndex + 1) ?: return ComposerPromptHistoryStep(null, "")
  return ComposerPromptHistoryStep(
    ComposerPromptHistoryPosition(entry.id, entry.prompt),
    entry.prompt,
  )
}

/**
 * `isCaretOnVisualEdge`, ComposerPromptEditorTiptap.tsx:1749: a collapsed caret on the first
 * ([ComposerPromptHistoryDirection.BACKWARD]) or last visual line, with no newline between it
 * and that end. [caretLine] and [lineCount] come from the text layout, null before one.
 */
fun isCaretOnVisualEdge(
  text: String,
  selection: TextRange,
  direction: ComposerPromptHistoryDirection,
  caretLine: Int?,
  lineCount: Int?,
): Boolean {
  if (text.isEmpty()) return true
  val cursor: Int = selection.end.coerceIn(0, text.length)
  val backward: Boolean = direction == ComposerPromptHistoryDirection.BACKWARD
  val beyond: String = if (backward) text.substring(0, cursor) else text.substring(cursor)
  if (beyond.contains('\n')) return false
  if (!selection.collapsed || caretLine == null || lineCount == null) return false
  return if (backward) caretLine == 0 else caretLine == lineCount - 1
}

/**
 * `navigatePromptHistory`'s guards, ChatComposer.tsx:4170: no modifier, no request waiting,
 * nothing attached, and a prompt that is empty or still the recalled text's.
 */
fun canNavigatePromptHistory(
  modified: Boolean,
  hasPendingRequest: Boolean,
  hasNonPromptContent: Boolean,
  position: ComposerPromptHistoryPosition?,
  prompt: String,
): Boolean = !modified &&
  !hasPendingRequest &&
  !hasNonPromptContent &&
  !(position == null && prompt.isNotEmpty())
