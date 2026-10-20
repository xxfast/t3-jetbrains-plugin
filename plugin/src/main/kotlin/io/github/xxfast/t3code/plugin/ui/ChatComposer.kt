package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
  onSettledHeight: (Int) -> Unit = {},
  onClearReview: () -> Unit = {},
) {
  // [alternate] is Ctrl/Cmd+Enter or Ctrl/Cmd-click while running: the other follow-up behaviour
  fun send(alternate: Boolean) {
    val text: String = draft.text.toString()
    if (!sendable(text, state, extras)) return
    onSend(text.trim(), resolveComposerDispatchMode(state.running, alternate, state.queue))
  }

  // `useComposerRestingTransition`: one clock per change moves the height, the prompt, the
  // actions and the controls together
  val folded: Boolean = resting && pending == null
  val motion: ComposerRestingMotion = remember { ComposerRestingMotion(folded) }
  LaunchedEffect(folded) { motion.moveTo(folded) }
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

  Column(
    // The timeline keeps room for the settled composer only, see [resolveComposerTimelineInset]
    modifier = modifier.onGloballyPositioned { coordinates ->
      if (motion.settled) onSettledHeight(coordinates.size.height)
    },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
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
      when (pending) {
        null -> ComposerBody(
          motion = motion,
          chips = { ComposerChips(extras, state.enabled, attachmentActions) },
          prompt = {
            ComposerPromptEditor(
              state = state,
              draft = draft,
              progress = { motion.expansion },
              attachmentActions = attachmentActions,
              onSend = ::send,
              onFocus = onPromptFocus,
            )
          },
          actions = actions,
          controls = {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
              footer?.let { ComposerControls(it, state.enabled, footerActions, draft) }
            }
          },
          restingControls = {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
              CompositionLocalProvider(LocalComposerControlSize provides ComposerControlSize.XS) {
                footer?.let { ComposerControls(it, state.enabled, footerActions, draft) }
              }
            }
          },
        )

        else -> Column(
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

/**
 * The prompt, upstream's `ComposerPromptEditor`: borderless and `leading-relaxed`, as tall as
 * [ComposerBody] makes it. Resting, its one line sits centred in the 32dp row (`leading-8`), so
 * the text eases down by [RESTING_TEXT_INSET] as [progress] falls to 0.
 */
@OptIn(ExperimentalComposeUiApi::class) // onPointerEvent
@Composable
private fun ComposerPromptEditor(
  state: ComposerState,
  draft: TextFieldState,
  progress: () -> Float,
  attachmentActions: AttachmentActions,
  onSend: (alternate: Boolean) -> Unit,
  onFocus: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // `onVisibleSelectionChange`: typing or moving the caret unfolds, and so does a click
  LaunchedEffect(draft) {
    snapshotFlow { draft.text.toString() to draft.selection }.drop(1).collect { onFocus() }
  }
  TextArea(
    state = draft,
    modifier = modifier
      .fillMaxWidth()
      .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { onFocus() }
      .offset { IntOffset(0, (RESTING_TEXT_INSET.toPx() * (1 - progress())).roundToInt()) }
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

/** How far a resting prompt's one line sits from the row's top to look centred in 32dp. */
private val RESTING_TEXT_INSET: Dp = 6.dp

/**
 * The composer without a request, laid out for [motion]'s expansion between resting (0) and
 * expanded (1) so one clock moves everything, as `useComposerRestingTransition` animates the
 * surface and pins the footer to its edge:
 *
 * - the prompt grows from one 32dp line beside the actions to at least 78dp (`min-h-19.5`, at most
 *   208dp, `max-h-52`) across the full width;
 * - the [actions] slide from the prompt's row (`data-chat-composer-transition-actions`) down to
 *   the footer's, riding the bottom edge;
 * - the footer's [controls] (`sm`, the model picker's `-ms-2.5` pulling them to 6dp) and the
 *   resting strip's [restingControls] (`xs`, `absolute bottom-2 inset-x-4`) swap in place, the
 *   departing set gone at once and the arriving one fading in as [ComposerRestingMotion] says.
 */
@Composable
private fun ComposerBody(
  motion: ComposerRestingMotion,
  chips: @Composable () -> Unit,
  prompt: @Composable () -> Unit,
  actions: @Composable () -> Unit,
  controls: @Composable () -> Unit,
  restingControls: @Composable () -> Unit,
) {
  Layout(
    contents = listOf(chips, prompt, actions, controls, restingControls),
    modifier = Modifier.fillMaxWidth(),
  ) { (chip, editor, action, control, rest), constraints ->
    val t: Float = motion.expansion
    val width: Int = constraints.maxWidth
    val side: Int = 16.dp.roundToPx()
    val gap: Int = 8.dp.roundToPx()
    val loose = Constraints(maxWidth = (width - 2 * side).coerceAtLeast(0))

    val chipsPlaceable: Placeable? = chip.firstOrNull()?.measure(loose)
    val actionsPlaceable: Placeable = action.first().measure(Constraints())
    val actionsWidth: Int = actionsPlaceable.width

    // One line beside the actions, or the full width at 78dp and up
    val restingWidth: Int = (width - 2 * side - actionsWidth - gap).coerceAtLeast(0)
    val editorWidth: Int = lerp(restingWidth, width - 2 * side, t)
    val restingHeight: Int = 32.dp.roundToPx()
    val minHeight: Int = 78.dp.roundToPx()
    val editorConstraints: Constraints = if (t >= 1f) {
      Constraints(editorWidth, editorWidth, minHeight, 208.dp.roundToPx())
    } else {
      Constraints.fixed(editorWidth, lerp(restingHeight, minHeight, t))
    }
    val editorPlaceable: Placeable = editor.first().measure(editorConstraints)

    val controlsWidth: Int = (width - 6.dp.roundToPx() - side - actionsWidth - gap).coerceAtLeast(0)
    val controlsPlaceable: Placeable? =
      control.firstOrNull()?.measure(Constraints(maxWidth = controlsWidth))
    val restPlaceable: Placeable? = rest.firstOrNull()?.measure(loose)

    val chipsHeight: Int = chipsPlaceable?.height ?: 0
    val editorTop: Int = lerp(8.dp.roundToPx(), 16.dp.roundToPx(), t) + chipsHeight
    val bodyBottom: Int = editorTop + editorPlaceable.height + gap
    // The resting strip's `h-8`, or the footer's 32dp row and its `pb-4`
    val height: Int = bodyBottom + lerp(32.dp.roundToPx(), 48.dp.roundToPx(), t)

    layout(width, height) {
      chipsPlaceable?.place(side, lerp(8.dp.roundToPx(), 16.dp.roundToPx(), t))
      editorPlaceable.place(side, editorTop)

      val footerCentre: Int = height - 32.dp.roundToPx()
      val restingCentre: Int = editorTop + restingHeight / 2
      val actionsY: Int = lerp(restingCentre, footerCentre, t) - actionsPlaceable.height / 2
      actionsPlaceable.place(width - side - actionsWidth, actionsY)

      val drift: Float = 4.dp.toPx()
      val arriving: Float = motion.footerAlpha
      controlsPlaceable?.let { placeable ->
        if (arriving > 0f) {
          val y: Int = footerCentre - placeable.height / 2
          placeable.placeWithLayer(6.dp.roundToPx(), y) {
            alpha = arriving
            translationY = drift * (1 - arriving)
          }
        }
      }
      val resting: Float = motion.stripAlpha
      restPlaceable?.let { placeable ->
        if (resting > 0f) {
          val y: Int = height - gap - placeable.height
          placeable.placeWithLayer(side, y) {
            alpha = resting
            translationY = -drift * (1 - resting)
          }
        }
      }
    }
  }
}

private fun lerp(start: Int, stop: Int, fraction: Float): Int =
  (start + (stop - start) * fraction).roundToInt()

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
