package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.SecretRequestAnswerInput
import io.github.xxfast.t3code.protocol.generated.TurnItem

const val SECRET_REQUEST_PRIVACY_NOTE: String = "Stored securely, never shown to the agent"
const val SECRET_REQUEST_FAILURE: String = "Could not answer the request. Try again."

/** `secretRequestDisplay`, client-runtime/src/secretRequest.ts, at v0.1.2. */
fun secretRequestDisplay(item: TurnItem.SecretRequest, visibility: String): String? =
  when (item.secretStatus) {
    "pending" -> if (visibility == "local") null
      else "Waiting for an answer in the original thread"
    "saved" -> "Saved securely and kept private"
    "declined" -> "Declined"
    else -> "Request ended"
  }

/** `secretRequestAnswerInput`, client-runtime/src/secretRequest.ts. Null means an empty value. */
fun secretRequestAnswerInput(
  item: TurnItem.SecretRequest,
  secret: String?,
): SecretRequestAnswerInput? {
  val answer: SecretRequestAnswerInput.Answer = if (secret == null) {
    SecretRequestAnswerInput.Answer.Decline()
  } else {
    val trimmed: String = secret.trim()
    if (trimmed.isEmpty()) return null
    SecretRequestAnswerInput.Answer.Save(secret = trimmed)
  }
  return SecretRequestAnswerInput(threadId = item.threadId, turnItemId = item.id, answer = answer)
}
