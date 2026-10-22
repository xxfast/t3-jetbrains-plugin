package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.plugin.ui.PendingUserInputDraftAnswer
import io.github.xxfast.t3code.plugin.ui.PendingApproval
import io.github.xxfast.t3code.plugin.ui.PendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.PendingUserInput
import io.github.xxfast.t3code.plugin.ui.answerLines
import io.github.xxfast.t3code.plugin.ui.answersResponse
import io.github.xxfast.t3code.plugin.ui.derivePendingThreadRequests
import io.github.xxfast.t3code.plugin.ui.dismissal
import io.github.xxfast.t3code.plugin.ui.hasCompleteProviderWorkspaceSnapshot
import io.github.xxfast.t3code.plugin.ui.hasCurrentProviderWorkspaceSnapshot
import io.github.xxfast.t3code.plugin.ui.resolveProviderSkillsForCwd
import io.github.xxfast.t3code.plugin.ui.searchProviderSkills
import io.github.xxfast.t3code.plugin.ui.instance
import io.github.xxfast.t3code.plugin.ui.isImageAttachment
import io.github.xxfast.t3code.plugin.ui.settled
import io.github.xxfast.t3code.plugin.ui.nextModelSelection
import io.github.xxfast.t3code.plugin.ui.setThreadInteractionMode
import io.github.xxfast.t3code.plugin.ui.setThreadRuntimeMode
import io.github.xxfast.t3code.plugin.ui.updateThreadModelSelection
import io.github.xxfast.t3code.plugin.ui.seedUserInputDraftAnswers
import io.github.xxfast.t3code.plugin.ui.slug
import io.github.xxfast.t3code.plugin.ui.buildPendingUserInputAnswers
import io.github.xxfast.t3code.plugin.review.checkpointForRun
import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.plugin.ui.contextLabels
import io.github.xxfast.t3code.plugin.ui.fileReviewComment
import io.github.xxfast.t3code.plugin.ui.messageContext
import io.github.xxfast.t3code.plugin.ui.reviewCommentReference
import io.github.xxfast.t3code.plugin.ui.toRecord
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import io.github.xxfast.t3code.protocol.generated.ChatFileAttachment
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchInput
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchResult
import io.github.xxfast.t3code.protocol.generated.ThreadLaunchWorkspaceStrategy
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.VcsListRefsInput
import io.github.xxfast.t3code.protocol.generated.WorktreeSetupSnapshot
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
import kotlinx.coroutines.flow.filterNotNull
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
    assertEquals(42, scenario.threads.size)
    val demos: Set<String> = scenario.threads
      .filter { it.title.startsWith("Demo: ") }
      .map { it.id }
      .toSet()
    assertEquals(setOf(THREAD, WAITING, QUESTION, UNKNOWN, MANY, WORKTREE, QUEUED, LINEAGE), demos)
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
  fun `a send with attachments and context carries them onto the item`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    val input = AttachmentCreateUploadUrlInput.File(
      name = "notes.txt",
      mimeType = "text/plain",
      sizeBytes = 5,
    )
    val minted = client.createUploadUrl(input)
    client.upload(minted.relativeUrl, "text/plain", "hello".toByteArray())
    val comment = fileReviewComment("c1", "src/a.kt", 1, 1, "", "val a = 1")
    val attachment = ChatFileAttachment(
      id = minted.attachmentId,
      name = "notes.txt",
      mimeType = "text/plain",
      sizeBytes = 5,
    )
    val context = messageContext(listOf(comment.toRecord()))

    client.dispatch(
      message(IDLE, messageId = "m1", text = "see ${reviewCommentReference(comment)}")
        .copy(attachments = listOf(attachment), context = context),
    )

    val sent: ThreadView = client.thread(IDLE).first { view -> view.items.isNotEmpty() }
    val user: TurnItem.UserMessage = assertIs(sent.items.single())
    assertEquals(listOf<ChatAttachment>(attachment), user.attachments)
    assertEquals(context, user.context)
    assertEquals(listOf("a.kt L1"), contextLabels(user.context))
    assertEquals(context, sent.messages.single().context)
  }

  @Test
  fun `uploads take a moment and the fixture refuses big ones`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val small = client.createUploadUrl(image(10))
    val started: Long = testScheduler.currentTime
    client.upload(small.relativeUrl, "image/png", ByteArray(10))
    assertTrue(testScheduler.currentTime - started >= 300, "Slow enough to see the spinner")

    val big = client.createUploadUrl(image(1024 * 1024 + 1))
    val refused = runCatching {
      client.upload(big.relativeUrl, "image/png", ByteArray(1024 * 1024 + 1))
    }.exceptionOrNull()
    assertIs<T3Exception.Transport>(refused)
    assertTrue(refused.message.orEmpty().contains("1 MiB"))
  }

  @Test
  fun `a deleted attachment is released and takes no bytes`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val minted = client.createUploadUrl(image(10))
    assertTrue(client.holds(minted.attachmentId))

    client.deleteAttachment(minted.attachmentId)

    assertFalse(client.holds(minted.attachmentId))
    val refused = runCatching {
      client.upload(minted.relativeUrl, "image/png", ByteArray(10))
    }.exceptionOrNull()
    assertTrue(refused?.message.orEmpty().contains("404"))
  }

  @Test
  fun `attachments come from the scenario folder or this session's uploads`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val shot: ByteArray = default.resolve("attachments/att_fixture_screenshot.png").readBytes()
    assertContentEquals(shot, client.attachment("att_fixture_screenshot"))

    val minted = client.createUploadUrl(image(3))
    client.upload(minted.relativeUrl, "image/png", byteArrayOf(1, 2, 3))
    assertContentEquals(byteArrayOf(1, 2, 3), client.attachment(minted.attachmentId))

    val missing = runCatching { client.attachment("att_nope") }.exceptionOrNull()
    assertIs<T3Exception.Transport>(missing)
  }

  @Test
  fun `the running demo's prompt carries a picture and a file`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    val running: ThreadView =
      client.thread(THREAD).first { view -> view.items.any { it is TurnItem.UserMessage } }
    val prompt = running.items.filterIsInstance<TurnItem.UserMessage>().first()
    assertEquals(listOf(true, false), prompt.attachments.map(::isImageAttachment))
  }

  private fun image(size: Int) = AttachmentCreateUploadUrlInput.Image(
    name = "a.png",
    mimeType = "image/png",
    sizeBytes = size.toLong(),
  )

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
  fun `default gives codex skills and a stale KStore snapshot that a refresh stamps`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { it.isNotEmpty() }
    val kstore = "/Users/xxfast/Developer/XXFAST/KMP/KStore"
    val codex: ServerProvider = client.serverInfo().providers.first { it.instanceId == "codex" }
    assertTrue(codex.skills.orEmpty().isNotEmpty())
    val skills: List<String> =
      searchProviderSkills(resolveProviderSkillsForCwd(codex, kstore), "").map { it.name }
    assertEquals(listOf("release-kstore", "kmp-targets", "gh-fix-ci", "babysit-pr"), skills)
    val now: Long = System.currentTimeMillis()
    assertFalse(hasCurrentProviderWorkspaceSnapshot(codex, kstore, now))

    val refreshed: ServerProvider = withContext(Dispatchers.Default) {
      client.refreshProviders("codex", kstore)
    }.first { it.instanceId == "codex" }

    assertTrue(hasCurrentProviderWorkspaceSnapshot(refreshed, kstore, System.currentTimeMillis()))
    assertEquals(
      skills,
      searchProviderSkills(resolveProviderSkillsForCwd(refreshed, kstore), "").map { it.name },
    )
    val again: ServerProvider = client.serverInfo().providers.first { it.instanceId == "codex" }
    assertTrue(hasCompleteProviderWorkspaceSnapshot(again, kstore))
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
  fun `the running demo's first turn can roll back, and the rollback lands`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == THREAD } }
    val view: ThreadView = client.thread(THREAD).first()
    val first: Run = view.runs.single { it.ordinal == 1L }
    val checkpoint: Checkpoint = assertNotNull(checkpointForRun(view, first))
    assertNull(checkpointForRun(view, view.runs.single { it.ordinal == 2L }))

    client.dispatch(
      Command.CheckpointRollback(
        commandId = "cmd_rollback",
        threadId = THREAD,
        scopeId = checkpoint.scopeId,
        checkpointId = checkpoint.id,
      ),
    )

    val after: ThreadView = client.thread(THREAD)
      .first { it.items.any { item -> item is TurnItem.SystemNotice } }
    val notice: TurnItem.SystemNotice = assertIs(after.items.last { it is TurnItem.SystemNotice })
    assertEquals("Rolled back to checkpoint ${checkpoint.id}", notice.message)
  }

  @Test
  fun `settle unpins and stamps, un-settle wakes it as upstream's orchestrator does`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == PINNED } }

    client.dispatch(Command.ThreadSettle(commandId = "cmd_settle", threadId = PINNED))
    val settled: Thread = client.threads.first { row(it, PINNED).settled }.let { row(it, PINNED) }
    assertNotNull(settled.settledAt)
    assertNull(settled.pinnedAt, "Settling unpins")
    assertNull(settled.pinOrderKey)

    client.dispatch(
      Command.ThreadUnsettle(commandId = "cmd_unsettle", threadId = PINNED, reason = "user"),
    )
    val active: Thread = client.threads.first { !row(it, PINNED).settled }.let { row(it, PINNED) }
    assertEquals("active", active.settledOverride)
    assertNull(active.settledAt)
    assertNotNull(active.unsettledAt)
  }

  @Test
  fun `snooze keeps its first stamp for the same wake time, wake clears it`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    val until = "2030-01-01T09:00:00Z"

    client.dispatch(Command.ThreadSnooze(commandId = "c1", threadId = IDLE, snoozedUntil = until))
    val snoozed: Thread = client.threads.first { row(it).snoozedUntil != null }.let(::row)
    assertNotNull(snoozed.snoozedAt)
    client.dispatch(Command.ThreadSnooze(commandId = "c2", threadId = IDLE, snoozedUntil = until))
    assertEquals(snoozed.snoozedAt, row(client.threads.value).snoozedAt)

    client.dispatch(Command.ThreadUnsnooze(commandId = "c3", threadId = IDLE, reason = "user"))
    val awake: Thread = client.threads.first { row(it).snoozedUntil == null }.let(::row)
    assertNull(awake.snoozedAt)
  }

  @Test
  fun `rename sets the title, regenerating marks it until the generator answers`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }

    client.dispatch(Command.ThreadMetadataUpdate(commandId = "c1", threadId = IDLE, title = "New"))
    client.threads.first { row(it).title == "New" }

    client.dispatch(
      Command.ThreadMetadataUpdate(commandId = "c2", threadId = IDLE, regenerateTitle = true),
    )
    client.threads.first { row(it).titleRegeneration?.requestId == "c2" }
    val done: Thread = client.threads.first { row(it).titleRegeneration == null }.let(::row)
    assertEquals("New (regenerated)", done.title)
  }

  @Test
  fun `delete takes the thread off the list`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }

    client.dispatch(Command.ThreadDelete(commandId = "c", threadId = IDLE))
    client.threads.first { threads -> threads.none { it.id == IDLE } }
  }

  @Test
  fun `auto-settle opts out and back in`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }

    client.dispatch(Command.ThreadAutoSettleSet(commandId = "c1", threadId = IDLE, enabled = false))
    client.threads.first { row(it).autoSettleDisabledAt != null }

    client.dispatch(Command.ThreadAutoSettleSet(commandId = "c2", threadId = IDLE, enabled = true))
    client.threads.first { row(it).autoSettleDisabledAt == null }
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

  private fun row(threads: List<Thread>, id: String = IDLE): Thread = threads.single { it.id == id }

  @Test
  fun `a launch for a new worktree streams the setup, lands the row, then sends`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    val before: Thread = client.threads.value.first { it.id == IDLE }
    val input = ThreadLaunchInput(
      commandId = "cmd_launch",
      threadId = IDLE,
      reuseExistingThread = true,
      projectId = before.projectId,
      title = "Try a worktree",
      generateTitle = true,
      modelSelection = before.modelSelection,
      runtimeMode = before.runtimeMode,
      interactionMode = before.interactionMode,
      workspaceStrategy = ThreadLaunchWorkspaceStrategy.Worktree(baseRef = "main"),
      initialMessage = ThreadLaunchInput.InitialMessage(
        messageId = "m1",
        text = "hello from a worktree",
        attachments = emptyList(),
      ),
    )

    val result: ThreadLaunchResult = client.launchThread(input)

    assertEquals(IDLE, result.threadId)
    assertTrue(result.resumed)
    assertEquals("Try a worktree", result.projection.thread.title)
    val phases: List<String> = client.worktreeSetup(IDLE)
      .filterNotNull()
      .transformWhile { snapshot ->
        emit(snapshot.phase)
        snapshot.phase == "running"
      }
      .toList()
    assertEquals("running", phases.first())
    assertEquals("done", phases.last())
    val landed: Thread = client.threads.first { threads ->
      threads.first { it.id == IDLE }.worktreePath != null
    }.first { it.id == IDLE }
    assertEquals("main", client.worktreeSetup(IDLE).first { it != null }?.baseRef)
    assertTrue(landed.worktreePath!!.contains("/.t3/worktrees/"))
    assertEquals("t3/", landed.branch!!.take(3))
    val sent: ThreadView = client.thread(IDLE).first { view -> view.items.isNotEmpty() }
    val user: TurnItem.UserMessage = assertIs(sent.items.first())
    assertEquals("hello from a worktree", user.text)
    assertEquals("m1", user.messageId)
  }

  @Test
  fun `cancelling a running setup marks it cancelled and no message goes out`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { threads -> threads.any { it.id == IDLE } }
    val before: Thread = client.threads.value.first { it.id == IDLE }
    client.launchThread(
      ThreadLaunchInput(
        commandId = "cmd_cancel",
        threadId = IDLE,
        reuseExistingThread = true,
        projectId = before.projectId,
        title = "Cancel me",
        modelSelection = before.modelSelection,
        runtimeMode = before.runtimeMode,
        interactionMode = before.interactionMode,
        workspaceStrategy = ThreadLaunchWorkspaceStrategy.Worktree(baseRef = "main"),
        initialMessage = ThreadLaunchInput.InitialMessage(
          messageId = "m2",
          text = "never",
          attachments = emptyList(),
        ),
      ),
    )
    client.worktreeSetup(IDLE).first { it?.phase == "running" }

    assertTrue(client.worktreeSetupCancel(IDLE))

    val cancelled: WorktreeSetupSnapshot = client.worktreeSetup(IDLE).first { it?.phase == "cancelled" }!!
    assertTrue(cancelled.stages.any { it.status == "failed" })
    assertFalse(client.worktreeSetupCancel(IDLE), "Nothing left to cancel")
    assertTrue(client.thread(IDLE).first().items.isEmpty())
    assertNull(client.threads.value.first { it.id == IDLE }.worktreePath)
  }

  @Test
  fun `thread create lists a fresh idle row that can be opened`() = runTest {
    val client = FixtureClient(default, backgroundScope)
    client.threads.first { it.isNotEmpty() }
    val project: String = client.projects.value.first().id

    client.dispatch(
      Command.ThreadCreate(
        createdBy = "user",
        creationSource = "web",
        commandId = "cmd_create",
        threadId = "thr_created",
        projectId = project,
        title = "New thread",
        modelSelection = ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("gpt-5")),
        runtimeMode = "full-access",
        interactionMode = "default",
        branch = null,
        worktreePath = null,
      ),
    )

    val row: Thread = client.threads.first { threads -> threads.any { it.id == "thr_created" } }
      .last()
    assertEquals("New thread", row.title)
    assertEquals("idle", row.status)
    val view: ThreadView = client.thread("thr_created").first()
    assertTrue(view.items.isEmpty())
    assertEquals(listOf("main", "develop"), client.listRefs(VcsListRefsInput(cwd = "/x")).refs.take(2).map { it.name })
  }

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
    const val WORKTREE = "thr_01JC5W0RKTREE001"
    const val QUEUED = "thr_01JC4C3Q8UQUEUED1"
    const val LINEAGE = "thr_01JC5L1N1LINEAG1"
    const val PINNED = "thr_01JC4B2W5RROUTER1"
    const val SETTLED = "thr_01JC1A2B3CSETTLE01"
    const val RUN = "run_fixture_m1"
    const val REQUEST = "req_01JC49ZM7TQ2"
    const val QUESTION = "thr_01JC4D7Q2KQUESTION"
    const val QUESTION_REQUEST = "req_01JC4D7R0QA1"
  }
}
