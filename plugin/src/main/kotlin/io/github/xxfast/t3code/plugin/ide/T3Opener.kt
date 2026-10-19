package io.github.xxfast.t3code.plugin.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.review.ADD_REVIEW_COMMENT
import io.github.xxfast.t3code.plugin.review.T3DiffSource
import io.github.xxfast.t3code.plugin.review.T3_DIFF_SOURCE
import io.github.xxfast.t3code.plugin.settings.T3Settings
import io.github.xxfast.t3code.plugin.settings.ThreadHost
import io.github.xxfast.t3code.protocol.FileContents
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.xxfast.t3code.protocol.Project as T3Project

/**
 * Opens what a thread points at: files in the editor, changes in the diff viewer. It opens, it
 * never applies; nothing here writes to disk. Files that aren't here come from the server
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

  /** Selects [threadId] and shows it where the settings say: an editor tab or "T3 Chat View". */
  fun openThread(threadId: String) {
    ThreadSelection.getInstance(project).select(threadId)
    val title: String = client.threads.value.find { it.id == threadId }?.title ?: "T3 Chat View"
    scope.launch(Dispatchers.EDT) {
      if (project.isDisposed) return@launch
      when (T3Settings.get().openThreadsIn) {
        ThreadHost.EDITOR -> {
          val file: ThreadVirtualFile = ThreadSelection.getInstance(project).file(threadId, title)
          FileEditorManager.getInstance(project).openFile(file, true)
        }

        ThreadHost.TOOL_WINDOW ->
          ToolWindowManager.getInstance(project).getToolWindow(CHAT_TOOL_WINDOW)?.activate(null)
      }
    }
  }

  /** Before and after of one `file_change`. */
  fun showDiff(view: ThreadView, change: TurnItem.FileChange) {
    launchSafely("Couldn't show the diff for ${change.fileName}") {
      val run: Run? = view.runs.find { it.id == change.runId }
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
            Sides(oldStr, newStr, excerpt = true)
          }
          val request: SimpleDiffRequest = request(view, change.fileName, sides, local, run)
          withContext(Dispatchers.EDT) { DiffManager.getInstance().showDiff(project, request) }
        }

        !diffStr.isNullOrBlank() -> showPatch(view, diffStr, change.fileName, change.fileName, run)
        else -> notify("No diff for ${change.fileName}", NotificationType.INFORMATION)
      }
    }
  }

  /** Everything run [run] changed, one file per step in the diff viewer. */
  fun showRunDiff(view: ThreadView, run: Run) {
    launchSafely("Couldn't show the diff for run ${run.ordinal}") {
      val patch: String = client.turnDiff(view.thread.id, run.ordinal - 1, run.ordinal)
      showPatch(view, patch, fallbackPath = null, label = "run ${run.ordinal}", run = run)
    }
  }

  private suspend fun showPatch(
    view: ThreadView,
    patch: String,
    fallbackPath: String?,
    label: String,
    run: Run?,
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
      request(view, file.path, file.sides(current), local, run)
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

  /**
   * One file's before and after, marked with where it came from so review comments can be left
   * on it, and with the review action in the viewer's toolbar.
   */
  private fun request(
    view: ThreadView,
    path: String,
    sides: Sides,
    local: VirtualFile?,
    run: Run?,
  ): SimpleDiffRequest {
    val factory: DiffContentFactory = DiffContentFactory.getInstance()
    val type: FileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName(path))
    fun content(text: String?): DiffContent =
      if (text == null) factory.createEmpty() else factory.create(project, text, type)
    val right: DiffContent = if (sides.afterIsBase && local != null) {
      factory.create(project, local)
    } else {
      content(sides.after)
    }
    val request = SimpleDiffRequest(path, content(sides.before), right, "Before", "After")
    val source = T3DiffSource(view.thread.id, path, run?.id, run?.ordinal, sides.excerpt)
    request.putUserData(T3_DIFF_SOURCE, source)
    ActionManager.getInstance().getAction(ADD_REVIEW_COMMENT)?.let { action ->
      request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(action))
    }
    return request
  }

  /**
   * The thread's cwd, and the file on this disk the path is, by [localPath]: with the server on
   * this machine any existing file, another project's included (the IDE gives it its own "not
   * part of the project" banner); with a remote one, only files inside this IDE project.
   */
  private data class Where(val cwd: String, val local: Path?, val t3Project: T3Project?)

  private fun where(view: ThreadView, path: String): Where {
    val t3Project: T3Project? = client.projects.value.find { it.id == view.thread.projectId }
    val cwd: String = cwdOf(view.thread.worktreePath, t3Project?.workspaceRoot ?: ".")
    val base: Path? = project.basePath?.let { Path.of(it) }
    val local: Path? = localPath(resolve(cwd, path), base, T3.serverIsLocal())
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
    scope.launchReporting(project, failure, block)
  }

  private fun notify(message: String, type: NotificationType) = notify(project, message, type)

  companion object {
    const val CHAT_TOOL_WINDOW: String = "T3 Chat View"

    private val LOG: Logger = Logger.getInstance(T3Opener::class.java)

    fun getInstance(project: Project): T3Opener = project.service()
  }
}

private fun fileName(path: String): String = path.substringAfterLast('/').ifEmpty { path }
