package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.deliveredIds
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.generated.TurnItem

/**
 * One thread's outbox: messages sent but not yet acknowledged by the server, which of those are
 * still being dispatched ([inFlight]), the server's sequence for each one it took ([delivered]),
 * and why the last send failed. Pure, so [ChatController] stays a thin shell.
 */
data class PendingMessages(
  val messages: List<TurnItem.UserMessage> = emptyList(),
  val inFlight: Set<String> = emptySet(),
  val delivered: Map<String, Long> = emptyMap(),
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

  /**
   * Retires what the server acknowledged by `messageId`, in [items] or [conversation]. A message
   * also goes once the view's [sequence] reaches the one its dispatch answered and the view
   * still doesn't carry it: the server took it and it's gone since, like a queued message
   * cancelled before its run started. Before that, it waits.
   */
  fun reconciled(
    items: List<TurnItem>,
    conversation: List<ConversationMessage>,
    sequence: Long,
  ): PendingMessages {
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
