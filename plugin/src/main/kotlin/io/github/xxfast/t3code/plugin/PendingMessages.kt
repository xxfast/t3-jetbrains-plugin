package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.deliveredIds
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.generated.TurnItem

/**
 * One thread's outbox: messages sent but not yet acknowledged by the server, which of those are
 * still being dispatched ([inFlight]), the server's sequence for each one it took ([delivered]),
 * the approvals and questions being answered ([responding]), and why the last send or answer
 * failed. Pure, so [ChatController] stays a thin shell.
 */
data class PendingMessages(
  val messages: List<TurnItem.UserMessage> = emptyList(),
  val inFlight: Set<String> = emptySet(),
  val delivered: Map<String, Long> = emptyMap(),
  val responding: Set<String> = emptySet(),
  val error: String? = null,
) {
  val sending: Boolean get() = inFlight.isNotEmpty()

  /** [message] is on its way. A new send clears the last error. */
  fun sent(message: TurnItem.UserMessage): PendingMessages = copy(
    messages = messages + message,
    inFlight = inFlight + message.messageId,
    error = null,
  )

  /** The server took [messageId] at [sequence], what `orchestration.dispatchCommand` answered. */
  fun delivered(messageId: String, sequence: Long): PendingMessages =
    copy(inFlight = inFlight - messageId, delivered = delivered + (messageId to sequence))

  /** The server refused [messageId], so it never shows up. */
  fun failed(messageId: String, error: String): PendingMessages = copy(
    messages = messages.filterNot { it.messageId == messageId },
    inFlight = inFlight - messageId,
    delivered = delivered - messageId,
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
