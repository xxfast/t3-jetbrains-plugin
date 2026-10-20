package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ide.CopyPasteManager
import io.github.xxfast.t3code.plugin.actions.T3DataKeys
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
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
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
  val onRemoveContext: (contextId: String) -> Unit = {},
  val onRefused: (reason: String) -> Unit = {},
)

/**
 * The draft's review comments and attachments as chips above the text field, like upstream's
 * composer context chips: a comment by its label (`Paths.kt L10 to L20`), an attachment by name
 * and `formatAttachmentSize`, a spinner while it uploads, the error colour when it failed (click
 * to retry), and a remove button on each. Then why the last attach was refused, or why Send
 * waits.
 */
@Composable
fun ComposerChips(extras: DraftExtras, enabled: Boolean, actions: AttachmentActions) {
  val note: String? = extras.notice ?: extras.blockReason
  if (extras.comments.isEmpty() && extras.attachments.isEmpty() && note == null) return
  Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      extras.comments.forEach { comment ->
        Chip(
          label = comment.label,
          detail = null,
          enabled = enabled,
          onRemove = { actions.onRemoveContext(comment.contextId) },
        )
      }
      extras.attachments.forEach { attachment ->
        val failed: Upload.Failed? = attachment.upload as? Upload.Failed
        Chip(
          label = attachment.name,
          detail = failed?.reason ?: formatAttachmentSize(attachment.sizeBytes),
          enabled = enabled,
          uploading = attachment.upload is Upload.Uploading,
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

@Composable
private fun Chip(
  label: String,
  detail: String?,
  enabled: Boolean,
  onRemove: () -> Unit,
  uploading: Boolean = false,
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
    if (uploading) CircularProgressIndicator(Modifier.size(12.dp))
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
 * Files dragged from outside the IDE onto the composer, as [onFiles]. A drop whose files can't be
 * read, a stale transferable on Windows say, goes to [onRefused] and never reaches AWT.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.fileDropTarget(
  enabled: Boolean,
  onFiles: (List<Path>) -> Unit,
  onRefused: (String) -> Unit,
): Modifier {
  val files: State<(List<Path>) -> Unit> = rememberUpdatedState(onFiles)
  val refused: State<(String) -> Unit> = rememberUpdatedState(onRefused)
  val target: DragAndDropTarget = remember {
    object : DragAndDropTarget {
      override fun onDrop(event: DragAndDropEvent): Boolean {
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
