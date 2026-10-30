package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Subagent
import io.github.xxfast.t3code.protocol.T3Json
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SubagentRowsLogicTest {
  @Test
  fun `statuses read as upstream's STATUS_VISUALS`() {
    assertEquals("Queued", subagentStatusVisual("pending").label)
    assertEquals("Idle · resumable", subagentStatusVisual("idle").label)
    assertEquals(SubagentStatusTone.Stopped, subagentStatusVisual("interrupted").tone)
    assertEquals("Stopped", subagentStatusVisual("cancelled").label)
    assertEquals(SubagentStatusTone.Destructive, subagentStatusVisual("failed").tone)
  }

  @Test
  fun `the detail is the result once settled, the progress while working`() {
    assertEquals("Found 3", subagentTimelineDetail("completed", "Found 3", "Reading"))
    assertEquals("Reading", subagentTimelineDetail("running", "Found 3", "Reading"))
    assertEquals("Found 3", subagentTimelineDetail("running", "Found 3", "  "))
    assertEquals("Reading", subagentTimelineDetail("failed", null, "Reading"))
    assertNull(subagentTimelineDetail("completed", "Child task ended with status completed", null))
    assertNull(subagentTimelineDetail("running", null, null))
  }

  @Test
  fun `plain detail drops links, ticks and bullets`() {
    assertEquals(
      "Read KStore.kt and the docs",
      plainDetail("- Read `KStore.kt`\n* and [the docs](https://example.com)"),
    )
  }

  @Test
  fun `the row prefers the projection over the item`() {
    val item: TurnItem.TurnItemSubagent = item(status = "running", progress = "Starting")
    val live: Subagent = agent(status = "failed", result = "Tests failed on iOS")
    val state: SubagentTimelineLinkState = subagentTimelineLink(item, live)
    assertEquals("failed", state.status)
    assertEquals("Tests failed on iOS", state.detail)
    assertTrue(state.showsInlineStatus)
    assertTrue(state.failed)
    assertEquals("Code Reviewer", state.title)
    assertEquals("child", state.threadId)
    val done: SubagentTimelineLinkState =
      subagentTimelineLink(item, agent(status = "completed", result = "Done"))
    assertFalse(done.showsInlineStatus)
  }

  @Test
  fun `elapsed counts like AgentElapsed`() {
    assertEquals("59s", formatElapsedSeconds(59))
    assertEquals("1m 00s", formatElapsedSeconds(60))
    assertEquals("12m 05s", formatElapsedSeconds(725))
    assertEquals("1h 00m", formatElapsedSeconds(3_600))
    assertEquals("0s", formatElapsedSeconds(-4))
  }

  @Test
  fun `a group sums its statuses in upstream's order`() {
    val statuses: List<String> = listOf("completed", "failed", "running", "idle", "cancelled")
    assertEquals(
      "1 working · 1 done · 1 failed · 1 stopped · 1 idle",
      summarizeSubagentStatuses(statuses),
    )
    val summary: SubagentGroupSummary = subagentGroupSummary(statuses)
    assertEquals("Kicked off 5 subagents", summary.label)
    assertTrue(summary.active)
    assertTrue(summary.failed)
    assertEquals("Ran 1 subagent", subagentGroupSummary(listOf("completed")).label)
  }

  @Test
  fun `a group runs from the first start to the last end`() {
    fun member(status: String, started: String?, completed: String?): SubagentGroupMember =
      SubagentGroupMember(item(), status, started, completed)
    val settled: SubagentTiming = subagentGroupTiming(
      listOf(
        member("completed", "2026-10-21T07:31:00Z", "2026-10-21T07:35:00Z"),
        member("failed", "2026-10-21T07:30:00Z", "2026-10-21T07:38:00Z"),
      ),
    )
    assertEquals(
      SubagentTiming("completed", "2026-10-21T07:30:00Z", "2026-10-21T07:38:00Z"),
      settled,
    )
    val unknownEnd: SubagentTiming = subagentGroupTiming(
      listOf(member("completed", "2026-10-21T07:31:00Z", null)),
    )
    assertNull(unknownEnd.completedAt)
    val live: SubagentTiming = subagentGroupTiming(
      listOf(
        member("running", "2026-10-21T07:31:00Z", null),
        member("completed", "2026-10-21T07:30:00Z", "2026-10-21T07:38:00Z"),
      ),
    )
    assertEquals("running", live.status)
    assertNull(live.completedAt)
  }

  @Test
  fun `the tooltip preview trims to 280 characters`() {
    assertEquals("a b", subagentDetailPreview("completed", " a\n b ", null))
    val long: String = "x".repeat(300)
    assertEquals("x".repeat(280) + "…", subagentDetailPreview("running", null, long))
    assertNull(subagentDetailPreview("running", null, null))
  }

  @Test
  fun `model slugs read as upstream names them`() {
    assertEquals("GPT-5-Codex", formatModelSlugName("gpt-5-codex"))
    assertEquals("Claude Opus 4.5", formatModelSlugName("claude-opus-4-5"))
    assertEquals("sonnet", formatModelSlugName("sonnet"))
  }

  private fun item(
    status: String = "completed",
    progress: String? = null,
  ): TurnItem.TurnItemSubagent = T3Json.decodeFromString(
    TurnItem.serializer(),
    """
      {
        "type": "subagent", "id": "i", "threadId": "t", "runId": "r", "nodeId": null,
        "providerThreadId": null, "providerTurnId": "p", "nativeItemRef": null,
        "parentItemId": null, "ordinal": 1, "status": "$status",
        "title": "Subagent: /root/review/code_reviewer", "startedAt": "2026-10-21T07:46:00Z",
        "completedAt": null, "updatedAt": "2026-10-21T07:46:00Z", "subagentId": "s",
        "origin": "app_owned", "driver": "codex", "providerInstanceId": "codex",
        "childThreadId": "child", "prompt": "Review", "result": null
        ${progress?.let { ", \"progress\": \"$it\"" }.orEmpty()}
      }
    """,
  ) as TurnItem.TurnItemSubagent

  private fun agent(status: String, result: String?): Subagent = T3Json.decodeFromString(
    Subagent.serializer(),
    """
      {
        "id": "s", "threadId": "t", "runId": "r", "parentNodeId": "n", "origin": "app_owned",
        "createdBy": "agent", "driver": "codex", "providerInstanceId": "codex",
        "providerThreadId": null, "childThreadId": "child", "nativeTaskRef": null,
        "prompt": "Review", "title": "Code reviewer", "model": "gpt-5", "status": "$status",
        "result": ${result?.let { "\"$it\"" } ?: "null"}, "startedAt": "2026-10-21T07:46:00Z",
        "completedAt": "2026-10-21T07:50:00Z", "updatedAt": "2026-10-21T07:50:00Z"
      }
    """,
  )
}
