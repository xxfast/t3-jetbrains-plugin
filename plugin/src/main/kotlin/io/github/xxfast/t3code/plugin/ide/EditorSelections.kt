package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import io.github.xxfast.t3code.plugin.ui.EditorSelection
import io.github.xxfast.t3code.plugin.ui.nextFileCommentId
import io.github.xxfast.t3code.plugin.ui.reviewCommentContextId
import io.github.xxfast.t3code.plugin.ui.selectedLines
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last selection in this project's editors, which the composer carries with the next send the
 * way Claude Code's IDE integration does with `selection_changed`. It stays while focus moves to
 * the composer; deselecting, closing the file, dismissing its chip or sending clears it.
 */
@Service(Service.Level.PROJECT)
class EditorSelections(private val project: Project) : Disposable {
  private val _selection: MutableStateFlow<EditorSelection?> = MutableStateFlow(null)
  val selection: StateFlow<EditorSelection?> = _selection.asStateFlow()

  // The editor [selection] came from
  @Volatile private var source: Editor? = null

  init {
    val factory: EditorFactory = EditorFactory.getInstance()
    val selections = object : SelectionListener {
      override fun selectionChanged(event: SelectionEvent) = track(event.editor)
    }
    factory.eventMulticaster.addSelectionListener(selections, this)
    val releases = object : EditorFactoryListener {
      override fun editorReleased(event: EditorFactoryEvent) {
        if (event.editor == source) clear()
      }
    }
    factory.addEditorFactoryListener(releases, this)
  }

  /** Forgets the selection whose comment has [contextId], when it's still the last one. */
  fun dismiss(contextId: String) {
    val current: EditorSelection = _selection.value ?: return
    if (reviewCommentContextId(current.id) == contextId) clear()
  }

  private fun track(editor: Editor) {
    if (editor.project != project || editor.editorKind != EditorKind.MAIN_EDITOR) return
    val model: SelectionModel = editor.selectionModel
    if (!model.hasSelection()) {
      if (editor == source) clear()
      return
    }
    val document: Document = editor.document
    val file: VirtualFile = FileDocumentManager.getInstance().getFile(document) ?: return
    val path: Path = file.fileSystem.getNioPath(file) ?: return
    val start: Int = model.selectionStart
    val end: Int = model.selectionEnd
    val endLine: Int = document.getLineNumber(end)
    val lines: IntRange = selectedLines(
      startLine = document.getLineNumber(start),
      endLine = endLine,
      endsAtLineStart = end > start && document.getLineStartOffset(endLine) == end,
    )
    val range = TextRange(
      document.getLineStartOffset(lines.first - 1),
      document.getLineEndOffset(lines.last - 1),
    )
    source = editor
    _selection.value = EditorSelection(
      id = nextFileCommentId(),
      path = path,
      lines = lines,
      text = document.getText(range),
    )
  }

  private fun clear() {
    source = null
    _selection.value = null
  }

  override fun dispose() = clear()

  companion object {
    fun getInstance(project: Project): EditorSelections = project.service()
  }
}
