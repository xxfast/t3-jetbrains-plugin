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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import io.github.xxfast.t3code.protocol.generated.ServerProvider
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
    // The trigger menu is a drawer over the box, `composerSuggestionsVisible`: not on a request
    val triggerMenu: ComposerTriggerMenu = rememberComposerTriggerMenu(
      draft = draft,
      footer = footer,
      footerActions = footerActions,
      enabled = state.enabled && pending == null,
      running = state.running,
    )
    // The context strip is a drawer tucked under the box, `ComposerSurface.ContextStrip`
    val strip: WorkspaceControlState? = footer?.workspace?.takeIf { pending == null }
    ComposerWithMenuDrawer(
      drawer = triggerMenu.menu?.let { menu ->
        { ComposerCommandMenu(menu, triggerMenu.activeItemId, triggerMenu.onHighlight) }
      },
    ) {
    ComposerWithDrawer(
      drawer = strip?.let { workspace ->
        { ComposerContextStrip(workspace, state.enabled, footerActions) }
      },
    ) {
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
              onMenuKey = triggerMenu.onKey,
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
 * The box with its [drawer] hanging under it: the drawer's top [CONTEXT_STRIP_OVERLAP] sits
 * behind the box (upstream's `-mt-4`), the box draws on top.
 */
@Composable
private fun ComposerWithDrawer(
  drawer: (@Composable () -> Unit)?,
  surface: @Composable () -> Unit,
) {
  if (drawer == null) return surface()
  Layout(contents = listOf(drawer, surface)) { (strip, box), constraints ->
    val boxPlaceable: Placeable = box.first().measure(constraints)
    val stripPlaceable: Placeable = strip.first().measure(constraints.copy(minHeight = 0))
    val overlap: Int = CONTEXT_STRIP_OVERLAP.roundToPx()
    val height: Int = boxPlaceable.height + (stripPlaceable.height - overlap).coerceAtLeast(0)
    layout(boxPlaceable.width, height) {
      stripPlaceable.placeRelative(0, boxPlaceable.height - overlap)
      boxPlaceable.placeRelative(0, 0)
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
  onMenuKey: (KeyEvent) -> Boolean,
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
      // The open menu sees the keys first, as `onComposerCommandKey` does before sending
      .onPreviewKeyEvent(onMenuKey)
      .composerKeys(draft, state.running, onSend),
    enabled = state.enabled,
    lineLimits = TextFieldLineLimits.MultiLine(),
    // Upstream's short placeholder (ChatComposer.tsx:6695). The long one offers @tags, $skills
    // and / commands, and only `$` has a menu so far (#44 `/`, #45 `@` still to come).
    placeholder = { Text("Ask anything...", color = JewelTheme.globalColors.text.info) },
    undecorated = true,
  )
}

/** The trigger menu for the prompt, [rememberComposerTriggerMenu]. */
private class ComposerTriggerMenu(
  /** What the drawer shows, null while no trigger with a menu is active. */
  val menu: ComposerMenu?,
  val activeItemId: String?,
  val onHighlight: (itemId: String) -> Unit,
  /** The prompt's preview key handler: true when the menu took the key. */
  val onKey: (KeyEvent) -> Boolean,
)

/**
 * The trigger at the caret and its menu, upstream's `useComposerTriggerState` plus the menu
 * half of `ChatComposer.tsx`. The text and caret say which trigger is active, Esc dismisses it
 * until the token changes, and the kind decides the rows: `$` lists the provider's skills for
 * the thread's cwd (`resolveProviderSkillsForCwd`). `/` (#44), `@` (#45) and `#` (#46) are
 * detected already and get their rows in [triggerMenuFor].
 *
 * Typing and a change of provider, cwd or snapshot ask the host to re-check the workspace
 * snapshot (`refreshWorkspaceSnapshotOnTyping` and the effect beside it, ChatComposer.tsx:1765).
 */
@Composable
private fun rememberComposerTriggerMenu(
  draft: TextFieldState,
  footer: ComposerFooterState?,
  footerActions: ComposerFooterActions,
  enabled: Boolean,
  running: Boolean,
): ComposerTriggerMenu {
  val triggers: ComposerTriggerState = remember(draft) { ComposerTriggerState() }
  var trigger: ComposerTrigger? by remember(draft) { mutableStateOf(null) }
  var highlightedItemId: String? by remember(draft) { mutableStateOf(null) }
  var highlightedSearchKey: String? by remember(draft) { mutableStateOf(null) }
  val refresh: State<() -> Unit> = rememberUpdatedState(footerActions.onRefreshWorkspaceSnapshot)
  LaunchedEffect(draft) {
    var lastText: String? = null
    snapshotFlow { draft.text.toString() to draft.selection }.collect { (text, selection) ->
      if (lastText != null && text != lastText) refresh.value()
      lastText = text
      trigger = triggers.setTrigger(detectComposerTrigger(text, selection.end))
    }
  }
  val provider: ServerProvider? = footer?.provider
  val cwd: String? = footer?.cwd
  val hasSnapshot: Boolean = hasCompleteProviderWorkspaceSnapshot(provider, cwd)
  LaunchedEffect(provider?.instanceId, cwd, hasSnapshot) { refresh.value() }

  // `applyPromptReplacement`, ChatComposer.tsx:3710, for a text field: the token's range, as
  // long as the text there is still what the trigger saw
  fun applyPromptReplacement(
    rangeStart: Int,
    rangeEnd: Int,
    replacement: String,
    expected: String,
  ) {
    val current: String = draft.text.toString()
    val safeStart: Int = rangeStart.coerceIn(0, current.length)
    val safeEnd: Int = rangeEnd.coerceIn(safeStart, current.length)
    if (current.substring(safeStart, safeEnd) != expected) return
    val next: TextReplacement = replaceTextRange(current, rangeStart, rangeEnd, replacement)
    draft.edit {
      replace(safeStart, safeEnd, replacement)
      selection = TextRange(next.cursor)
    }
    highlightedItemId = null
  }

  fun pick(active: ComposerTrigger, replacement: String) {
    val text: String = draft.text.toString()
    val end: Int = extendReplacementRangeForTrailingSpace(text, active.rangeEnd, replacement)
    val expected: String = text.substring(active.rangeStart.coerceIn(0, text.length), end)
    applyPromptReplacement(active.rangeStart, end, replacement, expected)
  }

  val active: ComposerTrigger? = trigger.takeIf { enabled }
  val menu: ComposerMenu? = remember(active, provider, cwd) {
    active?.let { triggerMenuFor(it, provider, cwd, ::pick) }
  }
  val searchKey: String? = active?.let(::composerMenuSearchKey)
  val itemIds: List<String> = menu?.items?.map { it.id }.orEmpty()
  val activeItemId: String? =
    resolveComposerMenuActiveItemId(itemIds, highlightedItemId, searchKey, highlightedSearchKey)

  fun highlight(itemId: String?) {
    highlightedItemId = itemId
    highlightedSearchKey = searchKey
  }

  val onKey: (KeyEvent) -> Boolean = onKey@{ event ->
    if (menu == null) return@onKey false
    val input: ComposerMenuInput = when (event.key) {
      Key.Escape -> ComposerMenuInput.ESCAPE
      Key.DirectionUp -> ComposerMenuInput.UP
      Key.DirectionDown -> ComposerMenuInput.DOWN
      Key.Enter, Key.NumPadEnter -> ComposerMenuInput.ENTER
      Key.Tab -> ComposerMenuInput.TAB
      else -> return@onKey false
    }
    val keyDown: Boolean = event.type == KeyEventType.KeyDown
    val submission: ComposerKey = composerKey(
      enter = input == ComposerMenuInput.ENTER,
      keyDown = keyDown,
      shift = event.isShiftPressed,
      alt = event.isAltPressed,
      ctrl = event.isCtrlPressed,
      meta = event.isMetaPressed,
      running = running,
    )
    val action: ComposerMenuKey = composerMenuKey(
      key = input,
      keyDown = keyDown,
      shift = event.isShiftPressed,
      submission = submission,
      hasItems = itemIds.isNotEmpty(),
    )
    when (action) {
      ComposerMenuKey.DISMISS -> {
        triggers.dismissTrigger(active)
        trigger = null
      }

      ComposerMenuKey.NEXT -> highlight(nudgeComposerMenuHighlight(itemIds, activeItemId, true))

      ComposerMenuKey.PREVIOUS ->
        highlight(nudgeComposerMenuHighlight(itemIds, activeItemId, false))

      // `activeComposerMenuItemRef.current ?? currentItems[0]`
      ComposerMenuKey.PICK ->
        (menu.items.find { it.id == activeItemId } ?: menu.items.first()).onPick()

      ComposerMenuKey.IGNORE -> return@onKey false
    }
    true
  }
  return ComposerTriggerMenu(menu, activeItemId, ::highlight, onKey)
}

/**
 * The rows for [trigger], `composerMenuItems` (ChatComposer.tsx:2690), or null for a kind that
 * has no menu yet. [pick] replaces the trigger's token.
 */
private fun triggerMenuFor(
  trigger: ComposerTrigger,
  provider: ServerProvider?,
  cwd: String?,
  pick: (ComposerTrigger, replacement: String) -> Unit,
): ComposerMenu? = when (trigger.kind) {
  ComposerTriggerKind.SKILL -> ComposerMenu(
    kind = trigger.kind,
    items = skillMenuItems(
      skills = provider?.let { resolveProviderSkillsForCwd(it, cwd) }.orEmpty(),
      query = trigger.query,
      provider = provider?.instanceId.orEmpty(),
      onPick = { skill -> pick(trigger, skillReplacement(skill)) },
    ),
    emptyStateText = SKILL_MENU_EMPTY_TEXT,
  )

  ComposerTriggerKind.SLASH_COMMAND,
  ComposerTriggerKind.PATH,
  ComposerTriggerKind.PULL_REQUEST,
  -> null
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
