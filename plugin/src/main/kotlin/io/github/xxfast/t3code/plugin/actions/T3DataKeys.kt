package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.DataKey
import io.github.xxfast.t3code.plugin.ui.AttachmentActions
import io.github.xxfast.t3code.plugin.ui.ComposerShortcuts
import io.github.xxfast.t3code.protocol.ThreadView

/** What thread actions act on. Without one they fall back to the selected thread. */
object T3DataKeys {
  val THREAD_ID: DataKey<String> = DataKey.create("T3Code.ThreadId")

  /** Set when the action came from a Sidebar row, which renames in place instead of a dialog. */
  val FROM_SIDEBAR: DataKey<Boolean> = DataKey.create("T3Code.FromSidebar")

  /** Which of [THREAD_ID]'s terminals to open, where the context names one. */
  val TERMINAL_ID: DataKey<String> = DataKey.create("T3Code.TerminalId")

  /** The live view of [THREAD_ID] where a host holds one, so actions needn't subscribe again. */
  val THREAD_VIEW: DataKey<ThreadView> = DataKey.create("T3Code.ThreadView")

  /** The focused composer's attachment actions, for [PasteAttachmentAction]. */
  val ATTACHMENTS: DataKey<AttachmentActions> = DataKey.create("T3Code.Attachments")

  /** The focused composer's shortcut targets, for the `T3Code.Composer.*` actions. */
  val COMPOSER: DataKey<ComposerShortcuts> = DataKey.create("T3Code.Composer")
}
