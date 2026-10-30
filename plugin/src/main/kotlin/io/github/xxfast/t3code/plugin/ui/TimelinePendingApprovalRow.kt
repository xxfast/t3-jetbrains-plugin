package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ProviderApprovalOption
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// An approval answered where it happened in the timeline,
// apps/web/src/components/chat/TimelinePendingApprovalRow.tsx

/**
 * `TimelinePendingApprovalRow`: the kind's icon, the detail as a selectable monospace block,
 * the app and the reason muted, then "Waiting for approval" and the decisions inline. Without
 * [onRespondToApproval] the decisions aren't offered.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class) // FlowRow, Jewel's Tooltip
@Composable
fun TimelinePendingApprovalRow(
  approval: PendingApproval,
  responding: Boolean, // `respondingRequestIds.includes(requestId)`
  canOperateThread: Boolean,
  onRespondToApproval: ((requestId: String, decision: String) -> Unit)?,
) {
  val presentation: ApprovalPresentation = approvalPresentation(approval.requestKind)
  val canRespond: Boolean = approval.resumable
  // `WorkLogRow` with `wrapLabel`: the icon tops the label rather than centring on it
  Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Glyph(presentation.icon, muted(), 16.dp)
      }
      // `max-h-40 overflow-auto whitespace-pre-wrap font-mono text-xs`, selectable
      Text(
        text = approval.detail ?: presentation.label,
        style = JewelTheme.editorTextStyle.copy(fontSize = small().fontSize),
        color = JewelTheme.globalColors.text.normal,
        modifier = Modifier
          .weight(1f)
          .padding(top = 2.dp)
          .heightIn(max = 160.dp)
          .verticalScroll(rememberScrollState()),
      )
    }
    Column(
      modifier = Modifier.padding(start = 30.dp, bottom = 4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      approval.appName?.let { Text(it, style = small(), color = muted()) }
      approval.reason?.let { Text(it, style = small(), color = muted()) }
      DisableSelection {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          val warning: Color = JewelTheme.globalColors.text.warning
          if (canRespond) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Glyph("shield", warning, 12.dp)
              Text("Waiting for approval", style = small(), color = warning)
            }
            if (onRespondToApproval != null) {
              Text("·", style = small(), color = muted())
              ComposerPendingApprovalActions(
                requestId = approval.requestId,
                disabled = !canOperateThread,
                isResponding = responding,
                canRespond = canRespond,
                options = approval.options,
                onRespondToApproval = onRespondToApproval,
              )
            }
          } else {
            Text(
              text = "Provider process is gone. Interrupt or restart the run to respond.",
              style = small(),
              color = warning,
            )
          }
          approval.fullAccessOverride?.let { override ->
            // A popover that opens on hover upstream; a tooltip is that here
            Tooltip(
              tooltip = {
                Text(fullAccessOverrideExplanation(override), Modifier.widthIn(max = 352.dp))
              },
            ) {
              InlineButton(FULL_ACCESS_OVERRIDE_QUESTION, muted = true, onClick = {})
            }
          }
        }
      }
    }
  }
}

/**
 * `ComposerPendingApprovalActions` with `presentation="inline"`,
 * ComposerPendingApprovalActions.tsx: accept and decline as inline buttons, accept in the normal
 * tone, the rest under "More". A warning gets an icon and a tooltip.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun ComposerPendingApprovalActions(
  requestId: String,
  disabled: Boolean,
  isResponding: Boolean,
  canRespond: Boolean,
  options: List<ProviderApprovalOption>?,
  onRespondToApproval: (requestId: String, decision: String) -> Unit,
) {
  val enabled: Boolean = !disabled && !isResponding && canRespond
  val all: List<ProviderApprovalOption> = approvalOptions(options)
  primaryOptions(all).forEach { option ->
    val button: @Composable () -> Unit = {
      InlineButton(
        text = option.label,
        muted = option.decision != "accept",
        enabled = enabled,
        warning = option.warning != null,
        onClick = { onRespondToApproval(requestId, option.decision) },
      )
    }
    val warning: String? = option.warning
    if (warning == null) button() else Tooltip(tooltip = { Text(warning) }) { button() }
  }
  val more: List<ProviderApprovalOption> = moreOptions(all)
  if (more.isNotEmpty()) {
    MoreOptions(more, enabled) { decision -> onRespondToApproval(requestId, decision) }
  }
}

/**
 * Upstream's "More approval options" menu, for every decision that isn't accept or decline. A
 * warning is an icon in front of the label and a tooltip.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun MoreOptions(
  options: List<ProviderApprovalOption>,
  enabled: Boolean,
  onDecide: (String) -> Unit,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box {
    InlineButton("More", muted = true, enabled = enabled, onClick = { open = !open })
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
            val label: @Composable () -> Unit = {
              Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (option.warning != null) {
                  Icon(AllIconsKeys.General.Warning, contentDescription = null)
                }
                Text(option.label)
              }
            }
            val warning: String? = option.warning
            if (warning == null) label() else Tooltip(tooltip = { Text(warning) }) { label() }
          }
        }
      }
    }
  }
}

/**
 * `InlineButton`, apps/web/src/components/ui/button.tsx: medium text that underlines on hover,
 * the foreground tone or the muted one that brightens on hover, faded while disabled.
 */
@Composable
private fun InlineButton(
  text: String,
  muted: Boolean,
  enabled: Boolean = true,
  warning: Boolean = false,
  onClick: () -> Unit,
) {
  val source: MutableInteractionSource = remember { MutableInteractionSource() }
  val hovered: Boolean by source.collectIsHoveredAsState()
  val color: Color =
    if (muted && !(hovered && enabled)) muted() else JewelTheme.globalColors.text.normal
  Row(
    modifier = Modifier
      .alpha(if (enabled) 1f else 0.64f)
      .hoverable(source, enabled)
      .clickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      )
      .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (warning) {
      Icon(AllIconsKeys.General.Warning, contentDescription = null, Modifier.size(12.dp))
    }
    Text(
      text = text,
      style = small().copy(
        fontWeight = FontWeight.Medium,
        textDecoration = if (hovered && enabled) TextDecoration.Underline else null,
      ),
      color = color,
    )
  }
}
