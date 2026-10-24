package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

class ThreadFileEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is ThreadVirtualFile

  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    ThreadFileEditor(project, file as ThreadVirtualFile)

  override fun getEditorTypeId(): String = "t3-thread"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

  // The timeline's place survives a restart, as a text editor's scroll does
  override fun readState(sourceElement: Element, project: Project, file: VirtualFile):
    FileEditorState = ThreadEditorState.read(sourceElement)

  override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
    if (state is ThreadEditorState) state.write(targetElement)
  }
}
