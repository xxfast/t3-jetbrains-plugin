package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.ConversationMessage
import io.github.xxfast.t3code.protocol.generated.TurnItem

// Who sent a user-role message: upstream's `packages/client-runtime/src/userMessage.ts`, and the
// fold rules of `apps/web/src/components/chat/TimelineUserMessageRow.tsx` and
// `QueuedRunsControl.tsx`, at the tag in contracts/T3CODE_VERSION. Guests aren't ported: sharing
// isn't in the plugin.

/** `resolveUserMessagePresentation`'s answer, userMessage.ts:24. */
data class UserMessagePresentation(
  val text: String,
  /** `"automation"`, `"agent"`, `"t3code"`, or null for what the user typed. */
  val attribution: String?,
  val scheduledTaskId: String?,
)

private val LEGACY_AUTOMATION_PREFIX: Regex =
  Regex("^\\[Triggered by schedule task: [^\\r\\n]+\\]\\r?\\n\\r?\\n")
private val LEGACY_AUTOMATION_MESSAGE_ID: Regex =
  Regex("^scheduled-task-message:(.+):\\d+:(?:scheduled|manual)$")

/** `resolveUserMessagePresentation`, userMessage.ts:24: who a user-role message came from. */
fun resolveUserMessagePresentation(
  id: String?,
  role: String,
  text: String,
  createdBy: String?,
  creationSource: String?,
  scheduledTaskId: String?,
): UserMessagePresentation {
  if (role != "user") return UserMessagePresentation(text, null, null)
  if (scheduledTaskId != null) {
    return UserMessagePresentation(text, "automation", scheduledTaskId)
  }
  val legacyPrefix: MatchResult? = LEGACY_AUTOMATION_PREFIX.find(text)
  val legacyTaskId: String? = legacyPrefix
    ?.let { LEGACY_AUTOMATION_MESSAGE_ID.find(id.orEmpty())?.groupValues?.get(1) }
  if (legacyPrefix != null && (legacyTaskId != null || createdBy == "agent")) {
    return UserMessagePresentation(
      text = text.substring(legacyPrefix.value.length),
      attribution = "automation",
      scheduledTaskId = legacyTaskId,
    )
  }
  val attribution: String? = when {
    createdBy != "agent" -> null
    creationSource == "server" -> "t3code"
    else -> "agent"
  }
  return UserMessagePresentation(text, attribution, null)
}

/** A sent message, as the timeline's `UserMessageRow` reads it. */
fun resolveUserMessagePresentation(message: TurnItem.UserMessage): UserMessagePresentation =
  resolveUserMessagePresentation(
    id = message.messageId,
    role = "user",
    text = message.text,
    createdBy = message.createdBy,
    creationSource = message.creationSource,
    scheduledTaskId = message.scheduledTaskId,
  )

/** A queued run's message, as `QueuedMessage` reads it. */
fun resolveUserMessagePresentation(message: ConversationMessage): UserMessagePresentation =
  resolveUserMessagePresentation(
    id = message.id,
    role = message.role,
    text = message.text,
    createdBy = message.createdBy,
    creationSource = message.creationSource,
    scheduledTaskId = message.scheduledTaskId,
  )

/** A prompt history entry, as `navigatePromptHistory` reads it. */
fun resolveUserMessagePresentation(
  message: ComposerPromptHistoryMessage,
): UserMessagePresentation = resolveUserMessagePresentation(
  id = message.id,
  role = message.role,
  text = message.text,
  createdBy = message.createdBy,
  creationSource = message.creationSource,
  scheduledTaskId = message.scheduledTaskId,
)

/** What `FoldedMessageHeader` (TimelineUserMessageRow.tsx:244) shows: icon, label, summary. */
data class MessageFold(val icon: String, val label: String, val summary: String)

/** `senderShell?.title.trim() || "Agent"`, TimelineUserMessageRow.tsx:329. */
fun senderLabel(senderTitle: String?): String =
  senderTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Agent"

/**
 * A sent row's fold, null when it shows in full. `AutomationMessageRow` folds an automation to
 * its first line (TimelineUserMessageRow.tsx:152), `UserTimelineRow` an agent's message to its
 * first non-blank one (:351). [plainText] is the text with each context link as its label.
 * Upstream leads an automation with its task's title; `scheduledTasksLive` isn't in the plugin.
 */
fun sentMessageFold(
  presentation: UserMessagePresentation,
  plainText: String,
  senderTitle: String?,
): MessageFold? = when (presentation.attribution) {
  "automation" -> MessageFold(
    icon = "calendar-clock",
    label = "Automation",
    summary = presentation.text.trim().split(LINE_BREAK, limit = 2).first(),
  )

  "agent" -> MessageFold(
    icon = "messages-square",
    label = senderLabel(senderTitle),
    summary = plainText.split(LINE_BREAK).find { it.trim().isNotEmpty() }?.trim().orEmpty(),
  )

  else -> null
}

/**
 * A queued row's fold, `fold` in `QueuedMessage` (QueuedRunsControl.tsx:415): the same as its
 * sent form, from the row's [previewText].
 */
fun queuedMessageFold(
  presentation: UserMessagePresentation?,
  previewText: String,
  senderTitle: String?,
): MessageFold? = when (presentation?.attribution) {
  "automation" -> MessageFold(
    icon = "calendar-clock",
    label = "Automation",
    summary = previewText.split(LINE_BREAK, limit = 2).first(),
  )

  "agent" -> MessageFold(
    icon = "messages-square",
    label = senderLabel(senderTitle),
    summary = previewText.split(LINE_BREAK).find { it.trim().isNotEmpty() }.orEmpty(),
  )

  else -> null
}

/** The "Sent by T3 Code" line over a message T3 Code sent itself. */
const val SENT_BY_T3_CODE: String = "Sent by T3 Code"

private val LINE_BREAK: Regex = Regex("\\r?\\n")
