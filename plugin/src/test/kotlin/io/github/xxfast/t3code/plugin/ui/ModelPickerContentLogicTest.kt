package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ModelPickerContentLogicTest {
  private val codex: ServerProvider = provider(
    instanceId = "codex",
    models = listOf(
      model("gpt-5", "GPT-5"),
      model("gpt-5-mini", "GPT-5 mini", badge = "new"),
      model("gpt-4.1", "GPT-4.1", isLegacy = true),
      model("o3", "o3", isLegacy = true),
    ),
  )
  private val claude: ServerProvider = provider(
    instanceId = "claude",
    driver = "claudeAgent",
    models = listOf(model("opus", "Opus"), model("sonnet", "Sonnet")),
  )
  private val cursor: ServerProvider = provider(
    instanceId = "cursor",
    status = "error",
    message = "Sign in first.",
    models = listOf(model("composer-1", "Composer 1")),
  )
  private val entries: List<ModelPickerEntry> = modelPickerEntries(listOf(codex, claude, cursor))
  private val models: List<ModelPickerModel> = modelPickerFlatModels(entries)
  private val order: List<String> = entries.map { it.instanceId }

  @Test
  fun `sidebar lists enabled providers and says why one is unavailable`() {
    assertEquals(listOf("codex", "claude", "cursor"), order)
    assertEquals(listOf(true, true, false), entries.map { it.ready })
    assertEquals("Cursor. Unavailable. Sign in first.", entries[2].tooltip)
    assertEquals("Codex", entries[0].tooltip)
    val disabled: ServerProvider = provider(instanceId = "pi", enabled = false)
    assertTrue(modelPickerEntries(listOf(disabled)).isEmpty())
  }

  @Test
  fun `a provider that isn't ready lists no models`() {
    assertTrue(models.none { it.instanceId == "cursor" })
    assertEquals(6, models.size)
  }

  @Test
  fun `a tab lists its provider with the legacy models folded`() {
    val rows: List<ModelPickerRow> =
      modelPickerRows(models, "codex", "", emptySet(), emptySet(), order)
    assertEquals(
      listOf("gpt-5", "gpt-5-mini"),
      rows.filterIsInstance<ModelPickerRow.Model>().map { it.model.slug },
    )
    val fold: ModelPickerRow = rows.last()
    assertIs<ModelPickerRow.LegacySection>(fold)
    assertEquals(2, fold.count)

    val expanded: List<ModelPickerRow> =
      modelPickerRows(models, "codex", "", emptySet(), setOf("codex"), order)
    assertEquals(
      listOf("gpt-5", "gpt-5-mini", "gpt-4.1", "o3"),
      expanded.filterIsInstance<ModelPickerRow.Model>().map { it.model.slug },
    )
    assertIs<ModelPickerRow.LegacySection>(expanded[2])
  }

  @Test
  fun `favourites come first on a tab, and All is every provider's current models`() {
    val favorites: Set<String> = setOf("claude:sonnet", "codex:gpt-5-mini", "codex:o3")
    val onCodex: List<ModelPickerRow> =
      modelPickerRows(models, "codex", "", favorites, emptySet(), order)
    assertEquals(
      listOf("gpt-5-mini", "gpt-5"),
      onCodex.filterIsInstance<ModelPickerRow.Model>().map { it.model.slug },
    )
    // All: favourites first, then provider order; legacy only when favourited
    val onAll: List<ModelPickerRow> =
      modelPickerRows(models, null, "", favorites, emptySet(), order)
    assertEquals(
      listOf("gpt-5-mini", "o3", "sonnet", "gpt-5", "opus"),
      onAll.filterIsInstance<ModelPickerRow.Model>().map { it.model.slug },
    )
    assertTrue(onAll.none { it is ModelPickerRow.LegacySection })
    // Favorites: only the favourites, in provider order, legacy included
    val onFavorites: List<ModelPickerRow> =
      modelPickerRows(models, FAVORITES_TAB, "", favorites, emptySet(), order)
    assertEquals(
      listOf("gpt-5-mini", "o3", "sonnet"),
      onFavorites.filterIsInstance<ModelPickerRow.Model>().map { it.model.slug },
    )
  }

  @Test
  fun `the picker opens on the thread's provider when it's ready, else All`() {
    assertEquals("claude", initialModelPickerTab("claude", entries))
    assertNull(initialModelPickerTab("cursor", entries))
    assertNull(initialModelPickerTab(null, entries))
    assertEquals(setOf("codex"), initialExpandedLegacy(models, "codex", "o3"))
    assertTrue(initialExpandedLegacy(models, "codex", "gpt-5").isEmpty())
  }

  @Test
  fun `a search ranks the tab's models, exact name first, no fold`() {
    val rows: List<ModelPickerRow> =
      modelPickerRows(models, null, "gpt-5", emptySet(), emptySet(), order)
    val slugs: List<String> = rows.filterIsInstance<ModelPickerRow.Model>().map { it.model.slug }
    assertEquals("gpt-5", slugs.first())
    assertTrue("gpt-5-mini" in slugs)
    assertTrue("opus" !in slugs)
    assertTrue(rows.none { it is ModelPickerRow.LegacySection })

    // Legacy models surface in a search
    assertTrue(
      modelPickerRows(models, "codex", "o3", emptySet(), emptySet(), order)
        .filterIsInstance<ModelPickerRow.Model>()
        .any { it.model.slug == "o3" },
    )
    // A search stays inside a provider's tab
    assertTrue(modelPickerRows(models, "claude", "gpt", emptySet(), emptySet(), order).isEmpty())
    // A favourite wins a tie
    val tied: List<ModelPickerRow> =
      modelPickerRows(models, "codex", "gpt", setOf("codex:gpt-5-mini"), emptySet(), order)
    assertEquals("gpt-5-mini", (tied.first() as ModelPickerRow.Model).model.slug)
    // The provider's name matches too
    assertEquals(
      listOf("opus", "sonnet"),
      modelPickerRows(models, null, "claude", emptySet(), emptySet(), order)
        .filterIsInstance<ModelPickerRow.Model>()
        .map { it.model.slug },
    )
  }

  @Test
  fun `subsequence and query scoring follow upstream's penalties`() {
    assertEquals(0, scoreSubsequenceMatch("abc", "", ignorePosition = false))
    assertNull(scoreSubsequenceMatch("abc", "d", ignorePosition = false))
    // "gpt" in "g-p-t": two gaps of one, span 5
    assertEquals(3 * 2 + 2, scoreSubsequenceMatch("g-p-t", "gpt", ignorePosition = true))
    assertEquals(10, scoreQueryMatch("opus", "opus", 10, 12, 14, 16, 110, ignorePosition = true))
    assertEquals(12, scoreQueryMatch("opus 4", "opus", 10, 12, 14, 16, 110, ignorePosition = true))
    assertEquals(14, scoreQueryMatch("claude opus", "opus", 10, 12, 14, 16, 110, true))
    assertEquals(16, scoreQueryMatch("xopus", "opus", 10, 12, 14, 16, 110, true))
    assertNull(scoreQueryMatch("xopus", "zzz", 10, 12, 14, 16, null, true))
  }

  @Test
  fun `names drop a leading sub-provider and rows label the provider with it`() {
    val model = ModelPickerModel(
      instanceId = "opencode",
      driver = "opencode",
      slug = "anthropic/claude-opus",
      name = "Anthropic Claude Opus",
      shortName = "Anthropic: Opus",
      subProvider = "Anthropic",
      isNew = false,
      isLegacy = false,
      providerDisplayName = "OpenCode",
    )
    assertEquals("Claude Opus", getDisplayModelName(model))
    assertEquals("Opus", getDisplayModelName(model, preferShortName = true))
    assertEquals("OpenCode · Anthropic", getProviderRowLabel("OpenCode", "Anthropic"))
    assertEquals("OpenCode Zen", getProviderRowLabel("OpenCode", "OpenCode Zen"))
    assertEquals("Codex", getProviderRowLabel("Codex", null))
  }

  @Test
  fun `a blocked model's reason says to start a new thread`() {
    assertNull(modelDisabledReason(null))
    val block = ModelChangeBlock("Start a new chat to switch providers", "Nope.")
    assertEquals("Nope. Start a new thread to use this model.", modelDisabledReason(block))
  }
}

private fun provider(
  instanceId: String,
  driver: String = instanceId,
  enabled: Boolean = true,
  status: String = "ready",
  message: String? = null,
  models: List<ServerProviderModel> = emptyList(),
): ServerProvider = ServerProvider(
  instanceId = instanceId,
  driver = driver,
  enabled = enabled,
  installed = true,
  version = null,
  status = status,
  auth = ServerProviderAuth(status = "authenticated"),
  checkedAt = "2026-10-21T10:00:00Z",
  message = message,
  models = models,
)

private fun model(
  slug: String,
  name: String,
  badge: String? = null,
  isLegacy: Boolean? = null,
): ServerProviderModel = ServerProviderModel(
  slug = slug,
  name = name,
  badge = badge,
  isCustom = false,
  isLegacy = isLegacy,
  capabilities = null,
)
