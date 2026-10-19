package io.github.xxfast.t3code.plugin.ide

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import io.github.xxfast.t3code.plugin.ChatContent
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.protocol.ThreadView
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel

/** One thread in an editor tab. One subscription per tab, shared by the screen and the title. */
class ThreadFileEditor(
  private val project: Project,
  private val file: ThreadVirtualFile,
) : UserDataHolderBase(), FileEditor, DumbAware {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val view: StateFlow<ThreadView?> = T3.get().client.thread(file.threadId)
    .stateIn(scope, SharingStarted.Eagerly, null)

  private val panel: JComponent = JewelComposePanel {
    val current: ThreadView? by view.collectAsState()
    ChatContent(project, current)
  }

  init {
    scope.launch {
      view.mapNotNull { it?.thread?.title }.distinctUntilChanged().collect { title ->
        if (title == file.title) return@collect
        file.title = title
        withContext(Dispatchers.EDT) {
          if (project.isDisposed) return@withContext
          FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }
      }
    }
  }

  override fun getComponent(): JComponent = panel

  override fun getPreferredFocusedComponent(): JComponent = panel

  override fun getName(): String = "T3 Chat View"

  override fun getFile(): ThreadVirtualFile = file

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  // Switching tabs moves the selection in T3 Sidebar along with it
  override fun selectNotify() {
    ThreadSelection.getInstance(project).select(file.threadId)
  }

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    scope.cancel()
  }
}
