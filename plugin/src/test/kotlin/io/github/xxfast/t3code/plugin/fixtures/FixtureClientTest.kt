package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.plugin.ui.PendingUserInputDraftAnswer
import io.github.xxfast.t3code.plugin.ui.PendingApproval
import io.github.xxfast.t3code.plugin.ui.PendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.PendingUserInput
import io.github.xxfast.t3code.plugin.ui.answerLines
import io.github.xxfast.t3code.plugin.ui.answersResponse
import io.github.xxfast.t3code.plugin.ui.derivePendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.dismissal
import io.github.xxfast.t3code.plugin.ui.instance
import io.github.xxfast.t3code.plugin.ui.nextModelSelection
import io.github.xxfast.t3code.plugin.ui.setThreadInteractionMode
import io.github.xxfast.t3code.plugin.ui.setThreadRuntimeMode
import io.github.xxfast.t3code.plugin.ui.updateThreadModelSelection
import io.github.xxfast.t3code.plugin.ui.seedUserInputDraftAnswers
import io.github.xxfast.t3code.plugin.ui.slug
import io.github.xxfast.t3code.plugin.ui.buildPendingUserInputAnswers
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
import io.github.xxfast.t3code.protocol.has
import io.github.xxfast.t3code.protocol.pinned
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FixtureClientTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val default: Path = scenarios.resolve("default")

  @Test
  fun `every file in every scenario decodes`() {
    val folders: List<Path> = list(scenarios).filter { it.isDirectory() }
    assertEquals(
      listOf("auth-error", "default", "disconnected", "empty-server", "pairing", "read-only"),
      folders.map { it.name },
    )
    folders.forEach { folder ->
      val parts: List<Scenario> = jsonFiles(folder).map { file ->
        val scenario: Scenario = Scenario.decode(file.readText())
        // A known item missing a field decodes as Unknown rather than failing, so check for it
        val unknown: List<String> = scenario.items.values.flatten()
          .map { it.item }
          .filterIsInstance<TurnItem.Unknown>()
          .map { it.type }
        assertTrue(unknown.all { it == "some_future_type" }, "$folder/${file.name}: $unknown")
        scenario
      }
      assertTrue(parts.isNotEmpty(), "$folder is empty")
      assertTrue(Scenario.merge(parts).connection !is ConnectionState.Error, "$folder failed")
    }
  }

  @Test
  fun `default carries the list and every demo thread, connected`() {
    val parts: List<Scenario> = jsonFiles(default).map { Scenario.decode(it.readText()) }
    val scenario: Scenario = Scenario.merge(parts)
    val connected: ConnectionState.Connected = assertIs(scenario.connection)
    assertTrue(connected.session.has("orchestration:operate"), "Thread actions work in default")
    assertEquals(24, scenario.threads.size)
    val demos: Set<String> = scenario.threads
      .filter { it.title.startsWith("Demo: ") }
      .map { it.id }
      .toSet()
    assertEquals(setOf(THREAD, WAITING, QUESTION, UNKNOWN, MANY), demos)
  }

  @Test
  fun `connection scenarios list the same threads as default, byte for byte`() {
    val expected: ByteArray = default.resolve("list.json").readBytes()
    listOf("disconnected", "pairing", "auth-error", "read-only").forEach { name ->
      val copy: ByteArray = scenarios.resolve(name).resolve("list.json").readBytes()
      assertContentEquals(expected, copy, "$name/list.json drifted from default/list.json")
    }
  }

  @Test
  fun `default can operate tasks, read-only can only read`() {
    fun session(name: String): ServerSession {
      val folder: Path = scenarios.resolve(name)
      val parts: List<Scenario> = jsonFiles(folder).map { Scenario.decode(it.readText()) }
      return assertIs<ConnectionState.Connected>(Scenario.merge(parts).connection).session
    }

    assertTrue(session("default").has("orchestration:operate"))
    assertFalse(session("read-only").has("orchestration:operate"))
    assertTrue(session("read-only").has("orchestration:read"))
  }

  @Test
  fun `unknown item types keep their raw json`() {
    val text: String = default.resolve("unknown-items.json").readText()
    val items: List<FixtureItem> = Scenario.decode(text).items.values.single()
    assertIs<TurnItem.WebSearch>(items[1].item)
    assertIs<TurnItem.TodoList>(items[2].item)
    val unknown: TurnItem.Unknown =
      assertIs(items.map { it.item }.single { it is TurnItem.Unknown })
    assertEquals("some_future_type", unknown.type)
    assertTrue("hologram" in unknown.raw)
  }

  @Test
  fun `many items has a few hundred`() {
    val text: String = default.resolve("many-items.json").readText()
    assertTrue(Scenario.decode(text).items.values.single().size >= 300)
  }

  @Test
  fun `files merge, threads dedupe by id and items append`(@TempDir directory: Path) = runTest {
    val threads: JsonArray = listJson()["threads"]!!.jsonArray
    val first: JsonObject = threads[0].jsonObject
    val second: JsonObject = threads[1].jsonObject
    val id: String = first.getValue("id").jsonPrimitive.content
    val items: List<JsonElement> = demoItems()
    directory.resolve("a.json").writeText(
      file(
        "threads" to JsonArray(listOf(retitled(first, "from a"))),
        "items" to JsonObject(mapOf(id to JsonArray(listOf(items[0])))),
      ),
    )
    directory.resolve("b.json").writeText(
      file(
        "threads" to JsonArray(listOf(retitled(first, "from b"), second)),
        "items" to JsonObject(mapOf(id to JsonArray(listOf(items[1])))),
      ),
    )

    val client = FixtureClient(directory, backgroundScope)
    val merged: List<String> = client.threads.first { it.isNotEmpty() }.map { it.title }

    assertEquals(listOf("from a", second.getValue("title").jsonPrimitive.content), merged)
    val view: ThreadView = client.thread(id).first()
    val expected: List<String> = items.take(2)
      .map { it.jsonObject.getValue("id").jsonPrimitive.content }
    assertEquals(expected, view.items.map { it.id })
  }

  @Test
  fun `the last file that sets a connection wins`(@TempDir directory: Path) = runTest {
    directory.resolve("a.json").writeText("""{ "connection": { "state": "authError" } }""")
    directory.resolve("b.json").writeText("""{ "connection": { "state": "unpaired" } }""")
    directory.resolve("c.json").writeText("""{ "threads": [] }""")

    val client = FixtureClient(directory, backgroundScope)

    val connection: ConnectionState = client.connection.first { it != ConnectionState.Connecting }
    assertEquals(ConnectionState.Unpaired, connection)
  }

  @Test
  fun `no file setting a connection means connected`(@TempDir directory: Path) = runTest {
    directory.resolve("a.json").writeText("""{ "threads": [] }""")

    val client = FixtureClient(directory, backgroundScope)

    val connection: ConnectionState = client.connection.first { it != ConnectionState.Connecting }
    assertIs<ConnectionState.Connected>(connection)
  }

  @Test
  fun `running thread replays the assistant message growing`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == THREAD } }

    val views: List<ThreadView> = client.thread(THREAD)
      .transformWhile { view ->
        emit(view)
        view.items.none { it.id == "a2" }
      }
      .toList()

    val texts: List<String?> = views
      .map { view -> view.items.find { it.id == "a1" } as TurnItem.AssistantMessage? }
      .map { it?.text }
      .distinct()
    assertEquals(
      listOf(
        null,
        "Looking at",
        "Looking at the version catalog first,",
        "Looking at the version catalog first, then the `Source` call sites",
        "Looking at the version catalog first, then the `Source` call sites in `:kstore-file`.",
      ),
      texts,
    )
    assertEquals(listOf("u1", "a1", "c1", "f1", "a2"), views.last().items.map { it.id })
    assertIs<TurnItem.FileChange>(views.last().items[3])
  }

  @Test
  fun `message dispatch appends the user message, then streams a reply`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }

    val at: Long = client.dispatch(message(IDLE, messageId = "m1", text = "hello"))

    val sent: ThreadView = client.thread(IDLE).first { view -> view.items.isNotEmpty() }
    assertTrue(sent.sequence >= at, "The view is at or past the dispatch")
    val user: TurnItem.UserMessage = assertIs(sent.items.single())
    assertEquals("hello", user.text)
    assertEquals("m1", user.messageId)
    assertEquals(listOf("m1"), sent.messages.map { it.id })
    assertEquals("user", sent.messages.single().role)
    assertEquals("running", sent.thread.status)
    assertEquals("running", sent.runs.single().status)

    val streaming: TurnItem.AssistantMessage = client.thread(IDLE)
      .first { view -> view.items.size == 2 }
      .items[1] as TurnItem.AssistantMessage
    assertTrue(streaming.streaming)

    val done: ThreadView = client.thread(IDLE).first { view -> view.thread.status == "idle" }
    val reply: TurnItem.AssistantMessage = assertIs(done.items.last())
    assertEquals("Fixture reply to: hello", reply.text)
    assertFalse(reply.streaming)
    assertEquals("completed", done.runs.single().status)
    client.threads.first { row(it).status == "idle" }
  }

  @Test
  fun `interrupting cancels the reply in flight`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    client.dispatch(message(IDLE, messageId = "m1", text = "hello"))
    client.thread(IDLE).first { view -> view.items.size == 2 }

    client.dispatch(Command.RunInterrupt(commandId = "cmd_stop", threadId = IDLE, runId = RUN))
    delay(2_000)

    val view: ThreadView = client.thread(IDLE).first()
    val reply: TurnItem.AssistantMessage = assertIs(view.items[1])
    assertEquals("cancelled", reply.status)
    assertFalse(reply.streaming)
    assertEquals("Fixture rep", reply.text, "The second half never arrives")
    assertEquals("interrupted", view.runs.single().status)
    assertEquals("idle", view.thread.status)
  }

  @Test
  fun `concurrent dispatches get their own ordinals`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }

    withContext(Dispatchers.Default) {
      (1..20)
        .map { index -> async { client.dispatch(message(IDLE, "m$index", "hi $index")) } }
        .awaitAll()
    }

    val view: ThreadView = client.thread(IDLE).first()
    val users: List<TurnItem> = view.items.filterIsInstance<TurnItem.UserMessage>()
    assertEquals(20, users.size)
    assertEquals(20, users.map { it.ordinal }.toSet().size)
    assertEquals(20, view.runs.map { it.ordinal }.toSet().size)
  }

  @Test
  fun `pin follows upstream's rules`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val before: List<Thread> = client.threads.first { threads -> threads.any { it.id == IDLE } }
    val pinned: Thread = before.single { it.id == PINNED }
    val settled: Thread = before.single { it.id == SETTLED }

    client.dispatch(Command.ThreadPin(commandId = "cmd_pin_again", threadId = PINNED))
    client.dispatch(Command.ThreadPin(commandId = "cmd_pin_settled", threadId = SETTLED))

    val after: List<Thread> = client.threads.first { threads ->
      threads.single { it.id == SETTLED }.pinned
    }
    assertEquals(pinned, after.single { it.id == PINNED }, "Already pinned, nothing to promote")
    val promoted: Thread = after.single { it.id == SETTLED }
    assertEquals("active", promoted.settledOverride)
    assertNull(promoted.settledAt)
    assertTrue(promoted.updatedAt > settled.updatedAt)
  }

  @Test
  fun `responding resolves the request and the thread runs on`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == WAITING } }
    val before: ThreadView = client.thread(WAITING).first()
    assertEquals("pending", before.runtimeRequests.single { it.id == REQUEST }.status)
    val pendingApproval: PendingApproval =
      derivePendingThreadRequests(before.runtimeRequests, before.items).approvals.single()
    assertTrue(pendingApproval.resumable, "Fixture approvals are live, so they can be answered")

    val respond = Command.RuntimeRequestRespond(
      commandId = "cmd_respond",
      threadId = WAITING,
      requestId = REQUEST,
      decision = "accept",
    )
    client.dispatch(respond)

    val after: ThreadView = client.thread(WAITING).first()
    val approval: TurnItem.ApprovalRequest =
      assertIs(after.items.single { it is TurnItem.ApprovalRequest })
    assertEquals("completed", approval.status)
    assertEquals("resolved", after.runtimeRequests.single { it.id == REQUEST }.status)
    assertEquals("running", after.thread.status)
    assertNull(after.thread.pendingRuntimeRequest)
  }

  @Test
  fun `the question is pending, then answering resolves it with the answers`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == QUESTION } }
    val before: ThreadView = client.thread(QUESTION).first()
    val pending: PendingThreadRequests =
      derivePendingThreadRequests(before.runtimeRequests, before.items)
    val input: PendingUserInput = pending.userInputs.single()
    assertEquals(listOf("targets", "name"), input.item.questions.map { it.id })
    assertTrue(input.dismissible, "The fixture's requests are message mode")
    assertEquals(emptyList(), pending.approvals)

    val questions: List<UserInputQuestion> = input.item.questions
    val picked = PendingUserInputDraftAnswer(selected = listOf("android", "Desktop"))
    val drafts: Map<String, PendingUserInputDraftAnswer> =
      seedUserInputDraftAnswers(questions) + ("targets" to picked)
    val answers: JsonObject = assertNotNull(buildPendingUserInputAnswers(questions, drafts))
    client.dispatch(answersResponse(QUESTION, QUESTION_REQUEST, answers, "cmd_answer"))

    val after: ThreadView = client.thread(QUESTION).first()
    val request: RuntimeRequest = after.runtimeRequests.single { it.id == QUESTION_REQUEST }
    assertEquals("resolved", request.status)
    assertEquals(
      listOf("Targets: Android, Desktop", "Module name: sample-nested"),
      answerLines(input.item.questions, request.answers),
    )
    assertEquals("running", after.thread.status)
  }

  @Test
  fun `dismissing a question resolves it without answers`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == QUESTION } }

    client.dispatch(dismissal(QUESTION, QUESTION_REQUEST, "cmd_dismiss"))

    val after: ThreadView = client.thread(QUESTION).first()
    val item: TurnItem.UserInputRequest =
      assertIs(after.items.single { it is TurnItem.UserInputRequest })
    assertEquals("completed", item.status)
    val pending: PendingThreadRequests =
      derivePendingThreadRequests(after.runtimeRequests, after.items)
    assertEquals(emptySet(), pending.requestIds)
    val answers: JsonObject? = after.runtimeRequests.single().answers
    assertEquals(listOf("Dismissed"), answerLines(item.questions, answers))
  }

  @Test
  fun `the model and both modes reach the thread the view shows`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    val providers: List<ServerProvider> = client.serverInfo().providers
    assertEquals(true, providers.single { it.instanceId == "codex" }.showInteractionModeToggle)
    assertEquals(false, providers.single { it.instanceId == "claude" }.showInteractionModeToggle)
    val before: ThreadView = client.thread(IDLE).first()

    client.dispatch(setThreadRuntimeMode(IDLE, "full-access", "c1"))
    client.dispatch(setThreadInteractionMode(IDLE, "plan", "c2"))
    val opus: ModelSelection = nextModelSelection(before.thread.modelSelection, "claude", "opus")
    client.dispatch(updateThreadModelSelection(IDLE, opus, "codex", true, "c3"))

    val after: ThreadView = client.thread(IDLE).first()
    assertEquals("full-access", after.thread.runtimeMode)
    assertEquals("plan", after.thread.interactionMode)
    assertEquals("claude", after.thread.modelSelection.instance)
    assertEquals("opus", after.thread.modelSelection.slug)
    assertEquals("claude", after.thread.providerInstanceId, "The row moves to the instance")
  }

  @Test
  fun `a provider switch moves the row too`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    val opus: ModelSelection = nextModelSelection(null, "claude", "opus")
    val command: Command = updateThreadModelSelection(IDLE, opus, "codex", false, "c1")
    assertIs<Command.ProviderSwitch>(command)

    client.dispatch(command)

    val after: ThreadView = client.thread(IDLE).first()
    assertEquals("claude", after.thread.providerInstanceId)
    assertEquals("opus", after.thread.modelSelection.slug)
  }

  @Test
  fun `pin and unpin flip the row`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    assertFalse(row(client.threads.value).pinned)

    client.dispatch(Command.ThreadPin(commandId = "cmd_pin", threadId = IDLE))
    client.threads.first { row(it).pinned }

    client.dispatch(Command.ThreadUnpin(commandId = "cmd_unpin", threadId = IDLE))
    client.threads.first { !row(it).pinned }
  }

  @Test
  fun `the terminal echoes what's written`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val events: MutableList<TerminalAttachStreamEvent> = mutableListOf()
    backgroundScope.launch {
      client.terminal(TerminalAttachInput(threadId = IDLE, terminalId = "t1")).toList(events)
    }
    runCurrent()

    client.terminalWrite(IDLE, "t1", "ls\r")
    runCurrent()

    val snapshot: TerminalAttachStreamEvent.Snapshot = assertIs(events[0])
    assertEquals("fixture shell\r\n$ ", snapshot.snapshot.history)
    val output: TerminalAttachStreamEvent.Output = assertIs(events[1])
    assertEquals("ls\r\n$ ", output.data)
  }

  @Test
  fun `closing the terminal tells the attached and the observers`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val attached: MutableList<TerminalAttachStreamEvent> = mutableListOf()
    val observed: MutableList<TerminalAttachStreamEvent> = mutableListOf()
    backgroundScope.launch {
      client.terminal(TerminalAttachInput(threadId = IDLE, terminalId = "t1")).toList(attached)
    }
    backgroundScope.launch { client.terminalObserve(IDLE, "t1").toList(observed) }
    runCurrent()

    client.terminalClose(IDLE, "t1", deleteHistory = true)
    runCurrent()

    assertIs<TerminalAttachStreamEvent.Closed>(attached.last())
    assertIs<TerminalAttachStreamEvent.Closed>(observed.last())
  }

  @Test
  fun `reloading the directory drops what was dispatched`(@TempDir directory: Path) = runTest {
    val list: Path = directory.resolve("list.json")
    list.writeText(default.resolve("list.json").readText())
    val client = FixtureClient(directory, backgroundScope, pollInterval = 20.milliseconds)
    client.threads.first { threads -> threads.any { it.id == IDLE } }

    client.dispatch(Command.ThreadPin(commandId = "cmd_pin", threadId = IDLE))
    client.threads.first { row(it).pinned }
    list.setLastModifiedTime(FileTime.fromMillis(list.getLastModifiedTime().toMillis() + 5_000))

    client.threads.first { !row(it).pinned }
  }

  @Test
  fun `bad file surfaces as an error state`() {
    val scenario: Scenario = try {
      Scenario.decode("""{ "connection": { "state": "sideways" } }""")
    } catch (e: Exception) {
      Scenario.failed("broken.json", e.message.orEmpty())
    }
    assertIs<ConnectionState.Error>(scenario.connection)
  }

  private fun row(threads: List<Thread>): Thread = threads.single { it.id == IDLE }

  private fun message(threadId: String, messageId: String, text: String) =
    Command.MessageDispatch(
      createdBy = "user",
      creationSource = "web",
      commandId = "cmd_$messageId",
      threadId = threadId,
      messageId = messageId,
      text = text,
      attachments = emptyList(),
      dispatchMode = Command.MessageDispatch.DispatchMode.StartImmediately(),
    )

  private fun list(directory: Path): List<Path> =
    Files.list(directory).use { stream -> stream.toList().sortedBy { it.name } }

  private fun jsonFiles(directory: Path): List<Path> =
    list(directory).filter { it.extension == "json" }

  private fun listJson(): JsonObject =
    T3Json.parseToJsonElement(default.resolve("list.json").readText()).jsonObject

  /** The running demo's items without their `after` delays, so they all show up at once. */
  private fun demoItems(): List<JsonElement> =
    T3Json.parseToJsonElement(default.resolve("running.json").readText())
      .jsonObject.getValue("items").jsonObject.getValue(THREAD).jsonArray
      .map { JsonObject(it.jsonObject - "after") }

  private fun retitled(thread: JsonObject, title: String): JsonObject =
    JsonObject(thread + ("title" to JsonPrimitive(title)))

  private fun file(vararg entries: Pair<String, JsonElement>): String =
    JsonObject(mapOf(*entries)).toString()

  private companion object {
    const val THREAD = "thr_01JC4A1F0QWMIGRATE"
    const val WAITING = "thr_01JC49ZK2HWAITING"
    const val UNKNOWN = "thr_01JC4C0X1UNKNOWN"
    const val MANY = "thr_01JC5D0M4NYITEMS"
    const val IDLE = "thr_01JC3QX81VIDLE001"
    const val PINNED = "thr_01JC4B2W5RROUTER1"
    const val SETTLED = "thr_01JC1A2B3CSETTLE01"
    const val RUN = "run_fixture_m1"
    const val REQUEST = "req_01JC49ZM7TQ2"
    const val QUESTION = "thr_01JC4D7Q2KQUESTION"
    const val QUESTION_REQUEST = "req_01JC4D7R0QA1"
  }
}
