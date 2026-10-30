package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ModelShortNameTest {
  private val claude: ServerProvider = provider(
    "claude",
    "claudeAgent",
    model("opus", "Claude Opus 5.5"),
    model("sonnet", "Claude Sonnet 4.5"),
  )
  private val codex: ServerProvider = provider(
    "codex",
    "codex",
    model("gpt-6.1-sol", "GPT-6.1-Sol"),
    model("gpt-5", "GPT-5"),
    model("gpt-5-codex", "GPT-5 Codex", shortName = "Codex"),
  )
  private val cursor: ServerProvider = provider(
    "cursor",
    "cursor",
    model("claude-4.5-sonnet", "Claude Sonnet 4.5"),
    model("gemini-3-pro", "Gemini 3 Pro"),
    model("claude-opus-4-6", "Opus 4.6", subProvider = "Anthropic"),
  )
  private val providers: List<ServerProvider> = listOf(claude, codex, cursor)

  // modelShortName.test.ts, case for case
  @ParameterizedTest(name = "gives {0} on families {1} the short name {2}")
  @MethodSource("upstreamCases")
  fun `modelShortName matches upstream`(name: String, families: List<String>, expected: String?) {
    assertEquals(expected, modelShortName(name, families))
  }

  @Test
  fun `a no-break space separates words, as JavaScript's whitespace does`() {
    assertNull(modelShortName("Gemini 3\u00A0Pro", emptyList()))
    assertEquals("Opus\u00A05.5", modelShortName("Claude Opus\u00A05.5", listOf("Claude ")))
  }

  @Test
  fun `the server's short name wins, else the driver's families`() {
    assertEquals("Codex", resolveModelShortName(model("x", "GPT-5 Codex", "Codex"), "codex"))
    assertEquals("5 Codex", resolveModelShortName(model("x", "GPT-5 Codex", ""), "codex"))
    assertEquals("Opus 5.5", resolveModelShortName(model("x", "Claude Opus 5.5"), "claudeAgent"))
    assertNull(resolveModelShortName(model("x", "Grok 4"), "codex"))
    assertEquals("4 Heavy", resolveModelShortName(model("x", "Grok 4 Heavy"), "grok"))
    assertEquals("4 Heavy", resolveModelShortName(model("x", "Grok 4 Heavy"), "cursor"))
    assertEquals("Bolt", resolveModelShortName(model("x", "Muse Bolt"), "muse"))
    assertEquals("2.5 Pro", resolveModelShortName(model("x", "Gemini 2.5 Pro"), "antigravity"))
    assertNull(resolveModelShortName(model("x", "Gemini 2.5 Pro"), "opencode"))
    assertNull(resolveModelShortName(model("x", "Gemini 2.5 Pro"), null))
  }

  @Test
  fun `the trigger says the short name, the tooltip the full one`() {
    assertEquals("Opus 5.5", modelPickerLabel(providers, "claude", "opus"))
    assertEquals("Sonnet 4.5", modelPickerLabel(providers, "claude", "sonnet"))
    assertEquals("6.1 Sol", modelPickerLabel(providers, "codex", "gpt-6.1-sol"))
    assertEquals("GPT-5", modelPickerLabel(providers, "codex", "gpt-5"))
    assertEquals("Codex", modelPickerLabel(providers, "codex", "gpt-5-codex"))
    assertEquals("Sonnet 4.5", modelPickerLabel(providers, "cursor", "claude-4.5-sonnet"))
    assertEquals("Gemini 3 Pro", modelPickerLabel(providers, "cursor", "gemini-3-pro"))

    assertEquals("Claude Opus 5.5", modelPickerFullLabel(providers, "claude", "opus"))
    assertEquals("GPT-6.1-Sol", modelPickerFullLabel(providers, "codex", "gpt-6.1-sol"))
    assertEquals("custom", modelPickerFullLabel(providers, "codex", "custom"))
    assertEquals("Model", modelPickerFullLabel(providers, "codex", null))

    val footer: ComposerFooterState = composerFooter(providers, "claude", "opus", "auto", "default")
    assertEquals("Opus 5.5", footer.modelLabel)
    assertEquals("Claude Opus 5.5", footer.modelFullLabel)
  }

  @Test
  fun `getTriggerDisplayModelName strips the sub-provider after shortening`() {
    val opus: ServerProviderModel = model("o", "Anthropic: Claude Opus", subProvider = "Anthropic")
    assertEquals("Opus", getTriggerDisplayModelName(opus, "claudeAgent"))
    assertEquals("Claude Opus", getDisplayModelName(opus))
  }

  @Test
  fun `the picker rows list the full name and search the short one`() {
    val models: List<ModelPickerModel> = modelPickerFlatModels(modelPickerEntries(providers))
    val opus: ModelPickerModel = models.single { it.slug == "opus" }
    assertEquals("Opus 5.5", opus.shortName)
    assertEquals("Claude Opus 5.5", getDisplayModelName(opus))
    assertEquals(-1000, scoreModelPickerSearch(opus, "opus 5.5", isFavorite = false))
  }

  @Test
  fun `the context window meter names the full model`() {
    fun name(instance: String, slug: String): String? = resolveContextWindowModelDisplayName(
      ModelSelection(instanceId = JsonPrimitive(instance), model = JsonPrimitive(slug)),
      providers,
    )

    assertEquals("Claude Opus 5.5", name("claude", "opus"))
    assertEquals("unknown", name("claude", "unknown"))
    assertNull(resolveContextWindowModelDisplayName(null, providers))
    val footer: ComposerFooterState = composerFooter(
      providers = providers,
      instanceId = "codex",
      model = "gpt-6.1-sol",
      runtimeMode = "auto",
      interactionMode = "default",
      selection = ModelSelection(
        instanceId = JsonPrimitive("codex"),
        model = JsonPrimitive("gpt-6.1-sol"),
      ),
    )
    assertEquals("GPT-6.1-Sol", footer.contextWindowModelName)
  }

  @Test
  fun `subagent labels say the model out loud`() {
    assertEquals("Opus 5.5", resolveSubagentModelLabel("opus", claude))
    assertEquals("Opus 5.5", resolveSubagentModelLabel(" Claude Opus 5.5 ", claude), "By name")
    assertEquals("Sonnet 4.5", resolveSubagentModelLabel("claude-4.5-sonnet", cursor))
    assertEquals("Opus 4.6", resolveSubagentModelLabel("opus-4.6", cursor), "Through an alias")
    assertEquals("Claude Opus 4.6", resolveSubagentModelLabel("claude-opus-4-6", null))
    assertEquals("GPT-5.4-Mini", resolveSubagentModelLabel("gpt-5.4-mini", codex))
    assertEquals("mystery", resolveSubagentModelLabel("mystery", codex))
    assertEquals("Not reported", resolveSubagentModelLabel(null, codex))
    assertEquals("Not reported", resolveSubagentModelLabel("  ", null))
  }

  @Test
  fun `formatModelSlugName reads known slugs and keeps the rest`() {
    assertEquals("Claude Opus 4.6", formatModelSlugName("claude-opus-4-6"))
    assertEquals("Claude Sonnet 4.5[1m]", formatModelSlugName("claude-sonnet-4-5[1m]"))
    assertEquals("openai/GPT-5.4-Mini", formatModelSlugName("openai/gpt-5.4-mini"))
    assertEquals("Gemini 3 Pro", formatModelSlugName("gemini-3-pro"))
    assertEquals("o3", formatModelSlugName("o3"))
  }

  companion object {
    @JvmStatic
    fun upstreamCases(): List<Arguments> = listOf(
      Arguments.of("Claude Opus 5.5", listOf("Claude "), "Opus 5.5"),
      Arguments.of("GPT-6.1-Sol", listOf("GPT-"), "6.1 Sol"),
      Arguments.of("GPT 6.0 Astra", listOf("GPT-", "GPT "), "6.0 Astra"),
      Arguments.of("Grok 4 Pro", emptyList<String>(), null),
      Arguments.of("Claude Opus 5.5", emptyList<String>(), "Opus 5.5"),
      Arguments.of("NVIDIA: Nemotron 3 Super", emptyList<String>(), "Nemotron 3 Super"),
      Arguments.of("GPT-5.5", listOf("GPT-"), null),
      Arguments.of("Gemini 3.1 Pro", listOf("Gemini "), "3.1 Pro"),
    )
  }
}

private fun provider(
  instanceId: String,
  driver: String,
  vararg models: ServerProviderModel,
): ServerProvider = ServerProvider(
  instanceId = instanceId,
  driver = driver,
  enabled = true,
  installed = true,
  version = null,
  status = "ready",
  auth = ServerProviderAuth(status = "authenticated"),
  checkedAt = "2026-10-30T10:00:00Z",
  models = models.toList(),
)

private fun model(
  slug: String,
  name: String,
  shortName: String? = null,
  subProvider: String? = null,
): ServerProviderModel = ServerProviderModel(
  slug = slug,
  name = name,
  shortName = shortName,
  subProvider = subProvider,
  isCustom = false,
  capabilities = null,
)
