package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Upstream's `ComposerBannerStack`, expanded: the other banners, then [attachedAbove] (the
 * queued drawer), then the front banner attached to the box. [items] come in upstream's listing
 * order and are sorted here. Upstream folds the others behind a peek that opens on hover; here
 * they all show.
 */
@Composable
fun ComposerBannerStack(
  items: List<ComposerBannerItem>,
  attachedAbove: (@Composable () -> Unit)?,
  banner: @Composable (item: ComposerBannerItem, attached: Boolean) -> Unit,
) {
  val ordered: List<ComposerBannerItem> = orderComposerBanners(items)
  Column {
    if (ordered.size > 1) {
      Column(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ordered.drop(1).forEach { item -> banner(item, false) }
      }
    }
    attachedAbove?.invoke()
    ordered.firstOrNull()?.let { front -> banner(front, true) }
  }
}

/**
 * `ComposerBanner`'s surface: a drawer over the box when [attached], with the part behind the
 * box padded out, else a card of its own. Warnings and errors tint the outline and the fill.
 */
@Composable
internal fun Modifier.composerBannerSurface(
  attached: Boolean,
  variant: ComposerBannerVariant,
): Modifier {
  val shape: Shape = if (attached) {
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
  } else {
    RoundedCornerShape(16.dp)
  }
  return this
    .fillMaxWidth()
    .padding(horizontal = DRAWER_INSET)
    .bannerSurface(shape, variant)
    .padding(
      start = 4.dp,
      end = 8.dp,
      top = 4.dp,
      bottom = if (attached) DRAWER_OVERLAP + 4.dp else 4.dp,
    )
    .heightIn(min = ROW_MIN_HEIGHT)
}

/**
 * `branchMismatchBannerItem`, `ThreadActivityBanners.tsx`: the checkout moved off the branch
 * this thread last ran on. "Restore branch" checks it out again, the tooltip says where a send
 * goes. Upstream's title has an em-dash; this one has a comma.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun BranchMismatchBanner(
  state: BranchMismatchBannerState,
  actions: BranchMismatchBannerActions,
  attached: Boolean,
  modifier: Modifier = Modifier,
) {
  val mismatch: BranchMismatch = state.mismatch
  Row(
    modifier = modifier.composerBannerSurface(attached, ComposerBannerVariant.INFO),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
      Icon(AllIconsKeys.Vcs.Branch, contentDescription = null)
    }
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text("Branch changed, was", style = small(), color = muted(), maxLines = 1)
      Tooltip(
        tooltip = {
          Text(
            "This thread last ran on ${mismatch.threadBranch}. " +
              "Sending will continue on ${mismatch.currentBranch}.",
          )
        },
        modifier = Modifier.weight(1f, fill = false),
      ) {
        Text(
          text = mismatch.threadBranch,
          style = small().copy(
            fontFamily = JewelTheme.editorTextStyle.fontFamily,
            fontWeight = FontWeight.Medium,
          ),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Link(
      text = if (state.restoring) "Restoring..." else "Restore branch",
      onClick = actions.onRestore,
      enabled = state.canRestore && !state.restoring,
      textStyle = small(),
    )
    IconActionButton(
      key = AllIconsKeys.General.Close,
      contentDescription = "Dismiss branch change notice",
      onClick = actions.onDismiss,
    )
  }
}

/**
 * The glass fill and the outline, `--chat-composer-attached-outline` and
 * `--chat-composer-attached-tint` for [variant]: warnings and errors tint both.
 */
@Composable
private fun Modifier.bannerSurface(shape: Shape, variant: ComposerBannerVariant): Modifier {
  val neutral: Color =
    if (JewelTheme.isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f)
  val accent: Color? = accentOf(variant)
  val outline: Color = accent?.let {
    it.copy(alpha = if (variant == ComposerBannerVariant.ERROR) 0.32f else 0.28f)
  } ?: neutral
  val tint: Color = accent?.copy(alpha = 0.08f) ?: Color.Transparent
  return this
    .clip(shape)
    .background(composerGlassSurface())
    .background(tint)
    .border(1.dp, outline, shape)
}

@Composable
private fun accentOf(variant: ComposerBannerVariant): Color? = when (variant) {
  ComposerBannerVariant.ERROR -> JewelTheme.globalColors.text.error
  ComposerBannerVariant.WARNING -> JewelTheme.globalColors.text.warning
  else -> null
}

/**
 * `usageLimitRecoveryBannerItem` with `RecoveryActions`, `UsageLimitRecoveryBanner.tsx`: when
 * the limit resets, and when that's after the stop, auto-resume at the reset and a snooze until
 * then. Snooze hides once snoozed and greys out once the reset passes.
 */
@Composable
fun UsageLimitRecoveryBanner(
  state: UsageLimitRecoveryState,
  onToggle: (LimitRecoveryAction) -> Unit,
  attached: Boolean,
  modifier: Modifier = Modifier,
) {
  // Upstream's timer at the reset, so Snooze greys out as it passes
  var passed: Boolean by remember(state.resetAt) {
    mutableStateOf(limitResetPassed(state.resetAt, Instant.now()))
  }
  LaunchedEffect(state.resetAt) {
    val reset: Instant = state.resetAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
      ?: return@LaunchedEffect
    val wait: Long = reset.toEpochMilli() - System.currentTimeMillis()
    if (wait > 0) delay(wait + 1)
    passed = true
  }
  Row(
    modifier = modifier.composerBannerSurface(attached, ComposerBannerVariant.WARNING),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.width(ICON_COLUMN), contentAlignment = Alignment.Center) {
      Icon(AllIconsKeys.General.Warning, contentDescription = null)
    }
    Column(Modifier.weight(1f)) {
      Text(
        text = "Usage limit reached",
        style = small().copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = usageLimitResetDescription(state.resetAt),
        style = small(),
        color = muted(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (state.canSchedule) {
      Link(
        text = when {
          state.saving -> "Saving..."
          state.scheduled -> "Cancel auto-resume"
          else -> "Resume at reset"
        },
        onClick = { onToggle(LimitRecoveryAction.RESUME) },
        enabled = !state.saving,
        textStyle = small(),
      )
      if (!state.snoozed) {
        Link(
          text = if (state.saving) "Saving..." else "Snooze until reset",
          onClick = { onToggle(LimitRecoveryAction.SNOOZE) },
          enabled = !state.saving && !passed,
          textStyle = small(),
        )
      }
    }
  }
}

/** What the thread error banner's controls do; the host acts. */
class ThreadErrorBannerActions(
  val onDismiss: () -> Unit = {},
  /** `ChatGptUsageButton`: [CHATGPT_USAGE_URL] in the browser. */
  val onManageUsage: () -> Unit = {},
  /** `copyAdminRequest`: [CHATGPT_WORKSPACE_ADMIN_REQUEST] on the clipboard. */
  val onCopyAdminRequest: () -> Unit = {},
)

/**
 * `ThreadErrorBanner`, pinned over the timeline as `ChatBannerLane` pins it: the error,
 * collapsed to its message and three lines with "Show full error", and a dismiss. A ChatGPT
 * usage limit says so instead, with "Manage usage" or, for a workspace cap, "Copy request for
 * admin". Upstream's OpenAI mark is the IDE's warning icon here.
 */
@Composable
fun ThreadErrorBanner(
  state: ThreadErrorBannerState,
  actions: ThreadErrorBannerActions,
  modifier: Modifier = Modifier,
) {
  val warning: Boolean = state.variant == ComposerBannerVariant.WARNING
  Row(
    modifier = modifier
      .bannerSurface(RoundedCornerShape(12.dp), state.variant)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      key = if (warning) AllIconsKeys.General.Warning else AllIconsKeys.General.Error,
      contentDescription = null,
    )
    Column(Modifier.weight(1f, fill = false)) {
      if (state.chatGptLimit) {
        Text("ChatGPT usage limit reached", fontWeight = FontWeight.Medium)
        Text(
          if (state.workspaceLimit) {
            "You've reached your workspace's limit for using your ChatGPT plan in other apps. " +
              "Copy a request to ask your workspace admin for increased usage limits."
          } else {
            "Review your usage settings in ChatGPT."
          },
        )
      } else {
        ExpandableText(state.error, getThreadErrorBannerMessage(state.error))
      }
    }
    if (state.workspaceLimit) {
      Link("Copy request for admin", onClick = actions.onCopyAdminRequest)
    } else if (state.chatGptLimit) {
      Link("Manage usage", onClick = actions.onManageUsage)
    }
    IconActionButton(
      key = AllIconsKeys.General.Close,
      contentDescription = "Dismiss error",
      onClick = actions.onDismiss,
    )
  }
}

/** `ExpandableText`, `settings/ExpandableText.tsx`: [collapsed] in three lines until expanded. */
@Composable
private fun ExpandableText(text: String, collapsed: String) {
  var expanded: Boolean by remember(text) { mutableStateOf(false) }
  val canExpand: Boolean = canExpandText(text, collapsed)
  Column {
    Text(
      text = if (expanded) text else collapsed,
      maxLines = if (!expanded && canExpand) 3 else Int.MAX_VALUE,
      overflow = TextOverflow.Ellipsis,
    )
    if (canExpand) {
      Link(
        text = if (expanded) "Show less" else "Show full error",
        onClick = { expanded = !expanded },
        textStyle = small(),
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val DRAWER_INSET: Dp = 22.dp

/** `--chat-composer-attachment-overlap`, `calc(1rem+1px)`: the part behind the box. */
private val DRAWER_OVERLAP: Dp = 17.dp

/** `--composer-banner-icon-column`, `--spacing(6)`. */
private val ICON_COLUMN: Dp = 24.dp

/** A row's `min-h-5`. */
private val ROW_MIN_HEIGHT: Dp = 20.dp
