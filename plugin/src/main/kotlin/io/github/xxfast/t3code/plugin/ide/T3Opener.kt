package io.github.xxfast.t3code.plugin.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.protocol.FileContents
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.TurnItem
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.xxfast.t3code.protocol.Project as T3Project

/**
 * Opens what a thread points at: files in the editor, changes in the diff viewer. It opens, it
 * never applies; nothing here writes to disk. Files outside this IDE project come from the server
 * as read-only copies.
 */
@Service(Service.Level.PROJECT)
class T3Opener(private val project: Project, private val scope: CoroutineScope) {
  private val client: T3Client get() = T3.get().client

  /** Opens [path] from [view]'s thread at 1-based [line]. */
  fun openFile(view: ThreadView, path: String, line: Int? = null) {
    launchSafely("Couldn't open $path") {
      val where: Where = where(view, path)
      val local: VirtualFile? = where.local?.let(::findLocal)
      if (local != null) {
        withContext(Dispatchers.EDT) {
          OpenFileDescriptor(project, local, editorLine(line), 0).navigate(true)
        }
        return@launchSafely
      }
      val contents: FileContents = client.readFile(where.cwd, path)
      withContext(Dispatchers.EDT) {
        val file: LightVirtualFile = readOnlyCopy(where, path, contents.contents)
        OpenFileDescriptor(project, file, editorLine(line), 0).navigate(true)
      }
    }
  }

  /** Before and after of one `file_change`. */
  fun showDiff(view: ThreadView, change: TurnItem.FileChange) {
    launchSafely("Couldn't show the diff for ${change.fileName}") {
      val where: Where = where(view, change.fileName)
      val local: VirtualFile? = where.local?.let(::findLocal)
      val oldStr: String? = change.oldStr
      val newStr: String? = change.newStr
      val diffStr: String? = change.diffStr
      when {
        oldStr != null || newStr != null -> {
          val current: String? = local?.let { file -> text(file) }
          val before: String? = current?.let { editBefore(it, oldStr, newStr) }
          val sides: Sides = if (before != null) {
            Sides(before.takeIf { oldStr != null }, current, afterIsBase = true)
          } else {
            Sides(oldStr, newStr)
          }
          val request: SimpleDiffRequest = request(change.fileName, sides, local)
          withContext(Dispatchers.EDT) { DiffManager.getInstance().showDiff(project, request) }
        }

        !diffStr.isNullOrBlank() -> showPatch(view, diffStr, change.fileName, change.fileName)
        else -> notify("No diff for ${change.fileName}", NotificationType.INFORMATION)
      }
    }
  }

  /** Everything run [run] changed, one file per step in the diff viewer. */
  fun showRunDiff(view: ThreadView, run: Run) {
    launchSafely("Couldn't show the diff for run ${run.ordinal}") {
      val patch: String = client.turnDiff(view.thread.id, run.ordinal - 1, run.ordinal)
      showPatch(view, patch, fallbackPath = null, label = "run ${run.ordinal}")
    }
  }

  private suspend fun showPatch(
    view: ThreadView,
    patch: String,
    fallbackPath: String?,
    label: String,
  ) {
    val files: List<FilePatch> = parsePatch(patch, fallbackPath)
    if (files.isEmpty()) {
      notify("No changes in $label", NotificationType.INFORMATION)
      return
    }
    val requests: List<SimpleDiffRequest> = files.map { file ->
      val where: Where = where(view, file.path)
      val local: VirtualFile? = where.local?.let(::findLocal)
      val current: String? = when {
        file.added || file.deleted -> null
        local != null -> text(local)
        else -> serverText(where.cwd, file.path)
      }
      request(file.path, file.sides(current), local)
    }
    withContext(Dispatchers.EDT) {
      val manager: DiffManager = DiffManager.getInstance()
      if (requests.size == 1) {
        manager.showDiff(project, requests.single())
      } else {
        manager.showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
      }
    }
  }

  private fun request(path: String, sides: Sides, local: VirtualFile?): SimpleDiffRequest {
    val factory: DiffContentFactory = DiffContentFactory.getInstance()
    val type: FileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName(path))
    fun content(text: String?): DiffContent =
      if (text == null) factory.createEmpty() else factory.create(project, text, type)
    val right: DiffContent = if (sides.afterIsBase && local != null) {
      factory.create(project, local)
    } else {
      content(sides.after)
    }
    return SimpleDiffRequest(path, content(sides.before), right, "Before", "After")
  }

  /** The thread's cwd, and the local path when it's inside this IDE project. */
  private data class Where(val cwd: String, val local: Path?, val t3Project: T3Project?)

  private fun where(view: ThreadView, path: String): Where {
    val t3Project: T3Project? = client.projects.value.find { it.id == view.thread.projectId }
    val cwd: String = cwdOf(view.thread.worktreePath, t3Project?.workspaceRoot ?: ".")
    val resolved: Path = resolve(cwd, path)
    val base: Path? = project.basePath?.let { Path.of(it) }
    val local: Path? = resolved.takeIf { base != null && isUnder(base, it) }
    return Where(cwd, local, t3Project)
  }

  private fun findLocal(path: Path): VirtualFile? =
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.takeIf { !it.isDirectory }

  /** The editor's text, unsaved edits included. */
  private suspend fun text(file: VirtualFile): String? =
    readAction { FileDocumentManager.getInstance().getDocument(file)?.text }

  private suspend fun serverText(cwd: String, path: String): String? = try {
    client.readFile(cwd, path).takeIf { !it.truncated }?.contents
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    LOG.info("No server copy of $path, showing hunks only", e)
    null
  }

  private fun readOnlyCopy(where: Where, path: String, text: String): LightVirtualFile {
    val name: String = fileName(path)
    val type: FileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
    val title: String = where.t3Project?.title ?: "unknown"
    return LightVirtualFile(name, type, text).apply {
      isWritable = false
      putUserData(T3_SOURCE, T3Source(title, path))
    }
  }

  private fun launchSafely(failure: String, block: suspend CoroutineScope.() -> Unit) {
    scope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info(failure, e)
        notify("$failure: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
      }
    }
  }

  private fun notify(message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(message, type)
      .notify(project)
  }

  companion object {
    const val NOTIFICATION_GROUP: String = "T3 Code"

    private val LOG: Logger = Logger.getInstance(T3Opener::class.java)

    fun getInstance(project: Project): T3Opener = project.service()
  }
}

private fun fileName(path: String): String = path.substringAfterLast('/').ifEmpty { path }
