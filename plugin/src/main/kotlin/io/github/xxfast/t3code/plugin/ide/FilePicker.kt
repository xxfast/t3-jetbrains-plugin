package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** Asks for files to attach, local ones only, and hands back their paths. On the EDT. */
fun chooseAttachmentFiles(project: Project, onChosen: (List<Path>) -> Unit) {
  val descriptor: FileChooserDescriptor =
    FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withTitle("Attach Files")
  FileChooser.chooseFiles(descriptor, project, null) { files: List<VirtualFile> ->
    val paths: List<Path> = files.filter { it.isInLocalFileSystem }.map { it.toNioPath() }
    if (paths.isNotEmpty()) onChosen(paths)
  }
}
