package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.plugin.ui.nextModelSelection
import io.github.xxfast.t3code.plugin.ui.setThreadInteractionMode
import io.github.xxfast.t3code.plugin.ui.setThreadRuntimeMode
import io.github.xxfast.t3code.plugin.ui.slug
import io.github.xxfast.t3code.plugin.ui.updateThreadModelSelection
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class PendingSettingsTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val thread: Thread = Scenario
    .decode(scenarios.resolve("default").resolve("list.json").readText())
    .threads
    .first { it.runtimeMode == "approval-required" && it.interactionMode == "default" }

  private val fullAccess: Command = setThreadRuntimeMode(thread.id, "full-access", "c1")

  @Test
  fun `a pick shows at once, over the row`() {
    val picked: PendingSettings = PendingSettings().picked(fullAccess)
    assertEquals("full-access", picked.runtimeMode(thread))
    assertEquals("default", picked.interactionMode(thread), "Untouched settings read the row")
    assertSame(picked, picked.picked(Command.ThreadPin(commandId = "c", threadId = thread.id)))
  }

  @Test
  fun `an undelivered pick stays, even when the row agrees`() {
    val picked: PendingSettings = PendingSettings().picked(fullAccess)
    assertSame(picked, picked.reconciled(thread.copy(runtimeMode = "full-access"), 99))
  }

  @Test
  fun `a delivered pick retires when the row agrees or the view is past it`() {
    val delivered: PendingSettings = PendingSettings().picked(fullAccess).delivered(fullAccess, 9)
    assertSame(delivered, delivered.reconciled(thread, sequence = 8))
    assertEquals(PendingSettings(), delivered.reconciled(thread, sequence = 9))
    val agreed: Thread = thread.copy(runtimeMode = "full-access")
    assertEquals(PendingSettings(), delivered.reconciled(agreed, sequence = 8))
  }

  @Test
  fun `plan then build keeps build until its own dispatch lands`() {
    val plan: Command = setThreadInteractionMode(thread.id, "plan", "c1")
    val build: Command = setThreadInteractionMode(thread.id, "default", "c2")
    // Build undoes Plan before either is answered; the row still says build
    val both: PendingSettings = PendingSettings().picked(plan).picked(build)
    assertEquals("default", both.interactionMode(thread))
    val planLanded: PendingSettings = both.delivered(plan, 5).reconciled(thread, 5)
    assertEquals("default", planLanded.interactionMode(thread.copy(interactionMode = "plan")))

    val buildLanded: PendingSettings = planLanded.delivered(build, 6)
    assertEquals(PendingSettings(), buildLanded.reconciled(thread, 6))
  }

  @Test
  fun `a refusal puts the row back, unless something newer was picked`() {
    val picked: PendingSettings = PendingSettings().picked(fullAccess)
    assertEquals("approval-required", picked.failed(fullAccess).runtimeMode(thread))

    val auto: Command = setThreadRuntimeMode(thread.id, "auto", "c2")
    val newer: PendingSettings = picked.picked(auto)
    assertEquals("auto", newer.failed(fullAccess).runtimeMode(thread))
    assertSame(newer, newer.delivered(fullAccess, 5))
  }

  @Test
  fun `a model pick is tracked the same way, by instance and slug`() {
    val opus: ModelSelection = nextModelSelection(thread.modelSelection, "claude", "opus")
    val command: Command = updateThreadModelSelection(thread.id, opus, "codex", true, "c1")
    val picked: PendingSettings = PendingSettings().picked(command).delivered(command, 3)
    assertEquals("opus", picked.modelSelection(thread).slug)
    assertEquals(PendingSettings(), picked.reconciled(thread.copy(modelSelection = opus), 0))
  }
}
