package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.AppThreadLineage
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
 * A new thread before its first message, upstream's `DraftThreadState` (composerDraftStore.ts):
 * the server knows nothing of it until the first send launches it with [threadId], minted
 * here as upstream's `newThreadId()` does. One per T3 project, as upstream keys drafts by
 * logical project. The composer's picks land here instead of on a row.
 */
data class DraftThread(
  val threadId: String,
  val projectId: String,
  val createdAt: String,
  val modelSelection: ModelSelection,
  val runtimeMode: String = DEFAULT_RUNTIME_MODE,
  val interactionMode: String = DEFAULT_PROVIDER_INTERACTION_MODE,
  val branch: String? = null,
  val worktreePath: String? = null,
)

/**
 * The draft `useNewThreadHandler` (useHandleNewThread.ts) sets up for [project]. [branch] and
 * [worktreePath] carry over from "New thread on <branch>", as upstream's `handleNewThread`
 * options do. The model is [newThreadModelSelection]'s, with [projectDefault] the project's
 * resolved `defaultModelSelection`. Null when no provider can run it.
 */
fun newDraftThread(
  project: Project,
  providers: List<ServerProvider>,
  threadId: String,
  createdAt: String,
  branch: String? = null,
  worktreePath: String? = null,
  projectDefault: ModelSelection? = null,
  sticky: ModelSelection? = null,
  carry: ModelSelection? = null,
): DraftThread? {
  val modelSelection: ModelSelection =
    newThreadModelSelection(projectDefault, providers, sticky, carry) ?: return null
  return DraftThread(
    threadId = threadId,
    projectId = project.id,
    createdAt = createdAt,
    modelSelection = modelSelection,
    branch = branch,
    worktreePath = worktreePath,
  )
}

/**
 * `buildLocalDraftThread`, apps/web/src/components/ChatView.logic.ts:527: the row the ChatView
 * shows for [draft] until the server has one.
 */
fun buildLocalDraftThread(draft: DraftThread): Thread = Thread(
  id = draft.threadId,
  projectId = draft.projectId,
  title = NEW_THREAD_TITLE,
  providerInstanceId = draft.modelSelection.instance.orEmpty(),
  modelSelection = draft.modelSelection,
  runtimeMode = draft.runtimeMode,
  interactionMode = draft.interactionMode,
  branch = draft.branch,
  worktreePath = draft.worktreePath,
  activeProviderThreadId = null,
  lineage = AppThreadLineage(
    rootThreadId = draft.threadId,
    parentThreadId = null,
    relationshipToParent = null,
  ),
  forkedFrom = null,
  createdBy = "user",
  creationSource = "web",
  latestRunId = null,
  activeRunId = null,
  status = "idle",
  pendingRuntimeRequest = null,
  latestVisibleMessage = null,
  latestUserMessageAt = null,
  hasActionableProposedPlan = false,
  itemCount = 0,
  visibleItemCount = 0,
  createdAt = draft.createdAt,
  updatedAt = draft.createdAt,
  archivedAt = null,
  settledOverride = null,
  settledAt = null,
  deletedAt = null,
)

/** [draft] as a thread with nothing in it, what the ChatView renders for a draft. */
fun draftThreadView(draft: DraftThread): ThreadView =
  ThreadView(thread = buildLocalDraftThread(draft), runs = emptyList(), items = emptyList())

/**
 * What a new thread runs, as `useNewThreadHandler` (useHandleNewThread.ts) sets up a draft: the
 * [sticky] last pick (`applyStickyState`), replaced by
 * [resolveNewThreadModelSelectionOverride] when that has one, else [defaultModelSelection].
 * Upstream's draft resolves a stale pick when it renders; the plugin picks once, when the draft
 * is made, so a remembered or carried pick on a provider that isn't ready is skipped here.
 */
fun newThreadModelSelection(
  projectDefault: ModelSelection?,
  providers: List<ServerProvider>,
  sticky: ModelSelection?,
  carry: ModelSelection?,
): ModelSelection? {
  val override: ModelSelection? =
    resolveNewThreadModelSelectionOverride(projectDefault, carry?.ready(providers))
  return override ?: sticky?.ready(providers) ?: defaultModelSelection(projectDefault, providers)
}

/**
 * `resolveNewThreadModelSelectionOverride`, apps/web/src/lib/chatThreadActions.ts:46: the
 * project's default above the selection carried from the thread the new one starts from.
 * Upstream also drops a carry that comes from the destination draft itself; the plugin only
 * carries from a thread the server lists, so it's always another thread's.
 */
fun resolveNewThreadModelSelectionOverride(
  projectDefaultSelection: ModelSelection?,
  carrySelection: ModelSelection?,
): ModelSelection? = projectDefaultSelection ?: carrySelection

/** This selection when its provider is one of the ready [providers], else null. */
private fun ModelSelection.ready(providers: List<ServerProvider>): ModelSelection? =
  takeIf { providers.any { it.instanceId == instance && it.pickerReady() } }

/**
 * What a new thread runs without a pick to go on: the project's [projectDefault], else
 * `getDefaultServerModel` (apps/web/src/providerModels.ts:87) on the first ready provider. Null
 * with none ready.
 */
fun defaultModelSelection(
  projectDefault: ModelSelection?,
  providers: List<ServerProvider>,
): ModelSelection? {
  projectDefault?.let { return it }
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
