package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProviderModel

// How a reported model string finds its catalog model, and how a bare slug reads when it
// doesn't, from upstream's `packages/shared/src/model.ts` and `packages/contracts/src/model.ts`.

/** `MODEL_SLUG_ALIASES_BY_PROVIDER`, `packages/contracts/src/model.ts:166`. */
private val MODEL_SLUG_ALIASES_BY_PROVIDER: Map<String, Map<String, String>> = mapOf(
  "codex" to mapOf(
    "gpt-5-codex" to "gpt-5.4",
    "5.4" to "gpt-5.4",
    "5.3" to "gpt-5.3-codex",
    "gpt-5.3" to "gpt-5.3-codex",
    "5.3-spark" to "gpt-5.3-codex-spark",
    "gpt-5.3-spark" to "gpt-5.3-codex-spark",
  ),
  "claudeAgent" to emptyMap(),
  "cursor" to mapOf(
    "composer" to "composer-2",
    "composer-1.5" to "composer-1.5",
    "composer-1" to "composer-1.5",
    "opus-4.6-thinking" to "claude-opus-4-6",
    "opus-4.6" to "claude-opus-4-6",
    "sonnet-4.6-thinking" to "claude-sonnet-4-6",
    "sonnet-4.6" to "claude-sonnet-4-6",
    "opus-4.5-thinking" to "claude-opus-4-5",
    "opus-4.5" to "claude-opus-4-5",
  ),
  "opencode" to emptyMap(),
)

/** `normalizeModelSlug`, `model.ts:350`: [model] trimmed, through [provider]'s aliases. */
fun normalizeModelSlug(model: String?, provider: String = "codex"): String? {
  val trimmed: String = model?.trim()?.ifEmpty { null } ?: return null
  return MODEL_SLUG_ALIASES_BY_PROVIDER[provider]?.get(trimmed) ?: trimmed
}

/**
 * `resolveSelectableModel`, `model.ts:428`: the slug [value] stands for in [options], by slug,
 * then name, then alias (case-insensitive), then [normalizeModelSlug].
 */
fun resolveSelectableModel(
  provider: String,
  value: String?,
  options: List<ServerProviderModel>,
): String? {
  val trimmed: String = value?.trim()?.ifEmpty { null } ?: return null
  options.find { it.slug == trimmed }?.let { return it.slug }
  options.find { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.slug }
  options
    .find { option -> option.aliases.orEmpty().any { it.equals(trimmed, ignoreCase = true) } }
    ?.let { return it.slug }
  val normalized: String = normalizeModelSlug(trimmed, provider) ?: return null
  return options.find { it.slug == normalized }?.slug
}

private val GPT_PREFIX: Regex = Regex("^gpt", RegexOption.IGNORE_CASE)
private val DASH_LETTER: Regex = Regex("-([a-z])")

/** `formatCodexModelName`, `model.ts:330`: `gpt-5.4-mini` reads `GPT-5.4-Mini`. */
fun formatCodexModelName(name: String): String = GPT_PREFIX.replaceFirst(name, "GPT")
  .replace(DASH_LETTER) { match -> "-" + match.groupValues[1].uppercase() }

private val CODEX_SLUG: Regex = Regex("^gpt-\\d", RegexOption.IGNORE_CASE)
private val FORMATTED_SLUG: Regex =
  Regex("^(claude-(opus|sonnet|haiku|fable)|gemini|grok|composer)-\\d", RegexOption.IGNORE_CASE)
private val CLAUDE_MINOR_VERSION: Regex =
  Regex("^(claude-[a-z]+-\\d+)-(\\d{1,2})(?=-|\\[|$)", RegexOption.IGNORE_CASE)

/**
 * `formatModelSlugName`, `model.ts:334`: a slug the catalog doesn't know, readable when it's a
 * GPT, Claude, Gemini, Grok or Composer one (`claude-opus-4-6` reads `Claude Opus 4.6`), else
 * as it is. A `provider/` prefix stays.
 */
fun formatModelSlugName(slug: String): String {
  val separator: Int = slug.lastIndexOf('/') + 1
  val prefix: String = slug.substring(0, separator)
  val name: String = slug.substring(separator)
  if (CODEX_SLUG.containsMatchIn(name)) return prefix + formatCodexModelName(name)
  if (!FORMATTED_SLUG.containsMatchIn(name)) return slug
  return prefix + CLAUDE_MINOR_VERSION.replaceFirst(name, "$1.$2")
    .split("-")
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}
