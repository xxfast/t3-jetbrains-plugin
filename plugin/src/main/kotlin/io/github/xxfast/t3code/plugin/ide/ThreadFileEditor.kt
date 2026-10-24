package io.github.xxfast.t3code.plugin.ide

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import io.github.xxfast.t3code.plugin.ChatContent
import io.github.xxfast.t3code.plugin.DraftThreads
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ThreadLoad
import io.github.xxfast.t3code.plugin.ThreadSelection
import io.github.xxfast.t3code.plugin.actions.ThreadPanel
import io.github.xxfast.t3code.plugin.threadLoad
import io.github.xxfast.t3code.plugin.ui.TimelineMemory
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel

/** One thread in an editor tab. One subscription per tab, shared by the screen and the title. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadFileEditor(
  private val project: Project,
  private val file: ThreadVirtualFile,
) : UserDataHolderBase(), FileEditor, DumbAware {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Bumped by Retry; every bump resubscribes. */
  private val attempt: MutableStateFlow<Int> = MutableStateFlow(0)

  private val load: StateFlow<ThreadLoad> = attempt
    .flatMapLatest {
      T3.get().client.threadLoad(file.threadId, DraftThreads.get().draft(file.threadId))
    }
    .stateIn(scope, SharingStarted.Eagerly, ThreadLoad())

  /** Where the timeline was, kept while the panel's composition comes and goes with the tab. */
  private val memory: TimelineMemory = TimelineMemory()

  private val panel: JComponent = JewelComposePanel {
    val current: ThreadLoad by load.collectAsState()
    // Side by side with code in the editor area, so it reads at the editor's size
    EditorSizedText {
      ChatContent(
        project = project,
        threadId = file.threadId,
        load = current,
        onRetry = { attempt.update { it + 1 } },
        memory = memory,
      )
    }
  }

  // The thread actions on top, acting on this tab's thread
  private val root: JComponent = ThreadPanel(panel, file.threadId, view = { load.value.view })

  init {
    scope.launch {
      load.mapNotNull { it.view?.thread?.title }.distinctUntilChanged().collect { title ->
        if (title == file.title) return@collect
        file.title = title
        withContext(Dispatchers.EDT) {
          if (project.isDisposed) return@withContext
          FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }
      }
    }
  }

  override fun getComponent(): JComponent = root

  override fun getPreferredFocusedComponent(): JComponent = panel

  override fun getName(): String = "T3 Chat View"

  override fun getFile(): ThreadVirtualFile = file

  override fun getState(level: FileEditorStateLevel): FileEditorState =
    ThreadEditorState.of(memory)

  override fun setState(state: FileEditorState) {
    if (state is ThreadEditorState) state.applyTo(memory)
  }

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  // Switching tabs moves the selection in T3 Sidebar along with it
  override fun selectNotify() {
    ThreadSelection.getInstance(project).select(file.threadId)
    ThreadVisits.getInstance(project).opened(file.threadId)
  }

  override fun deselectNotify() {
    ThreadVisits.getInstance(project).closed(file.threadId)
  }

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    ThreadVisits.getInstance(project).closed(file.threadId)
    scope.cancel()
  }
}
