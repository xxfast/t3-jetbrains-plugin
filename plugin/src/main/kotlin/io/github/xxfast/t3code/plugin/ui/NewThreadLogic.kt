package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import kotlinx.serialization.json.JsonPrimitive

/** `DEFAULT_RUNTIME_MODE`, packages/contracts/src/providerPolicy.ts:32. */
const val DEFAULT_RUNTIME_MODE: String = "full-access"

/** `DEFAULT_PROVIDER_INTERACTION_MODE`, providerPolicy.ts:36. */
const val DEFAULT_PROVIDER_INTERACTION_MODE: String = "default"

/** The title a new thread starts with, as ChatView.tsx:6854 names it. */
const val NEW_THREAD_TITLE: String = "New thread"

/**
 * `createThread`, packages/client-runtime/src/operations/commands.ts:386: a `thread.create` on
 * the current checkout, no worktree. Upstream's picker is draft-first and only creates the thread
 * on the first send; the plugin has no local drafts, so it creates the row at once, the way
 * "implement plan in new thread" does. The provider, model, runtime mode and workspace are
 * then the composer's: its footer changes them on the row, and a New worktree pick sends the
 * first message as an `orchestration.launchThread` that reuses the thread ([launchThreadInput]).
 * Null when no provider can run it.
 */
fun createThread(
  project: Project,
  providers: List<ServerProvider>,
  threadId: String,
  commandId: String,
): Command.ThreadCreate? {
  val modelSelection: ModelSelection = defaultModelSelection(project, providers) ?: return null
  return Command.ThreadCreate(
    createdBy = "user",
    creationSource = "web",
    commandId = commandId,
    threadId = threadId,
    projectId = project.id,
    title = NEW_THREAD_TITLE,
    modelSelection = modelSelection,
    runtimeMode = DEFAULT_RUNTIME_MODE,
    interactionMode = DEFAULT_PROVIDER_INTERACTION_MODE,
    branch = null,
    worktreePath = null,
  )
}

/**
 * What a new thread in [project] runs: the project's own default, else `getDefaultServerModel`
 * (apps/web/src/providerModels.ts:87) on the first ready provider. Null with none ready.
 */
fun defaultModelSelection(project: Project, providers: List<ServerProvider>): ModelSelection? {
  project.defaultModelSelection?.let { return it }
  val provider: ServerProvider = providers.firstOrNull { it.pickerReady() } ?: return null
  val slug: String = getDefaultServerModel(provider.models) ?: return null
  return ModelSelection(
    instanceId = JsonPrimitive(provider.instanceId),
    model = JsonPrimitive(slug),
  )
}

/**
 * `getDefaultServerModel`, providerModels.ts:87: the default, else the first non-custom model,
 * else the first. Upstream then falls back to a hardcoded slug per driver; the plugin doesn't
 * keep that table, so it's null.
 */
fun getDefaultServerModel(models: List<ServerProviderModel>): String? =
  models.firstOrNull { it.isDefault == true && !it.isCustom }?.slug
    ?: models.firstOrNull { !it.isCustom }?.slug
    ?: models.firstOrNull()?.slug
