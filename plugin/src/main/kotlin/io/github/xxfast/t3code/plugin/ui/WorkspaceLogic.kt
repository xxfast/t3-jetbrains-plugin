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
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.has

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
 * `resolveEffectiveEnvMode`, `BranchToolbar.logic.ts:156`, without the draft's own env mode
 * (the composer's [WorkspaceDraft] holds that): a worktree, or one being prepared, means
 * worktree mode.
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

/**
 * `shouldShowComposerContextStrip`, `BranchToolbar.logic.ts:63`, with upstream's defaults: the
 * strip shows in the draft hero state and nowhere else (`persistComposerContextStrip` is off).
 * A draft, or a server thread that can still choose its workspace, is in that state. A setup in
 * flight means a message is out, so no strip.
 */
fun shouldShowComposerContextStrip(view: ThreadView, isGitRepo: Boolean): Boolean =
  isGitRepo && canOverrideServerThreadEnvMode(view)

/** `resolveSendEnvMode`, `ChatView.logic.ts:792`: outside a git repo everything runs locally. */
fun resolveSendEnvMode(requestedEnvMode: EnvMode, isGitRepo: Boolean): EnvMode =
  if (isGitRepo) requestedEnvMode else EnvMode.LOCAL

/** `AuthSourceControlWriteScope`, `packages/contracts/src/auth.ts:39`. */
const val SOURCE_CONTROL_WRITE_SCOPE: String = "source-control:write"

/**
 * `canChangeThreadBranch`, `BranchToolbarBranchSelector.tsx:159`: the pairing can write source
 * control and operate the thread. Without it the refs list but can't be picked.
 */
fun canChangeThreadBranch(session: ServerSession?): Boolean =
  session != null && session.has(SOURCE_CONTROL_WRITE_SCOPE) && session.has(OPERATE_SCOPE)

/** Why a branch can't be picked, for the control's tooltip. */
const val BRANCH_LOCKED_REASON: String =
  "This pairing can't switch branches. Pair again to grant source-control:write."

/** `deriveLocalBranchNameFromRemoteRef`, `packages/shared/src/git.ts:95`: `origin/x` to `x`. */
fun deriveLocalBranchNameFromRemoteRef(branchName: String): String {
  val separator: Int = branchName.indexOf('/')
  if (separator <= 0 || separator == branchName.length - 1) return branchName
  return branchName.substring(separator + 1)
}

/** `sanitizeNewRefName`, `git.ts:270`: trimmed, whitespace runs to a dash. */
fun sanitizeNewRefName(rawName: String): String =
  rawName.trim().replace(Regex("[ \\t\\n\\r\\f\\u000B]+"), "-")

/** What picking a ref targets, `resolveBranchSelectionTarget`'s answer. */
data class BranchSelectionTarget(
  val checkoutCwd: String,
  val nextWorktreePath: String?,
  /** The ref already has a worktree: use it rather than check the ref out here. */
  val reuseExistingWorktree: Boolean,
)

/** `resolveBranchSelectionTarget`, `BranchToolbar.logic.ts:250`. */
fun resolveBranchSelectionTarget(
  activeProjectCwd: String,
  activeWorktreePath: String?,
  ref: VcsRef,
): BranchSelectionTarget {
  ref.worktreePath?.let { path ->
    return BranchSelectionTarget(
      checkoutCwd = path,
      nextWorktreePath = if (path == activeProjectCwd) null else path,
      reuseExistingWorktree = true,
    )
  }
  val nextWorktreePath: String? =
    if (activeWorktreePath != null && ref.isDefault) null else activeWorktreePath
  return BranchSelectionTarget(
    checkoutCwd = nextWorktreePath ?: activeProjectCwd,
    nextWorktreePath = nextWorktreePath,
    reuseExistingWorktree = false,
  )
}

/**
 * `resolveThreadMetadataUpdateForNextTurn`'s branch half, `ChatView.logic.ts:472`: the
 * `thread.metadata.update` a send puts out first when the picked branch isn't the row's. The
 * model half rides on the message itself in the plugin.
 */
fun threadBranchUpdateForNextTurn(
  thread: Thread,
  nextBranch: String?,
  commandId: String,
): Command.ThreadMetadataUpdate? {
  if (nextBranch == null || nextBranch == thread.branch) return null
  return Command.ThreadMetadataUpdate(
    commandId = commandId,
    threadId = thread.id,
    branch = nextBranch,
    worktreePath = null,
  )
}

/** The "create branch" row the picker offers for [query], `createBranchItemValue`: none when
 * blank or when a listed ref already has that exact name. */
fun createBranchName(query: String, refs: List<VcsRef>): String? {
  val name: String = sanitizeNewRefName(query)
  if (name.isEmpty() || refs.any { it.name == name }) return null
  return name
}

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
  /** [canChangeThreadBranch]: the local-mode picker switches branches. */
  val canChangeBranch: Boolean = false,
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
  canChangeBranch: Boolean = false,
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
    canChangeBranch = canChangeBranch,
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
 * The `orchestration.launchThread` request for a thread's first message, as `startThreadTurn`
 * builds it from a `bootstrap` (`commands.ts:634` to `686`). A [create] is a draft's
 * `createThread`; without it the thread the server has is reused. A [baseBranch] prepares a
 * new worktree from it (`prepareWorktree`), else the turn runs in the thread's worktree or
 * the project's checkout on [branch]. The title seed asks for a generated title, and the
 * message rides as `initialMessage`.
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
  baseBranch: String?,
  startFromOrigin: Boolean,
  titleSeed: String?,
  create: Boolean = false,
  branch: String? = null,
): ThreadLaunchInput {
  val thread: Thread = view.thread
  val worktreePath: String? = thread.worktreePath
  val strategy: ThreadLaunchWorkspaceStrategy = when {
    baseBranch != null -> ThreadLaunchWorkspaceStrategy.Worktree(
      baseRef = baseBranch,
      startFromOrigin = if (startFromOrigin) true else null,
    )

    worktreePath != null -> ThreadLaunchWorkspaceStrategy.ExistingWorktree(
      worktreePath = worktreePath,
      branch = branch ?: thread.branch,
    )

    else -> ThreadLaunchWorkspaceStrategy.Root(branch = branch ?: thread.branch)
  }
  return ThreadLaunchInput(
    commandId = commandId,
    creationSource = "web",
    threadId = thread.id,
    reuseExistingThread = if (create) null else true,
    projectId = thread.projectId,
    title = titleSeed ?: thread.title,
    generateTitle = titleSeed != null,
    modelSelection = modelSelection ?: thread.modelSelection,
    runtimeMode = runtimeMode,
    interactionMode = interactionMode,
    workspaceStrategy = strategy,
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
