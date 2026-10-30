package io.github.xxfast.t3code.plugin.ui

// The name you'd say out loud, upstream's `packages/provider-core/src/modelShortName.ts`: a
// family prefix the provider's icon already says goes, "Claude Opus 5.5" is "Opus 5.5" under
// Claude, and a vendor prefix goes everywhere.

private val FAMILY_PREFIXES: List<String> =
  listOf("Claude ", "GPT-", "GPT ", "Gemini ", "Muse ", "Grok ")

// Words that describe a variant within a family rather than name a model. Off the
// family's native provider there is no icon saying which family "4 Pro" belongs to.
private val VARIANT_WORDS: Set<String> = setOf(
  "pro",
  "flash",
  "mini",
  "max",
  "lite",
  "nano",
  "ultra",
  "plus",
  "fast",
  "turbo",
  "preview",
  "latest",
  "thinking",
  "reasoning",
  "build",
  "code",
  "image",
  "vision",
  "audio",
  "live",
  "custom",
  "tools",
  "exp",
  "experimental",
  "chat",
  "tts",
  "stt",
  "non",
  "transcribe",
)

private val VERSION: Regex = Regex("^v?\\d+(?:\\.\\d+)*[a-z]?$", RegexOption.IGNORE_CASE)

private val VENDOR_PREFIX: Regex = Regex("^[A-Z][\\w.&\\- ]{0,24}: (?=[^$JS_WHITESPACE])")

// JavaScript's `\s`, which takes the Unicode spaces the JVM's leaves out (no-break and friends)
private const val JS_WHITESPACE: String =
  "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

private val PARENTHESISED: Regex = Regex("\\(.*?\\)")
private val WORD_SEPARATORS: Regex = Regex("[$JS_WHITESPACE-]+")
private val WHITESPACE: Regex = Regex("[$JS_WHITESPACE]")

/**
 * `modelShortName`: [name] without its vendor prefix, and without its family prefix when what's
 * left still names a model (on a provider of [nativeFamilies], any word that isn't a version
 * will do). Null when that's [name] itself.
 */
fun modelShortName(name: String, nativeFamilies: List<String>): String? {
  val unprefixed: String = VENDOR_PREFIX.replaceFirst(name, "")
  val short: String = withoutFamily(unprefixed, nativeFamilies)
    ?: if (namesModel(unprefixed)) unprefixed else name
  return if (short == name) null else short
}

private fun withoutFamily(name: String, nativeFamilies: List<String>): String? {
  val prefix: String = FAMILY_PREFIXES.find { candidate -> name.startsWith(candidate) }
    ?: return null
  val rest: String = name.substring(prefix.length).trim()
  val keep: Boolean =
    if (prefix in nativeFamilies) nonVersionWords(rest).isNotEmpty() else namesModel(rest)
  if (!keep) return null
  return if (WHITESPACE.containsMatchIn(rest)) rest else rest.replace("-", " ")
}

private fun nonVersionWords(text: String): List<String> = PARENTHESISED.replace(text, " ")
  .split(WORD_SEPARATORS)
  .filter { word -> word.isNotEmpty() && !VERSION.containsMatchIn(word) }

private fun namesModel(text: String): Boolean =
  nonVersionWords(text).any { word -> word.lowercase() !in VARIANT_WORDS }
