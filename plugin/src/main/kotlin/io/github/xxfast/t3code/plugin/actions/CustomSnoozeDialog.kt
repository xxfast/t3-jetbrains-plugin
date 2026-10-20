package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import io.github.xxfast.t3code.plugin.ui.CustomSnooze
import io.github.xxfast.t3code.plugin.ui.SnoozeUnit
import io.github.xxfast.t3code.plugin.ui.resolveCustomSnooze
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Upstream's `CustomSnoozeDialog` (apps/web/src/components/CustomSnoozeDialog.tsx): a date and
 * time an hour from now, or two hours from now, and "Snooze" only takes a time in the future.
 * [until] is the answer once it closes with OK.
 */
class CustomSnoozeDialog(project: Project) : DialogWrapper(project) {
  private val initial: LocalDateTime = LocalDateTime.now().plusHours(1)
  private val byDate = JBRadioButton("Date and time", true)
  private val byDuration = JBRadioButton("Duration")
  private val date = JBTextField(initial.format(DateTimeFormatter.ISO_LOCAL_DATE), 10)
  private val time = JBTextField(initial.format(DateTimeFormatter.ofPattern("HH:mm")), 5)
  private val amount = JBTextField("2", 5)
  private val unit = ComboBox(SnoozeUnit.entries.toTypedArray()).apply {
    selectedItem = SnoozeUnit.Hours
    renderer = SimpleListCellRenderer.create("") { it.label }
  }

  var until: Instant? = null
    private set

  init {
    title = "Custom Snooze"
    setOKButtonText("Snooze")
    init()
    // Checked on every edit, so Snooze is only ever pressable with a time to send
    val recheck = object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = initValidation()
    }
    listOf(date, time, amount).forEach { it.document.addDocumentListener(recheck) }
    listOf(byDate, byDuration, unit).forEach { it.addItemListener { initValidation() } }
  }

  override fun createCenterPanel(): JComponent = panel {
    row { text("Choose when snoozed threads return to your inbox.") }
    buttonsGroup {
      row {
        cell(byDate)
        cell(byDuration)
      }
    }
    row("Date:") { cell(date).comment("yyyy-mm-dd") }.visibleIf(byDate.selected)
    row("Time:") { cell(time).comment("hh:mm, 24 hour") }.visibleIf(byDate.selected)
    row("Snooze for:") {
      cell(amount)
      cell(unit)
    }.visibleIf(byDuration.selected)
  }

  override fun doValidate(): ValidationInfo? {
    val input: CustomSnooze = if (byDate.isSelected) {
      CustomSnooze.At(date.text.trim(), time.text.trim())
    } else {
      CustomSnooze.For(amount.text.trim(), unit.item ?: SnoozeUnit.Hours)
    }
    until = resolveCustomSnooze(input, Instant.now())
    if (until != null) return null
    // In the dialog's error row, not a balloon over the fields it would cover
    val error: String = if (byDate.isSelected) {
      "Choose a valid date and time in the future."
    } else {
      "Enter a positive duration."
    }
    return ValidationInfo(error)
  }
}
