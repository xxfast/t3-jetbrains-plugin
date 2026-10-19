package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.nextModelSelection
import io.github.xxfast.t3code.plugin.ui.optimisticMessage
import io.github.xxfast.t3code.plugin.ui.setThreadRuntimeMode
import io.github.xxfast.t3code.plugin.ui.updateThreadModelSelection
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PendingMessagesTest {
  @Test
  fun `a send is pending and in flight until the server takes it`() {
    val sent: PendingMessages = PendingMessages(error = "old").sent(message("m1"))
    assertEquals(listOf("m1"), sent.messages.map { it.messageId })
    assertTrue(sent.sending)
    assertNull(sent.error)

    val delivered: PendingMessages = sent.delivered("m1", sequence = 42)
    assertFalse(delivered.sending)
    assertEquals(mapOf("m1" to 42L), delivered.delivered)
    assertEquals(listOf("m1"), delivered.messages.map { it.messageId })
  }

  @Test
  fun `the server's item reconciles the pending one by message id`() {
    val pending: PendingMessages = PendingMessages().sent(message("m1")).sent(message("m2"))
    val items: List<TurnItem> = listOf(message("m1").copy(id = "item_m1"))
    val reconciled: PendingMessages = pending.reconciled(items, emptyList(), sequence = 0)
    assertEquals(listOf("m2"), reconciled.messages.map { it.messageId })
    assertTrue(reconciled.sending)
  }

  @Test
  fun `a queued message reconciles by the projection's user message`() {
    val pending: PendingMessages = PendingMessages()
      .sent(message("m1"))
      .sent(message("m2"))
      .delivered("m2", sequence = 50)
    val reconciled: PendingMessages =
      pending.reconciled(emptyList(), listOf(conversation("m2", "user")), sequence = 10)
    assertEquals(listOf("m1"), reconciled.messages.map { it.messageId })
    assertEquals(emptyMap(), reconciled.delivered)
  }

  @Test
  fun `a delivered message waits until the view reaches its sequence`() {
    val pending: PendingMessages = PendingMessages().sent(message("m1")).delivered("m1", 42)
    // An unrelated event between the dispatch's answer and the message's own event
    assertSame(pending, pending.reconciled(emptyList(), emptyList(), sequence = 41))
  }

  @Test
  fun `a delivered message the view at its sequence doesn't carry is dropped`() {
    val pending: PendingMessages = PendingMessages()
      .sent(message("m1"))
      .sent(message("m2"))
      .delivered("m1", sequence = 42)
    val reconciled: PendingMessages = pending.reconciled(emptyList(), emptyList(), sequence = 42)
    assertEquals(listOf("m2"), reconciled.messages.map { it.messageId })
    assertEquals(emptyMap(), reconciled.delivered)
    assertEquals(setOf("m2"), reconciled.inFlight)
  }

  @Test
  fun `in flight messages wait however far the view goes`() {
    val pending: PendingMessages = PendingMessages().sent(message("m1"))
    assertSame(pending, pending.reconciled(listOf(message("m9")), emptyList(), sequence = 999))
  }

  @Test
  fun `an assistant message with the same id doesn't count`() {
    val pending: PendingMessages = PendingMessages().sent(message("m1"))
    val conversation: List<ConversationMessage> = listOf(conversation("m1", "assistant"))
    assertSame(pending, pending.reconciled(emptyList(), conversation, sequence = 0))
  }

  @Test
  fun `an answer responds until its request stops being pending`() {
    val responding: PendingMessages = PendingMessages(error = "old").respondingTo("r1")
    assertEquals(setOf("r1"), responding.responding)
    assertNull(responding.error)

    val still: PendingMessages =
      responding.reconciled(emptyList(), emptyList(), sequence = 0, pendingRequests = setOf("r1"))
    assertSame(responding, still)

    val resolved: PendingMessages =
      responding.reconciled(emptyList(), emptyList(), sequence = 0, pendingRequests = setOf("r2"))
    assertEquals(emptySet(), resolved.responding)
  }

  @Test
  fun `a refused answer stops responding and says why`() {
    val failed: PendingMessages = PendingMessages()
      .respondingTo("r1")
      .respondingTo("r2")
      .respondFailed("r1", "Couldn't respond: nope")
    assertEquals(setOf("r2"), failed.responding)
    assertEquals("Couldn't respond: nope", failed.error)
  }

  @Test
  fun `pruning answers still reconciles messages`() {
    val pending: PendingMessages = PendingMessages()
      .sent(message("m1"))
      .delivered("m1", sequence = 5)
      .respondingTo("r1")
    val reconciled: PendingMessages = pending.reconciled(emptyList(), emptyList(), sequence = 5)
    assertEquals(emptyList(), reconciled.messages)
    assertEquals(emptySet(), reconciled.responding)
  }

  @Test
  fun `a model drafted mid-run rides on the next message and goes once it's delivered`() {
    val drafted: PendingMessages = PendingMessages().draftedModel(OPUS)
    val sent: PendingMessages = drafted.sent(message("m1"), model = OPUS)
    assertEquals("m1", sent.modelDraftCarrier)
    assertEquals(OPUS, sent.modelDraft, "Still the pick until the server has it")
    val delivered: PendingMessages = sent.delivered("m1", sequence = 4)
    assertNull(delivered.modelDraft)
    assertNull(delivered.modelDraftCarrier)
  }

  @Test
  fun `a refused send keeps the draft for the next one`() {
    val sent: PendingMessages = PendingMessages().draftedModel(OPUS).sent(message("m1"), OPUS)
    val failed: PendingMessages = sent.failed("m1", "nope")
    assertEquals(OPUS, failed.modelDraft)
    assertNull(failed.modelDraftCarrier)
  }

  @Test
  fun `a newer draft isn't dropped by an older send`() {
    val sonnet: ModelSelection = nextModelSelection(null, "claude", "sonnet")
    val sent: PendingMessages = PendingMessages().draftedModel(OPUS).sent(message("m1"), OPUS)
    val redrafted: PendingMessages = sent.draftedModel(sonnet).delivered("m1", sequence = 4)
    assertEquals(sonnet, redrafted.modelDraft)
  }

  @Test
  fun `a model set while idle replaces the draft`() {
    val set: Command = updateThreadModelSelection("t1", OPUS, "codex", true, "c1")
    val picked: PendingMessages = PendingMessages().draftedModel(OPUS).picked(set)
    assertNull(picked.modelDraft)
    val mode: Command = setThreadRuntimeMode("t1", "auto", "c2")
    assertEquals(OPUS, PendingMessages().draftedModel(OPUS).picked(mode).modelDraft)
  }

  @Test
  fun `a refused send leaves and says why`() {
    val failed: PendingMessages = PendingMessages()
      .sent(message("m1"))
      .sent(message("m2"))
      .failed("m1", "Couldn't send: nope")
    assertEquals(listOf("m2"), failed.messages.map { it.messageId })
    assertEquals(setOf("m2"), failed.inFlight)
    assertEquals("Couldn't send: nope", failed.error)
  }
}

private val OPUS: ModelSelection = nextModelSelection(null, "claude", "opus")

private fun message(messageId: String): TurnItem.UserMessage =
  optimisticMessage("t1", messageId, "text", Instant.EPOCH, emptyList())

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
  createdAt = "1970-01-01T00:00:00Z",
  updatedAt = "1970-01-01T00:00:00Z",
)
