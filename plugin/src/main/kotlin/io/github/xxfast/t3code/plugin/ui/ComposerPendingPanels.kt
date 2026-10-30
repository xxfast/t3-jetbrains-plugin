package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxState
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.RadioButtonState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.styling.CheckboxStyle
import org.jetbrains.jewel.ui.component.styling.RadioButtonStyle
import org.jetbrains.jewel.ui.painter.hints.Selected
import org.jetbrains.jewel.ui.painter.hints.Stateful
import org.jetbrains.jewel.ui.painter.rememberResourcePainterProvider
import org.jetbrains.jewel.ui.theme.checkboxStyle
import org.jetbrains.jewel.ui.theme.radioButtonStyle

/** What the pending panels can do. The host wires these to the controller. */
class PendingActions(
  val onToggleOption: (questionId: String, value: String) -> Unit = { _, _ -> },
  val onCustomAnswer: (questionId: String, text: String) -> Unit = { _, _ -> },
  /** The next question, or submit on the last one. */
  val onAdvance: () -> Unit = {},
  val onBack: () -> Unit = {},
  val onDismiss: () -> Unit = {},
)

private val DIGITS: List<Key> = listOf(
  Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

private val NUMPAD_DIGITS: List<Key> = listOf(
  Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5,
  Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

/**
 * A question in place of the text field, one at a time: its header and `i/N`, the question, the
 * options, and a free-text answer when it allows one. Upstream's
 * `ComposerPendingUserInputPanel.tsx`. Digits 1 to 9 pick an option while focus is in the panel
 * and not in the answer field, like upstream's `selectOptionFromShortcut`.
 */
@Composable
fun ComposerPendingUserInputPanel(
  userInput: PendingRequest.UserInput,
  enabled: Boolean,
  actions: PendingActions,
) {
  val latest: PendingActions by rememberUpdatedState(actions)
  val question: UserInputQuestion? = userInput.activeQuestion
  val draft: PendingUserInputDraftAnswer? = question?.let { userInput.drafts[it.id] }
  val answerable: Boolean = enabled && !userInput.responding && userInput.resumable
  val count: Int = userInput.item.questions.size

  // Picking a single-select option moves on by itself after 200 ms, like upstream
  var picked: Int by remember(userInput.requestId, question?.id) { mutableIntStateOf(0) }
  LaunchedEffect(picked) {
    if (picked == 0) return@LaunchedEffect
    delay(200)
    latest.onAdvance()
  }

  fun pick(option: UserInputQuestion.Option) {
    if (question == null || !answerable) return
    actions.onToggleOption(question.id, option.answerValue)
    if (question.multiSelect != true) picked++
  }

  val answerInteractions: MutableInteractionSource = remember { MutableInteractionSource() }
  val typing: Boolean by answerInteractions.collectIsFocusedAsState()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(end = 6.dp)
      .onPreviewKeyEvent { event ->
        if (typing || question == null || event.type != KeyEventType.KeyDown) {
          return@onPreviewKeyEvent false
        }
        if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) {
          return@onPreviewKeyEvent false
        }
        val digit: Int = DIGITS.indexOf(event.key).takeIf { it >= 0 }
          ?: NUMPAD_DIGITS.indexOf(event.key)
        val option: UserInputQuestion.Option =
          question.options.getOrNull(digit) ?: return@onPreviewKeyEvent false
        pick(option)
        return@onPreviewKeyEvent true
      }
      .focusable(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = question?.header.orEmpty(),
        style = small(),
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (count > 1) {
        Text(
          text = "${userInput.questionIndex + 1}/$count",
          style = small(),
          color = JewelTheme.globalColors.text.info,
        )
      }
      if (userInput.dismissible) {
        Link(
          text = "Dismiss",
          onClick = actions.onDismiss,
          enabled = enabled && !userInput.responding,
          textStyle = small(),
        )
      }
    }
    if (question != null) {
      Text(question.question)
      // A typed answer wins, so no option shows as picked while there is one
      val custom: Boolean = draft?.customAnswer?.isNotBlank() == true
      // Rows bleed left so the marks align with the question text, as upstream's `-ms-1.5`
      Column(Modifier.bleedStart(OPTION_INSET)) {
        question.options.forEachIndexed { index, option ->
          OptionRow(
            option = option,
            multiSelect = question.multiSelect == true,
            selected = !custom && option.answerValue in draft?.selected.orEmpty(),
            shortcut = (index + 1).takeIf { it <= 9 },
            enabled = answerable,
            onClick = { pick(option) },
          )
        }
      }
      if (question.allowCustomAnswer != false) {
        key(userInput.requestId, question.id) {
          CustomAnswer(
            question = question,
            draft = draft,
            enabled = answerable,
            interactions = answerInteractions,
            onText = { text -> latest.onCustomAnswer(question.id, text) },
            onEnter = { if (nextEnabled(userInput)) latest.onAdvance() },
          )
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (userInput.questionIndex > 0) {
        Link("Back", onClick = actions.onBack, enabled = answerable, textStyle = small())
      }
      Spacer(Modifier.weight(1f))
      if (userInput.responding) CircularProgressIndicator(Modifier.size(16.dp))
      DefaultButton(onClick = actions.onAdvance, enabled = answerable && nextEnabled(userInput)) {
        Text(if (userInput.isLastQuestion) "Submit" else "Next")
      }
    }
  }
}

/** How far an option's hover fill reaches past its mark, upstream's `ps-1.5`. */
private val OPTION_INSET = 6.dp

/**
 * One option of upstream's `ComposerPendingUserInputCard`: a radio (a checkbox for multi-select)
 * on the label's first line, the label dim until picked, and the digit as a key cap in a column
 * of its own. The row is the click target; the marks only show state.
 */
@Composable
private fun OptionRow(
  option: UserInputQuestion.Option,
  multiSelect: Boolean,
  selected: Boolean,
  shortcut: Int?,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val interactions: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by interactions.collectIsHoveredAsState()
  val fill: Color = if (!selected && enabled && hovered) subtle(0.05f) else Color.Transparent
  val label: Color = if (selected) JewelTheme.globalColors.text.normal else subtle(0.7f)
  // Upstream's `h-5` slots: one label line tall, so the mark and key stay on its first line
  val measurer: TextMeasurer = rememberTextMeasurer()
  val style: TextStyle = JewelTheme.defaultTextStyle
  val density: Density = LocalDensity.current
  val line: Dp = remember(measurer, style, density) {
    with(density) { measurer.measure("0", style).size.height.toDp() }
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .alpha(if (enabled) 1f else 0.5f)
      .clip(RoundedCornerShape(6.dp))
      .background(fill)
      .hoverable(interactions)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      )
      .padding(start = OPTION_INSET, top = 6.dp, bottom = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.height(line), contentAlignment = Alignment.Center) {
      if (multiSelect) CheckboxMark(checked = selected) else OptionRadio(checked = selected)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(option.label, color = label)
      if (option.description.isNotEmpty() && option.description != option.label) {
        Text(option.description, style = small(), color = JewelTheme.globalColors.text.info)
      }
    }
    if (shortcut != null) {
      Box(Modifier.width(28.dp).heightIn(min = line), contentAlignment = Alignment.Center) {
        Kbd("$shortcut")
      }
    }
  }
}

/**
 * Jewel's radio, painted without its click handling. Upstream's `OptionRadio` draws the look of
 * `ui/radio-group` for the same reason: a real radio group's arrow keys would pick options and
 * trip the single-select auto-advance.
 */
@Composable
private fun OptionRadio(checked: Boolean) {
  val style: RadioButtonStyle = JewelTheme.radioButtonStyle
  val state: RadioButtonState = RadioButtonState.of(selected = checked)
  val painter: Painter by rememberResourcePainterProvider(style.icons.radioButton)
    .getPainter(Selected(state), Stateful(state))
  Box(Modifier.size(style.metrics.radioButtonSize).paint(painter, alignment = Alignment.TopStart))
}

/** Jewel's checkbox, painted without its click handling, as upstream's `CheckboxMark`. */
@Composable
private fun CheckboxMark(checked: Boolean) {
  val style: CheckboxStyle = JewelTheme.checkboxStyle
  val state: CheckboxState = CheckboxState.of(ToggleableState(checked))
  val painter: Painter by rememberResourcePainterProvider(style.icons.checkbox)
    .getPainter(Selected(checked), Stateful(state))
  Box(Modifier.size(style.metrics.checkboxSize).paint(painter, alignment = Alignment.TopStart))
}

/** Widens to the left by [start], like a negative `margin-inline-start`. */
private fun Modifier.bleedStart(start: Dp): Modifier = layout { measurable, constraints ->
  val bleed: Int = start.roundToPx()
  if (!constraints.hasBoundedWidth) {
    val placeable: Placeable = measurable.measure(constraints)
    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }
  val placeable: Placeable = measurable.measure(
    constraints.copy(
      minWidth = constraints.minWidth + bleed,
      maxWidth = constraints.maxWidth + bleed,
    ),
  )
  layout(placeable.width - bleed, placeable.height) { placeable.place(-bleed, 0) }
}

/** The free-text answer. Enter moves on when Next would, Shift+Enter is a newline. */
@Composable
private fun CustomAnswer(
  question: UserInputQuestion,
  draft: PendingUserInputDraftAnswer?,
  enabled: Boolean,
  interactions: MutableInteractionSource,
  onText: (String) -> Unit,
  onEnter: () -> Unit,
) {
  val field: TextFieldState = rememberTextFieldState(draft?.customAnswer.orEmpty())
  LaunchedEffect(field) {
    snapshotFlow { field.text.toString() }.collect(onText)
  }
  // Picking an option clears the custom answer; the field follows
  val modelText: String = draft?.customAnswer.orEmpty()
  LaunchedEffect(modelText) {
    if (modelText.isEmpty() && field.text.isNotEmpty()) field.clearText()
  }
  TextArea(
    state = field,
    modifier = Modifier.fillMaxWidth().composerKeys(field, running = false) { onEnter() },
    enabled = enabled,
    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
    interactionSource = interactions,
    // ChatComposer.tsx's placeholder while a question is pending
    placeholder = { Text("Type your own answer, or leave this blank to use the selected option") },
    undecorated = true,
  )
}
