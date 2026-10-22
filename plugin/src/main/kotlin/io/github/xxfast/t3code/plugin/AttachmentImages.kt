package io.github.xxfast.t3code.plugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ui.imageFileName
import io.github.xxfast.t3code.plugin.ui.previewSize
import io.github.xxfast.t3code.plugin.ui.safeFileName
import io.github.xxfast.t3code.protocol.generated.ChatAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Sent messages' image previews by attachment id, upstream's `useAssetUrls` over
 * `selectMessageImageResources` (`MessagesTimeline.tsx:2167`): fetched once each through
 * [io.github.xxfast.t3code.protocol.T3Client.attachment] and decoded off the EDT. The timeline
 * reads [previews] and calls [ensure] for the images a row shows.
 */
@Service(Service.Level.APP)
class AttachmentImages(private val scope: CoroutineScope) {
  private val _previews: MutableStateFlow<Map<String, ImageBitmap>> = MutableStateFlow(emptyMap())
  private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

  val previews: StateFlow<Map<String, ImageBitmap>> = _previews.asStateFlow()

  fun ensure(attachments: List<ChatAttachment>) {
    for (attachment in attachments) {
      if (!requested.add(attachment.id)) continue
      scope.launch(Dispatchers.IO) {
        val bitmap: ImageBitmap? = try {
          decodePreview(T3.get().client.attachment(attachment.id))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          LOG.info("Preview of ${attachment.name}: ${e.message}", e)
          null
        }
        // The row shows the name instead, and asks again the next time it's composed
        if (bitmap == null) {
          requested.remove(attachment.id)
          return@launch
        }
        _previews.update { it + (attachment.id to bitmap) }
      }
    }
  }

  /** Upstream's `onImageExpand`: the full image, here in the IDE's own image viewer. */
  fun open(project: Project, attachment: ChatAttachment) {
    scope.launchReporting(project, "Couldn't open ${attachment.name}") {
      val bytes: ByteArray = T3.get().client.attachment(attachment.id)
      openImage(project, attachment.id, attachment.name, attachment.mimeType, bytes)
    }
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(AttachmentImages::class.java)

    fun get(): AttachmentImages = service()
  }
}

/**
 * [bytes] decoded and scaled to fit [io.github.xxfast.t3code.plugin.ui.PREVIEW_MAX_SIDE], or null
 * when skia can't read them. GIF, JPEG, PNG and WebP, the types an image attachment can be.
 */
fun decodePreview(bytes: ByteArray): ImageBitmap? {
  val source: Image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
  return source.use { image ->
    val (width: Int, height: Int) = previewSize(image.width, image.height)
    if (width == image.width && height == image.height) return image.toComposeImageBitmap()
    Surface.makeRasterN32Premul(width, height).use { surface ->
      surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
        null,
        true,
      )
      surface.makeImageSnapshot().toComposeImageBitmap()
    }
  }
}

/**
 * Opens [bytes] in an editor tab, so the IDE's image viewer zooms and pans it. Written once per
 * [key] under the IDE's temp dir with its own name, which is how the viewer knows it's an image.
 */
suspend fun openImage(
  project: Project,
  key: String,
  name: String,
  mimeType: String,
  bytes: ByteArray,
) {
  val file: VirtualFile = withContext(Dispatchers.IO) {
    val folder: Path = Path.of(PathManager.getTempPath(), "t3code-attachments", safeFileName(key))
    val path: Path = Files.createDirectories(folder).resolve(imageFileName(name, mimeType))
    if (!Files.exists(path)) Files.write(path, bytes)
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
  } ?: error("the file couldn't be written")
  withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFile(file, true) }
}
