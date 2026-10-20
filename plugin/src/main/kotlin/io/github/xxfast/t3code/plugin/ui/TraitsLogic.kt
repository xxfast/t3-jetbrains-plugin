package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.BooleanProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.ModelCapabilities
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.ProviderThread
import io.github.xxfast.t3code.protocol.generated.SelectProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// The model's options ("traits": effort, thinking, fast mode, context window), ported from
// upstream's `packages/shared/src/model.ts` and `apps/web/src/components/chat/TraitsPicker.tsx`.
// A value is a string for a select and a boolean for a toggle, kept as a JsonPrimitive the way
// it travels in `ModelSelection.options`.

/** `ProviderOptionSelection`: one option's chosen value. */
data class ProviderOptionSelection(val id: String, val value: JsonPrimitive)

/** `modelSelection.options`, read off the wire. Anything that isn't `{id, value}` is skipped. */
val ModelSelection.optionSelections: List<ProviderOptionSelection>
  get() = (options as? JsonArray).orEmpty().mapNotNull { element ->
    val entry: JsonObject = element as? JsonObject ?: return@mapNotNull null
    val id: String = (entry["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    val value: JsonPrimitive = entry["value"] as? JsonPrimitive ?: return@mapNotNull null
    ProviderOptionSelection(id, value)
  }

/** [selections] as `modelSelection.options`, or null for none (`createModelSelection` drops it). */
fun optionsJson(selections: List<ProviderOptionSelection>?): JsonElement? {
  if (selections.isNullOrEmpty()) return null
  return buildJsonArray {
    selections.forEach { selection ->
      add(buildJsonObject {
        put("id", selection.id)
        put("value", selection.value)
      })
    }
  }
}

private val JsonPrimitive.stringOrNull: String? get() = if (isString) content else null
private val JsonPrimitive.boolOrNull: Boolean? get() = if (isString) null else booleanOrNull

/** `getRawSelectionValueById`, model.ts:40. */
private fun rawSelectionValue(
  selections: List<ProviderOptionSelection>?,
  id: String,
): JsonPrimitive? = selections?.find { it.id == id }?.value

private fun SelectProviderOptionDescriptor.defaultOption(): String? =
  options.find { it.isDefault == true }?.id

/** `resolveDescriptorChoiceValue`, model.ts:118. */
private fun resolveDescriptorChoiceValue(
  descriptor: SelectProviderOptionDescriptor,
  raw: String?,
): String? {
  val trimmed: String? = raw?.trim()?.ifEmpty { null }
  if (trimmed == null) return descriptor.currentValue ?: descriptor.defaultOption()
  if (descriptor.options.isEmpty()) return trimmed
  val known: Boolean = descriptor.options.any { it.id == trimmed }
  if (descriptor.promptInjectedValues?.contains(trimmed) == true && known) {
    return descriptor.defaultOption()
  }
  if (known) return trimmed
  return descriptor.currentValue ?: descriptor.defaultOption()
}

/** `withDescriptorCurrentValue`, model.ts:159. */
private fun withDescriptorCurrentValue(
  descriptor: ProviderOptionDescriptor,
  raw: JsonPrimitive?,
): ProviderOptionDescriptor = when (descriptor) {
  is BooleanProviderOptionDescriptor ->
    raw?.boolOrNull?.let { descriptor.copy(currentValue = it) } ?: descriptor

  is SelectProviderOptionDescriptor -> {
    val rawValue: String? = raw?.stringOrNull
    val current: String? = if (rawValue != null) {
      resolveDescriptorChoiceValue(descriptor, rawValue)
    } else {
      resolveDescriptorChoiceValue(descriptor, descriptor.currentValue)
    }
    descriptor.copy(currentValue = current)
  }

  else -> descriptor
}

private val ProviderOptionDescriptor.currentPrimitive: JsonPrimitive?
  get() = when (this) {
    is BooleanProviderOptionDescriptor -> currentValue?.let(::JsonPrimitive)
    is SelectProviderOptionDescriptor -> currentValue?.let(::JsonPrimitive)
    else -> null
  }

/** `getProviderOptionDescriptors`, model.ts:185: the model's descriptors, set to [selections]. */
fun getProviderOptionDescriptors(
  caps: ModelCapabilities?,
  selections: List<ProviderOptionSelection>?,
): List<ProviderOptionDescriptor> = caps?.optionDescriptors.orEmpty().map { descriptor ->
  withDescriptorCurrentValue(
    descriptor,
    rawSelectionValue(selections, descriptor.id) ?: descriptor.currentPrimitive,
  )
}

/** `getReportedOptionValue`, model.ts:200: what the provider says it's running, if it's ours. */
private fun reportedOptionValue(
  id: String,
  selection: ModelSelection?,
  reported: ModelSelection?,
): JsonPrimitive? {
  if (selection == null || reported == null) return null
  if (selection.instance != reported.instance || selection.slug != reported.slug) return null
  if (selection.optionSelections.any { it.id == id }) return null
  return rawSelectionValue(reported.optionSelections, id)
}

/** `getProviderOptionCurrentValue`, model.ts:216. */
fun getProviderOptionCurrentValue(
  descriptor: ProviderOptionDescriptor?,
  selection: ModelSelection? = null,
  reported: ModelSelection? = null,
): JsonPrimitive? {
  if (descriptor == null) return null
  val explicit: Boolean = selection?.optionSelections?.any { it.id == descriptor.id } == true
  // Reported values are display-only; callers that build dispatch options omit them
  reportedOptionValue(descriptor.id, selection, reported)?.let { return it }
  if (descriptor.id == "variant" && selection != null && !explicit) return null
  return when (descriptor) {
    is BooleanProviderOptionDescriptor -> descriptor.currentValue?.let(::JsonPrimitive)
    is SelectProviderOptionDescriptor ->
      (descriptor.currentValue ?: descriptor.defaultOption())?.let(::JsonPrimitive)

    else -> null
  }
}

/** `getProviderOptionCurrentLabel`, model.ts:238. */
fun getProviderOptionCurrentLabel(
  descriptor: ProviderOptionDescriptor?,
  selection: ModelSelection? = null,
  reported: ModelSelection? = null,
): String? = when (descriptor) {
  null -> null
  is BooleanProviderOptionDescriptor -> descriptor.currentValue?.let { if (it) "On" else "Off" }
  is SelectProviderOptionDescriptor -> {
    val current: String? =
      getProviderOptionCurrentValue(descriptor, selection, reported)?.stringOrNull
    descriptor.options.find { it.id == current }?.label
      ?: when {
        reportedOptionValue(descriptor.id, selection, reported)?.stringOrNull == "default" ->
          "Default"

        descriptor.id == "variant" -> "Unknown"
        else -> null
      }
  }

  else -> null
}

/** `buildProviderOptionSelectionsFromDescriptors`, model.ts:264. */
fun buildProviderOptionSelectionsFromDescriptors(
  descriptors: List<ProviderOptionDescriptor>,
): List<ProviderOptionSelection>? = descriptors
  .mapNotNull { descriptor ->
    getProviderOptionCurrentValue(descriptor)?.let { ProviderOptionSelection(descriptor.id, it) }
  }
  .ifEmpty { null }

/** `isClaudeUltrathinkPrompt`, model.ts:297. */
fun isClaudeUltrathinkPrompt(text: String?): Boolean =
  text != null && Regex("\\bultrathink\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

private val SLASH_COMMAND: Regex = Regex("^/[^\\s/]+(?:\\s|$)")

/** `applyClaudePromptEffortPrefix`, model.ts:483. */
fun applyClaudePromptEffortPrefix(text: String, effort: String?): String {
  val trimmed: String = text.trim()
  if (trimmed.isEmpty()) return trimmed
  // Prefixing a slash command turns it into prose, so Claude never runs it
  if (effort != "ultrathink" || SLASH_COMMAND.containsMatchIn(trimmed)) return trimmed
  if (trimmed.startsWith("Ultrathink:")) return trimmed
  return "$ULTRATHINK_PROMPT_PREFIX$trimmed"
}

const val ULTRATHINK_PROMPT_PREFIX: String = "Ultrathink:\n"

private val ULTRATHINK_LEADING: Regex = Regex("^Ultrathink:\\s*", RegexOption.IGNORE_CASE)

/** `REASONING_SELECT_DESCRIPTOR_IDS`, TraitsPicker.tsx:94. */
private val REASONING_SELECT_DESCRIPTOR_IDS: Set<String> =
  setOf("reasoningEffort", "reasoning", "effort", "variant", "thinking")

/**
 * `getProviderModelCapabilities`, `apps/web/src/providerModels.ts:52`, with `planModeEnabled`
 * on, as [resolveComposerInteractionMode] has it.
 */
fun getProviderModelCapabilities(
  models: List<ServerProviderModel>,
  model: String?,
  driver: String?,
): ModelCapabilities? {
  if (model == null) return null
  return resolveModelPickerSelectedModel(driver, model, models)?.capabilities
}

/**
 * `getSelectedTraits` and `getTraitsSectionVisibility`, TraitsPicker.tsx:142 and 222, for the
 * thread's model. OpenCode's unavailable-model descriptors aren't ported.
 */
data class SelectedTraits(
  val descriptors: List<ProviderOptionDescriptor>,
  val primarySelect: SelectProviderOptionDescriptor?,
  val ultrathinkPromptControlled: Boolean,
  val ultrathinkInBodyText: Boolean,
) {
  val selects: List<SelectProviderOptionDescriptor>
    get() = descriptors.filterIsInstance<SelectProviderOptionDescriptor>()
  val booleans: List<BooleanProviderOptionDescriptor>
    get() = descriptors.filterIsInstance<BooleanProviderOptionDescriptor>()

  /** `hasAnyControls`: effort, thinking, fast mode, context window or agent. */
  val hasAnyControls: Boolean
    get() = primarySelect != null ||
      booleans.any { it.id == "thinking" || it.id == "fastMode" } ||
      selects.any { it.id == "contextWindow" || it.id == "agent" }
}

fun selectedTraits(
  caps: ModelCapabilities?,
  prompt: String,
  modelOptions: List<ProviderOptionSelection>?,
): SelectedTraits {
  val descriptors: List<ProviderOptionDescriptor> =
    getProviderOptionDescriptors(caps, modelOptions)
  val primary: SelectProviderOptionDescriptor? =
    descriptors.filterIsInstance<SelectProviderOptionDescriptor>().firstOrNull()
  val controlled: Boolean = primary?.promptInjectedValues.orEmpty().isNotEmpty() &&
    isClaudeUltrathinkPrompt(prompt)
  val inBody: Boolean =
    controlled && isClaudeUltrathinkPrompt(prompt.replace(ULTRATHINK_LEADING, ""))
  return SelectedTraits(descriptors, primary, controlled, inBody)
}

/**
 * `getTraitSelectChange`, TraitsPicker.tsx:264. A prompt-injected value ("ultrathink") only edits
 * the prompt ([TraitSelectChange.Prompt]); anything else sets the options too.
 */
fun traitSelectChange(
  selected: SelectedTraits,
  descriptor: SelectProviderOptionDescriptor,
  prompt: String,
  value: String,
): TraitSelectChange? {
  if (value.isEmpty()) return null
  if (descriptor.promptInjectedValues?.contains(value) == true) {
    val next: String = if (prompt.trim().isEmpty()) {
      ULTRATHINK_PROMPT_PREFIX
    } else {
      applyClaudePromptEffortPrefix(prompt, "ultrathink")
    }
    return TraitSelectChange.Prompt(next)
  }
  val primary: Boolean = descriptor.id == selected.primarySelect?.id
  if (primary && selected.ultrathinkInBodyText) return null
  val next: String =
    if (primary && selected.ultrathinkPromptControlled) prompt.replace(ULTRATHINK_LEADING, "")
    else prompt
  val descriptors: List<ProviderOptionDescriptor> = selected.descriptors.map { candidate ->
    if (candidate.id == descriptor.id && candidate is SelectProviderOptionDescriptor) {
      candidate.copy(currentValue = value)
    } else {
      candidate
    }
  }
  return TraitSelectChange.Options(next, buildProviderOptionSelectionsFromDescriptors(descriptors))
}

/** What a trait pick changes, see [traitSelectChange]. */
sealed interface TraitSelectChange {
  val prompt: String

  data class Prompt(override val prompt: String) : TraitSelectChange

  data class Options(
    override val prompt: String,
    val modelOptions: List<ProviderOptionSelection>?,
  ) : TraitSelectChange
}

/** A boolean trait set to [value], `updateDescriptors` in `TraitsMenuContent`. */
fun traitBooleanChange(
  selected: SelectedTraits,
  descriptor: BooleanProviderOptionDescriptor,
  value: Boolean,
): List<ProviderOptionSelection>? {
  val descriptors: List<ProviderOptionDescriptor> = selected.descriptors.map { candidate ->
    if (candidate.id == descriptor.id && candidate is BooleanProviderOptionDescriptor) {
      candidate.copy(currentValue = value)
    } else {
      candidate
    }
  }
  return buildProviderOptionSelectionsFromDescriptors(descriptors)
}

/**
 * The radio value a select shows in the menu: "ultrathink" while the prompt asks for it, else
 * its current value, `TraitsMenuContent` around TraitsPicker.tsx:478.
 */
fun traitSelectValue(
  selected: SelectedTraits,
  descriptor: SelectProviderOptionDescriptor,
  selection: ModelSelection?,
  reported: ModelSelection?,
): String {
  if (selected.ultrathinkPromptControlled && descriptor.id == selected.primarySelect?.id) {
    return "ultrathink"
  }
  return getProviderOptionCurrentValue(descriptor, selection, reported)?.stringOrNull.orEmpty()
}

/**
 * `buildTraitsTriggerDisplay`, TraitsPicker.tsx:570: the trigger's label, every option's
 * current label joined with " · ", with a speed ("Fast", "Ultrafast") folded into the reasoning
 * label.
 */
fun buildTraitsTriggerLabel(
  driver: String?,
  selected: SelectedTraits,
  selection: ModelSelection?,
  reported: ModelSelection?,
): String {
  var fastModeFallback: String? = null
  var speed: String? = null
  var reasoningIndex: Int = -1
  val labels: MutableList<String> = mutableListOf()
  for (descriptor in selected.descriptors) {
    if (descriptor.id == "fastMode" && descriptor is BooleanProviderOptionDescriptor) {
      speed = if (descriptor.currentValue == true) "Fast" else null
      fastModeFallback = speed ?: "Normal"
      continue
    }
    if (
      driver == "codex" &&
      descriptor.id == "serviceTier" &&
      descriptor is SelectProviderOptionDescriptor
    ) {
      val current: String? = getProviderOptionCurrentValue(descriptor)?.stringOrNull
      val fast = descriptor.options.find { it.label == "Fast" }
      val ultrafast = descriptor.options.find { it.label == "Ultrafast" }
      val tiered: Boolean = ((fast != null || ultrafast != null) && current == "default") ||
        (fast != null && current == fast.id) ||
        (ultrafast != null && current == ultrafast.id)
      if (tiered) {
        speed = when {
          ultrafast != null && current == ultrafast.id -> "Ultrafast"
          fast != null && current == fast.id -> "Fast"
          else -> null
        }
        fastModeFallback = descriptor.options.find { it.id == current }?.label ?: "Normal"
        continue
      }
    }
    val label: String? = when {
      selected.ultrathinkPromptControlled && descriptor.id == selected.primarySelect?.id ->
        "Ultrathink"

      descriptor is BooleanProviderOptionDescriptor ->
        "${descriptor.label} ${if (descriptor.currentValue == true) "On" else "Off"}"

      else -> getProviderOptionCurrentLabel(descriptor, selection, reported)
    }
    if (!label.isNullOrEmpty()) {
      if (
        reasoningIndex == -1 &&
        descriptor is SelectProviderOptionDescriptor &&
        descriptor.id in REASONING_SELECT_DESCRIPTOR_IDS
      ) {
        reasoningIndex = labels.size
      }
      labels += label
    }
  }
  if (labels.isEmpty() && fastModeFallback != null) return fastModeFallback
  if (speed != null) {
    if (reasoningIndex >= 0) labels[reasoningIndex] = "${labels[reasoningIndex]} $speed"
    else labels += speed
  }
  return labels.joinToString(" · ")
}

/**
 * `deriveReportedModelSelection`, `packages/client-runtime/src/state/threadExecution.ts:136`:
 * the selection the active provider thread reports running, when it's on the thread's instance.
 */
fun deriveReportedModelSelection(view: ThreadView): ModelSelection? {
  val thread = view.thread
  val active: ProviderThread? = view.providerThreads.find { candidate ->
    candidate.id == thread.activeProviderThreadId &&
      candidate.providerInstanceId == thread.modelSelection.instance
  }
  return active?.nativeMetadata?.modelSelection
}
