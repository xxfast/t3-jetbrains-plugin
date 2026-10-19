package io.github.xxfast.t3code.plugin.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import javax.swing.Icon

/** A T3 thread as an editor tab, named after the thread. Set [title] when it changes upstream. */
class ThreadVirtualFile(val threadId: String, title: String) :
  LightVirtualFile(title, T3ThreadFileType, "") {
  // Nullable because the super constructor may ask for the name before this is initialised
  @Volatile
  private var renamed: String? = null

  var title: String
    get() = renamed ?: super.getName()
    set(value) {
      renamed = value
    }

  init {
    isWritable = false
  }

  override fun getName(): String = title
}

/** Only here to give the tab an icon; never registered, so no file is ever detected as one. */
object T3ThreadFileType : FileType {
  override fun getName(): String = "T3 Thread"

  override fun getDescription(): String = "T3 Code thread"

  override fun getDefaultExtension(): String = ""

  override fun getIcon(): Icon = AllIcons.Toolwindows.ToolWindowMessages

  override fun isBinary(): Boolean = true

  override fun isReadOnly(): Boolean = true

  override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
