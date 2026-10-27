package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.SecretRequestAnswerInput
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SecretRequestTest {
  private val directory: Path = Path.of(System.getProperty("t3code.scenarios"), "default")
  private val scenario: Scenario = Scenario.decode(
    directory.resolve("secret-requests.json").readText(),
  )
  private val request: TurnItem.SecretRequest = scenario.items.getValue("secret-demo")
    .map { it.item }.filterIsInstance<TurnItem.SecretRequest>().first()

  @Test
  fun `only local pending requests have a form`() {
    assertNull(secretRequestDisplay(request, "local"))
    listOf("inherited", "synthetic", "unknown").forEach { visibility ->
      assertEquals(
        "Waiting for an answer in the original thread",
        secretRequestDisplay(request, visibility),
      )
    }
    assertEquals("Saved securely and kept private",
      secretRequestDisplay(request.copy(secretStatus = "saved"), "local"))
    assertEquals("Declined",
      secretRequestDisplay(request.copy(secretStatus = "declined"), "local"))
    assertEquals("Request ended",
      secretRequestDisplay(request.copy(secretStatus = "cancelled"), "local"))
  }

  @Test
  fun `save trims the value and decline never carries a secret`() {
    assertNull(secretRequestAnswerInput(request, " \n "))
    val save: SecretRequestAnswerInput = assertNotNull(
      secretRequestAnswerInput(request, "  fixture-only-value \n"),
    )
    assertEquals(request.threadId, save.threadId)
    assertEquals(request.id, save.turnItemId)
    assertEquals("fixture-only-value", assertIs<SecretRequestAnswerInput.Answer.Save>(save.answer).secret)
    val decline: SecretRequestAnswerInput = assertNotNull(secretRequestAnswerInput(request, null))
    assertIs<SecretRequestAnswerInput.Answer.Decline>(decline.answer)
    assertFalse(T3Json.encodeToString(SecretRequestAnswerInput.serializer(), decline)
      .contains("\"secret\""))
  }

  @Test
  fun `answer updates only status without persisting the value`() = runTest {
    val client = FixtureClient(directory, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == request.threadId } }
    client.answerSecretRequest(assertNotNull(secretRequestAnswerInput(request, "fixture-secret")))
    val view: ThreadView = client.thread(request.threadId).first()
    val answered: TurnItem.SecretRequest = view.items.filterIsInstance<TurnItem.SecretRequest>()
      .first { it.id == request.id }
    assertEquals("saved", answered.secretStatus)
    assertEquals("completed", answered.status)
    assertFalse(T3Json.encodeToString(TurnItem.serializer(), answered).contains("fixture-secret"))
    assertFalse(view.messages.any { it.text.contains("fixture-secret") })
  }

  @Test
  fun `failed answer stays pending and can be retried`() = runTest {
    val client = FixtureClient(directory, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == request.threadId } }
    val retry: TurnItem.SecretRequest = request.copy(id = "secret-retry")
    val input: SecretRequestAnswerInput = assertNotNull(
      secretRequestAnswerInput(retry, "fixture-retry-secret"),
    )
    assertFailsWith<T3Exception> { client.answerSecretRequest(input) }
    val pending: ThreadView = client.thread(request.threadId).first()
    assertEquals("pending", pending.items.filterIsInstance<TurnItem.SecretRequest>()
      .first { it.id == retry.id }.secretStatus)
    client.answerSecretRequest(input)
    val answered: ThreadView = client.thread(request.threadId).first()
    assertEquals("saved", answered.items.filterIsInstance<TurnItem.SecretRequest>()
      .first { it.id == retry.id }.secretStatus)
  }

}
