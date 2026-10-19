package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ProviderApprovalOption
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class PendingRequestLogicTest {
  @Test
  fun `no options means upstream's four, and accept and decline are the buttons`() {
    val options: List<ProviderApprovalOption> = approvalOptions(null)
    assertEquals(listOf("cancel", "decline", "acceptForSession", "accept"), options.decisions())
    assertEquals(listOf("decline", "accept"), primaryOptions(options).decisions())
    assertEquals(listOf("cancel", "acceptForSession"), moreOptions(options).decisions())
    assertEquals(emptyList(), approvalOptions(emptyList()), "An empty list stays empty")

    val own: List<ProviderApprovalOption> = listOf(option("accept"), option("decline"))
    assertEquals(own, approvalOptions(own))
    assertEquals(emptyList(), moreOptions(own))
  }

  @Test
  fun `approval labels and detail by kind`() {
    assertEquals("Command approval", approvalLabel("command"))
    assertEquals("App access approval", approvalLabel("mcp-elicitation"))
    assertEquals("File read approval", approvalLabel("file-read"))
    assertEquals("App permission approval", approvalLabel("permission"))
    assertEquals("File change approval", approvalLabel("file-change"))

    val approval = PendingRequest.Approval("r", "command", null, null, emptyList(), 1, true, false)
    assertEquals("Command approval", approvalDetail(approval))
    assertEquals("ls", approvalDetail(approval.copy(detail = "ls")))
    assertEquals(
      "Provider process is gone. Interrupt or restart the run to respond.",
      approvalDetail(approval.copy(detail = "ls", resumable = false)),
    )
  }

  @Test
  fun `pending requests come from pending runtime requests, matched to their items`() {
    val requests: List<RuntimeRequest> = listOf(
      request("a1", "command", "pending"),
      request("a2", "command", "resolved"),
      request("q1", "user_input", "pending", capability = """{"type":"message"}"""),
      request("x1", "auth_refresh", "pending"),
      request("x2", "dynamic_tool_call", "pending"),
      request("a3", "file-change", "pending", capability = GONE),
      request("q2", "user_input", "pending"),
    )
    val items: List<TurnItem> = listOf(approvalItem("a1", prompt = "ls"), questionItem("q1"))

    val pending: PendingThreadRequests = derivePendingThreadRequests(requests, items)

    assertEquals(listOf("a1", "a3"), pending.approvals.map { it.requestId })
    assertEquals("ls", pending.approvals[0].detail)
    assertTrue(pending.approvals[0].resumable)
    assertNull(pending.approvals[1].detail, "An approval without its item still counts")
    assertFalse(pending.approvals[1].resumable)
    assertEquals(listOf("q1"), pending.userInputs.map { it.item.requestId }, "q2 has no item")
    assertTrue(pending.userInputs[0].dismissible)
  }

  @Test
  fun `request items no runtime request lists aren't pending, like upstream`() {
    val items: List<TurnItem> = listOf(approvalItem("a1"), questionItem("q1", status = "pending"))
    val pending: PendingThreadRequests = derivePendingThreadRequests(emptyList(), items)
    assertEquals(emptySet(), pending.requestIds)
  }

  @Test
  fun `an approval goes first, then the first question`() {
    val both = PendingThreadRequests(
      approvals = listOf(PendingApproval("a1", "command", null, null, null, true)),
      userInputs = listOf(PendingUserInput(questionItem("q1"), true, false)),
    )
    val approval: PendingRequest.Approval =
      assertIs(pendingRequest(both, setOf("a1"), emptyMap(), 0))
    assertTrue(approval.responding)
    assertEquals(DEFAULT_APPROVAL_OPTIONS, approval.options)

    val question: PendingRequest.UserInput =
      assertIs(pendingRequest(both.copy(approvals = emptyList()), emptySet(), emptyMap(), 9))
    assertEquals(1, question.questionIndex, "Clamped to the last question")
    assertFalse(question.responding)
    val nothing: PendingThreadRequests = PendingThreadRequests(emptyList(), emptyList())
    assertNull(pendingRequest(nothing, emptySet(), emptyMap(), 0))
  }

  @Test
  fun `an option answers with its value, else its label`() {
    val question: UserInputQuestion = single
    assertEquals(JsonPrimitive("v1"), answer(question, draft(listOf("v1"))))
    assertEquals(JsonPrimitive("Two"), answer(question, draft(listOf("Two"))))
    val byLabel: PendingUserInputDraftAnswer = draft(listOf("One"))
    assertNull(answer(question, byLabel), "Not the label when there's a value")
    assertNull(answer(question, null))
  }

  @Test
  fun `multi select sends a list, single select one string`() {
    assertEquals(
      JsonArray(listOf(JsonPrimitive("v1"), JsonPrimitive("Two"))),
      answer(multi, draft(listOf("v1", "Two", "v1", "nope"))),
    )
    assertEquals(JsonPrimitive("v1"), answer(single, draft(listOf("v1", "Two"))))
    assertNull(answer(multi, draft()))
  }

  @Test
  fun `a non-empty custom answer wins, trimmed, unless custom answers are off`() {
    val draft = draft(listOf("v1"), customAnswer = "  mine  ")
    assertEquals(JsonPrimitive("mine"), answer(single.copy(allowCustomAnswer = true), draft))
    assertEquals(JsonPrimitive("mine"), answer(multi, draft))
    assertEquals(JsonPrimitive("v1"), answer(single.copy(allowCustomAnswer = false), draft))
    assertEquals(JsonPrimitive("v1"), answer(single, draft.copy(customAnswer = "   ")))
  }

  @Test
  fun `with an initial answer the text is taken as typed, even emptied`() {
    val question: UserInputQuestion = single.copy(initialAnswer = "seed")
    val spaced: PendingUserInputDraftAnswer = draft(customAnswer = " seed ")
    assertEquals(JsonPrimitive(" seed "), answer(question, spaced))
    assertEquals(JsonPrimitive(""), answer(question, draft(customAnswer = "")))
  }

  @Test
  fun `answers are null until every question has one`() {
    val questions: List<UserInputQuestion> = listOf(single, multi.copy(id = "m"))
    assertNull(buildPendingUserInputAnswers(questions, mapOf("s" to draft(listOf("v1")))))
    assertEquals(
      JsonObject(
        mapOf("s" to JsonPrimitive("v1"), "m" to JsonArray(listOf(JsonPrimitive("Two")))),
      ),
      buildPendingUserInputAnswers(
        questions,
        mapOf("s" to draft(listOf("v1")), "m" to draft(listOf("Two"))),
      ),
    )
  }

  @Test
  fun `the initial answer seeds the draft when custom answers are allowed`() {
    val seeded: Map<String, PendingUserInputDraftAnswer> = seedUserInputDraftAnswers(
      listOf(
        single.copy(id = "a", initialAnswer = "x"),
        single.copy(id = "b", initialAnswer = "y", allowCustomAnswer = false),
        single.copy(id = "c"),
        single.copy(id = "d", initialAnswer = "z"),
      ),
      drafts = mapOf("d" to draft(listOf("v1"))),
    )
    assertEquals(
      mapOf("a" to draft(customAnswer = "x"), "d" to draft(listOf("v1"))),
      seeded,
    )
  }

  @Test
  fun `picking an option clears the custom answer, toggling only for multi select`() {
    val typed = draft(listOf("v1"), customAnswer = "mine")
    assertEquals(draft(listOf("Two"), ""), toggle(single, typed, "Two"))
    assertEquals(draft(listOf("v1", "Two"), ""), toggle(multi, typed, "Two"))
    assertEquals(draft(emptyList(), ""), toggle(multi, typed, "v1"))
  }

  @Test
  fun `typing drops the selection, a blank text keeps it, an echo changes nothing`() {
    val picked = draft(listOf("v1"), customAnswer = "")
    assertNull(custom(single, picked, ""))
    assertEquals(draft(emptyList(), "mine"), custom(single, picked, "mine"))
    assertEquals(draft(listOf("v1"), " "), custom(single, picked, " "))
    assertEquals(
      draft(emptyList(), " "),
      custom(single.copy(initialAnswer = "x"), picked, " "),
    )
  }

  @Test
  fun `advance moves on, then submits once every question has an answer`() {
    val item: TurnItem.UserInputRequest = questionItem("q1")
    val first = PendingRequest.UserInput(item, emptyMap(), 0, false, true, false)
    assertEquals(Advance.Next(1), advance(first), "Upstream moves on even unanswered")
    assertFalse(canAdvance(first))

    val last: PendingRequest.UserInput = first.copy(questionIndex = 1)
    assertEquals(Advance.Stay, advance(last))
    val answered: PendingRequest.UserInput = last.copy(
      drafts = mapOf("s" to draft(listOf("v1")), "m" to draft(listOf("Two"))),
    )
    assertIs<Advance.Submit>(advance(answered))
    assertTrue(canAdvance(answered))
    assertEquals(Advance.Stay, advance(answered.copy(resumable = false)))
  }

  @Test
  fun `zero questions is the last question, submitted as an empty object`() {
    val empty: TurnItem.UserInputRequest = questionItem("q1").copy(questions = emptyList())
    val userInput = PendingRequest.UserInput(empty, emptyMap(), 0, false, true, false)
    assertNull(userInput.activeQuestion)
    assertTrue(userInput.isLastQuestion)
    assertEquals(Advance.Submit(JsonObject(emptyMap())), advance(userInput))
    assertTrue(nextEnabled(userInput))
  }

  @Test
  fun `next is enabled like the button, answered to move on, all answered to submit`() {
    val item: TurnItem.UserInputRequest = questionItem("q1")
    val first = PendingRequest.UserInput(item, emptyMap(), 0, false, true, false)
    assertFalse(nextEnabled(first), "Unanswered, so Enter doesn't skip it")
    val answered: Map<String, PendingUserInputDraftAnswer> =
      mapOf("s" to draft(listOf("v1")))
    assertTrue(nextEnabled(first.copy(drafts = answered)))
    assertFalse(nextEnabled(first.copy(drafts = answered, questionIndex = 1)), "m unanswered")
  }

  @Test
  fun `the commands are upstream's`() {
    val approve: Command.RuntimeRequestRespond = approvalResponse("t", "r", "accept", "c")
    assertEquals("runtime-request.respond", approve.type)
    assertEquals("accept", approve.decision)
    assertNull(approve.answers)

    val answers = JsonObject(mapOf("s" to JsonPrimitive("v1")))
    val answer: Command.RuntimeRequestRespond = answersResponse("t", "r", answers, "c")
    assertEquals(answers, answer.answers)
    assertNull(answer.decision)

    val dismiss: Command.ThreadUserInputDismiss = assertIs(dismissal("t", "r", "c"))
    assertEquals("thread.user-input.dismiss", dismiss.type)
    assertEquals("r", dismiss.requestId)
  }

  @Test
  fun `cards wait on you while the request is pending, else show what was decided`() {
    val requests: Map<String, RuntimeRequest> =
      listOf(request("a1", "command", "pending"), request("a2", "command", "resolved"))
        .associateBy { it.id }
    assertTrue(awaitingResponse("a1", "completed", requests))
    assertFalse(awaitingResponse("a2", "waiting", requests))
    assertTrue(awaitingResponse("a9", "waiting", requests), "Unlisted: the item's status")
    assertFalse(awaitingResponse("a9", "completed", requests))

    assertEquals("Always allow this session", decisionLabel("acceptForSession", null))
    assertEquals("Run it", decisionLabel("accept", listOf(option("accept", "Run it"))))
    assertEquals("cancel", decisionLabel("cancel", listOf(option("accept"))))
    assertEquals("Resolved", decisionLabel(null, null))
  }

  @Test
  fun `answers read back as labels, a dismissal as dismissed`() {
    val answers = JsonObject(
      mapOf(
        "s" to JsonPrimitive("v1"),
        "m" to JsonArray(listOf(JsonPrimitive("v1"), JsonPrimitive("custom"))),
      ),
    )
    assertEquals(
      listOf("Single: One", "Multi: One, custom"),
      answerLines(listOf(single, multi.copy(id = "m")), answers),
    )
    assertEquals(listOf("Dismissed"), answerLines(listOf(single), JsonObject(emptyMap())))
    assertEquals(listOf("Dismissed"), answerLines(listOf(single), null))
  }
}

private const val GONE: String = """{"type":"not_resumable","reason":"gone"}"""

private val single: UserInputQuestion = UserInputQuestion(
  id = "s",
  header = "Single",
  question = "Pick one",
  options = listOf(
    UserInputQuestion.Option(label = "One", description = "First", value = "v1"),
    UserInputQuestion.Option(label = "Two", description = "Second"),
  ),
)

private val multi: UserInputQuestion = single.copy(id = "m", header = "Multi", multiSelect = true)

private fun option(decision: String, label: String = decision): ProviderApprovalOption =
  ProviderApprovalOption(decision = decision, label = label)

private fun List<ProviderApprovalOption>.decisions(): List<String> = map { it.decision }

private fun request(
  id: String,
  kind: String,
  status: String,
  capability: String = """{"type":"live","providerSessionId":"s1"}""",
): RuntimeRequest = T3Json.decodeFromString(
  RuntimeRequest.serializer(),
  """
  {"id":"$id","nodeId":"n1","providerTurnId":null,"nativeRequestRef":null,"kind":"$kind",
   "status":"$status","responseCapability":$capability,"createdAt":"2026-10-20T10:00:00Z",
   "resolvedAt":null}
  """,
)

private fun approvalItem(
  requestId: String,
  prompt: String? = null,
  status: String = "waiting",
): TurnItem.ApprovalRequest = T3Json.decodeFromString(
  TurnItem.serializer(),
  """
  {"type":"approval_request","id":"item_$requestId","threadId":"t1","runId":"r1","ordinal":1,
   "status":"$status","updatedAt":"2026-10-20T10:00:00Z","requestId":"$requestId",
   "requestKind":"command"${if (prompt != null) ""","prompt":"$prompt"""" else ""}}
  """,
) as TurnItem.ApprovalRequest

private fun questionItem(requestId: String, status: String = "waiting"): TurnItem.UserInputRequest =
  T3Json.decodeFromString(
    TurnItem.serializer(),
    """
    {"type":"user_input_request","id":"item_$requestId","threadId":"t1","runId":"r1",
     "ordinal":1,"status":"$status","updatedAt":"2026-10-20T10:00:00Z",
     "requestId":"$requestId","questions":[]}
    """,
  ).let { item ->
    (item as TurnItem.UserInputRequest).copy(questions = listOf(single, multi))
  }

// Short names for the ported functions, so the expectations fit on a line
private fun draft(
  selected: List<String> = emptyList(),
  customAnswer: String? = null,
): PendingUserInputDraftAnswer = PendingUserInputDraftAnswer(selected, customAnswer)

private fun answer(question: UserInputQuestion, draft: PendingUserInputDraftAnswer?): JsonElement? =
  resolvePendingUserInputAnswer(question, draft)

private fun toggle(
  question: UserInputQuestion,
  draft: PendingUserInputDraftAnswer?,
  value: String,
): PendingUserInputDraftAnswer =
  togglePendingUserInputOptionSelection(question, draft, value)

private fun custom(
  question: UserInputQuestion,
  draft: PendingUserInputDraftAnswer?,
  text: String,
): PendingUserInputDraftAnswer? =
  setPendingUserInputCustomAnswer(question, draft, text)
