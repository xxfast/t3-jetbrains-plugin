package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * What the timeline knows of sent images: the previews loaded so far by attachment id, a way to
 * ask for the ones a row shows, and a click to see one whole. The defaults show names only, for
 * previews and tests.
 */
class MessageImageState(
  val previews: Map<String, ImageBitmap> = emptyMap(),
  val onShown: (List<ChatAttachment>) -> Unit = {},
  val onOpen: (ChatAttachment) -> Unit = {},
)

/** Provided around the ChatView, so only the user message row reads it. */
val LocalMessageImages: ProvidableCompositionLocal<MessageImageState> =
  compositionLocalOf { MessageImageState() }

// `grid max-w-[210px] grid-cols-2 gap-2`, MessagesTimeline.tsx:2344
private val GRID_WIDTH: Dp = 210.dp
private val GRID_GAP: Dp = 8.dp
private val TILE_WIDTH: Dp = (GRID_WIDTH - GRID_GAP) / 2

/**
 * A user message's images over its text: upstream's grid in `UserTimelineRow`
 * (MessagesTimeline.tsx:2343), two columns of `aspect-[4/3] rounded-lg border` tiles, each
 * cropped to fill and a click to expand. Until a preview loads, or when it can't, the tile says
 * the name, as upstream does without a `previewUrl`.
 */
@Composable
fun MessageImages(images: List<ChatAttachment>, modifier: Modifier = Modifier) {
  if (images.isEmpty()) return
  val state: MessageImageState = LocalMessageImages.current
  LaunchedEffect(images.map { it.id }) { state.onShown(images) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
    images.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        pair.forEach { image -> MessageImageTile(image, state.previews[image.id], state.onOpen) }
      }
    }
  }
}

@Composable
private fun MessageImageTile(
  image: ChatAttachment,
  preview: ImageBitmap?,
  onOpen: (ChatAttachment) -> Unit,
) {
  val shape: RoundedCornerShape = RoundedCornerShape(8.dp)
  Box(
    modifier = Modifier
      .size(width = TILE_WIDTH, height = TILE_WIDTH * 3 / 4)
      .clip(shape)
      .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.7f))
      .border(1.dp, JewelTheme.globalColors.borders.normal, shape),
  ) {
    if (preview != null) {
      Image(
        bitmap = preview,
        contentDescription = "Preview ${image.name}",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().clickable { onOpen(image) },
      )
    } else {
      Text(
        text = image.name,
        style = JewelTheme.typography.small,
        color = JewelTheme.globalColors.text.info,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
      )
    }
  }
}
