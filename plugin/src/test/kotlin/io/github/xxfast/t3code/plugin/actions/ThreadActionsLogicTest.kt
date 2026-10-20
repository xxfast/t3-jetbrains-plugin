package io.github.xxfast.t3code.plugin.actions

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.plugin.ui.ProjectFilter
import io.github.xxfast.t3code.plugin.ui.SnoozePreset
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
import io.github.xxfast.t3code.protocol.generated.ThreadTitleRegeneration
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ThreadActionsLogicTest {
  // The running demo thread: a completed run, a running one, and one attached provider session
  private val running: Scenario = Scenario.decode(
    Path.of(System.getProperty("t3code.scenarios"), "default", "running.json").readText(),
  )
  private val row: Thread = running.threads.single()
  private val runs: List<Run> = running.runs.getValue(row.id)
  private val sessions: List<ProviderSession> = running.providerSessions.getValue(row.id)
  private val view: ThreadView = ThreadView(row, runs, emptyList(), providerSessions = sessions)

  @Test
  fun `nothing goes out unless connected with operate`() {
    assertEquals(NOT_CONNECTED, blockedBy(ConnectionState.Disconnected))
    assertEquals(NOT_CONNECTED, blockedBy(ConnectionState.Error("down")))
    assertEquals(READ_ONLY, blockedBy(connected("orchestration:read")))
    assertNull(blockedBy(connected("orchestration:read", OPERATE)))
  }

  @Test
  fun `every operating action is disabled by the connection first, then by the row`() {
    ThreadActionKind.entries.filter { it.requiresOperate }.forEach { kind ->
      assertEquals(NOT_CONNECTED, disabledReason(kind, ConnectionState.Connecting, row))
      assertEquals(READ_ONLY, disabledReason(kind, connected("orchestration:read"), row))
      assertEquals(NO_THREAD, disabledReason(kind, OPERATING, null))
    }
  }

  @Test
  fun `read-only keeps mark unread and copy, as threadActionRequiresOperate`() {
    val operating: List<ThreadActionKind> = ThreadActionKind.entries.filter { it.requiresOperate }
    assertEquals(ThreadActionKind.entries - operating, listOf(MARK_UNREAD, COPY))
    assertTrue(ThreadActionKind.FilterByProject in operating)
    val done: Thread = row.copy(latestRunCompletedAt = "2026-10-19T09:00:00Z")
    assertNull(disabledReason(MARK_UNREAD, connected("orchestration:read"), done))
    assertNull(disabledReason(COPY, connected("orchestration:read"), row))
    assertNull(disabledReason(COPY, ConnectionState.Disconnected, row))
    assertEquals(NO_THREAD, disabledReason(COPY, ConnectionState.Disconnected, null))
  }

  @Test
  fun `filter by project narrows to the row's project, then shows all again`() {
    val projects: List<Project> = listOf(project(row.projectId, "KStore"), project("p2", "Other"))
    val all: ProjectScope = assertNotNull(projectScope(row, projects, ProjectFilter.All))
    assertEquals("Filter by KStore", all.text)
    assertEquals(ProjectFilter.Only(setOf(row.projectId)), all.next)
    val only: ProjectScope = assertNotNull(projectScope(row, projects, all.next))
    assertEquals(ProjectScope("Show all projects", ProjectFilter.All), only)
    val both = ProjectFilter.Only(setOf(row.projectId, "p2"))
    assertEquals("Filter by KStore", projectScope(row, projects, both)?.text)
    assertNull(projectScope(row, emptyList(), ProjectFilter.All))
  }

  @Test
  fun `copy path is the worktree, else the project root, else unavailable`() {
    val projects: List<Project> = listOf(project(row.projectId, "KStore"))
    val inWorktree: Thread = row.copy(worktreePath = "/code/wt")
    assertEquals(Copied("Path copied", "/code/wt"), copyPath(inWorktree, projects))
    val local: Thread = row.copy(worktreePath = null)
    assertEquals(Copied("Path copied", "/code/${row.projectId}"), copyPath(local, projects))
    assertNull(copyPath(local, emptyList()))
  }

  @Test
  fun `undoing a settle puts the pin and the snooze back after un-settling`() {
    val plain: List<Command> = unsettleUndoCommands(row.copy(pinnedAt = null), "c")
    val unsettle: Command =
      Command.ThreadUnsettle(commandId = "c:unsettle", threadId = row.id, reason = "user")
    assertEquals(listOf(unsettle), plain)
    val pinned: Thread = row.copy(
      pinnedAt = "2026-10-19T09:00:00Z",
      pinOrderKey = "a0",
      snoozedUntil = "2026-10-20T09:00:00Z",
    )
    val types: List<String> = unsettleUndoCommands(pinned, "c").map { it.type }
    assertEquals(listOf("thread.unsettle", "thread.pin", "thread.snooze"), types)
    val repin: Command = unsettleUndoCommands(pinned, "c")[1]
    assertEquals(Command.ThreadPin(commandId = "c:pin", threadId = row.id, orderKey = "a0"), repin)
  }

  @Test
  fun `copy branch only with a branch, thread id always`() {
    assertEquals(Copied("Branch copied", "kotlinx-io-0.8"), copyBranch(row))
    assertNull(copyBranch(row.copy(branch = null)))
    assertEquals(Copied("Thread ID copied", row.id), copyThreadId(row))
  }

  @Test
  fun `interrupt needs a run in flight`() {
    assertNull(disabledReason(ThreadActionKind.Interrupt, OPERATING, row))
    val idle: Thread = row.copy(status = "completed", activeRunId = null)
    assertEquals("Nothing running", disabledReason(ThreadActionKind.Interrupt, OPERATING, idle))
  }

  @Test
  fun `archive is blocked while running, unarchive never is`() {
    assertFalse(row.canArchive)
    assertTrue(disabledReason(ThreadActionKind.Archive, OPERATING, row) != null)
    val idle: Thread = row.copy(status = "completed", activeRunId = null)
    assertNull(disabledReason(ThreadActionKind.Archive, OPERATING, idle))
    val archived: Thread = row.copy(archivedAt = "2026-10-19T09:00:00Z")
    assertNull(disabledReason(ThreadActionKind.Archive, OPERATING, archived))
  }

  @Test
  fun `can archive mirrors threadRuntimeCanArchive`() {
    assertTrue(row.copy(status = "queued", activeRunId = null).canArchive)
    assertFalse(row.copy(status = "queued", activeRunId = "r").canArchive)
    assertFalse(row.copy(status = "preparing").canArchive)
    assertFalse(row.copy(status = "starting").canArchive)
    assertTrue(row.copy(status = "waiting").canArchive)
    assertTrue(row.copy(status = "idle").canArchive)
  }

  @Test
  fun `mark unread needs a completed latest run`() {
    val inFlight: String? = disabledReason(ThreadActionKind.MarkUnread, OPERATING, row)
    assertEquals("No completed run yet", inFlight)
    val done: Thread = row.copy(latestRunCompletedAt = "2026-10-19T09:00:00Z")
    assertNull(disabledReason(ThreadActionKind.MarkUnread, OPERATING, done))
  }

  @Test
  fun `stop and pin only need the connection`() {
    assertNull(disabledReason(ThreadActionKind.Stop, OPERATING, row))
    assertNull(disabledReason(ThreadActionKind.Pin, OPERATING, row))
  }

  @Test
  fun `toggles read from the row`() {
    assertEquals("Archive thread", archiveText(row))
    assertEquals("Unarchive thread", archiveText(row.copy(archivedAt = "2026-10-19T09:00:00Z")))
    assertEquals("Archive thread", archiveText(null))
    assertEquals("Pin thread", pinText(row))
    assertEquals("Unpin thread", pinText(row.copy(pinnedAt = "2026-10-19T09:00:00Z")))
    assertIs<Command.ThreadArchive>(archiveCommand(row, "c"))
    assertIs<Command.ThreadUnarchive>(archiveCommand(row.copy(archivedAt = "x"), "c"))
    assertEquals(Command.ThreadPin(commandId = "c", threadId = row.id), pinCommand(row, "c"))
    assertIs<Command.ThreadUnpin>(pinCommand(row.copy(pinnedAt = "x"), "c"))
  }

  @Test
  fun `settle toggles on the server's settled override`() {
    val settled: Thread = row.copy(settledOverride = "settled", settledAt = "2026-10-19T09:00:00Z")
    assertEquals("Settle thread", settleText(row))
    assertEquals("Un-settle thread", settleText(settled))
    assertEquals(Command.ThreadSettle(commandId = "c", threadId = row.id), settleCommand(row, "c"))
    val unsettle = Command.ThreadUnsettle(commandId = "c", threadId = row.id, reason = "user")
    assertEquals(unsettle, settleCommand(settled, "c"))
    assertNull(disabledReason(ThreadActionKind.Settle, OPERATING, row))
  }

  @Test
  fun `snooze and wake`() {
    val until: Instant = Instant.parse("2026-10-20T09:00:00Z")
    val snooze = Command.ThreadSnooze(
      commandId = "c",
      threadId = row.id,
      snoozedUntil = "2026-10-20T09:00:00Z",
    )
    assertEquals(snooze, snoozeCommand(row, until, "c"))
    val wake = Command.ThreadUnsnooze(commandId = "c", threadId = row.id, reason = "user")
    assertEquals(wake, wakeCommand(row, "c"))
    val preset = SnoozePreset("tomorrow", "Tomorrow", "9:00 AM", until)
    assertEquals("Tomorrow (9:00 AM)", presetText(preset))
  }

  @Test
  fun `snooze waits while the thread is asking`() {
    val asking: Thread = row.copy(
      pendingRuntimeRequest = PendingRuntimeRequestSummary("r", "approval", "2026-10-19T09:00:00Z"),
    )
    assertEquals(NOT_SNOOZABLE, disabledReason(ThreadActionKind.Snooze, OPERATING, asking))
    assertNull(disabledReason(ThreadActionKind.Wake, OPERATING, asking))
  }

  @Test
  fun `rename trims, and sends nothing for the same or a blank title`() {
    assertEquals(Rename.To("New title"), rename(row, "  New title "))
    assertEquals(Rename.Unchanged, rename(row, " ${row.title} "))
    assertEquals(Rename.Empty, rename(row, "   "))
    val expected = Command.ThreadMetadataUpdate(commandId = "c", threadId = row.id, title = "T")
    assertEquals(expected, renameCommand(row, "T", "c"))
  }

  @Test
  fun `regenerate title waits for the one in flight`() {
    val regenerating: Thread =
      row.copy(titleRegeneration = ThreadTitleRegeneration("r", "2026-10-19T09:00:00Z"))
    assertEquals("Regenerate title", regenerateText(row))
    assertEquals("Regenerating…", regenerateText(regenerating))
    assertNull(disabledReason(ThreadActionKind.RegenerateTitle, OPERATING, row))
    assertTrue(disabledReason(ThreadActionKind.RegenerateTitle, OPERATING, regenerating) != null)
    val expected = Command.ThreadMetadataUpdate(
      commandId = "c",
      threadId = row.id,
      regenerateTitle = true,
    )
    assertEquals(expected, regenerateTitleCommand(row, "c"))
  }

  @Test
  fun `delete and the confirmations read as upstream's`() {
    assertEquals(Command.ThreadDelete(commandId = "c", threadId = row.id), deleteCommand(row, "c"))
    assertEquals("Delete thread \"${row.title}\"?", deletePrompt(row))
    assertEquals("Archive thread \"${row.title}\"?", archivePrompt(row))
  }

  @Test
  fun `auto-settle is on until the thread opts out`() {
    assertTrue(row.autoSettles)
    assertFalse(row.copy(autoSettleDisabledAt = "2026-10-19T09:00:00Z").autoSettles)
    val expected = Command.ThreadAutoSettleSet(commandId = "c", threadId = row.id, enabled = false)
    assertEquals(expected, autoSettleCommand(row, enabled = false, "c"))
  }

  @Test
  fun `the active run is the newest one in flight`() {
    assertEquals("run_01JC4A2K7QB8", activeRun(view)?.id)
    val first: Run = runs.first()
    val waiting: Run = first.copy(id = "w", status = "waiting")
    val queued: Run = first.copy(id = "q", status = "queued")
    val starting: Run = first.copy(id = "s", status = "starting")
    assertEquals("s", activeRun(view.copy(runs = listOf(waiting, starting, queued)))?.id)
    assertNull(activeRun(view.copy(runs = listOf(first, queued))))
  }

  @Test
  fun `interrupt holds the queue`() {
    val expected = Command.RunInterrupt(
      commandId = "c",
      threadId = row.id,
      runId = "run_01JC4A2K7QB8",
      holdQueue = true,
    )
    assertEquals(expected, interruptCommand(view, "c"))
    assertNull(interruptCommand(view.copy(runs = listOf(runs.first())), "c"))
  }

  @Test
  fun `stop detaches every provider session, with upstream's command ids`() {
    val second: ProviderSession = sessions.single().copy(id = "psn_2")
    val commands: List<Command.ProviderSessionDetach> =
      stopCommands(view.copy(providerSessions = sessions + second), "c")
    assertEquals(
      listOf(
        Command.ProviderSessionDetach(
          commandId = "c:detach:psn_01JC4A1F9ZCODEX",
          threadId = row.id,
          providerSessionId = "psn_01JC4A1F9ZCODEX",
          reason = "client-requested",
        ),
        Command.ProviderSessionDetach(
          commandId = "c:detach:psn_2",
          threadId = row.id,
          providerSessionId = "psn_2",
          reason = "client-requested",
        ),
      ),
      commands,
    )
    assertEquals(emptyList(), stopCommands(view.copy(providerSessions = emptyList()), "c"))
  }

  @Test
  fun `commands encode with their wire discriminators`() {
    val text: String = T3Json.encodeToString(Command.serializer(), stopCommands(view, "c").single())
    assertTrue("\"type\":\"provider-session.detach\"" in text, text)
    val interrupt: Command = assertNotNull(interruptCommand(view, "c"))
    assertTrue("\"holdQueue\":true" in T3Json.encodeToString(Command.serializer(), interrupt))
  }
}

private fun connected(vararg permissions: String): ConnectionState.Connected =
  ConnectionState.Connected(
    ServerSession(
      authenticated = true,
      auth = ServerAuthDescriptor(
        policy = "desktop-managed-local",
        bootstrapMethods = listOf("desktop-bootstrap"),
        sessionMethods = listOf("bearer-access-token"),
        sessionCookieName = "t3_session_3773",
      ),
      permissions = permissions.toList(),
    ),
  )

private val OPERATING: ConnectionState = connected("orchestration:read", OPERATE)

private val MARK_UNREAD: ThreadActionKind = ThreadActionKind.MarkUnread

private val COPY: ThreadActionKind = ThreadActionKind.Copy

private fun project(id: String, title: String): Project = T3Json.decodeFromString(
  Project.serializer(),
  """
    {
      "id": "$id", "title": "$title", "workspaceRoot": "/code/$id", "scripts": [],
      "createdAt": "2026-10-19T09:00:00Z", "updatedAt": "2026-10-19T09:00:00Z"
    }
  """,
)
