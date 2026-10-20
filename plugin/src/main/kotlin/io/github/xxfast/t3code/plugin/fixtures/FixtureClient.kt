package io.github.xxfast.t3code.plugin.fixtures

import com.intellij.openapi.diagnostic.Logger
import io.github.xxfast.t3code.plugin.ui.THREAD_AUTO_SETTLE_OPT_OUT
import io.github.xxfast.t3code.plugin.ui.THREAD_SETTLEMENT
import io.github.xxfast.t3code.plugin.ui.THREAD_SNOOZE
import io.github.xxfast.t3code.plugin.ui.THREAD_TITLE_REGENERATION
import io.github.xxfast.t3code.plugin.ui.instance
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.FileContents
import io.github.xxfast.t3code.protocol.OrchestrationV2Command
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlResult
import io.github.xxfast.t3code.protocol.generated.BooleanProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.ChatFileAttachment
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.LatestVisibleMessageSummary
import io.github.xxfast.t3code.protocol.generated.ModelCapabilities
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import io.github.xxfast.t3code.protocol.generated.PersistChatAttachmentsInput
import io.github.xxfast.t3code.protocol.generated.PersistChatAttachmentsResult
import io.github.xxfast.t3code.protocol.generated.ProviderOptionChoice
import io.github.xxfast.t3code.protocol.generated.SelectProviderOptionDescriptor
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel
import io.github.xxfast.t3code.protocol.generated.ServerProviderSlashCommand
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalMetadataStreamEvent
import io.github.xxfast.t3code.protocol.generated.TerminalSessionSnapshot
import io.github.xxfast.t3code.protocol.generated.ThreadTitleRegeneration
import io.github.xxfast.t3code.protocol.generated.TurnItem
import io.github.xxfast.t3code.protocol.generated.UserInputAttachmentAnswerPayload
import io.github.xxfast.t3code.protocol.generated.VcsStatusLocalResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusRemoteResult
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A [T3Client] that replays one scenario instead of talking to a server: every `*.json` in
 * [directory], merged by [Scenario.merge] in filename order. Polls the directory, so editing,
 * adding or removing a file updates whoever is collecting.
 *
 * [dispatch] never touches the files. It changes an in-memory [Overlay] that every flow reads
 * through, so the harness shows what a command does; the overlay resets when the directory
 * reloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixtureClient(
  private val directory: Path,
  private val scope: CoroutineScope,
  private val pollInterval: Duration = 1.seconds,
) : T3Client {
  private val log: Logger = Logger.getInstance(FixtureClient::class.java)

  private val loaded: MutableStateFlow<Scenario?> = MutableStateFlow(null)
  private val overlay: MutableStateFlow<Overlay> = MutableStateFlow(Overlay())
  private val online: MutableStateFlow<Boolean> = MutableStateFlow(true)
  private var loadedStamp: List<Pair<String, Long>>? = null
  private val sequence: AtomicLong = AtomicLong(0)

  /** Attachments minted by [createUploadUrl] and not deleted, by their number. */
  private val minted: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** The fake assistant replies in flight, by thread id, so an interrupt can stop one. */
  private val replies: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

  /** What [terminalWrite] echoes, by `threadId/terminalId`. */
  private val terminals: ConcurrentHashMap<String, MutableSharedFlow<TerminalAttachStreamEvent>> =
    ConcurrentHashMap()

  override val connection: StateFlow<ConnectionState> = combine(loaded, online) { scenario, on ->
    if (!on) ConnectionState.Disconnected else scenario?.connection ?: ConnectionState.Connecting
  }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Connecting)

  override val projects: StateFlow<List<Project>> = loaded
    .map { scenario -> scenario?.projects.orEmpty() }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  override val threads: StateFlow<List<Thread>> = combine(loaded, overlay) { scenario, overlay ->
    scenario?.threads.orEmpty().filter { it.id !in overlay.deleted }.map(overlay::row)
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    scope.launch(Dispatchers.IO) {
      while (isActive) {
        poll()
        delay(pollInterval)
      }
    }
  }

  override fun thread(id: String): Flow<ThreadView> = loaded
    .filterNotNull()
    .flatMapLatest { scenario ->
      val thread: Thread? = scenario.threads.find { it.id == id }
      if (thread == null) emptyFlow()
      else combine(replay(thread, scenario), overlay) { base, overlay -> overlay.over(base) }
    }

  private fun replay(thread: Thread, scenario: Scenario): Flow<ThreadView> = flow {
    val entries: List<FixtureItem> = scenario.items[thread.id].orEmpty()
    val snapshot: Int = entries.indexOfFirst { it.after > 0 }.takeIf { it >= 0 } ?: entries.size
    val items: LinkedHashMap<String, TurnItem> = LinkedHashMap()
    entries.take(snapshot).forEach { items[it.item.id] = it.item }
    fun view() = ThreadView(
      thread = thread,
      runs = scenario.runs[thread.id].orEmpty(),
      items = items.values.toList(),
      runtimeRequests = scenario.runtimeRequests[thread.id].orEmpty(),
      providerSessions = scenario.providerSessions[thread.id].orEmpty(),
      checkpoints = scenario.checkpoints[thread.id].orEmpty(),
      providerThreads = scenario.providerThreads[thread.id].orEmpty(),
    )
    emit(view())
    entries.drop(snapshot).forEach { entry ->
      delay(entry.after)
      items[entry.item.id] = entry.item
      emit(view())
    }
  }

  override suspend fun readFile(cwd: String, path: String): FileContents {
    val contents = "// fixture content for $path\n"
    return FileContents(path, contents, byteLength = contents.length.toLong(), truncated = false)
  }

  override suspend fun projectFavicon(cwd: String, path: String?): ByteArray? = null

  override suspend fun turnDiff(threadId: String, fromTurn: Long, toTurn: Long): String = PATCH

  override suspend fun fullThreadDiff(threadId: String, toTurn: Long): String = PATCH

  override suspend fun dispatch(command: OrchestrationV2Command): Long {
    val now: String = Instant.now().toString()
    val at: Long = sequence.incrementAndGet()
    when (command) {
      is Command.MessageDispatch -> send(command, now, at)

      is Command.RuntimeRequestRespond -> respond(command, now)

      // Resolves like an answer with none: the card says it was dismissed
      is Command.ThreadUserInputDismiss -> respond(
        Command.RuntimeRequestRespond(
          commandId = command.commandId,
          threadId = command.threadId,
          requestId = command.requestId,
        ),
        now,
      )

      is Command.RunInterrupt -> {
        replies.remove(command.threadId)?.cancel()
        overlay.update { overlay ->
          val run: Run = runsOf(overlay, command.threadId).find { it.id == command.runId }
            ?: return@update overlay
          // What the run left in flight is cancelled, as upstream's ProviderEventIngestor.ts
          // (around line 384) does; streaming stops too, or the reply would keep its caret
          val cancelled: List<TurnItem> = itemsOf(overlay, command.threadId)
            .filter { item -> item.runId == run.id && item.status in IN_FLIGHT }
            .map { item -> cancelled(item, now) }
          return@update cancelled
            .fold(overlay) { next, item -> next.upsertItem(command.threadId, item) }
            .upsertRun(command.threadId, run.copy(status = "interrupted", completedAt = now))
            .withRow(command.threadId) { row ->
              row.copy(status = "idle", activeRunId = null, latestRunCompletedAt = now)
            }
        }
      }

      is Command.ProviderSessionDetach -> overlay.update { overlay ->
        overlay
          .copy(detachedSessions = overlay.detachedSessions + command.providerSessionId)
          .withRow(command.threadId) { row -> row.copy(status = "idle") }
      }

      // The thread.* rows below port apps/server/src/orchestration-v2/Orchestrator.ts at the
      // pinned tag, line for line. The shell row has no lastSnoozeWakeAt, so pin can't set it.

      // Orchestrator.ts:2829
      is Command.ThreadArchive -> updateRow(command.threadId) { row ->
        row.copy(
          archivedAt = now,
          titleRegeneration = null,
          pullRequests = row.pullRequests?.map { link -> link.copy(watch = null) },
          updatedAt = now,
        )
      }

      // Orchestrator.ts:2837
      is Command.ThreadUnarchive -> updateRow(command.threadId) { row ->
        row.copy(archivedAt = null, updatedAt = now)
      }

      // Orchestrator.ts:2897
      is Command.ThreadPin -> updateRow(command.threadId) { row ->
        val alreadyPinned: Boolean = row.pinnedAt != null
        val promotes: Boolean = row.settledOverride == "settled" || row.snoozedUntil != null
        val settled: Boolean = row.settledOverride == "settled"
        row.copy(
          pinnedAt = if (alreadyPinned) row.pinnedAt else now,
          pinOrderKey = if (alreadyPinned || command.orderKey == null) row.pinOrderKey
          else command.orderKey,
          settledOverride = if (settled) "active" else row.settledOverride,
          settledAt = if (settled) null else row.settledAt,
          snoozedUntil = null,
          snoozedAt = null,
          updatedAt = if (alreadyPinned && !promotes) row.updatedAt else now,
        )
      }

      // Orchestrator.ts:2915
      is Command.ThreadUnpin -> updateRow(command.threadId) { row ->
        val alreadyUnpinned: Boolean = row.pinnedAt == null
        row.copy(
          pinnedAt = null,
          pinOrderKey = null,
          updatedAt = if (alreadyUnpinned) row.updatedAt else now,
        )
      }

      // Orchestrator.ts:2839
      is Command.ThreadSettle -> updateRow(command.threadId) { row ->
        val wasPinned: Boolean = row.pinnedAt != null
        val alreadySettled: Boolean =
          row.settledOverride == "settled" && row.settledAt != null && !wasPinned
        row.copy(
          settledOverride = "settled",
          settledAt = if (alreadySettled) row.settledAt else command.settledAt ?: now,
          pullRequests = row.pullRequests?.map { link -> link.copy(watch = null) },
          unsettledAt = null,
          pinnedAt = null,
          pinOrderKey = null,
          activeOrderKey = null,
          updatedAt = if (alreadySettled) row.updatedAt else now,
        )
      }

      // Orchestrator.ts:2855
      is Command.ThreadUnsettle -> updateRow(command.threadId) { row ->
        val alreadyActive: Boolean = row.settledOverride == "active"
        row.copy(
          settledOverride = "active",
          settledAt = null,
          unsettledAt = if (alreadyActive) row.unsettledAt else now,
          updatedAt = if (alreadyActive) row.updatedAt else now,
        )
      }

      // Orchestrator.ts:2865. The server also refuses a snooze while a run is queued; the
      // fixture doesn't check
      is Command.ThreadSnooze -> updateRow(command.threadId) { row ->
        val sameWakeTime: Boolean = row.snoozedUntil != null &&
          Instant.parse(row.snoozedUntil) == Instant.parse(command.snoozedUntil)
        val existingSnoozedAt: String? = if (sameWakeTime) row.snoozedAt else null
        row.copy(
          snoozedUntil = command.snoozedUntil,
          limitRecovery = row.limitRecovery?.copy(snooze = false),
          snoozedAt = existingSnoozedAt ?: now,
          updatedAt = if (existingSnoozedAt == null) now else row.updatedAt,
        )
      }

      // Orchestrator.ts:2879. The shell has no lastSnoozeWakeAt to stamp
      is Command.ThreadUnsnooze -> updateRow(command.threadId) { row ->
        val alreadyAwake: Boolean = row.snoozedUntil == null
        row.copy(
          snoozedUntil = null,
          snoozedAt = null,
          updatedAt = if (alreadyAwake) row.updatedAt else now,
        )
      }

      // Orchestrator.ts:2941, the title half. A regeneration stands in for the server's title
      // generator: it runs a couple of seconds, then completes with a made-up title
      is Command.ThreadMetadataUpdate -> {
        val title: String? = command.title
        if (title != null) {
          updateRow(command.threadId) { row -> row.copy(title = title, updatedAt = now) }
        }
        if (command.regenerateTitle == true) {
          regenerateTitle(command.threadId, command.commandId, now)
        }
      }

      // Orchestrator.ts:11262. Gone from the shell, as `thread.removed` takes it
      is Command.ThreadDelete -> overlay.update { overlay ->
        overlay.copy(deleted = overlay.deleted + command.threadId)
      }

      // Orchestrator.ts:2889
      is Command.ThreadAutoSettleSet -> updateRow(command.threadId) { row ->
        val unchanged: Boolean = command.enabled == (row.autoSettleDisabledAt == null)
        row.copy(
          autoSettleDisabledAt = if (command.enabled) null else row.autoSettleDisabledAt ?: now,
          updatedAt = if (unchanged) row.updatedAt else now,
        )
      }

      // Orchestrator.ts:2814 and 2939
      is Command.ThreadMarkUnread -> overlay.update { overlay ->
        // The last visit goes just before the latest run completed
        val completedAt: String? = runsOf(overlay, command.threadId).lastOrNull()?.completedAt
        if (completedAt == null) {
          log.info("Fixture: ${command.threadId} has no completed run to mark unread")
          return@update overlay
        }
        val visitedAt: String = Instant.parse(completedAt).minusMillis(1).toString()
        return@update overlay.withRow(command.threadId) { row ->
          row.copy(lastVisitedAt = visitedAt)
        }
      }

      // Orchestrator.ts:2321, dispatchThreadVisit: a visit only ever moves forward
      is Command.ThreadVisit -> updateRow(command.threadId) { row ->
        val last: String? = row.lastVisitedAt
        val movesForward: Boolean =
          last == null || Instant.parse(command.visitedAt).isAfter(Instant.parse(last))
        if (movesForward) row.copy(lastVisitedAt = command.visitedAt) else row
      }

      is Command.ThreadRuntimeModeSet -> updateRow(command.threadId) { row ->
        row.copy(runtimeMode = command.runtimeMode, updatedAt = now)
      }

      is Command.ThreadInteractionModeSet -> updateRow(command.threadId) { row ->
        row.copy(interactionMode = command.interactionMode, updatedAt = now)
      }

      // Both move the row to the selection's instance, like the server
      is Command.ThreadModelSelectionSet -> updateRow(command.threadId) { row ->
        row.withModel(command.modelSelection, now)
      }

      is Command.ProviderSwitch -> updateRow(command.threadId) { row ->
        row.withModel(command.modelSelection, now)
      }

      is Command.CheckpointRollback -> overlay.update { overlay ->
        val notice = TurnItem.SystemNotice(
          id = "item_fixture_rollback_${command.commandId}",
          threadId = command.threadId,
          runId = null,
          nodeId = null,
          providerThreadId = null,
          providerTurnId = null,
          nativeItemRef = null,
          parentItemId = null,
          ordinal = nextOrdinal(overlay, command.threadId),
          status = "completed",
          title = null,
          startedAt = now,
          completedAt = now,
          updatedAt = now,
          message = "Rolled back to checkpoint ${command.checkpointId}",
        )
        overlay.upsertItem(command.threadId, notice)
      }

      else -> log.info("Fixture ignores ${command.type}")
    }
    return at
  }

  /** The user's message now, a streaming reply after 600 ms, done 600 ms after that. */
  private fun send(command: Command.MessageDispatch, now: String, at: Long) {
    val threadId: String = command.threadId
    val runId = "run_fixture_${command.messageId}"
    val replyId = "msg_fixture_reply_${command.messageId}"
    val text = "Fixture reply to: ${command.text}"

    // Built inside the update from the overlay it's given, so concurrent dispatches can't share
    // an ordinal. The lambda may run more than once; the last run is the one that stuck.
    var sent: Run? = null
    overlay.update { overlay ->
      sent = null
      val row: Thread = rowOf(overlay, threadId) ?: return@update overlay
      val run = Run(
        id = runId,
        threadId = threadId,
        ordinal = runsOf(overlay, threadId).size + 1L,
        providerInstanceId = row.providerInstanceId,
        modelSelection = command.modelSelection ?: row.modelSelection,
        providerThreadId = null,
        userMessageId = command.messageId,
        rootNodeId = null,
        activeAttemptId = null,
        status = "running",
        requestedAt = now,
        startedAt = now,
        completedAt = null,
        checkpointId = null,
        contextHandoffId = null,
      )
      val message = TurnItem.UserMessage(
        id = "item_${command.messageId}",
        threadId = threadId,
        runId = runId,
        nodeId = null,
        providerThreadId = null,
        providerTurnId = null,
        nativeItemRef = null,
        parentItemId = null,
        ordinal = nextOrdinal(overlay, threadId),
        status = "completed",
        title = null,
        startedAt = now,
        completedAt = now,
        updatedAt = now,
        createdBy = command.createdBy,
        creationSource = command.creationSource,
        messageId = command.messageId,
        inputIntent = "turn_start",
        text = command.text,
        context = command.context,
        attachments = command.attachments,
      )
      // The projection's message, what a queued send has before its run starts
      val conversation = ConversationMessage(
        createdBy = command.createdBy,
        creationSource = command.creationSource,
        id = command.messageId,
        threadId = threadId,
        runId = runId,
        nodeId = null,
        role = "user",
        text = command.text,
        context = command.context,
        attachments = command.attachments,
        streaming = false,
        createdAt = now,
        updatedAt = now,
      )
      sent = run
      return@update overlay
        .upsertRun(threadId, run)
        .upsertItem(threadId, message)
        .upsertMessage(threadId, conversation)
        // What the views report, so a sent message retires by sequence like on a real server
        .copy(sequence = maxOf(overlay.sequence, at))
        .withRow(threadId) { thread ->
          thread.copy(
            status = "running",
            activeRunId = runId,
            latestRunId = runId,
            latestRunRequestedAt = now,
            latestRunStartedAt = now,
            latestUserMessageAt = now,
            latestVisibleMessage =
              LatestVisibleMessageSummary(command.messageId, "user", command.text, now),
            itemCount = thread.itemCount + 1,
            visibleItemCount = thread.visibleItemCount + 1,
            updatedAt = now,
          )
        }
    }
    val run: Run = sent ?: return

    fun reply(text: String, streaming: Boolean, at: String, ordinal: Long) =
      TurnItem.AssistantMessage(
        id = "item_$replyId",
        threadId = threadId,
        runId = runId,
        nodeId = null,
        providerThreadId = null,
        providerTurnId = null,
        nativeItemRef = null,
        parentItemId = null,
        ordinal = ordinal,
        status = if (streaming) "running" else "completed",
        title = null,
        startedAt = at,
        completedAt = if (streaming) null else at,
        updatedAt = at,
        messageId = replyId,
        text = text,
        streaming = streaming,
      )

    replies.remove(threadId)?.cancel()
    replies[threadId] = scope.launch {
      delay(REPLY_STEP_MILLIS)
      val started: String = Instant.now().toString()
      var ordinal = 0L
      overlay.update { overlay ->
        ordinal = nextOrdinal(overlay, threadId)
        overlay.upsertItem(threadId, reply(text.take(text.length / 2), true, started, ordinal))
      }

      delay(REPLY_STEP_MILLIS)
      val done: String = Instant.now().toString()
      overlay.update { overlay ->
        overlay
          .upsertItem(threadId, reply(text, false, done, ordinal))
          .upsertRun(threadId, run.copy(status = "completed", completedAt = done))
          .withRow(threadId) { thread ->
            thread.copy(
              status = "idle",
              activeRunId = null,
              latestRunCompletedAt = done,
              latestVisibleMessage = LatestVisibleMessageSummary(replyId, "assistant", text, done),
              itemCount = thread.itemCount + 1,
              visibleItemCount = thread.visibleItemCount + 1,
              updatedAt = done,
            )
          }
      }
    }
  }

  /** The request's item completes; the thread stops waiting and runs on. */
  private fun respond(command: Command.RuntimeRequestRespond, now: String) {
    val threadId: String = command.threadId
    overlay.update { overlay ->
      val resolved: TurnItem? = when (val item: TurnItem? = itemOf(overlay, command)) {
        is TurnItem.ApprovalRequest ->
          item.copy(status = "completed", completedAt = now, updatedAt = now)

        is TurnItem.UserInputRequest -> item.copy(
          status = "completed",
          completedAt = now,
          updatedAt = now,
          questionAnswer = UserInputAttachmentAnswerPayload(
            requestId = command.requestId,
            answers = command.answers ?: JsonObject(emptyMap()),
            attachmentsByQuestionId = command.attachmentsByQuestionId.orEmpty()
              .mapValues { (_, attachments) -> attachments.map(::answerAttachment) },
          ),
        )

        else -> null
      }
      val listed: List<RuntimeRequest> = loaded.value?.runtimeRequests?.get(threadId).orEmpty() +
        overlay.runtimeRequests[threadId].orEmpty()
      // Scenarios rarely list requests; then it's the one the overlay stands in for the item
      val listedOrItem: RuntimeRequest? = listed.lastOrNull { it.id == command.requestId }
        ?: itemOf(overlay, command)?.let { item ->
          val sessions: List<ProviderSession> =
            loaded.value?.providerSessions?.get(threadId).orEmpty()
          requestsOf(listOf(item), sessionOf(sessions)).firstOrNull()
        }
      val request: RuntimeRequest? = listedOrItem?.copy(
        status = "resolved",
        resolvedAt = now,
        decision = command.decision,
        answers = command.answers,
      )
      var next: Overlay = overlay
      if (resolved != null) next = next.upsertItem(threadId, resolved)
      if (request != null) next = next.upsertRequest(threadId, request)
      return@update next.withRow(threadId) { row ->
        row.copy(pendingRuntimeRequest = null, status = "running", updatedAt = now)
      }
    }
  }

  private fun itemOf(overlay: Overlay, command: Command.RuntimeRequestRespond): TurnItem? =
    itemsOf(overlay, command.threadId).find { item ->
      when (item) {
        is TurnItem.ApprovalRequest -> item.requestId == command.requestId
        is TurnItem.UserInputRequest -> item.requestId == command.requestId
        else -> false
      }
    }

  private fun regenerateTitle(threadId: String, requestId: String, now: String) {
    updateRow(threadId) { row ->
      row.copy(titleRegeneration = ThreadTitleRegeneration(requestId, now), updatedAt = now)
    }
    scope.launch {
      delay(TITLE_REGENERATION)
      updateRow(threadId) { row ->
        if (row.titleRegeneration?.requestId != requestId) return@updateRow row
        val title: String = row.title.removeSuffix(REGENERATED) + REGENERATED
        row.copy(title = title, titleRegeneration = null, updatedAt = Instant.now().toString())
      }
    }
  }

  private fun updateRow(threadId: String, change: (Thread) -> Thread) =
    overlay.update { overlay -> overlay.withRow(threadId, change) }

  private fun Overlay.withRow(threadId: String, change: (Thread) -> Thread): Overlay {
    val row: Thread = rowOf(this, threadId) ?: return this
    return copy(threads = threads + (threadId to change(row)))
  }

  private fun rowOf(overlay: Overlay, threadId: String): Thread? =
    overlay.threads[threadId] ?: loaded.value?.threads?.find { it.id == threadId }

  private fun runsOf(overlay: Overlay, threadId: String): List<Run> = loaded.value?.runs
    ?.get(threadId)
    .orEmpty()
    .upsertAll(overlay.runs[threadId].orEmpty(), Run::id)

  private fun itemsOf(overlay: Overlay, threadId: String): List<TurnItem> = loaded.value?.items
    ?.get(threadId)
    .orEmpty()
    .map { it.item }
    .upsertAll(overlay.items[threadId].orEmpty(), TurnItem::id)

  private fun nextOrdinal(overlay: Overlay, threadId: String): Long =
    (itemsOf(overlay, threadId).maxOfOrNull { it.ordinal } ?: 0) + 1

  override fun terminal(input: TerminalAttachInput): Flow<TerminalAttachStreamEvent> = flow {
    val snapshot = TerminalSessionSnapshot(
      threadId = input.threadId,
      terminalId = input.terminalId,
      cwd = input.cwd ?: input.worktreePath ?: directory.toString(),
      worktreePath = input.worktreePath,
      status = "running",
      pid = null,
      history = "fixture shell\r\n$ ",
      exitCode = null,
      exitSignal = null,
      label = "fixture",
      updatedAt = Instant.now().toString(),
    )
    emit(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot))
    emitAll(echoes(input.threadId, input.terminalId))
  }

  // The same shell, watched: an observer sees what other sessions type, and can't type itself
  override fun terminalObserve(
    threadId: String,
    terminalId: String,
  ): Flow<TerminalAttachStreamEvent> =
    terminal(TerminalAttachInput(threadId = threadId, terminalId = terminalId))

  // What the server tells attached clients when their terminal goes away
  override suspend fun terminalClose(threadId: String, terminalId: String, deleteHistory: Boolean) {
    val closed = TerminalAttachStreamEvent.Closed(threadId = threadId, terminalId = terminalId)
    echoes(threadId, terminalId).emit(closed)
  }

  override suspend fun terminalWrite(threadId: String, terminalId: String, data: String) {
    // What a shell would do: echo the keys, and prompt again after Enter
    val output = TerminalAttachStreamEvent.Output(
      threadId = threadId,
      terminalId = terminalId,
      data = data.replace("\r", "\r\n$ "),
    )
    echoes(threadId, terminalId).emit(output)
  }

  override suspend fun terminalResize(threadId: String, terminalId: String, cols: Int, rows: Int) =
    Unit

  private fun echoes(
    threadId: String,
    terminalId: String,
  ): MutableSharedFlow<TerminalAttachStreamEvent> = terminals
    .getOrPut("$threadId/$terminalId") { MutableSharedFlow(extraBufferCapacity = 64) }

  /** The scenario's `terminals`, as one snapshot each time the directory reloads. */
  override fun terminalMetadata(): Flow<TerminalMetadataStreamEvent> = loaded
    .filterNotNull()
    .map { scenario -> TerminalMetadataStreamEvent.Snapshot(terminals = scenario.terminals) }

  override fun vcsStatus(cwd: String): Flow<VcsStatusStreamEvent> = flow {
    val local = VcsStatusLocalResult(
      isRepo = true,
      hasPrimaryRemote = true,
      isDefaultRef = false,
      refName = "fixture/phase-2",
      hasWorkingTreeChanges = true,
      workingTree = VcsStatusLocalResult.WorkingTree(
        files = listOf(
          VcsStatusLocalResult.WorkingTree.File("src/main/kotlin/App.kt", 3, 1),
          VcsStatusLocalResult.WorkingTree.File("README.md", 1, 0),
        ),
        insertions = 4,
        deletions = 1,
      ),
    )
    val remote =
      VcsStatusRemoteResult(hasUpstream = true, aheadCount = 2, behindCount = 1, pr = null)
    emit(VcsStatusStreamEvent.Snapshot(local = local, remote = remote))
    // A live subscription stays open
    awaitCancellation()
  }

  override suspend fun createUploadUrl(
    input: AttachmentCreateUploadUrlInput,
  ): AttachmentCreateUploadUrlResult {
    val id: Long = sequence.incrementAndGet()
    minted += id.toString()
    return AttachmentCreateUploadUrlResult(
      attachmentId = "att_fixture_$id",
      relativeUrl = "$UPLOAD_PATH$id",
      expiresAt = (System.currentTimeMillis() + UPLOAD_URL_TTL_MILLIS).toDouble(),
    )
  }

  // Slow enough to see the chip's spinner, and a refusal to design the failed chip against
  override suspend fun upload(relativeUrl: String, mimeType: String, bytes: ByteArray) {
    delay(UPLOAD_MILLIS)
    if (relativeUrl.removePrefix(UPLOAD_PATH) !in minted) {
      throw T3Exception.Transport("Upload rejected (404): no such attachment")
    }
    if (bytes.size > UPLOAD_MAX_BYTES) {
      throw T3Exception.Transport("Upload rejected (413): the fixture server takes up to 1 MiB")
    }
  }

  override suspend fun deleteAttachment(attachmentId: String) {
    minted -= attachmentId.removePrefix("att_fixture_")
  }

  /** Whether [attachmentId] was minted and not deleted since. */
  fun holds(attachmentId: String): Boolean = attachmentId.removePrefix("att_fixture_") in minted

  override suspend fun persistAttachments(
    input: PersistChatAttachmentsInput,
  ): PersistChatAttachmentsResult {
    val attachments: List<ChatImageAttachment> = input.attachments.map { upload ->
      ChatImageAttachment(
        id = upload.id ?: "att_fixture_${sequence.incrementAndGet()}",
        name = upload.name,
        mimeType = upload.mimeType,
        sizeBytes = upload.sizeBytes,
      )
    }
    return PersistChatAttachmentsResult(attachments)
  }

  override suspend fun serverInfo(): ServerInfo = ServerInfo(
    // Codex shows Plan/Build and takes two runtime modes; Claude hides the toggle and takes all
    // four. Switch a thread's model to see one or the other.
    providers = listOf(
      provider(
        instanceId = "codex",
        driver = "codex",
        displayName = "Codex",
        showInteractionModeToggle = true,
        supportedRuntimeModes = listOf("approval-required", "full-access"),
        models = listOf(
          model("gpt-5", "GPT-5", capabilities = CODEX_TRAITS),
          model("gpt-5-mini", "GPT-5 mini", badge = "new", capabilities = CODEX_TRAITS),
          model("gpt-4.1", "GPT-4.1", isLegacy = true),
        ),
      ),
      // Claude can `/compact`, so its threads offer "Compact context" and compact-before-send
      provider(
        instanceId = "claude",
        driver = "claudeAgent",
        displayName = "Claude",
        showInteractionModeToggle = false,
        models = listOf(
          model("opus", "Opus", capabilities = CLAUDE_TRAITS),
          model("sonnet", "Sonnet", capabilities = CLAUDE_TRAITS),
        ),
        slashCommands = listOf(ServerProviderSlashCommand("compact", "Summarize the history")),
      ),
    ),
    serverResolvedCommandContext = true,
    capabilities = setOf(
      THREAD_SETTLEMENT,
      THREAD_AUTO_SETTLE_OPT_OUT,
      THREAD_SNOOZE,
      THREAD_TITLE_REGENERATION,
    ),
    environmentLabel = "Fixture MacBook",
    machine = "laptop",
  )

  override fun connect() {
    online.value = true
  }

  override fun disconnect() {
    online.value = false
  }

  private fun poll() {
    val files: List<Path>
    val stamp: List<Pair<String, Long>>
    try {
      files = Files.list(directory).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "json" }.toList().sortedBy { it.name }
      }
      stamp = files.map { it.name to it.getLastModifiedTime().toMillis() }
    } catch (e: Exception) {
      log.warn("Can't list fixtures in $directory", e)
      return
    }
    if (stamp == loadedStamp) return
    loadedStamp = stamp
    replies.values.forEach { it.cancel() }
    replies.clear()
    // The sequence only moves forward, or a sent message waiting on it would never retire
    overlay.value = Overlay(sequence = sequence.get())
    loaded.value = load(files)
  }

  private fun load(files: List<Path>): Scenario {
    val parts: List<Scenario> = files.map { file ->
      try {
        Scenario.decode(file.readText())
      } catch (e: Exception) {
        log.warn("Can't decode fixture ${file.name}", e)
        return Scenario.failed(file.name, e.message ?: e.javaClass.simpleName)
      }
    }
    return Scenario.merge(parts)
  }
}

private const val REPLY_STEP_MILLIS: Long = 600

/** How long the fixture's title generator takes, and the mark it leaves on the title. */
private val TITLE_REGENERATION: Duration = 2.seconds
private const val REGENERATED: String = " (regenerated)"

/** Item statuses that are still going; an interrupt cancels them. */
private val IN_FLIGHT: Set<String> = setOf("pending", "running", "waiting")

/** [item], whatever its type, closed as cancelled at [now], through its JSON to stay generic. */
private fun cancelled(item: TurnItem, now: String): TurnItem {
  val json: JsonObject = T3Json.encodeToJsonElement(TurnItem.serializer(), item).jsonObject
  val closed: Map<String, JsonElement> = buildMap {
    putAll(json)
    put("status", JsonPrimitive("cancelled"))
    put("completedAt", JsonPrimitive(now))
    put("updatedAt", JsonPrimitive(now))
    if ("streaming" in json) put("streaming", JsonPrimitive(false))
  }
  return T3Json.decodeFromJsonElement(TurnItem.serializer(), JsonObject(closed))
}

/**
 * The command's answer attachment as the item's. The known ones are the same classes, both
 * interfaces on `ChatImageAttachment` and `ChatFileAttachment`; only Unknown needs a copy.
 */
private fun answerAttachment(
  attachment: Command.RuntimeRequestRespond.AttachmentsByQuestionIdItem,
): UserInputAttachmentAnswerPayload.AttachmentsByQuestionIdItem = when (attachment) {
  is ChatImageAttachment -> attachment
  is ChatFileAttachment -> attachment
  is Command.RuntimeRequestRespond.AttachmentsByQuestionIdItem.Unknown ->
    UserInputAttachmentAnswerPayload.AttachmentsByQuestionIdItem.Unknown(
      type = attachment.type,
      id = attachment.id,
      name = attachment.name,
      mimeType = attachment.mimeType,
      sizeBytes = attachment.sizeBytes,
      raw = attachment.raw,
    )
}

// Upstream's ATTACHMENT_UPLOAD_URL_TTL_MS, contracts/src/assets.ts
private const val UPLOAD_URL_TTL_MILLIS: Long = 10 * 60_000
private const val UPLOAD_MILLIS: Long = 300
private const val UPLOAD_MAX_BYTES: Int = 1024 * 1024
private const val UPLOAD_PATH: String = "/api/attachments/fixture/"

private fun provider(
  instanceId: String,
  driver: String,
  displayName: String,
  showInteractionModeToggle: Boolean,
  supportedRuntimeModes: List<String>? = null,
  models: List<ServerProviderModel>,
  slashCommands: List<ServerProviderSlashCommand>? = null,
): ServerProvider = ServerProvider(
  instanceId = instanceId,
  driver = driver,
  displayName = displayName,
  showInteractionModeToggle = showInteractionModeToggle,
  supportedRuntimeModes = supportedRuntimeModes,
  enabled = true,
  installed = true,
  version = "fixture",
  status = "ready",
  auth = ServerProviderAuth(status = "authenticated"),
  checkedAt = "2026-10-19T06:00:00.000Z",
  models = models,
  slashCommands = slashCommands,
)

private fun model(
  slug: String,
  name: String,
  badge: String? = null,
  isLegacy: Boolean? = null,
  capabilities: ModelCapabilities? = null,
): ServerProviderModel = ServerProviderModel(
  slug = slug,
  name = name,
  badge = badge,
  isCustom = false,
  isLegacy = isLegacy,
  capabilities = capabilities,
)

/** Codex's reasoning effort, for the traits picker. */
private val CODEX_TRAITS: ModelCapabilities = ModelCapabilities(
  optionDescriptors = listOf(
    SelectProviderOptionDescriptor(
      id = "reasoningEffort",
      label = "Reasoning effort",
      options = listOf(
        ProviderOptionChoice("low", "Low"),
        ProviderOptionChoice("medium", "Medium", isDefault = true),
        ProviderOptionChoice("high", "High"),
      ),
    ),
  ),
)

/** Claude's effort, with Ultrathink going into the prompt, and fast mode. */
private val CLAUDE_TRAITS: ModelCapabilities = ModelCapabilities(
  optionDescriptors = listOf(
    SelectProviderOptionDescriptor(
      id = "effort",
      label = "Effort",
      options = listOf(
        ProviderOptionChoice("low", "Low"),
        ProviderOptionChoice("medium", "Medium"),
        ProviderOptionChoice("high", "High", isDefault = true),
        ProviderOptionChoice("max", "Max", description = "Deepest reasoning, slowest replies"),
        ProviderOptionChoice("ultrathink", "Ultrathink"),
      ),
      promptInjectedValues = listOf("ultrathink"),
    ),
    BooleanProviderOptionDescriptor(id = "fastMode", label = "Fast mode", currentValue = false),
  ),
)

private val PATCH: String = """
  diff --git a/src/main/kotlin/App.kt b/src/main/kotlin/App.kt
  --- a/src/main/kotlin/App.kt
  +++ b/src/main/kotlin/App.kt
  @@ -1,3 +1,4 @@
   fun main() {
  -  println("hello")
  +  val name: String = "fixture"
  +  println("hello, ${'$'}name")
   }

""".trimIndent()

/** [selection] on this row, and its instance as the row's provider. */
private fun Thread.withModel(selection: ModelSelection, now: String): Thread = copy(
  modelSelection = selection,
  providerInstanceId = selection.instance ?: providerInstanceId,
  updatedAt = now,
)
