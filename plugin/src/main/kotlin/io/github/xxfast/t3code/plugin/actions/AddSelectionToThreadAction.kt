package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.ide.T3Opener
import io.github.xxfast.t3code.plugin.ide.cwdOf
import io.github.xxfast.t3code.plugin.ui.NOT_CONNECTED
import io.github.xxfast.t3code.plugin.ui.OPERATE_SCOPE
import io.github.xxfast.t3code.plugin.ui.READ_ONLY
import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.plugin.ui.commentFilePath
import io.github.xxfast.t3code.plugin.ui.fileReviewComment
import io.github.xxfast.t3code.plugin.ui.nextFileCommentId
import io.github.xxfast.t3code.plugin.ui.selectedLines
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.has
import java.nio.file.Path

/**
 * "Add to T3 thread" in the editor's context menu: the selection, with its path and lines, joins
 * the selected thread's draft as a file comment, the record upstream's file preview makes
 * (`buildFileReviewComment`). The note is what you type in the composer, as upstream has it.
 */
class AddSelectionToThreadAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val project: Project? = event.project
    val editor: Editor? = event.getData(CommonDataKeys.EDITOR)
    val threadId: String? = project?.let { ThreadSelection.getInstance(it).selected.value }
    val selected: Boolean = editor?.selectionModel?.hasSelection() == true
    event.presentation.isVisible = selected && threadId != null
    val reason: String? = when (val connection = T3.get().client.connection.value) {
      !is ConnectionState.Connected -> NOT_CONNECTED
      else -> if (connection.session.has(OPERATE_SCOPE)) null else READ_ONLY
    }
    event.presentation.isEnabled = reason == null
    event.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project: Project = event.project ?: return
    val editor: Editor = event.getData(CommonDataKeys.EDITOR) ?: return
    val threadId: String = ThreadSelection.getInstance(project).selected.value ?: return
    val selection: SelectionModel = editor.selectionModel
    if (!selection.hasSelection()) return
    val document: Document = editor.document
    val file: VirtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
      ?: FileDocumentManager.getInstance().getFile(document)
      ?: return

    val start: Int = selection.selectionStart
    val end: Int = selection.selectionEnd
    val endLine: Int = document.getLineNumber(end)
    val lines: IntRange = selectedLines(
      startLine = document.getLineNumber(start),
      endLine = endLine,
      endsAtLineStart = end > start && document.getLineStartOffset(endLine) == end,
    )
    val comment: ReviewCommentContext = fileReviewComment(
      id = nextFileCommentId(),
      filePath = pathOf(threadId, file),
      startLine = lines.first,
      endLine = lines.last,
      text = "",
      contents = document.text,
    )
    ChatController.getInstance(project).addContext(threadId, comment)
    T3Opener.getInstance(project).openThread(threadId)
  }

  // Relative to where the thread's agent runs when the file is under it
  private fun pathOf(threadId: String, file: VirtualFile): String {
    val local: Path = file.fileSystem.getNioPath(file) ?: return file.path
    val client: T3Client = T3.get().client
    val row: Thread? = client.threads.value.find { it.id == threadId }
    val root: String? = client.projects.value.find { it.id == row?.projectId }?.workspaceRoot
    val cwd: String? = root?.let { cwdOf(row?.worktreePath, it) } ?: row?.worktreePath
    return commentFilePath(cwd, local)
  }
}
