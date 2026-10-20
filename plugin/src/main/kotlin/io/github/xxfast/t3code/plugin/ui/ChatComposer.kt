package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.typography

/**
 * The box at the bottom of the ChatView, upstream's `ChatComposer.tsx`: a [ComposerSurface] with
 * the prompt on top (`data-chat-composer-body`) and the footer below
 * (`data-chat-composer-footer`): [ComposerControls] on the left, the paperclip and
 * [ComposerPrimaryActions] on the right. Plain text only. While the thread waits on you,
 * [pending] takes the body over: an approval hides the prompt and the footer, a question shows
 * its own.
 */
@Composable
fun ChatComposer(
  state: ComposerState,
  draft: TextFieldState,
  onSend: (text: String, mode: ComposerDispatchMode) -> Unit,
  modifier: Modifier = Modifier,
  onInterrupt: () -> Unit = {},
  pending: PendingRequest? = null,
  pendingActions: PendingActions = PendingActions(),
  footer: ComposerFooterState? = null,
  footerActions: ComposerFooterActions = ComposerFooterActions(),
  extras: DraftExtras = DraftExtras(),
  attachmentActions: AttachmentActions = AttachmentActions(),
  contextWindow: ComposerContextWindow = ComposerContextWindow(usage = null),
  onClearReview: () -> Unit = {},
) {
  // [alternate] is Ctrl/Cmd+Enter or Ctrl/Cmd-click while running: the other follow-up behaviour
  fun send(alternate: Boolean) {
    val text: String = draft.text.toString()
    if (!sendable(text, state, extras)) return
    onSend(text.trim(), resolveComposerDispatchMode(state.running, alternate, state.queue))
  }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (state.reviewComments > 0) ReviewBanner(state.reviewComments, onClearReview)
    ComposerSurface(
      modifier = Modifier
        .fillMaxWidth()
        .fileDropTarget(
          enabled = state.enabled && pending == null,
          onFiles = attachmentActions.onFiles,
          onRefused = attachmentActions.onRefused,
        ),
    ) {
      Column {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .padding(bottom = if (pending is PendingRequest.Approval) 16.dp else 8.dp),
        ) {
          // Above whatever takes the box, so context added during an approval still shows
          ComposerChips(extras, state.enabled, attachmentActions)
          when (pending) {
            is PendingRequest.Approval ->
              ComposerPendingApprovalPanel(pending, state.enabled, pendingActions)

            is PendingRequest.UserInput ->
              ComposerPendingUserInputPanel(pending, state.enabled, pendingActions)

            null -> ComposerPromptEditor(state, draft, attachmentActions, ::send)
          }
        }
        if (pending == null) {
          ComposerFooter(
            state = state,
            draft = draft,
            hasSendableContent = sendable(draft.text.toString(), state, extras),
            footer = footer,
            footerActions = footerActions,
            attachmentActions = attachmentActions,
            contextWindow = contextWindow,
            onSend = ::send,
            onInterrupt = onInterrupt,
          )
        }
      }
    }
    state.disabledReason?.let { reason ->
      Text(reason, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.info)
    }
    state.error?.let { error ->
      Text(error, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.error)
    }
  }
}

/**
 * The prompt, upstream's `ComposerPromptEditor`: borderless, `leading-relaxed`, at least 78dp
 * (`min-h-19.5`) and at most 208dp (`max-h-52`) before it scrolls.
 */
@Composable
private fun ComposerPromptEditor(
  state: ComposerState,
  draft: TextFieldState,
  attachmentActions: AttachmentActions,
  onSend: (alternate: Boolean) -> Unit,
) {
  TextArea(
    state = draft,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 78.dp, max = 208.dp)
      .attachmentTarget(state.enabled, attachmentActions)
      .composerKeys(draft, state.running, onSend),
    enabled = state.enabled,
    lineLimits = TextFieldLineLimits.MultiLine(),
    // Upstream's short placeholder (ChatComposer.tsx:6695). The long one offers @tags,
    // skills and slash commands, which this plugin doesn't have.
    placeholder = { Text("Ask anything...", color = JewelTheme.globalColors.text.info) },
    undecorated = true,
  )
}

/**
 * `data-chat-composer-footer`: the controls on the left, scrolling sideways when they don't fit
 * (`overflow-x-auto`), and the actions on the right (`data-chat-composer-actions`).
 */
@Composable
private fun ComposerFooter(
  state: ComposerState,
  draft: TextFieldState,
  hasSendableContent: Boolean,
  footer: ComposerFooterState?,
  footerActions: ComposerFooterActions,
  attachmentActions: AttachmentActions,
  contextWindow: ComposerContextWindow,
  onSend: (alternate: Boolean) -> Unit,
  onInterrupt: () -> Unit,
) {
  Row(
    // The model picker's `-ms-2.5` pulls its icon back to the prompt's edge
    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, bottom = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      footer?.let { ComposerControls(it, state.enabled, footerActions, draft) }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AttachButton(state.enabled, attachmentActions)
      // `ComposerFooterPrimaryActions`: the meter, then the primary actions
      contextWindow.usage?.let { usage ->
        ContextWindowMeter(
          usage = usage,
          modelDisplayName = contextWindow.modelDisplayName,
          onCompact = contextWindow.onCompact,
          compactDisabledReason = contextWindow.compactDisabledReason,
        )
      }
      ComposerPrimaryActions(
        state = state,
        hasSendableContent = hasSendableContent,
        onSend = onSend,
        onInterrupt = onInterrupt,
        compactBeforeSendTokens = contextWindow.compactBeforeSendTokens,
        keepFullHistory = contextWindow.keepFullHistory,
        onToggleKeepFullHistory = contextWindow.onToggleKeepFullHistory,
      )
    }
  }
}

/**
 * The composer's keys on [field], see [composerKey]: Enter sends, Ctrl/Cmd+Enter while [running]
 * sends the alternate, Shift or Alt+Enter is a newline. The newline goes in by hand: the text
 * field's own mapping may not newline a shifted Enter.
 */
fun Modifier.composerKeys(
  field: TextFieldState,
  running: Boolean,
  onSend: (alternate: Boolean) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
  val action: ComposerKey = composerKey(
    enter = event.key == Key.Enter || event.key == Key.NumPadEnter,
    keyDown = event.type == KeyEventType.KeyDown,
    shift = event.isShiftPressed,
    alt = event.isAltPressed,
    ctrl = event.isCtrlPressed,
    meta = event.isMetaPressed,
    running = running,
  )
  when (action) {
    ComposerKey.SEND -> onSend(false)

    ComposerKey.SEND_ALTERNATE -> onSend(true)

    ComposerKey.NEWLINE -> field.edit {
      val at: TextRange = selection
      replace(at.min, at.max, "\n")
      placeCursorAfterCharAt(at.min)
    }

    ComposerKey.IGNORE -> return@onPreviewKeyEvent false
  }
  return@onPreviewKeyEvent true
}

/** The review comments going with the next message, and a way to drop them all. */
@Composable
private fun ReviewBanner(count: Int, onClear: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val comments: String = if (count == 1) "1 review comment" else "$count review comments"
    Text(
      text = "$comments will go with this message",
      style = small(),
      color = JewelTheme.globalColors.text.info,
      modifier = Modifier.weight(1f),
    )
    Link("Clear", onClick = onClear, textStyle = small())
  }
}
