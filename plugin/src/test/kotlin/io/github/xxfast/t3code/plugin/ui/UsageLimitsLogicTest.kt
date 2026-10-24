package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageLimits
import io.github.xxfast.t3code.protocol.generated.ServerProviderUsageWindow
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class UsageLimitsLogicTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
  private val now: Long = Instant.parse("2026-10-24T06:00:00Z").toEpochMilli()

  @Test
  fun `the default scenario's usage limits decode`() {
    val limits: Map<String, ServerProviderUsageLimits> = Scenario
      .decode(scenarios.resolve("default").resolve("usage-limits.json").readText())
      .providerUsageLimits
    assertEquals(setOf("claude", "codex", "cursor"), limits.keys)
    assertEquals("Could not read limits.", limitsNotice(limits.getValue("cursor")))
  }

  @Test
  fun `only enabled, installed, available providers with limits count`() {
    val limits = ServerProviderUsageLimits(checkedAt = "2026-10-24T06:00:00Z", windows = listOf())
    val shown: List<ServerProvider> = providersWithLimits(
      listOf(
        provider("a", usageLimits = limits),
        provider("b", usageLimits = limits, enabled = false),
        provider("c", usageLimits = limits, availability = "unavailable"),
        provider("d"),
      ),
    )
    assertEquals(listOf("a"), shown.map { it.instanceId })
  }

  @Test
  fun `a notice beats windows, as upstream's limitsNotice`() {
    val window: ServerProviderUsageWindow = window(usedPercent = 10.0)
    val unsupported = ServerProviderUsageLimits(
      checkedAt = "2026-10-24T06:00:00Z",
      windows = listOf(window),
      unavailable = ServerProviderUsageLimits.Unavailable(reason = "unsupported"),
    )
    assertEquals("This account has no subscription limits.", limitsNotice(unsupported))
    assertEquals(
      "No limits reported.",
      limitsNotice(unsupported.copy(windows = emptyList(), unavailable = null)),
    )
    assertNull(limitsNotice(unsupported.copy(unavailable = null)))
  }

  @Test
  fun `remaining rounds and clamps`() {
    assertEquals(58, remainingPercent(window(usedPercent = 42.4)))
    assertEquals(0, remainingPercent(window(usedPercent = 140.0)))
    assertEquals(100, remainingPercent(window(usedPercent = -3.0)))
  }

  @Test
  fun `pace is the share of the window left`() {
    // A 5-hour window resetting in 2 hours has 40% of it left
    val window: ServerProviderUsageWindow =
      window(resetsAt = "2026-10-24T08:00:00Z", windowDurationMins = 300)
    assertEquals(40, paceRemainingPercent(window, now))
    assertNull(paceRemainingPercent(window.copy(windowDurationMins = null), now))
  }

  @Test
  fun `resets in reads as upstream's`() {
    assertEquals("resets in 2h 14m", formatResetsIn(window(resetsAt = "2026-10-24T08:14:00Z"), now))
    assertEquals("resets in 4d 6h", formatResetsIn(window(resetsAt = "2026-10-28T12:00:00Z"), now))
    val soon: ServerProviderUsageWindow = window(resetsAt = "2026-10-24T06:00:30Z")
    assertEquals("Resets momentarily", formatResetsIn(soon, now))
    assertEquals("resets now", formatResetsIn(window(resetsAt = "2026-10-24T05:00:00Z"), now))
    assertNull(formatResetsIn(window(), now))
  }

  @Test
  fun `the account label adds the instance only when it's another name`() {
    assertEquals("Claude", usageAccountLabel(provider("claude", driver = "claudeAgent")))
    assertEquals(
      "Codex · Work",
      usageAccountLabel(provider("codex_work", driver = "codex", displayName = "Work")),
    )
    assertEquals("Codex · codex_work", usageAccountLabel(provider("codex_work", driver = "codex")))
  }

  @Test
  fun `a segment is the tightest window, and none for a notice`() {
    val limits = ServerProviderUsageLimits(
      checkedAt = "2026-10-24T06:00:00Z",
      windows = listOf(window("session", 83.0), window("weekly", 47.0)),
    )
    val failed = limits.copy(
      unavailable = ServerProviderUsageLimits.Unavailable(reason = "probeFailed"),
    )
    val segments: List<UsageSegment> = usageSegments(
      listOf(
        provider("codex", driver = "codex", usageLimits = limits),
        provider("cursor", driver = "cursor", usageLimits = failed),
      ),
    )
    assertEquals(listOf("session"), segments.map { it.window.id })
    assertEquals(17, segments.single().remaining)
    assertEquals(UsageLevel.Low, segments.single().level)
    val spent = limits.copy(windows = listOf(window("weekly", 100.0)))
    assertEquals(
      UsageLevel.Exhausted,
      usageSegments(listOf(provider("codex", usageLimits = spent))).single().level,
    )
  }

  private fun window(
    id: String = "five_hour",
    usedPercent: Double = 0.0,
    resetsAt: String? = null,
    windowDurationMins: Long? = null,
  ) = ServerProviderUsageWindow(
    id = id,
    kind = "session",
    label = id,
    usedPercent = usedPercent,
    resetsAt = resetsAt,
    windowDurationMins = windowDurationMins,
  )

  private fun provider(
    instanceId: String,
    driver: String = instanceId,
    displayName: String? = null,
    usageLimits: ServerProviderUsageLimits? = null,
    enabled: Boolean = true,
    availability: String? = null,
  ) = ServerProvider(
    instanceId = instanceId,
    driver = driver,
    displayName = displayName,
    enabled = enabled,
    installed = true,
    version = null,
    status = "ready",
    auth = ServerProviderAuth(status = "authenticated"),
    checkedAt = "2026-10-24T06:00:00Z",
    availability = availability,
    models = emptyList(),
    usageLimits = usageLimits,
  )
}
