package io.github.xxfast.t3code.plugin.ui

// The composer's triggers: the token at the caret that opens the menu above the prompt, how a
// pick replaces it, and how the menu remembers a dismissal and a highlight. Upstream's
// `apps/web/src/composer-logic.ts`, `packages/shared/src/composerTrigger.ts`,
// `useComposerTriggerState.ts`, `composerMenuHighlight.ts` and the helpers in `ChatComposer.tsx`.

/** `ComposerTriggerKind`, composer-logic.ts:20. */
enum class ComposerTriggerKind { PATH, PULL_REQUEST, SLASH_COMMAND, SKILL }

/** `ComposerTrigger`: what was typed after the sigil, and the range the token covers. */
data class ComposerTrigger(
  val kind: ComposerTriggerKind,
  val query: String,
  val rangeStart: Int,
  val rangeEnd: Int,
)

/** `clampCursor`. */
private fun clampCursor(text: String, cursor: Int): Int = cursor.coerceIn(0, text.length)

/** `isWhitespace`: the four characters upstream splits tokens on, not Unicode whitespace. */
private fun isWhitespace(char: Char): Boolean =
  char == ' ' || char == '\n' || char == '\t' || char == '\r'

/** `tokenStartForCursor`: just after the last whitespace before [cursor]. */
private fun tokenStartForCursor(text: String, cursor: Int): Int {
  var index: Int = cursor - 1
  while (index >= 0 && !isWhitespace(text[index])) index -= 1
  return index + 1
}

/**
 * `isPathWord`, packages/shared/src/composerTrigger.ts:52: a slash word with a second slash,
 * such as `/tmp/build.sh`, is a path, not a command. The whole word counts, so a caret before
 * the second slash still sees it.
 */
fun isPathWord(text: String, wordStart: Int, cursor: Int): Boolean {
  var wordEnd: Int = cursor
  while (wordEnd < text.length && !isWhitespace(text[wordEnd])) wordEnd += 1
  return text.substring(wordStart + 1, wordEnd).contains('/')
}

private val PULL_REQUEST_TOKEN: Regex = Regex("^#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]*)?$")
private val SKILL_PREFIX: Regex = Regex("^\\p{Sc}")

/**
 * `detectComposerTrigger`, apps/web/src/composer-logic.ts:281: the token between the last
 * whitespace and the caret, checked in order: `/` (unless it's a path), `#` with a word or
 * nothing after it, any currency sigil (`\p{Sc}`, not just `$`), then `@`.
 */
fun detectComposerTrigger(text: String, cursorInput: Int): ComposerTrigger? {
  val cursor: Int = clampCursor(text, cursorInput)
  val tokenStart: Int = tokenStartForCursor(text, cursor)
  val token: String = text.substring(tokenStart, cursor)
  if (token.startsWith("/")) {
    if (isPathWord(text, tokenStart, cursor)) return null
    return ComposerTrigger(ComposerTriggerKind.SLASH_COMMAND, token.drop(1), tokenStart, cursor)
  }
  PULL_REQUEST_TOKEN.find(token)?.let { match ->
    val query: String = match.groups[1]?.value.orEmpty()
    return ComposerTrigger(ComposerTriggerKind.PULL_REQUEST, query, tokenStart, cursor)
  }
  SKILL_PREFIX.find(token)?.let { prefix ->
    // A sigil outside the BMP is two chars, so drop the match, not one char
    val query: String = token.substring(prefix.value.length)
    return ComposerTrigger(ComposerTriggerKind.SKILL, query, tokenStart, cursor)
  }
  if (!token.startsWith("@")) return null
  return ComposerTrigger(ComposerTriggerKind.PATH, token.drop(1), tokenStart, cursor)
}

/** The text after a replacement and where the caret goes. */
data class TextReplacement(val text: String, val cursor: Int)

/** `replaceTextRange`, composer-logic.ts:362: [replacement] over the clamped range. */
fun replaceTextRange(
  text: String,
  rangeStart: Int,
  rangeEnd: Int,
  replacement: String,
): TextReplacement {
  val safeStart: Int = rangeStart.coerceIn(0, text.length)
  val safeEnd: Int = rangeEnd.coerceIn(safeStart, text.length)
  val next: String = text.substring(0, safeStart) + replacement + text.substring(safeEnd)
  return TextReplacement(next, safeStart + replacement.length)
}

/**
 * `extendReplacementRangeForTrailingSpace`, ChatComposer.tsx:1139: a replacement that ends in a
 * space swallows the one already after the token, so a pick mid-sentence doesn't double it.
 */
fun extendReplacementRangeForTrailingSpace(text: String, rangeEnd: Int, replacement: String): Int {
  if (!replacement.endsWith(" ")) return rangeEnd
  return if (text.getOrNull(rangeEnd) == ' ') rangeEnd + 1 else rangeEnd
}

/**
 * `useComposerTriggerState`: the active trigger, and the one Esc dismissed. A dismissed trigger
 * stays closed while the same kind sits at the same [ComposerTrigger.rangeStart], so typing more
 * of the token doesn't reopen it; a new token, or none, forgets the dismissal.
 */
class ComposerTriggerState(initial: ComposerTrigger? = null) {
  var trigger: ComposerTrigger? = initial
    private set
  private var dismissed: ComposerTrigger? = null

  /** `resolveTrigger`: [candidate], unless it's the dismissed one. */
  fun resolveTrigger(candidate: ComposerTrigger?): ComposerTrigger? {
    val dismissed: ComposerTrigger? = dismissed
    val same: Boolean = candidate != null &&
      dismissed != null &&
      candidate.kind == dismissed.kind &&
      candidate.rangeStart == dismissed.rangeStart
    return if (same) null else candidate
  }

  /** `setTrigger`: what the text and caret say now. */
  fun setTrigger(candidate: ComposerTrigger?): ComposerTrigger? {
    val active: ComposerTrigger? = resolveTrigger(candidate)
    if (candidate == null || active != null) dismissed = null
    trigger = active
    return active
  }

  /** `dismissTrigger`: Esc. */
  fun dismissTrigger(candidate: ComposerTrigger?) {
    dismissed = candidate
    trigger = null
  }

  /** `resetTrigger`. */
  fun resetTrigger(candidate: ComposerTrigger?) {
    dismissed = null
    trigger = candidate
  }
}

/** `composerMenuEmptyState` for `$`, ChatComposer.tsx:2858. */
const val SKILL_MENU_EMPTY_TEXT: String = "No skills found. Try / to browse provider commands."

/**
 * The menu's line while it has no rows, ComposerCommandMenu.tsx:142: the loading text for the
 * kind, else the host's [emptyStateText], else the kind's own empty text.
 */
fun composerMenuStatusText(
  kind: ComposerTriggerKind,
  isLoading: Boolean,
  emptyStateText: String?,
): String = when {
  isLoading -> when (kind) {
    ComposerTriggerKind.SKILL -> "Searching workspace skills..."
    ComposerTriggerKind.PULL_REQUEST -> "Finding pull request..."
    else -> "Searching workspace files..."
  }

  emptyStateText != null -> emptyStateText

  else -> when (kind) {
    ComposerTriggerKind.SKILL -> SKILL_MENU_EMPTY_TEXT
    ComposerTriggerKind.PATH -> "No matching files or folders."
    else -> "No matching command."
  }
}

/** The menu's search key, `composerMenuSearchKey` (ChatComposer.tsx:2810), without the list id. */
fun composerMenuSearchKey(trigger: ComposerTrigger): String =
  "${trigger.kind}:${trigger.query.trim().lowercase()}"

/**
 * `resolveComposerMenuActiveItemId`, composerMenuHighlight.ts: the highlighted item while the
 * search it was highlighted under still holds and it's still listed, else the first.
 */
fun resolveComposerMenuActiveItemId(
  itemIds: List<String>,
  highlightedItemId: String?,
  currentSearchKey: String?,
  highlightedSearchKey: String?,
): String? {
  if (itemIds.isEmpty()) return null
  if (
    currentSearchKey == highlightedSearchKey &&
    highlightedItemId != null &&
    highlightedItemId in itemIds
  ) {
    return highlightedItemId
  }
  return itemIds.first()
}

/**
 * `nudgeComposerMenuHighlight`, ChatComposer.tsx:3970: the next or previous item, wrapping. Down
 * from nothing highlighted is the first, up is the last. Upstream syncs its highlight to the
 * active item on render (ChatComposer.tsx:3398), so pass [resolveComposerMenuActiveItemId]'s.
 */
fun nudgeComposerMenuHighlight(
  itemIds: List<String>,
  highlightedItemId: String?,
  down: Boolean,
): String? {
  if (itemIds.isEmpty()) return null
  val highlighted: Int = itemIds.indexOf(highlightedItemId)
  val normalized: Int = if (highlighted >= 0) highlighted else if (down) -1 else 0
  val offset: Int = if (down) 1 else -1
  return itemIds[(normalized + offset + itemIds.size) % itemIds.size]
}

/** What a key does while the menu is open, [composerMenuKey]. */
enum class ComposerMenuKey { DISMISS, NEXT, PREVIOUS, PICK, IGNORE }

/**
 * The menu's half of `onComposerCommandKey`, ChatComposer.tsx:4228 to 4248. Esc dismisses. The
 * arrows move and Enter or Tab picks, but only for a key that would send in the foreground or
 * not send at all: Ctrl/Cmd+Enter while running ([ComposerKey.SEND_ALTERNATE]) and Shift+Tab
 * (the Plan/Build toggle) pass through. Nothing to pick passes Enter through too.
 */
fun composerMenuKey(
  key: ComposerMenuInput,
  keyDown: Boolean,
  shift: Boolean,
  submission: ComposerKey,
  hasItems: Boolean,
): ComposerMenuKey {
  if (!keyDown) return ComposerMenuKey.IGNORE
  if (key == ComposerMenuInput.ESCAPE) return ComposerMenuKey.DISMISS
  if (key == ComposerMenuInput.TAB && shift) return ComposerMenuKey.IGNORE
  if (submission == ComposerKey.SEND_ALTERNATE || !hasItems) return ComposerMenuKey.IGNORE
  return when (key) {
    ComposerMenuInput.DOWN -> ComposerMenuKey.NEXT
    ComposerMenuInput.UP -> ComposerMenuKey.PREVIOUS
    ComposerMenuInput.ENTER, ComposerMenuInput.TAB -> ComposerMenuKey.PICK
    ComposerMenuInput.ESCAPE, ComposerMenuInput.OTHER -> ComposerMenuKey.IGNORE
  }
}

/** The keys [composerMenuKey] reads. */
enum class ComposerMenuInput { ESCAPE, UP, DOWN, ENTER, TAB, OTHER }
