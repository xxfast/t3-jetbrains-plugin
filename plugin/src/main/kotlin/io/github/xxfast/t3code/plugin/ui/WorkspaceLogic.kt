package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.MessageContext
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchInput
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchWorkspaceStrategy
import io.github.xxfast.t3code.protocol.generated.VcsRef

/**
 * Where a thread's first turn runs, upstream's `EnvMode` (`apps/web/src/types.ts`): the
 * checkout the project points at, or a worktree the server makes for it.
 */
enum class EnvMode { LOCAL, WORKTREE }

/**
 * What the composer has picked for a thread the server already lists but nobody has messaged:
 * upstream's `pendingServerThreadEnvModeEntry`, `pendingServerThreadBranchEntry` and
 * `pendingServerThreadStartFromOriginByThreadId` (`ChatView.tsx:4620` to `4648`), one record.
 * Null fields fall back to what the thread and the checkout say. It only matters while
 * [canOverrideServerThreadEnvMode]; after the first message the server owns the workspace.
 */
data class WorkspaceDraft(
  val envMode: EnvMode? = null,
  /** The base branch for a new worktree, upstream's `activeThreadBranchOverride`. */
  val branch: String? = null,
  val startFromOrigin: Boolean? = null,
)

/** `resolveEnvModeLabel`, `apps/web/src/components/BranchToolbar.logic.ts:92`. */
fun resolveEnvModeLabel(mode: EnvMode): String =
  if (mode == EnvMode.WORKTREE) "New worktree" else "Current checkout"

/** `resolveCurrentWorkspaceLabel`, `BranchToolbar.logic.ts:102`. */
fun resolveCurrentWorkspaceLabel(activeWorktreePath: String?): String =
  if (activeWorktreePath != null) "Current worktree" else resolveEnvModeLabel(EnvMode.LOCAL)

/** `resolveLockedWorkspaceLabel`, `BranchToolbar.logic.ts:106`. */
fun resolveLockedWorkspaceLabel(activeWorktreePath: String?, effectiveEnvMode: EnvMode): String =
  when {
    activeWorktreePath != null -> "Worktree"
    effectiveEnvMode == EnvMode.WORKTREE -> resolveEnvModeLabel(EnvMode.WORKTREE)
    else -> "Local checkout"
  }

/**
 * `resolveEffectiveEnvMode`, `BranchToolbar.logic.ts:156`, for a server thread (the plugin has
 * no local drafts): a worktree, or one being prepared, means worktree mode.
 */
fun resolveEffectiveEnvMode(activeWorktreePath: String?, preparingWorktree: Boolean): EnvMode =
  if (activeWorktreePath != null || preparingWorktree) EnvMode.WORKTREE else EnvMode.LOCAL

/**
 * `resolveBranchToolbarValue`, `BranchToolbar.logic.ts:187`: what the branch control shows. A
 * new worktree starts from the picked branch, else the checkout's; otherwise the checkout's
 * branch, else the thread's.
 */
fun resolveBranchToolbarValue(
  envMode: EnvMode,
  activeWorktreePath: String?,
  activeThreadBranch: String?,
  currentGitBranch: String?,
): String? =
  if (envMode == EnvMode.WORKTREE && activeWorktreePath == null) {
    activeThreadBranch ?: currentGitBranch
  } else {
    currentGitBranch ?: activeThreadBranch
  }

/** `resolveBranchTriggerLabel`, `BranchToolbar.logic.ts:200`. */
fun resolveBranchTriggerLabel(
  activeWorktreePath: String?,
  effectiveEnvMode: EnvMode,
  resolvedActiveBranch: String?,
  resolvedActiveBranchIsRemote: Boolean?,
  startFromOrigin: Boolean,
): String {
  if (resolvedActiveBranch == null) return "Select ref"
  if (effectiveEnvMode == EnvMode.WORKTREE && activeWorktreePath == null) {
    val baseRef: String =
      if (startFromOrigin && resolvedActiveBranchIsRemote == false) "origin/$resolvedActiveBranch"
      else resolvedActiveBranch
    return "From $baseRef"
  }
  return resolvedActiveBranch
}

/**
 * `envLocked`, `ChatView.tsx:2194`: the workspace is settled once the thread has a message or
 * a runtime. The plugin counts user messages in the projection, as `activeMessageCount` does.
 */
fun envLocked(view: ThreadView): Boolean =
  activeMessageCount(view) > 0 || activeRun(view.runs) != null

/** `activeMessageCount`'s source: the projection's conversation messages, queued ones included. */
fun activeMessageCount(view: ThreadView): Int = view.messages.size

/**
 * `canOverrideServerThreadEnvMode`, `ChatView.tsx:4611`: a thread the server lists, with no
 * message yet and no worktree, can still pick where its first turn runs.
 */
fun canOverrideServerThreadEnvMode(view: ThreadView): Boolean =
  activeMessageCount(view) == 0 && view.thread.worktreePath == null && !envLocked(view)

/** `resolveSendEnvMode`, `ChatView.logic.ts:792`: outside a git repo everything runs locally. */
fun resolveSendEnvMode(requestedEnvMode: EnvMode, isGitRepo: Boolean): EnvMode =
  if (isGitRepo) requestedEnvMode else EnvMode.LOCAL

/** What the composer's Workspace control shows and allows for one thread. */
data class WorkspaceControlState(
  val envMode: EnvMode,
  /** The thread's worktree, once it has one. */
  val activeWorktreePath: String?,
  /** `envModeLocked`: the first message is out, or the thread already has a worktree. */
  val locked: Boolean,
  /** The base branch control's label, [resolveBranchTriggerLabel]. */
  val branchLabel: String,
  /** The base branch a new worktree starts from; null means "Select ref". */
  val baseBranch: String?,
  val startFromOrigin: Boolean,
  /** The refs the base branch picker lists, `vcs.listRefs` for the query. */
  val refs: List<VcsRef> = emptyList(),
  val refsQuery: String = "",
  /** "Loading refs...", an error, or null when the list is complete. */
  val refsStatus: String? = null,
) {
  /** `isSelectingWorktreeBase`, `BranchToolbarBranchSelector.tsx:241`. */
  val selectingWorktreeBase: Boolean
    get() = envMode == EnvMode.WORKTREE && !locked && activeWorktreePath == null

  /** The trigger's text, as `RunContextSelector` picks it (`BranchToolbar.tsx:188`). */
  val workspaceLabel: String
    get() = when {
      locked -> resolveLockedWorkspaceLabel(activeWorktreePath, envMode)
      envMode == EnvMode.WORKTREE -> resolveEnvModeLabel(EnvMode.WORKTREE)
      else -> resolveCurrentWorkspaceLabel(activeWorktreePath)
    }

  /** The trigger's glyph, `WorkspaceIcon` in `RunContextSelector`. */
  val workspaceIcon: String
    get() = when {
      envMode == EnvMode.WORKTREE -> "folder-git-2"
      activeWorktreePath != null -> "folder-git"
      else -> "folder"
    }
}

/**
 * The Workspace control for [view]'s thread with [draft] applied, as `ChatView.tsx:4605` to
 * `4648` derive `envMode`, `activeThreadBranch` and `startFromOrigin` for a server thread.
 * [currentGitBranch] is the checkout's, from `subscribeVcsStatus`. [preparingWorktree] is a
 * setup in flight, which reads as worktree mode. Null when the thread isn't on a git repo,
 * where upstream hides the git controls (`showGitControls`).
 */
fun workspaceControlState(
  view: ThreadView,
  draft: WorkspaceDraft,
  currentGitBranch: String?,
  isGitRepo: Boolean,
  preparingWorktree: Boolean,
  refs: List<VcsRef> = emptyList(),
  refsQuery: String = "",
  refsStatus: String? = null,
  defaultStartFromOrigin: Boolean = false,
): WorkspaceControlState? {
  if (!isGitRepo) return null
  val thread: Thread = view.thread
  val worktreePath: String? = thread.worktreePath
  val derived: EnvMode = resolveEffectiveEnvMode(worktreePath, preparingWorktree)
  val canOverride: Boolean = canOverrideServerThreadEnvMode(view) && !preparingWorktree
  val envMode: EnvMode = if (canOverride) draft.envMode ?: derived else derived
  val activeThreadBranch: String? =
    if (canOverride && draft.branch != null) draft.branch else thread.branch
  val startFromOrigin: Boolean =
    if (canOverride) draft.startFromOrigin ?: defaultStartFromOrigin else false
  val resolvedBranch: String? =
    resolveBranchToolbarValue(envMode, worktreePath, activeThreadBranch, currentGitBranch)
  val listed: VcsRef? = refs.find { it.name == resolvedBranch }
  return WorkspaceControlState(
    envMode = envMode,
    activeWorktreePath = worktreePath,
    locked = !canOverride,
    branchLabel = resolveBranchTriggerLabel(
      activeWorktreePath = worktreePath,
      effectiveEnvMode = envMode,
      resolvedActiveBranch = resolvedBranch,
      resolvedActiveBranchIsRemote = listed?.let { it.isRemote == true },
      startFromOrigin = startFromOrigin,
    ),
    baseBranch = resolvedBranch,
    startFromOrigin = startFromOrigin,
    refs = refs,
    refsQuery = refsQuery,
    refsStatus = refsStatus,
  )
}

/** `ChatView.tsx:6327`, when worktree mode has no base branch to start from. */
const val SELECT_BASE_BRANCH_ERROR: String =
  "Select a base branch before sending in New worktree mode."

/**
 * Whether a send on [view] prepares a worktree, `shouldCreateWorktree` (`ChatView.tsx:6324`):
 * the first message, in worktree mode, on a thread without one.
 */
fun shouldCreateWorktree(view: ThreadView, sendEnvMode: EnvMode): Boolean =
  activeMessageCount(view) == 0 && sendEnvMode == EnvMode.WORKTREE &&
    view.thread.worktreePath == null

/**
 * The `orchestration.launchThread` request for a first message that prepares a worktree on a
 * thread the server already has, as `startThreadTurn` builds it from a `bootstrap` with
 * `prepareWorktree` and no `createThread` (`commands.ts:634` to `686`): the thread is reused,
 * the title seed asks for a generated title, and the message rides as `initialMessage`.
 */
fun launchThreadInput(
  view: ThreadView,
  commandId: String,
  messageId: String,
  text: String,
  context: MessageContext?,
  attachments: List<ChatAttachment>,
  modelSelection: ModelSelection?,
  runtimeMode: String,
  interactionMode: String,
  baseBranch: String,
  startFromOrigin: Boolean,
  titleSeed: String?,
): ThreadLaunchInput {
  val thread: Thread = view.thread
  return ThreadLaunchInput(
    commandId = commandId,
    creationSource = "web",
    threadId = thread.id,
    reuseExistingThread = true,
    projectId = thread.projectId,
    title = titleSeed ?: thread.title,
    generateTitle = titleSeed != null,
    modelSelection = modelSelection ?: thread.modelSelection,
    runtimeMode = runtimeMode,
    interactionMode = interactionMode,
    workspaceStrategy = ThreadLaunchWorkspaceStrategy.Worktree(
      baseRef = baseBranch,
      startFromOrigin = if (startFromOrigin) true else null,
    ),
    initialMessage = ThreadLaunchInput.InitialMessage(
      messageId = messageId,
      text = text,
      context = context,
      attachments = attachments,
    ),
  )
}

/**
 * The latest run as the shell row would report it for `activityRun`, what upstream's
 * `useWorktreeSetup` reads `status === "preparing"` off.
 */
fun latestRun(runs: List<Run>): Run? = runs.maxByOrNull { it.ordinal }
