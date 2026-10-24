package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.Command.MessageDispatch.DispatchMode
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProviderCapabilities
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ComposerLogicTest {
  private val active: Run = run("r2", "running")
  private val steerR2: DispatchMode = DispatchMode.SteerActive(targetRunId = "r2")
  private val restartR2: DispatchMode = DispatchMode.RestartActive(targetRunId = "r2")

  @Test
  fun `dispatch mode follows startThreadTurn`() {
    data class Case(
      val name: String,
      val serverResolved: Boolean,
      val requested: ComposerDispatchMode,
      val run: Run?,
      val capabilities: ProviderCapabilities?,
      val expected: DispatchMode,
    )

    val auto: ComposerDispatchMode = ComposerDispatchMode.AUTO
    val queue: ComposerDispatchMode = ComposerDispatchMode.QUEUE
    val steer: ComposerDispatchMode = ComposerDispatchMode.STEER
    val restart: ComposerDispatchMode = ComposerDispatchMode.RESTART
    val start: DispatchMode = DispatchMode.StartImmediately()
    val queueAfter: DispatchMode = DispatchMode.QueueAfterActive()
    val cases: List<Case> = listOf(
      Case("server, auto", true, auto, active, steering, start),
      Case("server, steer", true, steer, active, nothing, start),
      Case("server, queue", true, queue, active, steering, queueAfter),
      Case("idle", false, auto, null, steering, start),
      Case("idle, steer", false, steer, null, steering, start),
      Case("idle, queue", false, queue, null, steering, start),
      Case("steer asked", false, steer, active, nothing, steerR2),
      Case("restart asked", false, restart, active, nothing, restartR2),
      Case("queue asked", false, queue, active, steering, queueAfter),
      Case("auto steers", false, auto, active, steering, steerR2),
      Case("auto queues", false, auto, active, queueing, queueAfter),
      Case("auto restarts", false, auto, active, restarting, restartR2),
      Case("auto, can't do any", false, auto, active, nothing, queueAfter),
      Case("auto, no session", false, auto, active, null, queueAfter),
      Case("auto, steer wins", false, auto, active, all, steerR2),
    )
    cases.forEach { case ->
      assertEquals(
        expected = case.expected,
        actual = dispatchMode(case.serverResolved, case.requested, case.run, case.capabilities),
        message = case.name,
      )
    }
  }

  @Test
  fun `requested mode is auto when idle, else the follow-up behaviour or its alternate`() {
    fun mode(running: Boolean, alternate: Boolean, queue: Boolean): ComposerDispatchMode =
      resolveComposerDispatchMode(running, alternate, queue)

    assertEquals(ComposerDispatchMode.AUTO, mode(running = false, alternate = false, queue = false))
    assertEquals(ComposerDispatchMode.AUTO, mode(running = false, alternate = true, queue = true))
    assertEquals(ComposerDispatchMode.STEER, mode(running = true, alternate = false, queue = false))
    assertEquals(ComposerDispatchMode.QUEUE, mode(running = true, alternate = true, queue = false))
    assertEquals(ComposerDispatchMode.QUEUE, mode(running = true, alternate = false, queue = true))
    assertEquals(ComposerDispatchMode.STEER, mode(running = true, alternate = true, queue = true))
  }

  @Test
  fun `follow-ups queue by the setting, but steer while an approval is pending`() {
    val approval = PendingApproval("a1", "command", null, null, null, true)

    assertTrue(followUpQueues("queue", approvals = emptyList()))
    assertFalse(followUpQueues("steer", approvals = emptyList()))
    assertFalse(followUpQueues("queue", approvals = listOf(approval)))
  }

  @Test
  fun `delivery intent is the requested mode when the server resolves and it isn't a queue`() {
    assertEquals("auto", deliveryIntent(true, ComposerDispatchMode.AUTO))
    assertEquals("steer", deliveryIntent(true, ComposerDispatchMode.STEER))
    assertNull(deliveryIntent(true, ComposerDispatchMode.QUEUE))
    assertNull(deliveryIntent(false, ComposerDispatchMode.STEER))
  }

  @Test
  fun `the command is the desktop's follow-up in plain text`() {
    val command: Command.MessageDispatch = messageDispatch(
      threadId = "t1",
      commandId = "c1",
      messageId = "m1",
      text = "hello",
      serverResolved = true,
      requested = ComposerDispatchMode.STEER,
      activeRun = active,
      capabilities = null,
    )
    assertEquals("message.dispatch", command.type)
    assertEquals("user", command.createdBy)
    assertEquals("web", command.creationSource)
    assertEquals("c1", command.commandId)
    assertEquals("t1", command.threadId)
    assertEquals("m1", command.messageId)
    assertEquals("hello", command.text)
    assertEquals(emptyList(), command.attachments)
    assertEquals("steer", command.deliveryIntent)
    assertEquals(DispatchMode.StartImmediately(), command.dispatchMode)
  }

  @Test
  fun `a model drafted mid-run rides on the message`() {
    val opus: ModelSelection = nextModelSelection(null, "claude", "opus")
    val command: Command.MessageDispatch = messageDispatch(
      threadId = "t1",
      commandId = "c1",
      messageId = "m1",
      text = "hi",
      serverResolved = true,
      requested = ComposerDispatchMode.QUEUE,
      activeRun = null,
      capabilities = null,
      modelSelection = opus,
    )
    assertEquals(opus, command.modelSelection)
  }

  @Test
  fun `active run is the last one still going`() {
    val runs: List<Run> = listOf(
      run("a", "waiting"),
      run("b", "completed"),
      run("c", "preparing"),
      run("d", "queued"),
    )
    assertEquals("c", activeRun(runs)?.id)
    assertNull(activeRun(listOf(run("a", "completed"), run("b", "failed"))))
  }

  @Test
  fun `capabilities follow run to provider thread to provider session`() {
    val sessions: List<ProviderSession> = listOf(session("s1", queueing), session("s2", steering))
    val threads: List<ProviderThread> = listOf(
      providerThread("pt1", sessionId = "s2"),
      providerThread("pt2", sessionId = null),
      providerThread("pt3", sessionId = "gone"),
    )
    assertEquals(steering, activeCapabilities(run("r", "running", "pt1"), threads, sessions))
    assertNull(activeCapabilities(null, threads, sessions))
    assertNull(activeCapabilities(run("r", "running", null), threads, sessions))
    assertNull(activeCapabilities(run("r", "running", "pt9"), threads, sessions))
    assertNull(activeCapabilities(run("r", "running", "pt2"), threads, sessions))
    assertNull(activeCapabilities(run("r", "running", "pt3"), threads, sessions))
  }

  @Test
  fun `a miss in the lookup queues`() {
    val run: Run = run("r2", "running", "pt9")
    val capabilities: ProviderCapabilities? = activeCapabilities(
      run = run,
      providerThreads = listOf(providerThread("pt1", "s1")),
      providerSessions = listOf(session("s1", steering)),
    )
    assertEquals(
      DispatchMode.QueueAfterActive(),
      dispatchMode(false, ComposerDispatchMode.AUTO, run, capabilities),
    )
  }

  @Test
  fun `pending messages the server acknowledges are dropped and the rest append in order`() {
    val items: List<TurnItem> = listOf(user("m1", "item_m1"), user("m0", "item_m0"))
    val messages: List<ConversationMessage> = listOf(
      conversation("m3", "user"),
      conversation("m2", "assistant"),
    )
    val pending: List<TurnItem.UserMessage> = listOf(
      optimistic("m2"),
      optimistic("m1"),
      optimistic("m3"),
      optimistic("m4"),
    )
    assertEquals(
      listOf("item_m1", "item_m0", "optimistic:m2", "optimistic:m4"),
      mergeOptimistic(items, messages, pending).map { it.id },
    )
    assertEquals(items, mergeOptimistic(items, messages, emptyList()))
  }

  @Test
  fun `delivered ids are user items and user messages`() {
    assertEquals(
      setOf("m1", "m3"),
      deliveredIds(
        listOf(user("m1", "item_m1")),
        listOf(conversation("m3", "user"), conversation("m2", "assistant")),
      ),
    )
  }

  @Test
  fun `optimistic message is an honest user message`() {
    val now: Instant = Instant.parse("2026-10-20T10:00:00Z")
    val message: TurnItem.UserMessage =
      optimisticMessage("t1", "m1", "hi", now, listOf(user("m0", "item_m0", ordinal = 7)))
    assertEquals("optimistic:m1", message.id)
    assertEquals("m1", message.messageId)
    assertEquals("turn_start", message.inputIntent)
    assertEquals("completed", message.status)
    assertEquals(8, message.ordinal)
    assertEquals("2026-10-20T10:00:00Z", message.startedAt)
    assertEquals("user", message.createdBy)
    assertEquals("web", message.creationSource)
    assertNull(message.nativeItemRef)
  }

  @Test
  fun `shift and alt newline first, mod sends the alternate while running, enter sends`() {
    fun key(
      enter: Boolean = true,
      keyDown: Boolean = true,
      shift: Boolean = false,
      alt: Boolean = false,
      ctrl: Boolean = false,
      meta: Boolean = false,
      running: Boolean = false,
    ): ComposerKey = composerKey(enter, keyDown, shift, alt, ctrl, meta, running)

    assertEquals(ComposerKey.SEND, key())
    assertEquals(ComposerKey.SEND, key(running = true))
    assertEquals(ComposerKey.NEWLINE, key(shift = true))
    assertEquals(ComposerKey.NEWLINE, key(alt = true))
    assertEquals(ComposerKey.NEWLINE, key(ctrl = true, shift = true))
    assertEquals(ComposerKey.NEWLINE, key(meta = true, shift = true, running = true))
    assertEquals(ComposerKey.NEWLINE, key(ctrl = true, alt = true, running = true))
    assertEquals(ComposerKey.SEND, key(ctrl = true))
    assertEquals(ComposerKey.SEND, key(meta = true))
    assertEquals(ComposerKey.SEND_ALTERNATE, key(ctrl = true, running = true))
    assertEquals(ComposerKey.SEND_ALTERNATE, key(meta = true, running = true))
    assertEquals(ComposerKey.IGNORE, key(keyDown = false))
    assertEquals(ComposerKey.IGNORE, key(enter = false))
    assertEquals(ComposerKey.IGNORE, key(enter = false, ctrl = true, running = true))
  }

  @Test
  fun `composer is disabled with a reason when offline or read only`() {
    val offline: ComposerState =
      composerState(ConnectionState.Disconnected, true, false, null, true)
    assertFalse(offline.enabled)
    assertEquals(NOT_CONNECTED, offline.disabledReason)
    assertFalse(offline.canInterrupt)

    val readOnly: ComposerState =
      composerState(connected("orchestration:read"), false, false, null, false)
    assertFalse(readOnly.enabled)
    assertEquals(READ_ONLY, readOnly.disabledReason)

    val operate: ComposerState =
      composerState(connected("orchestration:read", OPERATE_SCOPE), false, false, "boom", false)
    assertTrue(operate.enabled)
    assertNull(operate.disabledReason)
    assertEquals("boom", operate.error)
  }

  @Test
  fun `stop only offers while a run is going`() {
    val connection: ConnectionState.Connected = connected(OPERATE_SCOPE)
    val idle: ComposerState = composerState(connection, false, false, null, queue = true)
    assertFalse(idle.canInterrupt)
    assertFalse(idle.running)

    val running: ComposerState = composerState(connection, true, false, null, queue = true)
    assertTrue(running.canInterrupt)
    assertTrue(running.running)
    assertTrue(running.queue)
  }

  @Test
  fun `blank text never sends, nor a second send while one is out`() {
    val ready: ComposerState = composerState(connected(OPERATE_SCOPE), false, false, null, false)
    assertTrue(sendable("hi", ready))
    assertFalse(sendable("  \n ", ready))
    assertFalse(sendable("hi", ready.copy(sending = true)))
    assertFalse(sendable("hi", ready.copy(enabled = false)))
  }
}

private val steering: ProviderCapabilities = capabilities(steer = true)
private val queueing: ProviderCapabilities = capabilities(queue = true)
private val restarting: ProviderCapabilities = capabilities(restart = true)
private val nothing: ProviderCapabilities = capabilities()
private val all: ProviderCapabilities = capabilities(steer = true, queue = true, restart = true)

private fun capabilities(
  steer: Boolean = false,
  queue: Boolean = false,
  restart: Boolean = false,
): ProviderCapabilities = T3Json.decodeFromString(
  ProviderCapabilities.serializer(),
  """
  {
    "sessions": {"supportsMultipleProviderThreadsPerSession": false,
      "supportsModelSwitchInSession": false, "supportsProviderSwitchingViaHandoff": false,
      "supportsRuntimeModeSwitchInSession": false, "pendingRequestsSurviveRestart": false},
    "threads": {"canCreateEmptyThread": false, "canReadThreadSnapshot": false,
      "canRollbackThread": false, "canForkThread": false, "canForkFromTurn": false,
      "canForkFromSubagentThread": false, "exposesNativeThreadId": false},
    "turns": {"exposesNativeTurnId": false, "emitsTurnStarted": false,
      "emitsTurnCompleted": false, "supportsInterrupt": false,
      "supportsActiveSteering": $steer, "supportsSteeringByInterruptRestart": $restart,
      "supportsQueuedMessages": $queue, "terminalStatusQuality": "exact"},
    "streaming": {"streamsAssistantText": false, "streamsReasoning": false,
      "streamsToolOutput": false, "streamsPlanText": false, "emitsMessageCompleted": false},
    "tools": {"exposesToolItemIds": false, "emitsToolStarted": false,
      "emitsToolCompleted": false, "emitsToolOutput": false, "supportsMcpTools": false,
      "supportsDynamicToolCallbacks": false},
    "approvals": {"supportsCommandApproval": false, "supportsFileReadApproval": false,
      "supportsFileChangeApproval": false, "supportsApplyPatchApproval": false,
      "approvalsHaveNativeRequestIds": false, "approvalCallbacksAreLiveOnly": false,
      "approvalsCanOriginateFromSubagents": false},
    "planning": {"emitsPlanUpdated": false, "emitsTodoList": false,
      "emitsProposedPlan": false, "supportsStructuredQuestions": false,
      "planDeltasHaveItemIds": false},
    "subagents": {"supportsSubagents": false, "exposesSubagentThreadIds": false,
      "emitsSubagentLifecycle": false, "canWaitForSubagents": false,
      "canCloseSubagents": false, "canForkSubagentThread": false},
    "context": {"acceptsSystemContext": false, "acceptsDeveloperContext": false,
      "acceptsSyntheticUserContext": false, "canGenerateSummaries": false,
      "canConsumeHandoffSummaries": false, "supportsDeltaHandoff": false,
      "supportsFullThreadHandoff": false, "maxRecommendedHandoffChars": null},
    "checkpointing": {"appCanCheckpointFilesystem": false,
      "supportsNestedCheckpointScopes": false, "providerCanRollbackConversation": false,
      "providerRollbackReturnsSnapshot": false, "providerCanReadConversationSnapshot": false},
    "identity": {"nativeThreadIds": "none", "nativeTurnIds": "none",
      "nativeItemIds": "none", "nativeRequestIds": "none"}
  }
  """,
)

private fun session(id: String, capabilities: ProviderCapabilities): ProviderSession =
  ProviderSession(
    id = id,
    driver = "codex",
    providerInstanceId = "codex",
    status = "ready",
    cwd = "/",
    model = null,
    capabilities = capabilities,
    createdAt = "2026-10-20T10:00:00Z",
    updatedAt = "2026-10-20T10:00:00Z",
    lastError = null,
  )

private fun providerThread(id: String, sessionId: String?): ProviderThread = ProviderThread(
  id = id,
  driver = "codex",
  providerInstanceId = "codex",
  providerSessionId = sessionId,
  appThreadId = "t1",
  ownerNodeId = null,
  nativeThreadRef = null,
  nativeConversationHeadRef = null,
  status = "active",
  firstRunOrdinal = 1,
  lastRunOrdinal = 1,
  handoffIds = emptyList(),
  forkedFrom = null,
  createdAt = "2026-10-20T10:00:00Z",
  updatedAt = "2026-10-20T10:00:00Z",
)

private fun conversation(id: String, role: String): ConversationMessage = ConversationMessage(
  createdBy = "user",
  creationSource = "web",
  id = id,
  threadId = "t1",
  runId = null,
  nodeId = null,
  role = role,
  text = "text",
  attachments = emptyList(),
  streaming = false,
  createdAt = "2026-10-20T10:00:00Z",
  updatedAt = "2026-10-20T10:00:00Z",
)

private fun run(id: String, status: String, providerThreadId: String? = null): Run = Run(
  id = id,
  threadId = "t1",
  ordinal = 1,
  providerInstanceId = "codex",
  modelSelection = ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("m")),
  providerThreadId = providerThreadId,
  userMessageId = "m",
  rootNodeId = null,
  activeAttemptId = null,
  status = status,
  requestedAt = "2026-10-20T10:00:00Z",
  startedAt = null,
  completedAt = null,
  checkpointId = null,
  contextHandoffId = null,
)

private fun optimistic(messageId: String): TurnItem.UserMessage =
  optimisticMessage("t1", messageId, "text", Instant.EPOCH, emptyList())

private fun user(messageId: String, id: String, ordinal: Long = 1): TurnItem.UserMessage =
  optimistic(messageId).copy(id = id, ordinal = ordinal)

private fun connected(vararg permissions: String): ConnectionState.Connected =
  ConnectionState.Connected(
    ServerSession(
      authenticated = true,
      auth = ServerAuthDescriptor(
        policy = "desktop-managed-local",
        bootstrapMethods = emptyList(),
        sessionMethods = emptyList(),
        sessionCookieName = "t3",
      ),
      permissions = permissions.toList(),
    ),
  )
