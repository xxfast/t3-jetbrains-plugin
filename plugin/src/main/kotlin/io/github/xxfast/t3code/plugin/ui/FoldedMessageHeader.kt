package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * `FoldedMessageHeader`, TimelineUserMessageRow.tsx:244: a message folded to one line, its
 * sender's icon, the muted label (at most half the width), the summary, and a chevron that
 * turns when [expanded]. The whole line toggles the body. An expanded bubble is full width, so
 * the header spans it and the chevron goes to the end, as `w-full` and `flex-1` do. Sent rows
 * take the defaults; the queued drawer's compact rows pass their own [contentPadding] and [style].
 */
@Composable
fun FoldedMessageHeader(
  fold: MessageFold,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
  style: TextStyle = JewelTheme.defaultTextStyle,
) {
  // A button upstream, so it selects nothing
  val span: Modifier = if (expanded) Modifier.fillMaxWidth() else Modifier
  DisableSelection {
    BoxWithConstraints(modifier.then(span)) {
      val labelMaxWidth: Dp = maxWidth / 2 // `max-w-1/2`
      Row(
        modifier = span
          .clip(RoundedCornerShape(16.dp))
          .clickable(onClick = onToggle)
          .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Glyph(fold.icon, muted(), 16.dp)
        Text(
          text = fold.label,
          style = style,
          color = muted(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.widthIn(max = labelMaxWidth),
        )
        Text(
          text = fold.summary,
          style = style,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = expanded),
        )
        Glyph(
          name = "chevron-down",
          tint = muted(),
          size = 14.dp,
          modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
      }
    }
  }
}
