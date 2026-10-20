package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ProviderTurnTokenUsage
import io.github.xxfast.t3code.protocol.generated.ThreadTokenUsageSnapshot
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ContextWindowLogicTest {
  @Test
  fun `tokens format like upstream's meter`() {
    assertEquals("0", formatContextWindowTokens(null))
    assertEquals("950", formatContextWindowTokens(950))
    assertEquals("1.2k", formatContextWindowTokens(1_234))
    assertEquals("5k", formatContextWindowTokens(5_000))
    assertEquals("301k", formatContextWindowTokens(301_400))
    assertEquals("1m", formatContextWindowTokens(999_600))
    assertEquals("1.5m", formatContextWindowTokens(1_500_000))
  }

  @Test
  fun `percentages keep a decimal under ten`() {
    assertEquals("4.5%", formatContextWindowPercentage(4.5))
    assertEquals("4%", formatContextWindowPercentage(4.0))
    assertEquals("43%", formatContextWindowPercentage(42.6))
    assertNull(formatContextWindowPercentage(null))
  }

  @Test
  fun `live usage wins, with a percentage when the size is known`() {
    val live = ProviderTurnTokenUsage(
      usedTokens = 50_000,
      maxTokens = 200_000,
      updatedAt = "2026-10-20T10:00:00Z",
    )
    val snapshot: ContextWindowSnapshot =
      deriveLatestContextWindowSnapshot(emptyList(), live, null)!!
    assertEquals(50_000, snapshot.usedTokens)
    assertEquals(25.0, snapshot.usedPercentage)
    assertEquals(true, snapshot.compactsAutomatically)
  }

  @Test
  fun `nothing to show without usage or a compaction`() {
    assertNull(deriveLatestContextWindowSnapshot(emptyList(), null, null))
  }

  @Test
  fun `the compaction message names the threshold or the model`() {
    assertEquals(
      "Compacts automatically at 160,000 tokens.",
      formatContextWindowCompactionMessage("Opus", 160_000),
    )
    assertEquals(
      "Context for Opus compacts automatically when needed.",
      formatContextWindowCompactionMessage("Opus", null),
    )
    assertEquals(
      "Context compacts automatically when needed.",
      formatContextWindowCompactionMessage(null, null),
    )
  }

  @Test
  fun `cost shows four places under a cent`() {
    assertEquals("USD 0.0040", formatContextWindowCost(ThreadTokenUsageSnapshot.Cost(0.004, "USD")))
    assertEquals("USD 1.25", formatContextWindowCost(ThreadTokenUsageSnapshot.Cost(1.25, "USD")))
  }

  @Test
  fun `compact before send offers on a big cold Claude thread`() {
    val updated = "2026-10-20T10:00:00Z"
    val later: Instant = Instant.parse("2026-10-20T11:15:00Z")
    assertTrue(shouldOfferResumeCompaction("claudeAgent", 301_000, updated, null, later))
    // Warm cache, small thread, or another provider
    val soon: Instant = Instant.parse("2026-10-20T10:30:00Z")
    assertFalse(shouldOfferResumeCompaction("claudeAgent", 301_000, updated, null, soon))
    assertFalse(shouldOfferResumeCompaction("claudeAgent", 99_999, updated, null, later))
    assertFalse(shouldOfferResumeCompaction("codex", 301_000, updated, null, later))
    // A reported cache lifetime replaces the 70 minute fallback
    assertTrue(shouldOfferResumeCompaction("claudeAgent", 301_000, updated, 300_000, soon))
  }
}
