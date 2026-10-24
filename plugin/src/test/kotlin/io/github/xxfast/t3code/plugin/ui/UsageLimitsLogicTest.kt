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
  fun `an account is named by its instance, its email, then its driver`() {
    val work: ServerProvider = provider("codex_work", driver = "codex", displayName = "Work")
    assertEquals("Work", limitAccountName(work))
    assertEquals("Claude", limitAccountName(provider("claude", driver = "claudeAgent")))
  }

  @Test
  fun `codex pools two accounts by kind, with a column each`() {
    val session = window("primary", 40.0, "2026-10-24T08:00:00Z", 300)
    val weekly = window("secondary", 10.0, "2026-10-28T00:00:00Z", 10080, kind = "weekly")
    val limits = ServerProviderUsageLimits("2026-10-24T06:00:00Z", listOf(session, weekly))
    val work = provider("codex_work", driver = "codex", usageLimits = limits)
    val home = provider(
      "codex",
      usageLimits = limits.copy(
        windows = listOf(session.copy(id = "secondary", usedPercent = 60.0)),
      ),
    )
    val pool: LimitPool = collectLimitPools(listOf(work, home), now).single()
    assertEquals(listOf("session", "weekly"), pool.windows.map { it.id })
    val pooled: LimitPoolWindow = pool.windows.first()
    assertEquals(50, pooled.remainingPercent)
    assertEquals(2, pooled.columns.size)
    // Same reset, so by name: `codex` first, and it has no weekly window
    assertEquals(listOf("codex", "codex_work"), pool.accounts.map { it.instanceId })
    assertEquals(listOf(false, true), pool.windows.last().columns.map { it.member != null })
  }

  @Test
  fun `a session counts as spent while the weekly window is`() {
    val session = window("five_hour", 20.0, "2026-10-24T08:00:00Z", 300)
    val weekly = window("seven_day", 100.0, "2026-10-26T00:00:00Z", 10080, kind = "weekly")
    val claude = provider(
      "claude",
      driver = "claudeAgent",
      usageLimits = ServerProviderUsageLimits("2026-10-24T06:00:00Z", listOf(session, weekly)),
    )
    val pooled: LimitPoolWindow = collectLimitPools(listOf(claude), now).single().windows.first()
    assertEquals(0, pooled.remainingPercent)
    // The session resets first, so the weekly reset is when it comes back, all of it
    assertEquals("2026-10-26T00:00:00Z", pooled.members.single().window.resetsAt)
    assertEquals(100.0, pooled.members.single().refillPercent)
    assertNull(pooled.pace)
  }

  @Test
  fun `cursor drops overall when both pools report, and notices skip unsupported`() {
    val windows = listOf("totalPercentUsed", "apiPercentUsed", "autoPercentUsed")
      .map { id -> window(id, 10.0, kind = "monthly") }
    val cursor = provider(
      "cursor",
      usageLimits = ServerProviderUsageLimits("2026-10-24T06:00:00Z", windows),
    )
    val pool: LimitPool = collectLimitPools(listOf(cursor), now).single()
    assertEquals(
      listOf("autoPercentUsed", "apiPercentUsed"),
      displayLimitWindows(pool).map { it.id },
    )
    val unsupported = ServerProviderUsageLimits(
      checkedAt = "2026-10-24T06:00:00Z",
      windows = emptyList(),
      unavailable = ServerProviderUsageLimits.Unavailable(reason = "unsupported"),
    )
    val failed =
      unsupported.copy(unavailable = ServerProviderUsageLimits.Unavailable("probeFailed"))
    assertEquals(
      listOf("Pi: Could not read limits."),
      collectLimitNotices(
        listOf(
          provider("claude", usageLimits = unsupported),
          provider("pi", displayName = "Pi", usageLimits = failed),
        ),
      ),
    )
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
    kind: String = "session",
  ) = ServerProviderUsageWindow(
    id = id,
    kind = kind,
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
