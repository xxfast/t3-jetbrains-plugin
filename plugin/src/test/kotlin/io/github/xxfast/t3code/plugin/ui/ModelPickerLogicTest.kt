package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ModelPickerLogicTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val idle: Thread = Scenario
    .decode(scenarios.resolve("default").resolve("list.json").readText())
    .threads
    .first { it.status == "idle" }

  @Test
  fun `runtime modes are upstream's four, or the ones the provider supports`() {
    assertEquals(
      listOf("Supervised", "Auto-accept edits", "Auto", "Full access"),
      compatibleRuntimeModeOptions(null).map { it.label },
    )
    val empty: ServerProvider = provider(supportedRuntimeModes = emptyList())
    assertEquals(RUNTIME_MODES, compatibleRuntimeModeOptions(empty))
    val limited: ServerProvider =
      provider(supportedRuntimeModes = listOf("full-access", null, "approval-required"))
    assertEquals(
      listOf("approval-required", "full-access"),
      compatibleRuntimeModeOptions(limited).map { it.mode },
      "Upstream's order, not the provider's",
    )
  }

  @Test
  fun `only modes this build doesn't know still leave all four to pick from`() {
    val unknown: ServerProvider = provider(supportedRuntimeModes = listOf("yolo"))
    assertEquals(RUNTIME_MODES, compatibleRuntimeModeOptions(unknown))
  }

  @Test
  fun `an unsupported runtime mode shows as the first supported one`() {
    val options: List<RuntimeModeOption> = compatibleRuntimeModeOptions(
      provider(supportedRuntimeModes = listOf("auto", "full-access")),
    )
    assertEquals("full-access", compatibleRuntimeMode("full-access", options))
    assertEquals("auto", compatibleRuntimeMode("approval-required", options))
    assertEquals("x", compatibleRuntimeMode("x", emptyList()))
  }

  @Test
  fun `the plan toggle needs the setting and a provider that doesn't turn it off`() {
    assertEquals(
      InteractionModeResolution(false, "default"),
      resolveComposerInteractionMode(true, null, "plan"),
    )
    assertTrue(resolveComposerInteractionMode(true, provider(toggle = null), "plan").enabled)
    assertEquals(
      InteractionModeResolution(true, "plan"),
      resolveComposerInteractionMode(true, provider(toggle = true), "plan"),
    )
    assertEquals(
      InteractionModeResolution(false, "default"),
      resolveComposerInteractionMode(true, provider(toggle = false), "plan"),
    )
    assertEquals(
      InteractionModeResolution(false, "default"),
      resolveComposerInteractionMode(false, provider(toggle = true), "plan"),
      "Plan mode is a legacy setting, off by default",
    )
    assertEquals("plan", toggledInteractionMode("default"))
    assertEquals("default", toggledInteractionMode("plan"))
    assertEquals("Plan mode. Click to return to normal build mode.", interactionModeTooltip("plan"))
    assertEquals("Default mode. Click to enter plan mode.", interactionModeTooltip("default"))
  }

  @Test
  fun `a selection's instance falls back to the legacy provider field`() {
    val current = ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("m"))
    assertEquals("codex", current.instance)
    assertEquals("m", current.slug)
    val legacy = ModelSelection(provider = JsonPrimitive("claude"), model = JsonPrimitive("m"))
    assertEquals("claude", legacy.instance)
    assertNull(ModelSelection(model = JsonObject(emptyMap())).slug)
  }

  @Test
  fun `the selected model is the slug, or antigravity's available alias then default`() {
    val models: List<ServerProviderModel> = listOf(
      model("a", "A", isDefault = true),
      model("b", "B", aliases = listOf("antigravity-default")),
      model("antigravity-default", "Default"),
    )
    assertEquals("a", resolveModelPickerSelectedModel("codex", "a", models)?.slug)
    assertNull(resolveModelPickerSelectedModel("codex", "z", models))
    assertEquals(
      "b",
      resolveModelPickerSelectedModel("antigravity", "antigravity-default", models)?.slug,
    )
    assertEquals(
      "a",
      resolveModelPickerSelectedModel(
        driverKind = "antigravity",
        model = "antigravity-default",
        options = models,
        unavailable = setOf("b"),
      )?.slug,
      "An unavailable alias doesn't count",
    )
  }

  @Test
  fun `display names follow resolveProviderInstanceDisplayName`() {
    fun name(instanceId: String, driver: String, displayName: String?): String =
      resolveProviderInstanceDisplayName(provider(instanceId, driver, displayName))

    assertEquals("Claude", name("claudeAgent", "claudeAgent", null))
    assertEquals("Work", name("claudeAgent", "claudeAgent", " Work "))
    assertEquals(
      "Claude Work",
      name("claude_work", "claudeAgent", "Claude"),
      "A second instance of a driver is named by its id",
    )
    assertEquals("My Driver", name("myDriver", "myDriver", null))
  }

  @Test
  fun `the picker groups ready providers, hides legacy models unless current`() {
    val providers: List<ServerProvider> = listOf(
      provider(
        "codex",
        "codex",
        "Codex",
        models = listOf(
          model("gpt-5", "GPT-5"),
          model("mini", "Mini", badge = "new"),
          model("old", "Old", isLegacy = true),
        ),
      ),
      provider("claude", "claudeAgent", "Claude", models = listOf(model("opus", "Opus"))),
      provider("off", "cursor", "Off", enabled = false, models = listOf(model("c", "C"))),
      provider("down", "grok", "Down", status = "error", models = listOf(model("g", "G"))),
    )

    val groups: List<ModelPickerGroup> = modelPickerItems(providers, "codex", "gpt-5")
    assertEquals(listOf("Codex", "Claude"), groups.map { it.displayName })
    assertEquals(listOf("gpt-5", "mini"), groups[0].models.map { it.slug })
    assertEquals(listOf(true, false), groups[0].models.map { it.selected })
    assertEquals(listOf(false, true), groups[0].models.map { it.isNew })

    val onLegacy: List<ModelPickerGroup> = modelPickerItems(providers, "codex", "old")
    assertEquals(listOf("gpt-5", "mini", "old"), onLegacy[0].models.map { it.slug })

    val onDown: List<ModelPickerGroup> = modelPickerItems(providers, "down", "g")
    assertEquals(listOf("g"), onDown.single { it.instanceId == "down" }.models.map { it.slug })

    assertEquals("GPT-5", modelPickerLabel(providers, "codex", "gpt-5"))
    assertEquals("custom", modelPickerLabel(providers, "codex", "custom"))
    assertEquals("Model", modelPickerLabel(providers, "codex", null))
  }

  @Test
  fun `antigravity's default slug never lists, even as the current model`() {
    val antigravity: ServerProvider = provider(
      instanceId = "antigravity",
      models = listOf(
        model("antigravity-default", "Default"),
        model("gem", "Gem", aliases = listOf("antigravity-default")),
      ),
    )
    val groups: List<ModelPickerGroup> =
      modelPickerItems(listOf(antigravity), "antigravity", "antigravity-default")
    assertEquals(listOf("gem"), groups.single().models.map { it.slug })
    assertTrue(groups.single().models.single().selected, "The alias stands for it")
  }

  @Test
  fun `a started thread blocks a model change its provider can't take`() {
    val strict: List<ServerProvider> = listOf(
      provider("codex", requiresNewThreadForModelChange = true),
      provider("claude", "claudeAgent"),
    )
    fun block(
      started: Boolean = true,
      handoff: Boolean = false,
      instance: String = "codex",
      model: String = "b",
      providers: List<ServerProvider> = strict,
    ): ModelChangeBlock? = getStartedThreadModelChangeBlockReason(
      providers = providers,
      hasStartedSession = started,
      supportsProviderSwitchingViaHandoff = handoff,
      currentInstance = "codex",
      currentModel = "a",
      nextInstance = instance,
      nextModel = model,
    )

    assertNull(block(started = false))
    assertNull(block(model = "a"), "Same model")
    assertEquals("Start a new chat to change models", block()?.title)
    assertNull(block(providers = listOf(provider("codex"))), "This provider takes it")
    assertEquals("Start a new chat to switch providers", block(instance = "claude")?.title)
    assertNull(block(instance = "claude", handoff = true))
    assertEquals(
      "Start a new chat to change models. This provider does not allow switching models " +
        "after a conversation has started.",
      block().toString(),
    )
  }

  @Test
  fun `a thread with no runs can hand off to another provider`() {
    val fresh = ThreadView(thread = idle, runs = emptyList(), items = emptyList())
    assertTrue(threadSupportsProviderHandoff(fresh))
  }

  @Test
  fun `mode commands are setThreadRuntimeMode and setThreadInteractionMode`() {
    val runtime: Command.ThreadRuntimeModeSet =
      assertIs(setThreadRuntimeMode("t1", "full-access", "c1"))
    assertEquals("thread.runtime-mode.set", runtime.type)
    assertEquals("full-access", runtime.runtimeMode)
    val interaction: Command.ThreadInteractionModeSet =
      assertIs(setThreadInteractionMode("t1", "plan", "c2"))
    assertEquals("thread.interaction-mode.set", interaction.type)
    assertEquals("plan", interaction.interactionMode)
  }

  @Test
  fun `a pick keeps the options within the instance and drops them across`() {
    val options = JsonObject(mapOf("reasoningEffort" to JsonPrimitive("high")))
    val current = ModelSelection(
      instanceId = JsonPrimitive("codex"),
      model = JsonPrimitive("gpt-5"),
      options = options,
    )
    assertEquals(options, nextModelSelection(current, "codex", "mini").options)
    assertEquals("mini", nextModelSelection(current, "codex", "mini").slug)
    assertNull(nextModelSelection(current, "claude", "opus").options)
    assertEquals("claude", nextModelSelection(current, "claude", "opus").instance)
    assertNull(nextModelSelection(null, "claude", "opus").options)
  }

  @Test
  fun `without server resolved context a change of the row's instance is a provider switch`() {
    val opus: ModelSelection = nextModelSelection(null, "claude", "opus")
    assertIs<Command.ProviderSwitch>(updateThreadModelSelection("t1", opus, "codex", false, "c1"))
    assertIs<Command.ThreadModelSelectionSet>(
      updateThreadModelSelection("t1", opus, "claude", false, "c1"),
      "Compared with the row, not with a pending pick",
    )
    assertIs<Command.ThreadModelSelectionSet>(
      updateThreadModelSelection("t1", opus, "codex", true, "c1"),
    )
  }

  @Test
  fun `the wire shape is instanceId and model, options only when there are some`() {
    val selection: ModelSelection = nextModelSelection(null, "claude", "opus")
    val command: Command = updateThreadModelSelection("t1", selection, "codex", true, "c1")
    val encoded: String = T3Json.encodeToString(OrchestrationV2Command.serializer(), command)
    assertEquals(
      """{"type":"thread.model-selection.set","commandId":"c1","threadId":"t1",""" +
        """"modelSelection":{"instanceId":"claude","model":"opus"}}""",
      encoded,
    )
  }

  @Test
  fun `the footer follows the thread's provider`() {
    val providers: List<ServerProvider> = listOf(
      provider(
        "codex",
        "codex",
        "Codex",
        toggle = true,
        supportedRuntimeModes = listOf("approval-required", "full-access"),
        models = listOf(model("gpt-5", "GPT-5")),
      ),
      provider("claude", "claudeAgent", "Claude", toggle = false, models = listOf(model("o", "O"))),
    )

    val codex: ComposerFooterState =
      composerFooter(providers, "codex", "gpt-5", "auto", "plan", planModeEnabled = true)
    assertEquals("GPT-5", codex.modelLabel)
    assertEquals("approval-required", codex.runtimeMode, "Not Codex's, so the first is shown")
    assertEquals(2, codex.runtimeModes.size)
    assertTrue(codex.showInteractionModeToggle)
    assertEquals("plan", codex.interactionMode)

    val claude: ComposerFooterState =
      composerFooter(providers, "claude", "o", "auto", "plan", planModeEnabled = true)
    assertEquals("auto", claude.runtimeMode)
    assertFalse(claude.showInteractionModeToggle)
    assertEquals("default", claude.interactionMode, "No toggle, no plan mode")

    val off: ComposerFooterState = composerFooter(providers, "codex", "gpt-5", "auto", "plan")
    assertFalse(off.showInteractionModeToggle, "Plan mode is off by default")
    assertEquals("default", off.interactionMode)
  }

  @Test
  fun `the footer badges an instance that shares its driver`() {
    val work: ServerProvider = provider("codex_work", driver = "codex")
    val personal: ServerProvider = provider("codex_personal", driver = "codex")
    val claude: ServerProvider = provider("claude", driver = "claudeAgent")
    val off: ServerProvider = provider("codex_off", driver = "codex", enabled = false)

    fun badge(providers: List<ServerProvider>, instance: String): Boolean =
      composerFooter(providers, instance, null, "auto", "default").providerBadge

    assertTrue(badge(listOf(work, personal, claude), "codex_work"))
    assertFalse(badge(listOf(work, personal, claude), "claude"))
    assertFalse(badge(listOf(work, off), "codex_work"), "A disabled sibling doesn't count")
    assertTrue(badge(listOf(work, off), "codex_off"), "A disabled one still tells them apart")
  }
}

private fun provider(
  instanceId: String = "codex",
  driver: String = instanceId,
  displayName: String? = null,
  toggle: Boolean? = null,
  supportedRuntimeModes: List<String?>? = null,
  requiresNewThreadForModelChange: Boolean? = null,
  enabled: Boolean = true,
  status: String = "ready",
  models: List<ServerProviderModel> = emptyList(),
): ServerProvider = ServerProvider(
  instanceId = instanceId,
  driver = driver,
  displayName = displayName,
  showInteractionModeToggle = toggle,
  supportedRuntimeModes = supportedRuntimeModes,
  requiresNewThreadForModelChange = requiresNewThreadForModelChange,
  enabled = enabled,
  installed = true,
  version = null,
  status = status,
  auth = ServerProviderAuth(status = "authenticated"),
  checkedAt = "2026-10-20T10:00:00Z",
  models = models,
)

private fun model(
  slug: String,
  name: String,
  badge: String? = null,
  isLegacy: Boolean? = null,
  isDefault: Boolean? = null,
  aliases: List<String>? = null,
): ServerProviderModel = ServerProviderModel(
  slug = slug,
  name = name,
  aliases = aliases,
  badge = badge,
  isCustom = false,
  isDefault = isDefault,
  isLegacy = isLegacy,
  capabilities = null,
)
