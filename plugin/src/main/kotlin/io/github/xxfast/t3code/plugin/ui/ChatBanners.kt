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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
  val neutral: Color =
    if (JewelTheme.isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f)
  val accent: Color? = when (variant) {
    ComposerBannerVariant.ERROR -> JewelTheme.globalColors.text.error
    ComposerBannerVariant.WARNING -> JewelTheme.globalColors.text.warning
    else -> null
  }
  // `--chat-composer-attached-outline` and `--chat-composer-attached-tint`
  val outline: Color = accent?.let {
    it.copy(alpha = if (variant == ComposerBannerVariant.ERROR) 0.32f else 0.28f)
  } ?: neutral
  val tint: Color = accent?.copy(alpha = 0.08f) ?: Color.Transparent
  val shape: Shape = if (attached) {
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
  } else {
    RoundedCornerShape(16.dp)
  }
  return this
    .fillMaxWidth()
    .padding(horizontal = DRAWER_INSET)
    .clip(shape)
    .background(composerGlassSurface())
    .background(tint)
    .border(1.dp, outline, shape)
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

/** `--chat-composer-drawer-inset`, 1.375rem. */
private val DRAWER_INSET: Dp = 22.dp

/** `--chat-composer-attachment-overlap`, `calc(1rem+1px)`: the part behind the box. */
private val DRAWER_OVERLAP: Dp = 17.dp

/** `--composer-banner-icon-column`, `--spacing(6)`. */
private val ICON_COLUMN: Dp = 24.dp

/** A row's `min-h-5`. */
private val ROW_MIN_HEIGHT: Dp = 20.dp
