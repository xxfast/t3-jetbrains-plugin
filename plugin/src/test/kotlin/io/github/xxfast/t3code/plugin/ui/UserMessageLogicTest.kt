package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

// Cases from upstream's packages/client-runtime/src/userMessage.test.ts, and the fold rules of
// TimelineUserMessageRow.tsx and QueuedRunsControl.tsx
class UserMessageLogicTest {
  private fun presentation(
    text: String = "hello",
    id: String? = "msg_1",
    role: String = "user",
    createdBy: String? = "user",
    creationSource: String? = "web",
    scheduledTaskId: String? = null,
  ): UserMessagePresentation =
    resolveUserMessagePresentation(id, role, text, createdBy, creationSource, scheduledTaskId)

  @Test
  fun `what the user typed has no attribution`() {
    assertEquals(UserMessagePresentation("hello", null, null), presentation())
    assertNull(presentation(role = "assistant", createdBy = "agent").attribution)
  }

  @Test
  fun `a scheduled task's message is an automation`() {
    assertEquals(
      UserMessagePresentation("hello", "automation", "task_1"),
      presentation(scheduledTaskId = "task_1"),
    )
  }

  @Test
  fun `a legacy automation drops its prefix and keeps the task from its id`() {
    val text = "[Triggered by schedule task: Nightly]\n\nRun the checks"
    assertEquals(
      UserMessagePresentation("Run the checks", "automation", "task_9"),
      presentation(text = text, id = "scheduled-task-message:task_9:3:manual"),
    )
    assertEquals(
      UserMessagePresentation("Run the checks", "automation", null),
      presentation(text = text, id = "msg_1", createdBy = "agent"),
    )
    // From the user, the prefix is just text
    assertNull(presentation(text = text, id = "msg_1").attribution)
  }

  @Test
  fun `an agent's message is the agent's, unless T3 Code sent it`() {
    assertEquals("agent", presentation(createdBy = "agent", creationSource = "mcp").attribution)
    assertEquals("t3code", presentation(createdBy = "agent", creationSource = "server").attribution)
    assertNull(presentation(createdBy = "user", creationSource = "server").attribution)
  }

  @Test
  fun `a sent automation folds to its first line`() {
    val automation: UserMessagePresentation =
      presentation(text = "  Nightly check\nRun everything", scheduledTaskId = "task_1")
    assertEquals(
      MessageFold("calendar-clock", "Automation", "Nightly check"),
      sentMessageFold(automation, automation.text, senderTitle = null),
    )
  }

  @Test
  fun `a sent agent message folds to its first non-blank line, under its sender`() {
    val agent: UserMessagePresentation = presentation(createdBy = "agent", creationSource = "mcp")
    val text = "\n  \n  Survey done: 3 block 1.0  \nThe rest"
    assertEquals(
      MessageFold("messages-square", "Survey the open issues", "Survey done: 3 block 1.0"),
      sentMessageFold(agent, text, senderTitle = " Survey the open issues "),
    )
    assertEquals("Agent", sentMessageFold(agent, text, senderTitle = "  ")?.label)
    assertEquals("Agent", sentMessageFold(agent, text, senderTitle = null)?.label)
  }

  @Test
  fun `only automations and agents fold`() {
    assertNull(sentMessageFold(presentation(), "hello", senderTitle = null))
    val t3code: UserMessagePresentation =
      presentation(createdBy = "agent", creationSource = "server")
    assertNull(sentMessageFold(t3code, "hello", senderTitle = null))
    assertNull(queuedMessageFold(t3code, "hello", senderTitle = null))
    assertNull(queuedMessageFold(null, "hello", senderTitle = null))
  }

  @Test
  fun `a queued message folds as its sent form does, from its preview`() {
    val agent: UserMessagePresentation = presentation(createdBy = "agent", creationSource = "mcp")
    assertEquals(
      MessageFold("messages-square", "Agent", "Survey done"),
      queuedMessageFold(agent, "\n\nSurvey done\nThe rest", senderTitle = null),
    )
    val automation: UserMessagePresentation = presentation(scheduledTaskId = "task_1")
    assertEquals(
      MessageFold("calendar-clock", "Automation", "Nightly"),
      queuedMessageFold(automation, "Nightly\r\nRun everything", senderTitle = null),
    )
  }

  @Test
  fun `context links read as their labels`() {
    val text = "Fix [Foo.kt:12](t3-context://v1/review-comment/rc_1) now"
    assertEquals("Fix Foo.kt:12 now", replaceComposerContextReferences(text) { it.label })
    assertEquals("plain", replaceComposerContextReferences("plain") { it.label })
  }
}
