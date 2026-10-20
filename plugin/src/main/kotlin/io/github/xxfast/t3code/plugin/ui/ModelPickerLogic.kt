package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelCapabilities
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// The thread settings the server owns, set from the composer's footer like upstream's
// `ChatComposer.tsx` (`ComposerFooterModeControls`, `ProviderModelPicker`). Nothing is kept here.

/**
 * One runtime mode, `runtimeModeConfig` in `apps/web/src/components/chat/runtimeModeConfig.ts`.
 * [icon] is the lucide glyph's name.
 */
data class RuntimeModeOption(
  val mode: String,
  val label: String,
  val description: String,
  val icon: String,
)

/** `runtimeModeConfig`, in upstream's order. */
val RUNTIME_MODES: List<RuntimeModeOption> = listOf(
  RuntimeModeOption(
    mode = "approval-required",
    label = "Supervised",
    description = "Ask before commands and file changes.",
    icon = "lock",
  ),
  RuntimeModeOption(
    mode = "auto-accept-edits",
    label = "Auto-accept edits",
    description = "Auto-approve edits, ask before other actions.",
    icon = "pen-line",
  ),
  RuntimeModeOption(
    mode = "auto",
    label = "Auto",
    description = "Supported providers approve routine actions; others still ask.",
    icon = "sparkles",
  ),
  RuntimeModeOption(
    mode = "full-access",
    label = "Full access",
    description = "Allow commands and edits without prompts.",
    icon = "lock-open",
  ),
)

/**
 * `compatibleRuntimeModeOptions`, `ChatComposer.tsx:2310`: the modes the provider supports, or
 * all four. Also all four when it only lists modes this build doesn't know, so the select is
 * never empty.
 */
fun compatibleRuntimeModeOptions(provider: ServerProvider?): List<RuntimeModeOption> {
  val supported: List<String> = provider?.supportedRuntimeModes.orEmpty().filterNotNull()
  return RUNTIME_MODES.filter { it.mode in supported }.ifEmpty { RUNTIME_MODES }
}

/**
 * `compatibleRuntimeMode`, `ChatComposer.tsx:2315`: the thread's mode when the provider takes
 * it, else the first one it does. Only what's shown; nothing is sent until you pick.
 */
fun compatibleRuntimeMode(current: String, options: List<RuntimeModeOption>): String =
  if (options.any { it.mode == current }) current else options.firstOrNull()?.mode ?: current

/** Whether Plan/Build shows, and the mode it reads. */
data class InteractionModeResolution(val enabled: Boolean, val interactionMode: String)

/**
 * `resolveComposerInteractionMode`, `apps/web/src/components/ChatView.logic.ts:612`, with
 * `planModeEnabled` on: that's a desktop setting, and this plugin keeps no copy of it.
 */
fun resolveComposerInteractionMode(
  provider: ServerProvider?,
  interactionMode: String,
): InteractionModeResolution {
  val enabled: Boolean = provider != null && provider.showInteractionModeToggle != false
  return InteractionModeResolution(enabled, if (enabled) interactionMode else "default")
}

/** Plan when it's plan, else the default ("Build"). */
fun toggledInteractionMode(current: String): String = if (current == "plan") "default" else "plan"

/** The Plan/Build tooltip, `ChatComposer.tsx:1242`, reworded without the dash. */
fun interactionModeTooltip(current: String): String =
  if (current == "plan") "Plan mode. Click to return to normal build mode."
  else "Default mode. Click to enter plan mode."

/** The selection's provider instance: `instanceId`, else the legacy `provider` field. */
val ModelSelection.instance: String?
  get() = (instanceId as? JsonPrimitive)?.contentOrNull
    ?: (provider as? JsonPrimitive)?.contentOrNull

val ModelSelection.slug: String? get() = (model as? JsonPrimitive)?.contentOrNull

/** The provider the thread runs on, from the server's catalog. */
fun selectedProvider(
  providers: List<ServerProvider>,
  instanceId: String?,
): ServerProvider? = providers.find { it.instanceId == instanceId }

private const val ANTIGRAVITY_DEFAULT_MODEL: String = "antigravity-default"

/**
 * `resolveModelPickerSelectedModel`, `apps/web/src/components/chat/ModelPickerContent.tsx:83`.
 * Antigravity's default stands for whichever available model aliases it, else its default.
 * Upstream's `isUnavailable` marks a stand-in for a current slug the catalog lacks
 * (`apps/web/src/modelSelection.ts:90`); [unavailable] is those slugs.
 */
fun resolveModelPickerSelectedModel(
  driverKind: String?,
  model: String,
  options: List<ServerProviderModel>,
  unavailable: Set<String> = emptySet(),
): ServerProviderModel? {
  if (driverKind == "antigravity" && model == ANTIGRAVITY_DEFAULT_MODEL) {
    val available: List<ServerProviderModel> = options
      .filter { it.slug != ANTIGRAVITY_DEFAULT_MODEL && it.slug !in unavailable }
    return available.find { it.aliases.orEmpty().contains(ANTIGRAVITY_DEFAULT_MODEL) }
      ?: available.find { it.isDefault == true }
  }
  return options.find { it.slug == model }
}

/** `PROVIDER_DISPLAY_NAMES`, `packages/contracts/src/model.ts:196`. */
private val PROVIDER_DISPLAY_NAMES: Map<String, String> = mapOf(
  "antigravity" to "Antigravity",
  "codex" to "Codex",
  "claudeAgent" to "Claude",
  "cursor" to "Cursor",
  "grok" to "Grok",
  "muse" to "Muse Code",
  "acpRegistry" to "ACP Registry",
  "pi" to "Pi",
  "opencode" to "OpenCode",
)

private fun humanizeSlug(slug: String): String = slug
  .replace(Regex("([a-z])([A-Z])"), "$1 $2")
  .replace(Regex("[_-]+"), " ")
  .trim()
  .replace(Regex("\\b\\w")) { it.value.uppercase() }

/**
 * `resolveProviderInstanceDisplayName`,
 * `packages/client-runtime/src/state/providerInstanceDisplay.ts`. A default instance's id is its
 * driver (`defaultInstanceIdForDriver`).
 */
fun resolveProviderInstanceDisplayName(provider: ServerProvider): String {
  val named: String? = provider.displayName?.trim()?.takeIf { it.isNotEmpty() }
  val kind: String = PROVIDER_DISPLAY_NAMES[provider.driver] ?: humanizeSlug(provider.driver)
  if (named != null && named != kind) return named
  if (provider.instanceId != provider.driver) {
    val humanized: String = humanizeSlug(provider.instanceId)
    if (humanized.isNotEmpty()) return humanized
  }
  return named ?: kind
}

/** One model in the picker. */
data class ModelPickerItem(
  val instanceId: String,
  val slug: String,
  val name: String,
  val selected: Boolean,
  /** `badge == "new"`, shown as a small "new". */
  val isNew: Boolean,
)

/** A provider's models, under its display name. */
data class ModelPickerGroup(
  val instanceId: String,
  val displayName: String,
  val models: List<ModelPickerItem>,
)

/**
 * `isProviderInstancePickerReady`, `apps/web/src/providerInstances.ts:70`: enabled, available
 * and ready. Others list only the thread's current model, so it still reads as picked.
 */
private fun ServerProvider.pickerReady(): Boolean =
  enabled && availability != "unavailable" && status == "ready"

/**
 * The picker's menu: enabled providers (`isProviderInstancePickerVisible`) by display name, their
 * models by name. Antigravity's default slug never lists (`shouldIncludeModelPickerOption`,
 * `ModelPickerContent.tsx:106`); legacy models stay hidden unless one is the thread's current.
 */
fun modelPickerItems(
  providers: List<ServerProvider>,
  instanceId: String?,
  model: String?,
): List<ModelPickerGroup> = providers
  .filter { it.enabled }
  .map { provider ->
    val current: ServerProviderModel? = model
      ?.takeIf { provider.instanceId == instanceId }
      ?.let { resolveModelPickerSelectedModel(provider.driver, it, provider.models) }
    val ready: Boolean = provider.pickerReady()
    val models: List<ModelPickerItem> = provider.models
      .filter { candidate ->
        val isCurrent: Boolean = candidate == current
        val listable: Boolean = !(provider.driver == "antigravity" &&
          candidate.slug == ANTIGRAVITY_DEFAULT_MODEL)
        listable && (isCurrent || (ready && candidate.isLegacy != true))
      }
      .map { candidate ->
        ModelPickerItem(
          instanceId = provider.instanceId,
          slug = candidate.slug,
          name = candidate.name,
          selected = candidate == current,
          isNew = candidate.badge == "new",
        )
      }
    ModelPickerGroup(provider.instanceId, resolveProviderInstanceDisplayName(provider), models)
  }
  .filter { it.models.isNotEmpty() }

/** What the picker button says: the current model's name, else its slug. */
fun modelPickerLabel(providers: List<ServerProvider>, instanceId: String?, model: String?): String {
  if (model == null) return "Model"
  val provider: ServerProvider = selectedProvider(providers, instanceId) ?: return model
  return resolveModelPickerSelectedModel(provider.driver, model, provider.models)?.name ?: model
}

/** Why a model change can't happen in this thread, `getStartedThreadModelChangeBlockReason`. */
data class ModelChangeBlock(val title: String, val description: String) {
  override fun toString(): String = "$title. $description"
}

/**
 * `getStartedThreadModelChangeBlockReason`, `apps/web/src/components/ChatView.logic.ts:1001`.
 * Once a thread has started, a provider that needs a new thread for a model change, or a switch
 * of instance without handoff, means starting a new chat. [currentInstance] is the thread row's
 * own provider instance, [currentModel] its model.
 */
fun getStartedThreadModelChangeBlockReason(
  providers: List<ServerProvider>,
  hasStartedSession: Boolean,
  supportsProviderSwitchingViaHandoff: Boolean,
  currentInstance: String?,
  currentModel: String?,
  nextInstance: String,
  nextModel: String,
): ModelChangeBlock? {
  if (!hasStartedSession) return null
  if (currentInstance == nextInstance && currentModel == nextModel) return null
  if (currentInstance != nextInstance) {
    if (supportsProviderSwitchingViaHandoff) return null
    return ModelChangeBlock(
      title = "Start a new chat to switch providers",
      description = "This thread does not support switching providers after it has started.",
    )
  }

  val current: ServerProvider? = selectedProvider(providers, currentInstance)
  val next: ServerProvider? = selectedProvider(providers, nextInstance)
  val blocked: Boolean = current?.requiresNewThreadForModelChange == true ||
    next?.requiresNewThreadForModelChange == true
  if (!blocked) return null
  return ModelChangeBlock(
    title = "Start a new chat to change models",
    description = "This provider does not allow switching models after a conversation has started.",
  )
}

/**
 * `threadSupportsProviderHandoff`, `packages/client-runtime/src/state/threadWorkflows.ts:89`:
 * the session's capability when there is one; else a thread with no runs (or imported from v1),
 * or one whose active provider thread still has its native thread for a portable handoff.
 */
fun threadSupportsProviderHandoff(view: ThreadView): Boolean {
  val session: ProviderSession? = resolveThreadProviderSession(view)
  if (session != null) return session.capabilities.sessions.supportsProviderSwitchingViaHandoff
  if (activeRun(view.runs) != null) return false
  if (view.thread.historyOrigin == "v1_import" || view.runs.isEmpty()) return true
  return view.providerThreads.any { thread ->
    thread.id == view.thread.activeProviderThreadId &&
      thread.appThreadId == view.thread.id &&
      thread.providerInstanceId == view.thread.modelSelection.instance &&
      thread.nativeThreadRef != null
  }
}

/** `resolveThreadProviderSession`, `threadWorkflows.ts:65`. */
private fun resolveThreadProviderSession(view: ThreadView): ProviderSession? {
  val providerThreadId: String? =
    activeRun(view.runs)?.providerThreadId ?: view.thread.activeProviderThreadId
  val active: ProviderThread? =
    providerThreadId?.let { id -> view.providerThreads.find { it.id == id } }
  val attached: ProviderThread? = active ?: view.providerThreads.find { thread ->
    thread.appThreadId == view.thread.id && thread.providerSessionId != null
  }
  val sessionId: String = attached?.providerSessionId ?: return null
  return view.providerSessions.find { it.id == sessionId }
}

/** `setThreadRuntimeMode`, `packages/client-runtime/src/operations/commands.ts:598`. */
fun setThreadRuntimeMode(threadId: String, runtimeMode: String, commandId: String): Command =
  Command.ThreadRuntimeModeSet(
    commandId = commandId,
    threadId = threadId,
    runtimeMode = runtimeMode,
  )

/** `setThreadInteractionMode`, `commands.ts:609`. */
fun setThreadInteractionMode(
  threadId: String,
  interactionMode: String,
  commandId: String,
): Command =
  Command.ThreadInteractionModeSet(
    commandId = commandId,
    threadId = threadId,
    interactionMode = interactionMode,
  )

/**
 * The new selection for a pick. Upstream sends `{instanceId, model}` with options remembered per
 * model in a client-side draft store (`onProviderModelSelect`, `ChatView.tsx:7467`); this plugin
 * keeps no such store, so [current]'s options carry over within its instance and drop across.
 */
fun nextModelSelection(
  current: ModelSelection?,
  instanceId: String,
  model: String,
): ModelSelection = ModelSelection(
  instanceId = JsonPrimitive(instanceId),
  model = JsonPrimitive(model),
  options = current?.options?.takeIf { current.instance == instanceId },
)

/**
 * The model change `updateThreadMetadata` dispatches (`commands.ts:553`). When the server
 * doesn't resolve command context, `modelSelectionCommandType` (`packages/shared/src/model.ts:17`)
 * compares with the thread row's [rowInstance] and makes a change of instance a `provider.switch`.
 */
fun updateThreadModelSelection(
  threadId: String,
  selection: ModelSelection,
  rowInstance: String?,
  serverResolved: Boolean,
  commandId: String,
): Command {
  if (!serverResolved && selection.instance != rowInstance) {
    return Command.ProviderSwitch(
      commandId = commandId,
      threadId = threadId,
      modelSelection = selection,
    )
  }
  return Command.ThreadModelSelectionSet(
    commandId = commandId,
    threadId = threadId,
    modelSelection = selection,
  )
}

/** What the composer's footer shows for the thread's settings. Built by [composerFooter]. */
data class ComposerFooterState(
  val provider: ServerProvider?,
  val modelLabel: String,
  val models: List<ModelPickerGroup>,
  val runtimeModes: List<RuntimeModeOption>,
  val runtimeMode: String,
  val showInteractionModeToggle: Boolean,
  val interactionMode: String,
  /** The model's option descriptors, for the traits picker. */
  val capabilities: ModelCapabilities? = null,
  /** What the next message sends: the instance, the model and its options. */
  val selection: ModelSelection? = null,
  /** What the provider says it runs, [deriveReportedModelSelection]. Display only. */
  val reported: ModelSelection? = null,
)

/**
 * The footer for a thread on [instanceId] running [model], from the server's [providers]. The
 * provider limits the runtime modes and decides on the Plan/Build toggle.
 */
fun composerFooter(
  providers: List<ServerProvider>,
  instanceId: String?,
  model: String?,
  runtimeMode: String,
  interactionMode: String,
  selection: ModelSelection? = null,
  reported: ModelSelection? = null,
): ComposerFooterState {
  val provider: ServerProvider? = selectedProvider(providers, instanceId)
  val modes: List<RuntimeModeOption> = compatibleRuntimeModeOptions(provider)
  val interaction: InteractionModeResolution =
    resolveComposerInteractionMode(provider, interactionMode)
  return ComposerFooterState(
    provider = provider,
    modelLabel = modelPickerLabel(providers, instanceId, model),
    models = modelPickerItems(providers, instanceId, model),
    runtimeModes = modes,
    runtimeMode = compatibleRuntimeMode(runtimeMode, modes),
    showInteractionModeToggle = interaction.enabled,
    interactionMode = interaction.interactionMode,
    capabilities = provider?.let { getProviderModelCapabilities(it.models, model, it.driver) },
    selection = selection,
    reported = reported,
  )
}
