package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.awt.RelativePoint
import io.github.xxfast.t3code.plugin.actions.T3DataKeys
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/** What the composer's attachments and context do. The defaults do nothing, for previews. */
class AttachmentActions(
  val onFiles: (List<Path>) -> Unit = {},
  val onPasteImage: (Image) -> Unit = {},
  val onPickFiles: () -> Unit = {},
  val onRemoveAttachment: (id: String) -> Unit = {},
  val onRetryAttachment: (id: String) -> Unit = {},
  val onOpenAttachment: (id: String) -> Unit = {}, // an image tile's click, the full image
  val onRemoveContext: (contextId: String) -> Unit = {},
  val onRefused: (reason: String) -> Unit = {},
  val onStash: (at: RelativePoint) -> Unit = {}, // the stash shortcut, `StashPromptAction`
  val onOpenStash: (at: RelativePoint) -> Unit = {}, // the Stash badge's click
  // What `foldPastedText` would do with a text paste, asked before the IDE's paste runs
  val pasteFold: (text: String) -> PasteFold = { PasteFold.Inline },
  val onFoldPaste: (text: String) -> Unit = {},
  // The paste-as-text shortcut: the text over the selection, never folded
  val onPasteInline: (text: String) -> Unit = {},
)

/**
 * The draft's images as tiles, then the editor's selection, its review comments and other files
 * as chips, above the text field. Images are upstream's composer tiles (`ChatComposer.tsx:6763`): a wrapping row
 * with `gap-2`, each `h-16 w-16 rounded-lg border` with the picture cropped to fill, a click to
 * see it whole, the upload's percentage along the bottom, a retry when it failed and a remove
 * button in the corner; videos join them as play tiles. The chips are like upstream's composer
 * context chips: a comment by its label (`Paths.kt L10 to L20`), a file by name and
 * `formatAttachmentSize` (its percentage while it uploads), the error colour when it failed
 * (click to retry), and a remove button on each. The selection is ours, a comment's chip marked
 * "selected". Then why the last attach was refused, or why Send waits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerChips(extras: DraftExtras, enabled: Boolean, actions: AttachmentActions) {
  val note: String? = extras.notice ?: extras.blockReason
  val selection: ReviewCommentContext? = extras.selection
  val empty: Boolean = extras.comments.isEmpty() && extras.attachments.isEmpty()
  if (empty && selection == null && note == null) return
  val (images: List<DraftAttachment>, others: List<DraftAttachment>) =
    extras.attachments.partition { it.kind == AttachmentKind.IMAGE }
  // `composerOtherFilesForPresentation`: videos are tiles beside the images
  val (videos: List<DraftAttachment>, files: List<DraftAttachment>) =
    others.partition(::isVideoAttachment)
  Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
    if (images.isNotEmpty() || videos.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        images.forEach { image -> ImageTile(image, enabled, actions) }
        videos.forEach { video -> VideoTile(video, enabled, actions) }
      }
    }
    if (selection != null || extras.comments.isNotEmpty() || files.isNotEmpty()) Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (selection != null) Chip(
        label = selection.label,
        detail = "selected",
        enabled = enabled,
        onRemove = { actions.onRemoveContext(selection.contextId) },
      )
      extras.comments.forEach { comment ->
        Chip(
          label = comment.label,
          detail = null,
          enabled = enabled,
          onRemove = { actions.onRemoveContext(comment.contextId) },
        )
      }
      files.forEach { attachment ->
        val failed: Upload.Failed? = attachment.upload as? Upload.Failed
        val uploading: Upload.Uploading? = attachment.upload as? Upload.Uploading
        Chip(
          label = attachment.name,
          // `ChatComposer.tsx:7052`: the percentage while it uploads, else the size
          detail = failed?.reason
            ?: uploading?.let { formatAttachmentUploadProgress(it.progress) }
            ?: formatAttachmentSize(attachment.sizeBytes),
          enabled = enabled,
          failed = failed != null,
          onClick = if (failed != null) {
            { actions.onRetryAttachment(attachment.id) }
          } else {
            null
          },
          onRemove = { actions.onRemoveAttachment(attachment.id) },
        )
      }
    }
    if (note != null) {
      val color: Color = if (extras.notice != null) {
        JewelTheme.globalColors.text.error
      } else {
        JewelTheme.globalColors.text.info
      }
      Text(note, style = JewelTheme.typography.small, color = color)
    }
  }
}

/** Upstream's 64px composer image tile, see [ComposerChips]. */
@Composable
private fun ImageTile(attachment: DraftAttachment, enabled: Boolean, actions: AttachmentActions) {
  AttachmentTile(attachment, enabled, actions) {
    // A draft restored after a restart or from the stash has no local bytes: its uploaded image
    // is fetched by server id, through the loader and cache the timeline uses
    val images: MessageImageState = LocalMessageImages.current
    val uploaded: ChatAttachment? = (attachment.upload as? Upload.Ready)?.attachment
    val fetched: Boolean = attachment.preview == null && uploaded != null
    LaunchedEffect(uploaded?.id, fetched) { if (fetched) images.onShown(listOf(uploaded!!)) }
    val preview: ImageBitmap? = attachment.preview ?: uploaded?.let { images.previews[it.id] }
    if (preview != null) {
      Image(
        bitmap = preview,
        contentDescription = "Preview ${attachment.name}",
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxSize()
          .clickable { actions.onOpenAttachment(attachment.id) },
      )
    } else {
      Text(
        text = attachment.name,
        style = JewelTheme.typography.small,
        color = JewelTheme.globalColors.text.info,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
      )
    }
  }
}

/**
 * Upstream's composer video tile (`ChatComposer.tsx:6945`): black, a play icon, a click to play
 * it. Upstream draws the first frame behind the icon from a `<video>` element; the JVM can't
 * decode video, so the plugin's tile has the icon alone.
 */
@Composable
private fun VideoTile(attachment: DraftAttachment, enabled: Boolean, actions: AttachmentActions) {
  AttachmentTile(attachment, enabled, actions, background = Color.Black) {
    Box(
      modifier = Modifier.fillMaxSize().clickable { actions.onOpenAttachment(attachment.id) },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        key = AllIconsKeys.Actions.Execute,
        contentDescription = "Play ${attachment.name}",
        modifier = Modifier.size(16.dp),
      )
    }
  }
}

/**
 * A 64px composer tile around [content]: the upload's percentage along the bottom
 * (`formatAttachmentUploadProgress`, `ChatComposer.tsx:6872`), a retry when it failed and a
 * remove button in the corner.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun AttachmentTile(
  attachment: DraftAttachment,
  enabled: Boolean,
  actions: AttachmentActions,
  background: Color = Color.Unspecified,
  content: @Composable BoxScope.() -> Unit,
) {
  val failed: Upload.Failed? = attachment.upload as? Upload.Failed
  val uploading: Upload.Uploading? = attachment.upload as? Upload.Uploading
  val shape: RoundedCornerShape = RoundedCornerShape(8.dp)
  val outline: Color = if (failed != null) {
    JewelTheme.globalColors.text.error
  } else {
    JewelTheme.globalColors.borders.normal
  }
  // `bg-background/85`, behind the strip and the buttons over the picture
  val scrim: Color = JewelTheme.globalColors.panelBackground.copy(alpha = 0.85f)
  Box(
    Modifier.size(64.dp).clip(shape).background(background).border(1.dp, outline, shape),
  ) {
    content()
    if (uploading != null) {
      Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(scrim),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = formatAttachmentUploadProgress(uploading.progress),
          style = JewelTheme.typography.small,
        )
      }
    }
    if (failed != null) {
      Box(Modifier.align(Alignment.BottomStart).padding(2.dp).background(scrim, shape)) {
        Tooltip(tooltip = { Text(failed.reason) }) {
          IconActionButton(
            key = AllIconsKeys.Actions.Refresh,
            contentDescription = "Retry upload for ${attachment.name}",
            onClick = { actions.onRetryAttachment(attachment.id) },
            enabled = enabled,
          )
        }
      }
    }
    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).background(scrim, shape)) {
      IconActionButton(
        key = AllIconsKeys.General.Close,
        contentDescription = "Remove ${attachment.name}",
        onClick = { actions.onRemoveAttachment(attachment.id) },
        enabled = enabled,
      )
    }
  }
}

@Composable
private fun Chip(
  label: String,
  detail: String?,
  enabled: Boolean,
  onRemove: () -> Unit,
  failed: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  val outline: Color = if (failed) {
    JewelTheme.globalColors.text.error
  } else {
    JewelTheme.globalColors.borders.normal
  }
  val clickable: Modifier =
    if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier
  Row(
    modifier = Modifier
      .border(1.dp, outline, RoundedCornerShape(8.dp))
      .then(clickable)
      .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = JewelTheme.typography.small,
      color = if (failed) JewelTheme.globalColors.text.error else Color.Unspecified,
    )
    if (detail != null) {
      Text(detail, style = JewelTheme.typography.small, color = JewelTheme.globalColors.text.info)
    }
    IconActionButton(
      key = AllIconsKeys.General.Close,
      contentDescription = "Remove",
      onClick = onRemove,
      enabled = enabled,
    )
  }
}

/**
 * The footer's paperclip, `ChatComposer.tsx` around line 7330: a ghost `icon-sm` button that
 * opens a file picker, tooltip "Attach files".
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
fun AttachButton(enabled: Boolean, actions: AttachmentActions) {
  Tooltip(tooltip = { Text("Attach files") }) {
    ComposerControl(onClick = actions.onPickFiles, enabled = enabled, square = true) {
      ComposerControlIcon("paperclip")
    }
  }
}
/**
 * Files dragged from outside the IDE onto the chat, as [onFiles]. Upstream's
 * `makeWorkspaceFileDropHandlers` (chat/workspaceFileDrop.ts) on the whole chat canvas: the
 * composer is a thin strip and a file let go over the timeline should still land in it.
 * [onDragActive] is its `setDragActive`, true while a file drag is over the target, for the
 * composer's highlight. A drop whose files can't be read, a stale transferable on Windows say,
 * goes to [onRefused] and never reaches AWT.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.fileDropTarget(
  enabled: Boolean,
  onFiles: (List<Path>) -> Unit,
  onRefused: (String) -> Unit,
  onDragActive: (Boolean) -> Unit = {},
): Modifier {
  val files: State<(List<Path>) -> Unit> = rememberUpdatedState(onFiles)
  val refused: State<(String) -> Unit> = rememberUpdatedState(onRefused)
  val active: State<(Boolean) -> Unit> = rememberUpdatedState(onDragActive)
  val target: DragAndDropTarget = remember {
    object : DragAndDropTarget {
      override fun onEntered(event: DragAndDropEvent) = active.value(true)

      override fun onExited(event: DragAndDropEvent) = active.value(false)

      // A drag let go outside the target, or cancelled, gets no `onExited`
      override fun onEnded(event: DragAndDropEvent) = active.value(false)

      override fun onDrop(event: DragAndDropEvent): Boolean {
        active.value(false)
        val paths: List<Path>? = try {
          filesOf(event.awtTransferable)
        } catch (_: TransferFailed) {
          refused.value(UNREADABLE_DROP)
          return false
        }
        files.value(paths ?: return false)
        return true
      }
    }
  }
  return dragAndDropTarget(
    shouldStartDragAndDrop = { event ->
      enabled && event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    },
    target = target,
  )
}

/**
 * Offers [actions] to the IDE's action system while the text field has focus, so a paste
 * shortcut with files or an image on the clipboard reaches `PasteAttachmentAction`. Jewel's
 * `provideData` only answers for a focused node, which is what keeps the action off other text
 * fields.
 */
fun Modifier.attachmentTarget(enabled: Boolean, actions: AttachmentActions): Modifier =
  provideData { if (enabled) set(T3DataKeys.ATTACHMENTS.name, actions) }

/**
 * Files or an image on the clipboard, as [AttachmentActions.onFiles] or
 * [AttachmentActions.onPasteImage]; files win when both are there. Through [CopyPasteManager],
 * which already copes with a clipboard another app holds or a transferable gone stale.
 */
fun attachFromClipboard(clipboard: CopyPasteManager, actions: AttachmentActions) {
  try {
    if (clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)) {
      val files: List<Path> = clipboard.getContents<Any>(DataFlavor.javaFileListFlavor)
        ?.let(::pathsOf)
        ?: return
      actions.onFiles(files)
      return
    }
    if (!clipboard.areDataFlavorsAvailable(DataFlavor.imageFlavor)) return
    val image: Image = clipboard.getContents<Image>(DataFlavor.imageFlavor) ?: return
    actions.onPasteImage(image)
  } catch (_: Exception) {
    actions.onRefused("The clipboard could not be read.")
  }
}

private const val UNREADABLE_DROP: String = "The dropped files could not be read."

/** A transferable that offered files and then couldn't hand them over. */
private class TransferFailed(cause: Exception) : Exception(cause)

// Throws TransferFailed, never the AWT exceptions themselves
private fun filesOf(contents: Transferable): List<Path>? {
  if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
  val data: Any = try {
    contents.getTransferData(DataFlavor.javaFileListFlavor)
  } catch (e: UnsupportedFlavorException) {
    throw TransferFailed(e)
  } catch (e: IOException) {
    throw TransferFailed(e)
  }
  return pathsOf(data)
}

private fun pathsOf(data: Any): List<Path>? =
  (data as? List<*>)?.filterIsInstance<File>()?.map { it.toPath() }?.ifEmpty { null }

/**
 * A pasted image's name. The web app keeps the clipboard file's own name, `image.png` from a
 * browser's screenshot paste, else `image` (`ChatComposer.tsx`); AWT's clipboard image has none.
 */
const val PASTED_IMAGE_NAME: String = "image.png"

/**
 * [image] as PNG bytes. Off the EDT: a large screenshot takes a moment. A clipboard image may not
 * have loaded yet, so it's loaded first; one that still has no size is refused.
 */
fun encodePng(image: Image): ByteArray {
  val buffered: BufferedImage = image as? BufferedImage ?: run {
    // ImageIcon waits on a MediaTracker until the image has loaded
    val loaded: Image = ImageIcon(image).image
    val width: Int = loaded.getWidth(null)
    val height: Int = loaded.getHeight(null)
    require(width > 0 && height > 0) { "The image has no size" }
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
      val graphics = copy.createGraphics()
      graphics.drawImage(loaded, 0, 0, null)
      graphics.dispose()
    }
  }
  require(buffered.width > 0 && buffered.height > 0) { "The image has no size" }
  return ByteArrayOutputStream().use { out ->
    ImageIO.write(buffered, "png", out)
    out.toByteArray()
  }
}
