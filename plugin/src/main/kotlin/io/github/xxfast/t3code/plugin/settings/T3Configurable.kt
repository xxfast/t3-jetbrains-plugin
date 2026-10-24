package io.github.xxfast.t3code.plugin.settings

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.protocol.ConnectionState
import javax.swing.JComponent
import javax.swing.JEditorPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class T3Configurable : BoundConfigurable("T3 Code") {
  private var status: Job? = null

  override fun createPanel(): DialogPanel {
    lateinit var comment: Cell<JEditorPane>
    val panel: DialogPanel = panel {
      row("Server URL") {
        textField()
          .bindText(T3Settings.get()::serverUrl)
          .columns(COLUMNS_LARGE)
      }
      row {
        checkBox("Confirm thread archiving")
          .bindSelected(T3Settings.get()::confirmThreadArchive)
          .comment("Ask before a thread is archived.")
      }
      row {
        checkBox("Confirm thread deletion")
          .bindSelected(T3Settings.get()::confirmThreadDelete)
          .comment("Ask before deleting a thread and its chat history.")
      }
      row {
        checkBox("Show skills in slash menu")
          .bindSelected(T3Settings.get()::showSkillsInSlashMenu)
          .comment(
            "Also include skills in the / command menu. Skills always appear when you type $.",
          )
      }
      row {
        checkBox("Rich text composer")
          .bindSelected(T3Settings.get()::composerRichTextEnabled)
          .comment("Show formatted Markdown as you type.")
      }
      row("Follow-up behavior") {
        // Upstream's follow-up behavior row, SettingsPanels.tsx:1507
        comboBox(listOf(T3Settings.FOLLOW_UP_QUEUE, T3Settings.FOLLOW_UP_STEER))
          .bindItem(
            getter = { T3Settings.get().followUpBehavior },
            setter = { value -> value?.let { T3Settings.get().followUpBehavior = it } },
          )
          .applyToComponent {
            renderer = textListCellRenderer { value -> value?.replaceFirstChar(Char::uppercase) }
          }
          .comment(
            "Queue follow-ups while the agent runs or steer the current run. " +
              "Press ${if (SystemInfo.isMac) "⌘" else "Ctrl"} + Enter to do the opposite " +
              "for one message.",
          )
      }
      row {
        button("Pair...") {
          apply()
          PairDialog().takeIf { it.showAndGet() }?.let { T3.get().pair(it.input) }
        }
        button("Forget credential") { T3.get().forget() }
      }
      row { comment = comment("Checking...") }
    }
    status = T3.get().watch { connection ->
      val url: String = T3Settings.get().serverUrl
      val paired: Boolean = T3Credentials.load(url) != null
      val text: String = when {
        connection is ConnectionState.AuthError -> connection.message
        paired -> "Paired with $url"
        else -> "Not paired with $url"
      }
      withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        comment.component.text = text
      }
    }
    return panel
  }

  override fun apply() {
    val before: String = T3Settings.get().serverUrl
    super.apply()
    if (T3Settings.get().serverUrl != before) T3.get().reconnect()
  }

  override fun disposeUIResources() {
    status?.cancel()
    status = null
    super.disposeUIResources()
  }
}

private class PairDialog : DialogWrapper(true) {
  var input: String = ""

  init {
    title = "Pair with T3 Code"
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row("Pairing link or code") {
      textField()
        .bindText(::input)
        .columns(COLUMNS_LARGE)
        .focused()
    }
    row { comment("Desktop app > Settings > Connections > create a pairing link") }
  }
}
