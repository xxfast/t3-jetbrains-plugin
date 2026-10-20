package io.github.xxfast.t3code.plugin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
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
 *
 * [resting] is upstream's resting layout: the prompt shrinks to one 32dp line with the actions
 * beside it, and the controls drop to an `xs` strip underneath. The change animates its height
 * with upstream's easing while the strip and the footer cross-fade. [onPromptFocus] is the
 * prompt taking focus, which unfolds it.
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
  resting: Boolean = false,
  onPromptFocus: () -> Unit = {},
  onClearReview: () -> Unit = {},
) {
  // [alternate] is Ctrl/Cmd+Enter or Ctrl/Cmd-click while running: the other follow-up behaviour
  fun send(alternate: Boolean) {
    val text: String = draft.text.toString()
    if (!sendable(text, state, extras)) return
    onSend(text.trim(), resolveComposerDispatchMode(state.running, alternate, state.queue))
  }

  val folded: Boolean = resting && pending == null
  val actions: @Composable () -> Unit = {
    ComposerActions(
      state = state,
      hasSendableContent = sendable(draft.text.toString(), state, extras),
      attachmentActions = attachmentActions,
      contextWindow = contextWindow,
      onSend = ::send,
      onInterrupt = onInterrupt,
    )
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
      // `useComposerRestingTransition`: the surface's height eases between the two layouts
      Column(Modifier.animateContentSize(restingTween())) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (folded) 8.dp else 16.dp)
            .padding(bottom = if (pending is PendingRequest.Approval) 16.dp else 8.dp),
        ) {
          // Above whatever takes the box, so context added during an approval still shows
          ComposerChips(extras, state.enabled, attachmentActions)
          when (pending) {
            is PendingRequest.Approval ->
              ComposerPendingApprovalPanel(pending, state.enabled, pendingActions)

            is PendingRequest.UserInput ->
              ComposerPendingUserInputPanel(pending, state.enabled, pendingActions)

            // One prompt in both layouts, so it keeps its focus and caret across the change
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
              ComposerPromptEditor(
                state = state,
                draft = draft,
                resting = folded,
                attachmentActions = attachmentActions,
                onSend = ::send,
                onFocus = onPromptFocus,
                modifier = Modifier.weight(1f),
              )
              // The actions move beside the prompt (`data-chat-composer-transition-actions`)
              AnimatedVisibility(folded, enter = arrive(), exit = depart()) {
                Box(Modifier.padding(start = 8.dp)) { actions() }
              }
            }
          }
        }
        if (pending == null) {
          AnimatedContent(
            targetState = folded,
            transitionSpec = {
              (arrive() togetherWith depart())
                .using(SizeTransform(clip = false) { _, _ -> restingTween() })
            },
            label = "composer footer",
          ) { rests ->
            if (rests) {
              RestingControls(state, footer, footerActions, draft)
            } else {
              ComposerFooter(state, draft, footer, footerActions, actions)
            }
          }
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

private fun <T> restingTween(): FiniteAnimationSpec<T> =
  tween(COMPOSER_RESTING_TRANSITION_MS, easing = COMPOSER_RESTING_TRANSITION_EASING)

/** Arriving controls fade in and drift up `COMPOSER_RESTING_CONTROLS_ARRIVAL_DRIFT_PX`. */
private fun arrive(): EnterTransition =
  fadeIn(restingTween()) + slideInVertically(restingTween()) { RESTING_ARRIVAL_DRIFT }

private const val RESTING_ARRIVAL_DRIFT: Int = 4

/** Departing controls clear out in the first third, so the two sets barely overlap. */
private fun depart(): ExitTransition =
  fadeOut(tween(COMPOSER_RESTING_TRANSITION_MS / 3, easing = COMPOSER_RESTING_TRANSITION_EASING))

/**
 * The prompt, upstream's `ComposerPromptEditor`: borderless, `leading-relaxed`, at least 78dp
 * (`min-h-19.5`) and at most 208dp (`max-h-52`) before it scrolls. Resting, it's one line in a
 * 32dp row (`max-h-8 min-h-8 leading-8`).
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent
@Composable
private fun ComposerPromptEditor(
  state: ComposerState,
  draft: TextFieldState,
  resting: Boolean,
  attachmentActions: AttachmentActions,
  onSend: (alternate: Boolean) -> Unit,
  onFocus: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // `onVisibleSelectionChange`: typing or moving the caret unfolds, and so does a click
  LaunchedEffect(draft) {
    snapshotFlow { draft.text.toString() to draft.selection }.drop(1).collect { onFocus() }
  }
  Box(
    modifier = modifier
      .heightIn(min = if (resting) 32.dp else 78.dp)
      .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { onFocus() },
    contentAlignment = if (resting) Alignment.CenterStart else Alignment.TopStart,
  ) {
    TextArea(
      state = draft,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = if (resting) 0.dp else 78.dp, max = if (resting) 32.dp else 208.dp)
        .onFocusChanged { if (it.isFocused) onFocus() }
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
}

/**
 * `data-chat-composer-footer`: the controls on the left, scrolling sideways when they don't fit
 * (`overflow-x-auto`), and the actions on the right (`data-chat-composer-actions`).
 */
@Composable
private fun ComposerFooter(
  state: ComposerState,
  draft: TextFieldState,
  footer: ComposerFooterState?,
  footerActions: ComposerFooterActions,
  actions: @Composable () -> Unit,
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
    actions()
  }
}

/**
 * `data-chat-composer-resting-controls`: the same controls at `xs`, in a 32dp strip under the
 * resting prompt (`absolute bottom-2 inset-x-4`).
 */
@Composable
private fun RestingControls(
  state: ComposerState,
  footer: ComposerFooterState?,
  footerActions: ComposerFooterActions,
  draft: TextFieldState,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(32.dp)
      .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
      .horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.Bottom,
  ) {
    CompositionLocalProvider(LocalComposerControlSize provides ComposerControlSize.XS) {
      footer?.let { ComposerControls(it, state.enabled, footerActions, draft) }
    }
  }
}

/** `data-chat-composer-actions`: the paperclip, the meter and [ComposerPrimaryActions]. */
@Composable
private fun ComposerActions(
  state: ComposerState,
  hasSendableContent: Boolean,
  attachmentActions: AttachmentActions,
  contextWindow: ComposerContextWindow,
  onSend: (alternate: Boolean) -> Unit,
  onInterrupt: () -> Unit,
) {
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
