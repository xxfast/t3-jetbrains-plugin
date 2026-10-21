package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.WorkspaceDraft
import io.github.xxfast.t3code.plugin.ui.deliveredIds
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.TurnItem

/**
 * One thread's outbox: messages sent but not yet acknowledged by the server, which of those are
 * still being dispatched ([inFlight]), the server's sequence for each one it took ([delivered]),
 * the approvals and questions being answered ([responding]), the thread settings picked but not
 * yet confirmed ([settings]), and why the last send, answer or pick failed. Pure, so
 * [ChatController] stays a thin shell.
 */
data class PendingMessages(
  val messages: List<TurnItem.UserMessage> = emptyList(),
  val inFlight: Set<String> = emptySet(),
  val delivered: Map<String, Long> = emptyMap(),
  val responding: Set<String> = emptySet(),
  val settings: PendingSettings = PendingSettings(),
  /**
   * A model picked while a run was going. Upstream never changes a running thread's model; the
   * pick waits as a composer draft and rides on the next message (`onProviderModelSelect`,
   * `ChatView.tsx:7467`; `resolveThreadMetadataUpdateForNextTurn`, `ChatView.logic.ts:472`).
   */
  val modelDraft: ModelSelection? = null,
  /** The message carrying [modelDraft], once one is on its way. */
  val modelDraftCarrier: String? = null,
  /**
   * Where the first turn runs, picked in the composer's Workspace control before the first
   * message: upstream's pending server thread env mode, branch and start-from-origin entries.
   */
  val workspace: WorkspaceDraft = WorkspaceDraft(),
  /**
   * An `orchestration.launchThread` on its way for a new worktree, upstream's `localPreparing`:
   * the setup card shows from the send until the first snapshot arrives.
   */
  val launching: Boolean = false,
  val error: String? = null,
) {
  val sending: Boolean get() = inFlight.isNotEmpty()

  /** The Workspace control changed. The draft stays until the server takes the first message. */
  fun draftedWorkspace(change: (WorkspaceDraft) -> WorkspaceDraft): PendingMessages =
    copy(workspace = change(workspace), error = null)

  /**
   * [message] is on its way, carrying [model] when it's the drafted one. A new send clears the
   * last error.
   */
  fun sent(message: TurnItem.UserMessage, model: ModelSelection? = null): PendingMessages = copy(
    messages = messages + message,
    inFlight = inFlight + message.messageId,
    modelDraftCarrier =
      if (model != null && model == modelDraft) message.messageId else modelDraftCarrier,
    error = null,
  )

  /** [selection] waits for the next message. A newer draft isn't carried by an older send. */
  fun draftedModel(selection: ModelSelection): PendingMessages =
    copy(modelDraft = selection, modelDraftCarrier = null, error = null)

  /** A thread command is on its way; a model set now replaces any draft. */
  fun picked(command: Command): PendingMessages {
    val model: Boolean =
      command is Command.ThreadModelSelectionSet || command is Command.ProviderSwitch
    return copy(
      settings = settings.picked(command),
      modelDraft = if (model) null else modelDraft,
      modelDraftCarrier = if (model) null else modelDraftCarrier,
      error = null,
    )
  }

  /** The server took [messageId] at [sequence], what `orchestration.dispatchCommand` answered. */
  fun delivered(messageId: String, sequence: Long): PendingMessages {
    val carried: Boolean = modelDraftCarrier == messageId
    return copy(
      inFlight = inFlight - messageId,
      delivered = delivered + (messageId to sequence),
      modelDraft = if (carried) null else modelDraft,
      modelDraftCarrier = if (carried) null else modelDraftCarrier,
    )
  }

  /** The server refused [messageId], so it never shows up. */
  fun failed(messageId: String, error: String): PendingMessages = copy(
    messages = messages.filterNot { it.messageId == messageId },
    inFlight = inFlight - messageId,
    delivered = delivered - messageId,
    // The draft stays for the next try
    modelDraftCarrier = modelDraftCarrier.takeUnless { it == messageId },
    error = error,
  )

  /** An answer to [requestId] is on its way. It stays responding until the request resolves. */
  fun respondingTo(requestId: String): PendingMessages =
    copy(responding = responding + requestId, error = null)

  /** The server refused the answer to [requestId]; the panel takes answers again. */
  fun respondFailed(requestId: String, error: String): PendingMessages =
    copy(responding = responding - requestId, error = error)

  /**
   * Retires what the server acknowledged by `messageId`, in [items] or [conversation]. A message
   * also goes once the view's [sequence] reaches the one its dispatch answered and the view
   * still doesn't carry it: the server took it and it's gone since, like a queued message
   * cancelled before its run started. Before that, it waits. Answers stop [responding] once
   * their request is no longer in [pendingRequests], what the server still waits on.
   */
  fun reconciled(
    items: List<TurnItem>,
    conversation: List<ConversationMessage>,
    sequence: Long,
    pendingRequests: Set<String> = emptySet(),
  ): PendingMessages {
    val stillWaiting: Set<String> = responding intersect pendingRequests
    if (stillWaiting.size != responding.size) {
      val pruned: PendingMessages = copy(responding = stillWaiting)
      return pruned.reconciled(items, conversation, sequence, pendingRequests)
    }

    if (messages.isEmpty()) return this
    val acknowledged: Set<String> = deliveredIds(items, conversation)
    val left: List<TurnItem.UserMessage> = messages.filterNot { message ->
      val at: Long? = delivered[message.messageId]
      message.messageId in acknowledged || (at != null && sequence >= at)
    }
    if (left.size == messages.size) return this
    val ids: Set<String> = left.mapTo(mutableSetOf()) { it.messageId }
    return copy(messages = left, delivered = delivered.filterKeys { it in ids })
  }
}
