package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.has
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.Command.MessageDispatch.DispatchMode
import io.github.xxfast.t3code.protocol.generated.ProviderCapabilities
import io.github.xxfast.t3code.protocol.generated.TurnCapabilities
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant

/** What the composer shows. Built by [composerState], rendered by [Composer]. */
data class ComposerState(
  val enabled: Boolean,
  val disabledReason: String?,
  val sending: Boolean,
  val error: String?,
  /** A run is going, so a send is a follow-up: steered into it or queued behind it. */
  val running: Boolean,
  val showQueueToggle: Boolean,
  /** The follow-up behaviour: queue when on, steer when off. */
  val queue: Boolean,
)

const val NOT_CONNECTED: String = "Not connected"

const val READ_ONLY: String =
  "This pairing is read only. Pair again with a code that can operate tasks."

/** `AuthOrchestrationOperateScope` in upstream's `packages/contracts/src/auth.ts`. */
const val OPERATE_SCOPE: String = "orchestration:operate"

/**
 * Upstream doesn't stop a message to an archived thread: `ChatComposer.tsx` has no archived
 * state and the archived guard in `Orchestrator.ts` (around line 2548) leaves out
 * `message.dispatch`. So neither does this.
 */
fun composerState(
  connection: ConnectionState,
  running: Boolean,
  sending: Boolean,
  error: String?,
  queue: Boolean,
): ComposerState {
  val reason: String? = when {
    connection !is ConnectionState.Connected -> NOT_CONNECTED
    !connection.session.has(OPERATE_SCOPE) -> READ_ONLY
    else -> null
  }
  val enabled: Boolean = reason == null
  return ComposerState(
    enabled = enabled,
    disabledReason = reason,
    sending = sending,
    error = error,
    running = running,
    showQueueToggle = enabled && running,
    queue = queue,
  )
}

/** Whether [text] goes out now. Blank never does, and one send at a time. */
fun sendable(text: String, state: ComposerState): Boolean =
  state.enabled && !state.sending && text.isNotBlank()

/** Upstream's `ComposerDispatchMode`, `packages/client-runtime/src/state/composerDispatch.ts`. */
enum class ComposerDispatchMode(val wire: String) {
  AUTO("auto"),
  QUEUE("queue"),
  STEER("steer"),
  RESTART("restart"),
}

/**
 * `resolveComposerDispatchMode` from `composerDispatch.ts` lines 4 to 16: `auto` with nothing
 * running, else the follow-up behaviour ([queue] on is `queue`, off is `steer`), flipped by
 * [alternate]. Upstream's `canSteer` (false while compacting) isn't projected here, so it's left
 * out.
 */
fun resolveComposerDispatchMode(
  running: Boolean,
  alternate: Boolean,
  queue: Boolean,
): ComposerDispatchMode {
  if (!running) return ComposerDispatchMode.AUTO
  val default: ComposerDispatchMode =
    if (queue) ComposerDispatchMode.QUEUE else ComposerDispatchMode.STEER
  if (!alternate) return default
  return if (default == ComposerDispatchMode.QUEUE) {
    ComposerDispatchMode.STEER
  } else {
    ComposerDispatchMode.QUEUE
  }
}

/** Run statuses `startThreadTurn` counts as active, `commands.ts` around line 698. */
private val ACTIVE_RUN: Set<String> = setOf("preparing", "starting", "running", "waiting")

/** The run a follow-up would land behind: the last active one, like upstream's `findLast`. */
fun activeRun(runs: List<Run>): Run? = runs.lastOrNull { run -> run.status in ACTIVE_RUN }

/**
 * The turn capabilities behind [run], as `startThreadTurn` finds them (`commands.ts` lines 700
 * to 712): the run's provider thread by `providerThreadId`, then that thread's provider session
 * by `providerSessionId`. Any miss is null, which ends up queueing.
 */
fun activeCapabilities(
  run: Run?,
  providerThreads: List<ProviderThread>,
  providerSessions: List<ProviderSession>,
): ProviderCapabilities? {
  if (run == null) return null
  val thread: ProviderThread =
    providerThreads.find { thread -> thread.id == run.providerThreadId } ?: return null
  val sessionId: String = thread.providerSessionId ?: return null
  return providerSessions.find { session -> session.id == sessionId }?.capabilities
}

/**
 * `dispatchMode` from `startThreadTurn`, `packages/client-runtime/src/operations/commands.ts`
 * lines 620 to 766. The composer never requests `start`, so that early branch isn't here.
 */
fun dispatchMode(
  serverResolved: Boolean,
  requested: ComposerDispatchMode,
  activeRun: Run?,
  capabilities: ProviderCapabilities?,
): DispatchMode {
  if (serverResolved) {
    return if (requested == ComposerDispatchMode.QUEUE) {
      DispatchMode.QueueAfterActive()
    } else {
      DispatchMode.StartImmediately()
    }
  }

  if (activeRun == null) return DispatchMode.StartImmediately()
  val turns: TurnCapabilities? = capabilities?.turns
  return when {
    requested == ComposerDispatchMode.STEER -> DispatchMode.SteerActive(targetRunId = activeRun.id)

    requested == ComposerDispatchMode.RESTART ->
      DispatchMode.RestartActive(targetRunId = activeRun.id)

    requested == ComposerDispatchMode.QUEUE -> DispatchMode.QueueAfterActive()
    turns?.supportsActiveSteering == true -> DispatchMode.SteerActive(targetRunId = activeRun.id)
    turns?.supportsQueuedMessages == true -> DispatchMode.QueueAfterActive()
    turns?.supportsSteeringByInterruptRestart == true ->
      DispatchMode.RestartActive(targetRunId = activeRun.id)

    else -> DispatchMode.QueueAfterActive()
  }
}

/** Upstream sends `deliveryIntent: requestedMode` when the server resolves and it isn't a queue. */
fun deliveryIntent(serverResolved: Boolean, requested: ComposerDispatchMode): String? =
  if (serverResolved && requested != ComposerDispatchMode.QUEUE) requested.wire else null

/** The `message.dispatch` the desktop app sends for a plain-text follow-up. */
fun messageDispatch(
  threadId: String,
  commandId: String,
  messageId: String,
  text: String,
  serverResolved: Boolean,
  requested: ComposerDispatchMode,
  activeRun: Run?,
  capabilities: ProviderCapabilities?,
): Command.MessageDispatch = Command.MessageDispatch(
  createdBy = "user",
  creationSource = "web",
  commandId = commandId,
  threadId = threadId,
  messageId = messageId,
  text = text,
  attachments = emptyList(),
  deliveryIntent = deliveryIntent(serverResolved, requested),
  dispatchMode = dispatchMode(serverResolved, requested, activeRun, capabilities),
)

/** What the thread shows for [text] until the server acknowledges it. */
fun optimisticMessage(
  threadId: String,
  messageId: String,
  text: String,
  now: Instant,
  items: List<TurnItem>,
): TurnItem.UserMessage {
  val at: String = now.toString()
  return TurnItem.UserMessage(
    id = "optimistic:$messageId",
    threadId = threadId,
    runId = null,
    nodeId = null,
    providerThreadId = null,
    providerTurnId = null,
    nativeItemRef = null,
    parentItemId = null,
    ordinal = (items.maxOfOrNull { it.ordinal } ?: 0) + 1,
    status = "completed",
    title = null,
    startedAt = at,
    completedAt = at,
    updatedAt = at,
    createdBy = "user",
    creationSource = "web",
    messageId = messageId,
    inputIntent = "turn_start",
    text = text,
    attachments = emptyList(),
  )
}

/**
 * The message ids the server has acknowledged: user-message items, plus user messages in the
 * projection, since a queued message has no item until its run starts. `ChatView.tsx` lines
 * 1168 to 1177.
 */
fun deliveredIds(items: List<TurnItem>, messages: List<ConversationMessage>): Set<String> {
  val ids: MutableSet<String> = items
    .filterIsInstance<TurnItem.UserMessage>()
    .mapTo(mutableSetOf()) { message -> message.messageId }
  messages.filter { it.role == "user" }.mapTo(ids) { message -> message.id }
  return ids
}

/** [items] with the [pending] messages the server hasn't acknowledged appended, in send order. */
fun mergeOptimistic(
  items: List<TurnItem>,
  messages: List<ConversationMessage>,
  pending: List<TurnItem.UserMessage>,
): List<TurnItem> {
  if (pending.isEmpty()) return items
  val delivered: Set<String> = deliveredIds(items, messages)
  return items + pending.filterNot { message -> message.messageId in delivered }
}

enum class ComposerKey { SEND, SEND_ALTERNATE, NEWLINE, IGNORE }

/**
 * `composerSubmissionIntentForKey`, `apps/web/src/composer-logic.ts` lines 49 to 70, with the
 * default keybindings: Ctrl/Cmd+Enter alone while [running] is `composer.sendAlternate`
 * (`packages/shared/src/keybindings.ts:155`); then Shift or Alt with Enter is a newline; any
 * other Enter sends.
 */
fun composerKey(
  enter: Boolean,
  keyDown: Boolean,
  shift: Boolean,
  alt: Boolean,
  ctrl: Boolean,
  meta: Boolean,
  running: Boolean,
): ComposerKey = when {
  !enter || !keyDown -> ComposerKey.IGNORE
  (ctrl || meta) && !shift && !alt && running -> ComposerKey.SEND_ALTERNATE
  shift || alt -> ComposerKey.NEWLINE
  else -> ComposerKey.SEND
}
