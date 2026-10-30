package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProviderModel

/**
 * Each driver's `modelFamilies`, from upstream's `packages/client-runtime/src/providerClients.ts`
 * and the provider packages' `client.ts`: the family prefixes its icon already says. Every other
 * driver has none.
 */
private val MODEL_FAMILIES: Map<String, List<String>> = mapOf(
  "codex" to listOf("GPT-", "GPT "),
  "claudeAgent" to listOf("Claude "),
  "cursor" to listOf("Grok "),
  "grok" to listOf("Grok "),
  "antigravity" to listOf("Gemini "),
  "muse" to listOf("Muse "),
)

/**
 * `resolveModelShortName`, `providerClients.ts:53`: the server's `shortName` first, else
 * [modelShortName] on [driverKind]'s families.
 */
fun resolveModelShortName(model: ServerProviderModel, driverKind: String?): String? {
  if (!model.shortName.isNullOrEmpty()) return model.shortName
  val families: List<String>? = driverKind?.let { MODEL_FAMILIES[it] }
  return modelShortName(model.name, families.orEmpty())
}
