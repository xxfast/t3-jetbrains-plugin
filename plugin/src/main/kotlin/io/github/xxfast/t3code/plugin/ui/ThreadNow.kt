package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.linkStyle

// What the thread is doing now, at the end of the timeline,
// apps/web/src/components/chat/ThreadNow.tsx

/**
 * A line of thread state for the end of the timeline, upstream's `ThreadNowItem`. A link in
 * [description] is a `LinkAnnotation`, coloured by the line.
 */
class ThreadNowItem(
  val id: String,
  val icon: @Composable () -> Unit,
  val title: AnnotatedString,
  val description: AnnotatedString? = null,
  val actions: (@Composable () -> Unit)? = null,
)

/**
 * Whether the timeline has a footer, `timelineFooter`'s rule (ChatView.tsx:3014) over the parts
 * the plugin has: `hasThreadNow` (ChatView.tsx:2990) and `hasQueuedTurns`. Handoffs, the status
 * line and guest messages aren't at the end of the plugin's timeline.
 */
fun hasTimelineFooter(
  attention: ThreadNowItem? = null,
  work: ThreadNowItem? = null,
  sync: ThreadNowItem? = null,
  goal: ThreadNowItem? = null,
  queuedTurns: Boolean = false,
): Boolean = attention != null || work != null || sync != null || goal != null || queuedTurns

/**
 * `ThreadNow`: what needs you, background work, the sync status while messages load, then the
 * goal. Only [work] is filled today; child input, the sync line and goals aren't ported.
 */
@Composable
fun ThreadNow(
  attention: ThreadNowItem? = null,
  work: ThreadNowItem? = null,
  sync: ThreadNowItem? = null,
  goal: ThreadNowItem? = null,
) {
  attention?.let { ThreadNowLine(it) }
  work?.let { ThreadNowLine(it) }
  sync?.let { ThreadNowLine(it) }
  goal?.let { ThreadNowLine(it) }
}

/**
 * `ThreadNowLine`: a 14px icon, the title and a dimmer description as one line that ellipsises
 * before the actions, so Stop stays reachable, and the actions after it. A cut line gets
 * `NoticeText`'s details button (ComposerBannerStack.tsx:52), which opens all of it, links still
 * live.
 */
@Composable
fun ThreadNowLine(item: ThreadNowItem, modifier: Modifier = Modifier) {
  // `text-muted-foreground/60 light:text-muted-foreground/80`
  val dim: Color = muted().copy(alpha = if (JewelTheme.isDark) 0.6f else 0.8f)
  val link: SpanStyle = SpanStyle(color = JewelTheme.linkStyle.colors.content)
  val text: AnnotatedString = remember(item.title, item.description, dim, link) {
    buildAnnotatedString {
      append(item.title)
      val description: AnnotatedString = item.description ?: return@buildAnnotatedString
      append(" ")
      val start: Int = length
      withStyle(SpanStyle(color = dim)) { append(description) }
      description.getLinkAnnotations(0, description.length).forEach { range ->
        addStyle(link, start + range.start, start + range.end)
      }
    }
  }
  var cut: Boolean by remember { mutableStateOf(false) }
  Row(
    modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(ICON), contentAlignment = Alignment.Center) { item.icon() }
    Text(
      text = text,
      style = small(),
      color = muted(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = { layout -> cut = layout.hasVisualOverflow },
      modifier = Modifier.weight(1f, fill = false),
    )
    if (cut) NoticeDetails(text)
    item.actions?.let { actions ->
      Row(
        modifier = Modifier.padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) { actions() }
    }
  }
}

/**
 * `NoticeText`'s details button and popover: an info icon that opens the whole line, wrapped,
 * with its links. Upstream opens on hover too; a popup that holds clickable links opens on click.
 */
@Composable
private fun NoticeDetails(text: AnnotatedString) {
  var open: Boolean by remember { mutableStateOf(false) }
  Box(
    modifier = Modifier
      .size(18.dp)
      .clip(RoundedCornerShape(4.dp))
      .clickable { open = !open },
    contentAlignment = Alignment.Center,
  ) {
    Glyph("info", muted(), ICON)
    if (open) {
      Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = { open = false },
        properties = PopupProperties(focusable = true),
      ) {
        val shape: Shape = RoundedCornerShape(8.dp)
        Box(
          Modifier
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(8.dp),
        ) {
          Text(text = text, style = small(), color = muted())
        }
      }
    }
  }
}

/** `[&_svg]:size-3.5`. */
private val ICON: Dp = 14.dp
