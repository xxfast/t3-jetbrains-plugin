package io.github.xxfast.t3code.plugin.ui

// `contextChipAccents.ts` (apps/web/src/lib): each chip kind's accent, before
// `contextChipVariables` (`ThemePalette.kt`) solves its lightness for the theme.

/** `CONTEXT_CHIP_ACCENTS`, keyed by upstream's `ContextChipAccentKind`. */
val CONTEXT_CHIP_ACCENTS: Map<String, String> = linkedMapOf(
  "image" to "oklch(0.62 0.136 237)",
  "video" to "oklch(0.62 0.16 48)",
  "file" to "oklch(0.62 0.136 237)",
  "mention" to "oklch(0.62 0.11 215)",
  "terminal" to "oklch(0.62 0.134 163)",
  "element" to "oklch(0.62 0.134 70)",
  "preview-annotation" to "oklch(0.62 0.134 70)",
  "review-comment" to "oklch(0.62 0.16 292)",
  "pull-request" to "oklch(0.62 0.16 277)",
  "pr-open" to "oklch(0.62 0.134 163)",
  "pr-draft" to "oklch(0.62 0.02 259)",
  "pr-merged" to "oklch(0.62 0.16 292)",
  "pr-closed" to "oklch(0.62 0.16 16)",
  "skill" to "oklch(0.62 0.16 322)",
  "thread" to "oklch(0.62 0.12 190)",
  "environment" to "oklch(0.62 0.11 145)",
  "citation" to "oklch(0.62 0.16 259)",
  "doc-pdf" to "oklch(0.62 0.16 24)",
  "doc-word" to "oklch(0.62 0.136 248)",
  "doc-spreadsheet" to "oklch(0.62 0.134 148)",
  "doc-slides" to "oklch(0.62 0.134 56)",
  "doc-epub" to "oklch(0.62 0.16 291)",
)

/** `contextChipColorVariable`: the CSS variable a kind's solved ink goes in. */
fun contextChipColorVariable(kind: String): String = "--context-chip-$kind"
