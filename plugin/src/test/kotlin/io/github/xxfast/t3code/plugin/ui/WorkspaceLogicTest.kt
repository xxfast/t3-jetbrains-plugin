package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.T3JsonOut
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchInput
import io.github.xxfast.t3code.protocol.generated.VcsRef
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class WorkspaceLogicTest {
  @Test
  fun `labels follow the upstream strings`() {
    assertEquals("Current checkout", resolveEnvModeLabel(EnvMode.LOCAL))
    assertEquals("New worktree", resolveEnvModeLabel(EnvMode.WORKTREE))
    assertEquals("Current worktree", resolveCurrentWorkspaceLabel("/wt"))
    assertEquals("Current checkout", resolveCurrentWorkspaceLabel(null))
    assertEquals("Worktree", resolveLockedWorkspaceLabel("/wt", EnvMode.LOCAL))
    assertEquals("New worktree", resolveLockedWorkspaceLabel(null, EnvMode.WORKTREE))
    assertEquals("Local checkout", resolveLockedWorkspaceLabel(null, EnvMode.LOCAL))
  }

  @Test
  fun `a worktree or a setup in flight reads as worktree mode`() {
    assertEquals(EnvMode.WORKTREE, resolveEffectiveEnvMode("/wt", preparingWorktree = false))
    assertEquals(EnvMode.WORKTREE, resolveEffectiveEnvMode(null, preparingWorktree = true))
    assertEquals(EnvMode.LOCAL, resolveEffectiveEnvMode(null, preparingWorktree = false))
  }

  @Test
  fun `the branch control prefers the picked base in worktree mode, the checkout's otherwise`() {
    assertEquals(
      "feature/x",
      resolveBranchToolbarValue(EnvMode.WORKTREE, null, "feature/x", "main"),
    )
    assertEquals("main", resolveBranchToolbarValue(EnvMode.WORKTREE, null, null, "main"))
    assertEquals("main", resolveBranchToolbarValue(EnvMode.LOCAL, null, "feature/x", "main"))
    assertEquals("feature/x", resolveBranchToolbarValue(EnvMode.LOCAL, null, "feature/x", null))
  }

  @Test
  fun `the trigger says From the base, with origin when it starts there`() {
    assertEquals("Select ref", resolveBranchTriggerLabel(null, EnvMode.WORKTREE, null, null, false))
    assertEquals(
      "From main",
      resolveBranchTriggerLabel(null, EnvMode.WORKTREE, "main", false, startFromOrigin = false),
    )
    assertEquals(
      "From origin/main",
      resolveBranchTriggerLabel(null, EnvMode.WORKTREE, "main", false, startFromOrigin = true),
    )
    // A remote ref already is the origin's
    assertEquals(
      "From origin/main",
      resolveBranchTriggerLabel(null, EnvMode.WORKTREE, "origin/main", true, true),
    )
    assertEquals("main", resolveBranchTriggerLabel("/wt", EnvMode.WORKTREE, "main", false, true))
  }

  @Test
  fun `only an empty thread without a worktree can still choose`() {
    assertTrue(canOverrideServerThreadEnvMode(view()))
    assertFalse(canOverrideServerThreadEnvMode(view(messages = 1)))
    assertFalse(canOverrideServerThreadEnvMode(view(worktreePath = "/wt")))
    assertFalse(canOverrideServerThreadEnvMode(view(running = true)))
  }

  @Test
  fun `the strip shows in the draft hero state only, as upstream's default`() {
    assertTrue(shouldShowComposerContextStrip(view(), isGitRepo = true))
    assertFalse(shouldShowComposerContextStrip(view(), isGitRepo = false))
    assertFalse(shouldShowComposerContextStrip(view(messages = 1), isGitRepo = true))
    assertFalse(shouldShowComposerContextStrip(view(worktreePath = "/wt"), isGitRepo = true))
  }

  @Test
  fun `a remote ref's local name drops the remote`() {
    assertEquals("main", deriveLocalBranchNameFromRemoteRef("origin/main"))
    assertEquals("feature/x", deriveLocalBranchNameFromRemoteRef("origin/feature/x"))
    assertEquals("main", deriveLocalBranchNameFromRemoteRef("main"))
    assertEquals("origin/", deriveLocalBranchNameFromRemoteRef("origin/"))
  }

  @Test
  fun `a typed branch name is trimmed and dashed, and offered only when new`() {
    assertEquals("fix-the-thing", sanitizeNewRefName("  fix the\tthing "))
    val refs: List<VcsRef> =
      listOf(VcsRef(name = "main", current = true, isDefault = true, worktreePath = null))
    assertEquals("feature/x", createBranchName("feature/x", refs))
    assertNull(createBranchName("main", refs))
    assertNull(createBranchName("   ", refs))
  }

  @Test
  fun `picking a ref targets its worktree when it has one, else the checkout`() {
    val root = "/p"
    val inWorktree = VcsRef(name = "wt", current = false, isDefault = false, worktreePath = "/p/.t3/wt")
    val target: BranchSelectionTarget = resolveBranchSelectionTarget(root, null, inWorktree)
    assertTrue(target.reuseExistingWorktree)
    assertEquals("/p/.t3/wt", target.checkoutCwd)
    assertEquals("/p/.t3/wt", target.nextWorktreePath)
    val plain = VcsRef(name = "dev", current = false, isDefault = false, worktreePath = null)
    val local: BranchSelectionTarget = resolveBranchSelectionTarget(root, null, plain)
    assertFalse(local.reuseExistingWorktree)
    assertEquals(root, local.checkoutCwd)
    assertNull(local.nextWorktreePath)
    // The default branch from a worktree goes back to the root
    val main = VcsRef(name = "main", current = false, isDefault = true, worktreePath = null)
    assertNull(resolveBranchSelectionTarget(root, "/p/.t3/other", main).nextWorktreePath)
    assertEquals("/p/.t3/other", resolveBranchSelectionTarget(root, "/p/.t3/other", plain).checkoutCwd)
  }

  @Test
  fun `a picked branch lands on the row before the turn, only when it differs`() {
    val thread: Thread = thread(null)
    assertNull(threadBranchUpdateForNextTurn(thread, null, "c"))
    assertNull(threadBranchUpdateForNextTurn(thread.copy(branch = "dev"), "dev", "c"))
    val update = threadBranchUpdateForNextTurn(thread, "dev", "c")
    assertEquals("dev", update?.branch)
    assertEquals("t", update?.threadId)
  }

  @Test
  fun `outside a git repo a send runs locally whatever was picked`() {
    assertEquals(EnvMode.LOCAL, resolveSendEnvMode(EnvMode.WORKTREE, isGitRepo = false))
    assertEquals(EnvMode.WORKTREE, resolveSendEnvMode(EnvMode.WORKTREE, isGitRepo = true))
  }

  @Test
  fun `the control is null off a git repo and locked once a message is out`() {
    assertNull(workspaceControlState(view(), WorkspaceDraft(), "main", false, false))
    val locked: WorkspaceControlState =
      assertNotNull(workspaceControlState(view(messages = 1), WorkspaceDraft(), "main", true, false))
    assertTrue(locked.locked)
    assertEquals("Local checkout", locked.workspaceLabel)
    assertFalse(locked.selectingWorktreeBase)
  }

  @Test
  fun `the draft picks the mode, the base and origin while the thread is empty`() {
    val refs: List<VcsRef> = listOf(
      VcsRef(name = "main", current = true, isDefault = true, worktreePath = null),
      VcsRef(name = "origin/dev", isRemote = true, current = false, isDefault = false, worktreePath = null),
    )
    val draft = WorkspaceDraft(envMode = EnvMode.WORKTREE, branch = "origin/dev", startFromOrigin = true)
    val state: WorkspaceControlState =
      assertNotNull(workspaceControlState(view(), draft, "main", true, false, refs = refs))
    assertFalse(state.locked)
    assertEquals(EnvMode.WORKTREE, state.envMode)
    assertEquals("New worktree", state.workspaceLabel)
    assertEquals("folder-git-2", state.workspaceIcon)
    assertTrue(state.selectingWorktreeBase)
    assertEquals("origin/dev", state.baseBranch)
    // A remote base isn't prefixed again
    assertEquals("From origin/dev", state.branchLabel)
    assertTrue(state.startFromOrigin)
  }

  @Test
  fun `without a draft the base is the checkout's branch`() {
    val draft = WorkspaceDraft(envMode = EnvMode.WORKTREE)
    val state: WorkspaceControlState =
      assertNotNull(workspaceControlState(view(), draft, "main", true, false))
    assertEquals("main", state.baseBranch)
    assertEquals("From main", state.branchLabel)
    assertFalse(state.startFromOrigin)
  }

  @Test
  fun `a setup in flight locks the control in worktree mode`() {
    val state: WorkspaceControlState =
      assertNotNull(workspaceControlState(view(), WorkspaceDraft(), "main", true, true))
    assertTrue(state.locked)
    assertEquals(EnvMode.WORKTREE, state.envMode)
    assertEquals("New worktree", state.workspaceLabel)
  }

  @Test
  fun `the first message in worktree mode creates one, later ones don't`() {
    assertTrue(shouldCreateWorktree(view(), EnvMode.WORKTREE))
    assertFalse(shouldCreateWorktree(view(), EnvMode.LOCAL))
    assertFalse(shouldCreateWorktree(view(messages = 1), EnvMode.WORKTREE))
    assertFalse(shouldCreateWorktree(view(worktreePath = "/wt"), EnvMode.WORKTREE))
  }

  @Test
  fun `the launch reuses the thread and asks for a worktree from the base`() {
    val input: ThreadLaunchInput = launchThreadInput(
      view = view(),
      commandId = "cmd",
      messageId = "msg",
      text = "hello",
      context = null,
      attachments = emptyList(),
      modelSelection = null,
      runtimeMode = "full-access",
      interactionMode = "default",
      baseBranch = "main",
      startFromOrigin = true,
      titleSeed = "hello",
    )
    val json: JsonObject =
      T3JsonOut.encodeToJsonElement(ThreadLaunchInput.serializer(), input).jsonObject
    assertEquals("t", json.getValue("threadId").jsonPrimitive.content)
    assertEquals("true", json.getValue("reuseExistingThread").jsonPrimitive.content)
    assertEquals("true", json.getValue("generateTitle").jsonPrimitive.content)
    assertEquals("hello", json.getValue("title").jsonPrimitive.content)
    val strategy: JsonObject = json.getValue("workspaceStrategy").jsonObject
    assertEquals("worktree", strategy.getValue("type").jsonPrimitive.content)
    assertEquals("main", strategy.getValue("baseRef").jsonPrimitive.content)
    assertEquals("true", strategy.getValue("startFromOrigin").jsonPrimitive.content)
    val message: JsonObject = json.getValue("initialMessage").jsonObject
    assertEquals("msg", message.getValue("messageId").jsonPrimitive.content)
    assertEquals("hello", message.getValue("text").jsonPrimitive.content)
    // The thread's own model when nothing's drafted
    assertEquals("gpt-5", json.getValue("modelSelection").jsonObject.getValue("model").jsonPrimitive.content)
  }

  @Test
  fun `a launch without a title seed keeps the thread's and doesn't regenerate`() {
    val input: ThreadLaunchInput = launchThreadInput(
      view = view(),
      commandId = "cmd",
      messageId = "msg",
      text = "hello",
      context = null,
      attachments = emptyList(),
      modelSelection = null,
      runtimeMode = "full-access",
      interactionMode = "default",
      baseBranch = "main",
      startFromOrigin = false,
      titleSeed = null,
    )
    assertEquals("New thread", input.title)
    assertEquals(false, input.generateTitle)
    val json: JsonObject =
      T3JsonOut.encodeToJsonElement(ThreadLaunchInput.serializer(), input).jsonObject
    assertNull(json.getValue("workspaceStrategy").jsonObject["startFromOrigin"])
  }

  private fun view(
    messages: Int = 0,
    worktreePath: String? = null,
    running: Boolean = false,
  ): ThreadView = ThreadView(
    thread = thread(worktreePath),
    runs = if (running) listOf(run("running")) else emptyList(),
    items = emptyList(),
    messages = List(messages) { index -> conversation("m$index") },
  )

  private fun thread(worktreePath: String?): Thread = T3Json.decodeFromString(
    Thread.serializer(),
    """
      {
        "id": "t", "projectId": "p", "title": "New thread", "createdBy": "user",
        "creationSource": "web", "providerInstanceId": "codex",
        "modelSelection": { "instanceId": "codex", "model": "gpt-5" },
        "runtimeMode": "full-access", "interactionMode": "default", "branch": null,
        "worktreePath": ${worktreePath?.let { "\"$it\"" } ?: "null"},
        "lineage": { "rootThreadId": "t" }, "status": "idle", "itemCount": 0,
        "visibleItemCount": 0, "hasActionableProposedPlan": false,
        "createdAt": "2026-10-21T10:00:00Z", "updatedAt": "2026-10-21T10:00:00Z"
      }
    """,
  )

  private fun run(status: String): Run = T3Json.decodeFromString(
    Run.serializer(),
    """
      {
        "id": "r", "threadId": "t", "ordinal": 1, "status": "$status",
        "providerInstanceId": "codex", "modelSelection": { "model": "gpt-5" },
        "userMessageId": "m", "requestedAt": "2026-10-21T10:00:00Z",
        "startedAt": "2026-10-21T10:00:00Z", "completedAt": null, "providerThreadId": null,
        "rootNodeId": null, "activeAttemptId": null, "checkpointId": null, "contextHandoffId": null
      }
    """,
  )

  private fun conversation(id: String): ConversationMessage = T3Json.decodeFromString(
    ConversationMessage.serializer(),
    """
      {
        "id": "$id", "threadId": "t", "runId": null, "nodeId": null, "role": "user",
        "text": "hi", "createdBy": "user", "creationSource": "web", "attachments": [],
        "streaming": false, "createdAt": "2026-10-21T10:00:00Z",
        "updatedAt": "2026-10-21T10:00:00Z"
      }
    """,
  )
}
