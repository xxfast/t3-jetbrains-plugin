package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ProviderApprovalOption
import io.github.xxfast.t3code.protocol.generated.UserInputQuestion
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What the pending panels can do. The host wires these to the controller. */
class PendingActions(
  val onDecide: (decision: String) -> Unit = {},
  val onToggleOption: (questionId: String, value: String) -> Unit = { _, _ -> },
  val onCustomAnswer: (questionId: String, text: String) -> Unit = { _, _ -> },
  /** The next question, or submit on the last one. */
  val onAdvance: () -> Unit = {},
  val onBack: () -> Unit = {},
  val onDismiss: () -> Unit = {},
)

/**
 * An approval in place of the text field: the kind, the app, the detail, then the decisions.
 * Upstream's `ComposerPendingApprovalPanel.tsx` with `ComposerPendingApprovalActions.tsx`.
 */
@Composable
fun ComposerPendingApprovalPanel(
  approval: PendingRequest.Approval,
  enabled: Boolean,
  actions: PendingActions,
) {
  val answerable: Boolean = enabled && !approval.responding && approval.resumable
  Column(
    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = approvalLabel(approval.requestKind),
        style = small(),
        color = JewelTheme.globalColors.text.warning,
      )
      approval.appName?.let { app ->
        Text(
          text = app,
          style = small(),
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.weight(1f, fill = false),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(Modifier.weight(1f))
      if (approval.count > 1) {
        Text("1/${approval.count}", style = small(), color = JewelTheme.globalColors.text.info)
      }
    }
    // Commands and paths read as code; an app's own request reads as prose
    val prose: Boolean = approval.requestKind == "mcp-elicitation" || !approval.resumable
    Text(
      text = approvalDetail(approval),
      style = if (prose) JewelTheme.defaultTextStyle else JewelTheme.editorTextStyle,
      maxLines = 6,
      overflow = TextOverflow.Ellipsis,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (approval.responding) CircularProgressIndicator(Modifier.size(16.dp))
      primaryOptions(approval.options).forEach { option ->
        OptionButton(option, enabled = answerable) { actions.onDecide(option.decision) }
      }
      val more: List<ProviderApprovalOption> = moreOptions(approval.options)
      if (more.isNotEmpty()) MoreOptions(more, enabled = answerable, onDecide = actions.onDecide)
    }
  }
}

/** Approve is the default button, everything else outlined; a warning gets an icon and a tip. */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun OptionButton(option: ProviderApprovalOption, enabled: Boolean, onClick: () -> Unit) {
  val button: @Composable () -> Unit = {
    val label: @Composable () -> Unit = { OptionLabel(option) }
    if (option.decision == "accept") {
      DefaultButton(onClick = onClick, enabled = enabled) { label() }
    } else {
      OutlinedButton(onClick = onClick, enabled = enabled) { label() }
    }
  }
  val warning: String? = option.warning
  if (warning == null) button() else Tooltip(tooltip = { Text(warning) }) { button() }
}

@Composable
private fun OptionLabel(option: ProviderApprovalOption) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (option.warning != null) Icon(AllIconsKeys.General.Warning, contentDescription = null)
    Text(
      text = option.label,
      modifier = Modifier.widthIn(max = 200.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Upstream's "More approval options" menu, for every decision that isn't accept or decline. */
@Composable
private fun MoreOptions(
  options: List<ProviderApprovalOption>,
  enabled: Boolean,
  onDecide: (String) -> Unit,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    IconActionButton(
      key = AllIconsKeys.Actions.More,
      contentDescription = "More approval options",
      onClick = { open = !open },
      enabled = enabled,
    )
    if (open) {
      PopupMenu(
        onDismissRequest = {
          open = false
          return@PopupMenu true
        },
        horizontalAlignment = Alignment.End,
      ) {
        options.forEach { option ->
          selectableItem(
            selected = false,
            onClick = {
              open = false
              onDecide(option.decision)
            },
            enabled = enabled,
          ) {
            Column {
              OptionLabel(option)
              option.warning?.let { warning ->
                Text(warning, style = small(), color = JewelTheme.globalColors.text.warning)
              }
            }
          }
        }
      }
    }
  }
}

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
      if (question.multiSelect == true) {
        val hint = "Select one or more options."
        Text(hint, style = small(), color = JewelTheme.globalColors.text.info)
      }
      // A typed answer wins, so no option shows as picked while there is one
      val custom: Boolean = draft?.customAnswer?.isNotBlank() == true
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        question.options.forEachIndexed { index, option ->
          OptionRow(
            option = option,
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

@Composable
private fun OptionRow(
  option: UserInputQuestion.Option,
  selected: Boolean,
  shortcut: Int?,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val fill: Color = if (selected) subtle(0.07f) else Color.Transparent
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(fill)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(option.label)
      if (option.description.isNotEmpty() && option.description != option.label) {
        Text(option.description, style = small(), color = JewelTheme.globalColors.text.info)
      }
    }
    if (selected) {
      Icon(AllIconsKeys.Actions.Checked, contentDescription = "Selected")
    } else if (shortcut != null) {
      Text("$shortcut", style = small(), color = JewelTheme.globalColors.text.info)
    }
  }
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
