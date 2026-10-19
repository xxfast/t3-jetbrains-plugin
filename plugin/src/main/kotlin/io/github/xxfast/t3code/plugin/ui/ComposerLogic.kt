package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.ProviderThread
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.has
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.Command.MessageDispatch.DispatchMode
import io.github.xxfast.t3code.protocol.generated.MessageContext
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ProviderApprovalOption
import io.github.xxfast.t3code.protocol.generated.ProviderCapabilities
import io.github.xxfast.t3code.protocol.generated.TurnCapabilities
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
  /** Review comments going with the next message. */
  val reviewComments: Int = 0,
)

const val NOT_CONNECTED: String = "Not connected"

const val READ_ONLY: String =
  "This pairing is read only. Pair again with a code that can operate tasks."

/** `AuthOrchestrationOperateScope` in upstream's `packages/contracts/src/auth.ts`. */
const val OPERATE_SCOPE: String = "orchestration:operate"

/**
 * Why nothing that changes a thread can happen over [connection]: not connected, or a pairing
 * without `orchestration:operate`. Null when it can. The composer, review comments and roll back
 * share it.
 */
fun operateBlockReason(connection: ConnectionState): String? = when {
  connection !is ConnectionState.Connected -> NOT_CONNECTED
  !connection.session.has(OPERATE_SCOPE) -> READ_ONLY
  else -> null
}

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
  reviewComments: Int = 0,
): ComposerState {
  val reason: String? = operateBlockReason(connection)
  val enabled: Boolean = reason == null
  return ComposerState(
    enabled = enabled,
    disabledReason = reason,
    sending = sending,
    error = error,
    running = running,
    showQueueToggle = enabled && running,
    queue = queue,
    reviewComments = reviewComments,
  )
}

/**
 * Whether [text] goes out now. One send at a time, and nothing while an attachment in [extras]
 * is uploading or failed. Blank text goes only with attachments: `hasSendableContent` in
 * upstream's `deriveComposerSendState` (`ChatView.logic.ts`) counts the prompt, images, terminal
 * and element contexts, never review comments.
 */
fun sendable(text: String, state: ComposerState, extras: DraftExtras = DraftExtras()): Boolean =
  state.enabled &&
    !state.sending &&
    extras.blockReason == null &&
    (text.isNotBlank() || extras.hasContent)

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

/**
 * The `message.dispatch` the desktop app sends for a plain-text follow-up, with a model picked
 * mid-run riding along as [modelSelection].
 */
fun messageDispatch(
  threadId: String,
  commandId: String,
  messageId: String,
  text: String,
  serverResolved: Boolean,
  requested: ComposerDispatchMode,
  activeRun: Run?,
  capabilities: ProviderCapabilities?,
  modelSelection: ModelSelection? = null,
  context: MessageContext? = null,
): Command.MessageDispatch = Command.MessageDispatch(
  createdBy = "user",
  creationSource = "web",
  commandId = commandId,
  threadId = threadId,
  messageId = messageId,
  text = text,
  context = context,
  attachments = emptyList(),
  deliveryIntent = deliveryIntent(serverResolved, requested),
  modelSelection = modelSelection,
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

// Approvals and questions. They take over the composer while the thread waits on you, like
// upstream's `ComposerPendingApprovalPanel.tsx` and `ComposerPendingUserInputPanel.tsx`.

/** What the composer shows instead of the text field while the thread waits on you. */
sealed interface PendingRequest {
  val requestId: String

  /** The response is on its way and the server hasn't resolved the request yet. */
  val responding: Boolean

  /** False when the provider can't take an answer any more (`not_resumable`). */
  val resumable: Boolean

  data class Approval(
    override val requestId: String,
    val requestKind: String,
    val detail: String?,
    val appName: String?,
    val options: List<ProviderApprovalOption>,
    /** How many approvals are pending, for upstream's `1/N`. */
    val count: Int,
    override val resumable: Boolean,
    override val responding: Boolean,
  ) : PendingRequest

  data class UserInput(
    val item: TurnItem.UserInputRequest,
    val drafts: Map<String, PendingUserInputDraftAnswer>,
    /** The question on screen; upstream shows one at a time. */
    val questionIndex: Int,
    /** Message-mode prompts can be dismissed without an answer. */
    val dismissible: Boolean,
    override val resumable: Boolean,
    override val responding: Boolean,
  ) : PendingRequest {
    override val requestId: String get() = item.requestId

    val activeQuestion: UserInputQuestion? get() = item.questions.getOrNull(questionIndex)

    // Zero questions is the last question too, answered with `{}`
    val isLastQuestion: Boolean get() = questionIndex >= item.questions.lastIndex
  }
}

/** Upstream's `PendingUserInputDraftAnswer`, without attachments. */
data class PendingUserInputDraftAnswer(
  val selected: List<String> = emptyList(),
  val customAnswer: String? = null,
)

/** An approval the thread waits on, `ThreadPendingApproval` in `threadRequests.ts`. */
data class PendingApproval(
  val requestId: String,
  val requestKind: String,
  val detail: String?,
  val appName: String?,
  val options: List<ProviderApprovalOption>?,
  val resumable: Boolean,
)

/** A question the thread waits on, `ThreadPendingUserInput` in `threadRequests.ts`. */
data class PendingUserInput(
  val item: TurnItem.UserInputRequest,
  val resumable: Boolean,
  val dismissible: Boolean,
)

data class PendingThreadRequests(
  val approvals: List<PendingApproval>,
  val userInputs: List<PendingUserInput>,
) {
  val requestIds: Set<String>
    get() = (approvals.map { it.requestId } + userInputs.map { it.item.requestId }).toSet()
}

/** Requests the composer never answers, `threadRequests.ts`: the provider handles these itself. */
val UNANSWERED_KINDS: Set<String> = setOf("auth_refresh", "dynamic_tool_call")

private val IN_FLIGHT_ITEM: Set<String> = setOf("pending", "waiting", "running")

/**
 * `derivePendingThreadRequests`, `packages/client-runtime/src/state/threadRequests.ts` lines 66
 * to 115: every pending runtime request, matched to the last item with its `requestId`.
 */
fun derivePendingThreadRequests(
  requests: List<RuntimeRequest>,
  items: List<TurnItem>,
): PendingThreadRequests {
  val approvals: MutableList<PendingApproval> = mutableListOf()
  val userInputs: MutableList<PendingUserInput> = mutableListOf()
  for (request in requests) {
    if (request.status != "pending") continue
    val capability: String = request.responseCapability.type
    if (request.kind == "user_input") {
      val item: TurnItem.UserInputRequest = items
        .lastOrNull { it is TurnItem.UserInputRequest && it.requestId == request.id }
        as? TurnItem.UserInputRequest ?: continue
      val message: Boolean = item.responseMode == "message" || capability == "message"
      userInputs += PendingUserInput(item, capability != "not_resumable", dismissible = message)
      continue
    }

    if (request.kind in UNANSWERED_KINDS) continue
    val item: TurnItem.ApprovalRequest? = items
      .lastOrNull { it is TurnItem.ApprovalRequest && it.requestId == request.id }
      as? TurnItem.ApprovalRequest
    approvals += PendingApproval(
      requestId = request.id,
      requestKind = request.kind,
      detail = item?.prompt?.takeIf { it.isNotEmpty() },
      appName = item?.appName?.takeIf { it.isNotEmpty() },
      options = item?.options,
      resumable = capability == "live",
    )
  }

  return PendingThreadRequests(approvals, userInputs)
}

/**
 * The one request the composer shows: the first approval, else the first question
 * (`ChatComposer.tsx:5385` shows questions only when no approval is pending).
 */
fun pendingRequest(
  pending: PendingThreadRequests,
  responding: Set<String>,
  drafts: Map<String, PendingUserInputDraftAnswer>,
  index: Int,
): PendingRequest? {
  val approval: PendingApproval? = pending.approvals.firstOrNull()
  if (approval != null) {
    return PendingRequest.Approval(
      requestId = approval.requestId,
      requestKind = approval.requestKind,
      detail = approval.detail,
      appName = approval.appName,
      options = approvalOptions(approval.options),
      count = pending.approvals.size,
      resumable = approval.resumable,
      responding = approval.requestId in responding,
    )
  }

  val input: PendingUserInput = pending.userInputs.firstOrNull() ?: return null
  return PendingRequest.UserInput(
    item = input.item,
    drafts = drafts,
    questionIndex = index.coerceIn(0, maxOf(input.item.questions.lastIndex, 0)),
    dismissible = input.dismissible,
    resumable = input.resumable,
    responding = input.item.requestId in responding,
  )
}

/** `DEFAULT_APPROVAL_OPTIONS`, `ComposerPendingApprovalActions.tsx`. */
val DEFAULT_APPROVAL_OPTIONS: List<ProviderApprovalOption> = listOf(
  ProviderApprovalOption(decision = "cancel", label = "Cancel"),
  ProviderApprovalOption(decision = "decline", label = "Decline"),
  ProviderApprovalOption(decision = "acceptForSession", label = "Always allow this session"),
  ProviderApprovalOption(decision = "accept", label = "Approve"),
)

/** The item's options, or upstream's defaults when it has none (an empty list stays empty). */
fun approvalOptions(options: List<ProviderApprovalOption>?): List<ProviderApprovalOption> =
  options ?: DEFAULT_APPROVAL_OPTIONS

/** The buttons: `decline` and `accept`. Everything else goes behind "more". */
fun primaryOptions(options: List<ProviderApprovalOption>): List<ProviderApprovalOption> =
  options.filter { it.decision == "decline" || it.decision == "accept" }

fun moreOptions(options: List<ProviderApprovalOption>): List<ProviderApprovalOption> =
  options.filterNot { it.decision == "decline" || it.decision == "accept" }

/** `fallbackLabel`, `ComposerPendingApprovalPanel.tsx`. */
fun approvalLabel(requestKind: String): String = when (requestKind) {
  "mcp-elicitation" -> "App access approval"
  "command" -> "Command approval"
  "file-read" -> "File read approval"
  "permission" -> "App permission approval"
  else -> "File change approval"
}

/** What the approval panel shows under its label, `ComposerPendingApprovalPanel.tsx`. */
fun approvalDetail(approval: PendingRequest.Approval): String =
  if (!approval.resumable) {
    "Provider process is gone. Interrupt or restart the run to respond."
  } else {
    approval.detail ?: approvalLabel(approval.requestKind)
  }

/** `option.value ?? option.label`. */
val UserInputQuestion.Option.answerValue: String get() = value ?: label

/**
 * `resolvePendingUserInputAnswer`, `apps/web/src/pendingUserInput.ts`: a non-empty custom answer
 * wins (trimmed, unless the question has an `initialAnswer`), then the selected options that
 * exist, as a list for `multiSelect` and the first one otherwise. Null is unanswered.
 */
fun resolvePendingUserInputAnswer(
  question: UserInputQuestion,
  draft: PendingUserInputDraftAnswer?,
): JsonElement? {
  val custom: String? = when {
    question.allowCustomAnswer == false -> null
    question.initialAnswer != null -> draft?.customAnswer
    else -> draft?.customAnswer?.trim()?.takeIf { it.isNotEmpty() }
  }
  if (!custom.isNullOrEmpty()) return JsonPrimitive(custom)

  val selected: List<String> = draft?.selected.orEmpty()
    .distinct()
    .filter { value -> question.options.any { it.answerValue == value } }
  if (question.multiSelect == true) {
    if (selected.isNotEmpty()) return JsonArray(selected.map { JsonPrimitive(it) })
    return custom?.let { JsonPrimitive(it) }
  }

  return (selected.firstOrNull() ?: custom)?.let { JsonPrimitive(it) }
}

/** `buildPendingUserInputAnswers`: every question's answer, or null while any is unanswered. */
fun buildPendingUserInputAnswers(
  questions: List<UserInputQuestion>,
  drafts: Map<String, PendingUserInputDraftAnswer>,
): JsonObject? {
  val answers: MutableMap<String, JsonElement> = linkedMapOf()
  for (question in questions) {
    val answer: JsonElement =
      resolvePendingUserInputAnswer(question, drafts[question.id]) ?: return null
    answers[question.id] = answer
  }
  return JsonObject(answers)
}

/** `seedUserInputDraftAnswers`, `threadRequests.ts`: `initialAnswer` pre-fills the text. */
fun seedUserInputDraftAnswers(
  questions: List<UserInputQuestion>,
  drafts: Map<String, PendingUserInputDraftAnswer> = emptyMap(),
): Map<String, PendingUserInputDraftAnswer> {
  val seeded: MutableMap<String, PendingUserInputDraftAnswer> = drafts.toMutableMap()
  for (question in questions) {
    val initial: String = question.initialAnswer ?: continue
    if (question.allowCustomAnswer == false || question.id in seeded) continue
    seeded[question.id] = PendingUserInputDraftAnswer(customAnswer = initial)
  }
  return seeded
}

/** `togglePendingUserInputOptionSelection`: picking an option clears the custom answer. */
fun togglePendingUserInputOptionSelection(
  question: UserInputQuestion,
  draft: PendingUserInputDraftAnswer?,
  value: String,
): PendingUserInputDraftAnswer {
  if (question.multiSelect != true) {
    return PendingUserInputDraftAnswer(listOf(value), customAnswer = "")
  }

  val selected: List<String> = draft?.selected.orEmpty().distinct()
  val next: List<String> = if (value in selected) selected - value else selected + value
  return PendingUserInputDraftAnswer(next, customAnswer = "")
}

/**
 * `setPendingUserInputCustomAnswer`: typing drops the selection, unless the text is blank and
 * the question has no `initialAnswer`. Null when [text] is what the draft already has, so a
 * text field echoing the model back changes nothing.
 */
fun setPendingUserInputCustomAnswer(
  question: UserInputQuestion,
  draft: PendingUserInputDraftAnswer?,
  text: String,
): PendingUserInputDraftAnswer? {
  if (text == draft?.customAnswer.orEmpty()) return null
  val keep: Boolean = question.initialAnswer == null && text.isBlank()
  val selected: List<String> = if (keep) draft?.selected.orEmpty() else emptyList()
  return PendingUserInputDraftAnswer(selected, customAnswer = text)
}

/** What "Next" does on [question]'s current question. */
sealed interface Advance {
  data class Next(val index: Int) : Advance

  data class Submit(val answers: JsonObject) : Advance

  data object Stay : Advance
}

/**
 * `onAdvanceActivePendingUserInput`, `apps/web/src/components/chat/usePendingThreadRequests.ts`
 * line 476: the next question, or on the last one, submit once every question has an answer.
 * Zero questions is the last question, submitted as `{}`.
 */
fun advance(userInput: PendingRequest.UserInput): Advance {
  if (!userInput.resumable) return Advance.Stay
  if (!userInput.isLastQuestion) return Advance.Next(userInput.questionIndex + 1)
  val answers: JsonObject =
    buildPendingUserInputAnswers(userInput.item.questions, userInput.drafts) ?: return Advance.Stay
  return Advance.Submit(answers)
}

/** `canAdvance` in `derivePendingUserInputProgress`: the question on screen has an answer. */
fun canAdvance(userInput: PendingRequest.UserInput): Boolean {
  val question: UserInputQuestion = userInput.activeQuestion ?: return false
  return resolvePendingUserInputAnswer(question, userInput.drafts[question.id]) != null
}

/**
 * Whether Next (or Submit, on the last question) goes anywhere: the button and Enter in the
 * custom answer share this, so neither moves past what the other wouldn't.
 */
fun nextEnabled(userInput: PendingRequest.UserInput): Boolean =
  if (userInput.isLastQuestion) advance(userInput) is Advance.Submit else canAdvance(userInput)

/** `respondToThreadApproval`, `packages/client-runtime/src/operations/commands.ts` line 835. */
fun approvalResponse(
  threadId: String,
  requestId: String,
  decision: String,
  commandId: String,
): Command.RuntimeRequestRespond = Command.RuntimeRequestRespond(
  commandId = commandId,
  threadId = threadId,
  requestId = requestId,
  decision = decision,
)

/** `respondToThreadUserInput`, `commands.ts` line 847. */
fun answersResponse(
  threadId: String,
  requestId: String,
  answers: JsonObject,
  commandId: String,
): Command.RuntimeRequestRespond = Command.RuntimeRequestRespond(
  commandId = commandId,
  threadId = threadId,
  requestId = requestId,
  answers = answers,
)

/** `dismissThreadUserInput`, `commands.ts` line 862. */
fun dismissal(threadId: String, requestId: String, commandId: String): Command =
  Command.ThreadUserInputDismiss(commandId = commandId, threadId = threadId, requestId = requestId)

/** Whether a request card in the timeline is still waiting on you. */
fun awaitingResponse(
  requestId: String,
  status: String,
  requests: Map<String, RuntimeRequest>,
): Boolean {
  val request: RuntimeRequest = requests[requestId] ?: return status in IN_FLIGHT_ITEM
  return request.status == "pending"
}

/** A resolved approval's decision, by the label the item offered for it. */
fun decisionLabel(decision: String?, options: List<ProviderApprovalOption>?): String {
  if (decision == null) return "Resolved"
  return approvalOptions(options).find { it.decision == decision }?.label ?: decision
}

/** A resolved question's answers, one `header: answer` line per question, labels for values. */
fun answerLines(questions: List<UserInputQuestion>, answers: JsonObject?): List<String> {
  if (answers.isNullOrEmpty()) return listOf("Dismissed")
  return questions.mapNotNull { question ->
    val answer: JsonElement = answers[question.id] ?: return@mapNotNull null
    val values: List<String> = when (answer) {
      is JsonArray -> answer.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      is JsonPrimitive -> listOfNotNull(answer.contentOrNull)
      else -> emptyList()
    }
    val labels: List<String> = values.map { value ->
      question.options.find { it.answerValue == value }?.label ?: value
    }
    return@mapNotNull "${question.header}: ${labels.joinToString(", ")}"
  }
}
