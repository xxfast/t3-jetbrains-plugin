package io.github.xxfast.t3code.plugin.actions

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.plugin.ui.SnoozePreset
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.ProviderSession
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.ServerSession
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.PendingRuntimeRequestSummary
import io.github.xxfast.t3code.protocol.generated.ServerAuthDescriptor
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
  fun `every action is disabled by the connection first, then by the row`() {
    ThreadActionKind.entries.forEach { kind ->
      assertEquals(NOT_CONNECTED, disabledReason(kind, ConnectionState.Connecting, row))
      assertEquals(READ_ONLY, disabledReason(kind, connected("orchestration:read"), row))
      assertEquals(NO_THREAD, disabledReason(kind, OPERATING, null))
    }
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
