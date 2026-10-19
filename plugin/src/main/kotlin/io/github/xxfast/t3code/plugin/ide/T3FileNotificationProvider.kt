package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/** Where a read-only copy came from. Lives in the user data of the files [T3Opener] makes. */
data class T3Source(val projectTitle: String, val path: String)

val T3_SOURCE: Key<T3Source> = Key.create("io.github.xxfast.t3code.source")

/** The banner on files fetched from the server: they're copies, not the real thing. */
class T3FileNotificationProvider : EditorNotificationProvider, DumbAware {
  override fun collectNotificationData(
    project: Project,
    file: VirtualFile,
  ): Function<in FileEditor, out JComponent?>? {
    val source: T3Source = file.getUserData(T3_SOURCE) ?: return null
    return Function { editor ->
      EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info)
        .text("Read-only copy from T3 project ${source.projectTitle}: ${source.path}")
    }
  }
}
