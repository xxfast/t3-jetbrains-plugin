package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

class CommandJsonTest {
  @Test
  fun keepsTheTypeAndDropsNulls() {
    val pin = Command.ThreadPin(commandId = "c1", threadId = "t1")
    val encoded: String = T3Json.encodeToString(OrchestrationV2Command.serializer(), pin)
    assertEquals("""{"type":"thread.pin","commandId":"c1","threadId":"t1"}""", encoded)
  }

  @Test
  fun nestedUnionsKeepTheirTypeToo() {
    val dispatch = Command.MessageDispatch(
      createdBy = "user",
      creationSource = "web",
      commandId = "c1",
      threadId = "t1",
      messageId = "m1",
      text = "hi",
      attachments = emptyList(),
      modelSelection = ModelSelection(
        instanceId = JsonPrimitive("codex"),
        model = JsonPrimitive("gpt-5"),
      ),
      dispatchMode = Command.MessageDispatch.DispatchMode.QueueAfterActive(),
    )
    val encoded: String = T3Json.encodeToString(OrchestrationV2Command.serializer(), dispatch)
    assertEquals(
      """{"type":"message.dispatch","createdBy":"user","creationSource":"web",""" +
        """"commandId":"c1","threadId":"t1","messageId":"m1","text":"hi","attachments":[],""" +
        """"modelSelection":{"instanceId":"codex","model":"gpt-5"},""" +
        """"dispatchMode":{"type":"queue_after_active"}}""",
      encoded,
    )
  }

  @Test
  fun anOptionalDiscriminatorIsStillSent() {
    val image = AttachmentCreateUploadUrlInput.Image(
      name = "shot.png",
      mimeType = "image/png",
      sizeBytes = 3,
    )
    val encoded: String =
      T3Json.encodeToString(AttachmentCreateUploadUrlInput.serializer(), image)
    assertEquals(
      """{"type":"image","name":"shot.png","mimeType":"image/png","sizeBytes":3}""",
      encoded,
    )
  }
}
