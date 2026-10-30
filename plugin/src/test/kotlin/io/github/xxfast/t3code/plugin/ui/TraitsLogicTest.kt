package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.BooleanProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.ModelCapabilities
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProviderOptionChoice
import io.github.xxfast.t3code.protocol.generated.SelectProviderOptionDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class TraitsLogicTest {
  private val effort = SelectProviderOptionDescriptor(
    id = "effort",
    label = "Effort",
    options = listOf(
      ProviderOptionChoice("low", "Low"),
      ProviderOptionChoice("medium", "Medium", isDefault = true),
      ProviderOptionChoice("high", "High"),
      ProviderOptionChoice("ultrathink", "Ultrathink"),
    ),
    promptInjectedValues = listOf("ultrathink"),
  )
  private val fastMode = BooleanProviderOptionDescriptor(id = "fastMode", label = "Fast mode")
  private val caps = ModelCapabilities(optionDescriptors = listOf(effort, fastMode))

  private fun selection(vararg options: Pair<String, Any>): ModelSelection = ModelSelection(
    instanceId = JsonPrimitive("claude"),
    model = JsonPrimitive("opus"),
    options = optionsJson(
      options.map { (id, value) ->
        val primitive: JsonPrimitive = when (value) {
          is Boolean -> JsonPrimitive(value)
          else -> JsonPrimitive(value.toString())
        }
        ProviderOptionSelection(id, primitive)
      },
    ),
  )

  @Test
  fun `plan mode off drops the plan agent, and the agent select when that empties it`() {
    val agent = SelectProviderOptionDescriptor(
      id = "agent",
      label = "Agent",
      options = listOf(
        ProviderOptionChoice("default", "Default", isDefault = true),
        ProviderOptionChoice("plan", "Plan"),
      ),
      currentValue = "plan",
    )
    val filtered: ModelCapabilities =
      withoutPlanAgentOption(ModelCapabilities(optionDescriptors = listOf(agent, effort)))
    val select: SelectProviderOptionDescriptor = assertIs(filtered.optionDescriptors?.first())
    assertEquals(listOf("default"), select.options.map { it.id })
    assertEquals("default", select.currentValue, "A current value of plan moves to the default")
    assertEquals(effort, filtered.optionDescriptors?.last(), "Other selects are untouched")

    val planOnly = agent.copy(options = listOf(ProviderOptionChoice("plan", "Plan")))
    assertEquals(
      emptyList(),
      withoutPlanAgentOption(ModelCapabilities(optionDescriptors = listOf(planOnly)))
        .optionDescriptors,
    )
  }

  @Test
  fun `options round trip through the wire shape`() {
    val picked: ModelSelection = selection("effort" to "high", "fastMode" to true)
    assertEquals(
      listOf(
        ProviderOptionSelection("effort", JsonPrimitive("high")),
        ProviderOptionSelection("fastMode", JsonPrimitive(true)),
      ),
      picked.optionSelections,
    )
    assertNull(optionsJson(emptyList()))
  }

  @Test
  fun `a select falls back to its default, an unknown value too`() {
    val none = selectedTraits(caps, "", null)
    assertEquals("Medium", buildTraitsTriggerDisplay("claudeAgent", none, null, null).label)
    val unknown = selectedTraits(caps, "", selection("effort" to "max").optionSelections)
    assertEquals("Medium", buildTraitsTriggerDisplay("claudeAgent", unknown, null, null).label)
    val high = selectedTraits(caps, "", selection("effort" to "high").optionSelections)
    assertEquals("High", buildTraitsTriggerDisplay("claudeAgent", high, null, null).label)
  }

  @Test
  fun `fast mode folds into the reasoning label`() {
    val fast = selectedTraits(
      caps,
      "",
      selection("effort" to "high", "fastMode" to true).optionSelections,
    )
    assertEquals("High Fast", buildTraitsTriggerDisplay("claudeAgent", fast, null, null).label)
    val onlyFast = selectedTraits(ModelCapabilities(listOf(fastMode)), "", null)
    assertEquals("Normal", buildTraitsTriggerDisplay("claudeAgent", onlyFast, null, null).label)
  }

  @Test
  fun `the display splits out the speed and the labels other than reasoning`() {
    val window = SelectProviderOptionDescriptor(
      id = "contextWindow",
      label = "Context window",
      options = listOf(ProviderOptionChoice("1m", "1M", isDefault = true)),
    )
    val fast = selectedTraits(
      ModelCapabilities(listOf(effort, window, fastMode)),
      "",
      selection("effort" to "high", "fastMode" to true).optionSelections,
    )
    val display: TraitsTriggerDisplay =
      buildTraitsTriggerDisplay("claudeAgent", fast, null, null)
    assertEquals("High Fast · 1M", display.label)
    assertEquals("Fast", display.speedLabel)
    assertEquals(listOf("1M"), display.otherLabels)
    val slow = selectedTraits(caps, "", selection("effort" to "low").optionSelections)
    val plain: TraitsTriggerDisplay = buildTraitsTriggerDisplay("claudeAgent", slow, null, null)
    assertNull(plain.speedLabel)
    assertEquals(emptyList(), plain.otherLabels)
  }

  @Test
  fun `ultrathink in the prompt takes over the effort`() {
    val prompted = selectedTraits(caps, "Ultrathink:\nfix it", null)
    assertTrue(prompted.ultrathinkPromptControlled)
    assertFalse(prompted.ultrathinkInBodyText)
    assertEquals("Ultrathink", buildTraitsTriggerDisplay("claudeAgent", prompted, null, null).label)
    assertEquals("ultrathink", traitSelectValue(prompted, effort, null, null))

    val inBody = selectedTraits(caps, "please ultrathink about it", null)
    assertTrue(inBody.ultrathinkInBodyText)
    assertNull(traitSelectChange(inBody, effort, "please ultrathink about it", "low"))
  }

  @Test
  fun `picking ultrathink edits the prompt, picking a level drops the prefix`() {
    val none = selectedTraits(caps, "fix it", null)
    val ultra = traitSelectChange(none, effort, "fix it", "ultrathink")
    assertIs<TraitSelectChange.Prompt>(ultra)
    assertEquals("Ultrathink:\nfix it", ultra.prompt)
    assertEquals("Ultrathink:\n", traitSelectChange(none, effort, "", "ultrathink")?.prompt)

    val prompted = selectedTraits(caps, "Ultrathink:\nfix it", null)
    val low = traitSelectChange(prompted, effort, "Ultrathink:\nfix it", "low")
    assertIs<TraitSelectChange.Options>(low)
    assertEquals("fix it", low.prompt)
    assertEquals(
      listOf(ProviderOptionSelection("effort", JsonPrimitive("low"))),
      low.modelOptions,
    )
  }

  @Test
  fun `a slash command never gets the ultrathink prefix`() {
    assertEquals("/compact", applyClaudePromptEffortPrefix("/compact", "ultrathink"))
    assertEquals(
      "Ultrathink:\n/home/me/app.ts",
      applyClaudePromptEffortPrefix("/home/me/app.ts", "ultrathink"),
    )
    assertEquals("fix it", applyClaudePromptEffortPrefix(" fix it ", "high"))
  }

  @Test
  fun `a boolean pick keeps the other options`() {
    val traits = selectedTraits(caps, "", selection("effort" to "high").optionSelections)
    assertEquals(
      listOf(
        ProviderOptionSelection("effort", JsonPrimitive("high")),
        ProviderOptionSelection("fastMode", JsonPrimitive(true)),
      ),
      traitBooleanChange(traits, fastMode, true),
    )
  }

  @Test
  fun `the reported selection shows only for options not picked`() {
    val picked: ModelSelection = selection()
    val reported: ModelSelection = selection("effort" to "default")
    assertEquals("Default", getProviderOptionCurrentLabel(effort, picked, reported))
    val explicit: ModelSelection = selection("effort" to "high")
    val traits = selectedTraits(caps, "", explicit.optionSelections)
    assertEquals("High", getProviderOptionCurrentLabel(traits.primarySelect, explicit, reported))
  }

  @Test
  fun `a model without options shows no picker`() {
    assertFalse(selectedTraits(ModelCapabilities(), "", null).hasAnyControls)
    assertFalse(selectedTraits(null, "", null).hasAnyControls)
  }
}
