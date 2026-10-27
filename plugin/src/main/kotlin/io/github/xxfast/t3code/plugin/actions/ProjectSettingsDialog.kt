package io.github.xxfast.t3code.plugin.actions

import androidx.compose.ui.graphics.toArgb
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueIs
import com.intellij.ui.layout.selectedValueMatches
import com.intellij.util.ui.ColorIcon
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ide.launchReporting
import io.github.xxfast.t3code.plugin.ui.NEEDS_OPERATE
import io.github.xxfast.t3code.plugin.ui.PROJECT_ICON_COLORS
import io.github.xxfast.t3code.plugin.ui.PROJECT_READ_ONLY
import io.github.xxfast.t3code.plugin.ui.PROJECT_TITLE_EMPTY
import io.github.xxfast.t3code.plugin.ui.ProjectIconDraft
import io.github.xxfast.t3code.plugin.ui.ProjectIconKind
import io.github.xxfast.t3code.plugin.ui.projectIconColor
import io.github.xxfast.t3code.plugin.ui.projectIconDescription
import io.github.xxfast.t3code.plugin.ui.projectIconDraft
import io.github.xxfast.t3code.plugin.ui.projectIconError
import io.github.xxfast.t3code.plugin.ui.projectIconOverride
import io.github.xxfast.t3code.plugin.ui.projectSettingsUpdate
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project as T3Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.ProjectMutation
import io.github.xxfast.t3code.protocol.has
import java.awt.Color
import java.util.UUID
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * `openProjectSettings` (Sidebar.tsx): upstream routes to `/projects/$projectKey`; the plugin
 * opens [ProjectSettingsDialog] on [projectId] and sends what changed over `projects.mutate`.
 */
fun openProjectSettings(project: Project, projectId: String) {
  val client: T3Client = T3.get().client
  val target: T3Project = client.projects.value.find { it.id == projectId } ?: return
  val dialog = ProjectSettingsDialog(project, target, saveBlockedBy(client.connection.value))
  if (!dialog.showAndGet()) return
  val update: ProjectMutation.ProjectUpdate = dialog.update ?: return
  ThreadActionScope.getInstance(project).scope
    .launchReporting(project, "Failed to update project") { client.mutateProject(update) }
}

/** Why the dialog can't save over [connection], or null: upstream's `canEditGroup`. */
private fun saveBlockedBy(connection: ConnectionState): String? = when {
  connection !is ConnectionState.Connected -> NOT_CONNECTED
  !connection.session.has(OPERATE) -> NEEDS_OPERATE
  else -> null
}

/**
 * Upstream's Project section (apps/web/src/components/settings/ProjectSettingsPanel.tsx): the
 * name, and the project icon as its `ProjectIconPickerDialog` picks one. Icon type, a colour for
 * icons and monograms, and the icon's name, emoji or letters. A pairing that can't operate sees
 * the values, greyed, with upstream's note, and Save stays off. [update] is the answer.
 */
class ProjectSettingsDialog(
  project: Project,
  private val target: T3Project,
  private val blocked: String?,
) : DialogWrapper(project) {
  private val draft: ProjectIconDraft = projectIconDraft(target)
  private val name = JBTextField(target.title, 24)
  private val kind = ComboBox(ProjectIconKind.entries.toTypedArray()).apply {
    selectedItem = draft.kind
    renderer = SimpleListCellRenderer.create("") { it.label }
  }
  private val color = ComboBox(PROJECT_ICON_COLORS.toTypedArray()).apply {
    selectedItem = draft.color
    renderer = SimpleListCellRenderer.create { label, value, _ ->
      label.text = value
      label.icon = ColorIcon(12, swatch(value))
    }
  }
  private val lucide = JBTextField(draft.lucide, 16)
  private val emoji = JBTextField(draft.emoji, 6)
  private val letters = JBTextField(draft.letters, 6)

  // Upstream commits a field once it's touched; untouched ones send nothing
  private var titleEdited: Boolean = false
  private var iconEdited: Boolean = false

  var update: ProjectMutation.ProjectUpdate? = null
    private set

  init {
    title = "Project Settings"
    setOKButtonText("Save")
    init()
    if (blocked != null) {
      listOf(name, kind, color, lucide, emoji, letters).forEach { it.isEnabled = false }
      isOKActionEnabled = false
      getButton(okAction)?.toolTipText = blocked
    } else {
      name.document.addDocumentListener(edits { titleEdited = true })
      listOf(lucide, emoji, letters)
        .forEach { it.document.addDocumentListener(edits { iconEdited = true }) }
      listOf(kind, color).forEach { combo ->
        combo.addItemListener {
          iconEdited = true
          initValidation()
        }
      }
    }
  }

  private fun edits(edited: () -> Unit) = object : DocumentAdapter() {
    override fun textChanged(e: DocumentEvent) {
      edited()
      initValidation()
    }
  }

  override fun createCenterPanel(): JComponent = panel {
    if (blocked != null) row { comment(PROJECT_READ_ONLY) }
    row("Name:") {
      cell(name).align(AlignX.FILL)
        .comment("The shared name for this project group in the sidebar and thread lists.")
    }
    group("Project Icon") {
      row { comment(projectIconDescription(target)) }
      row("Icon type:") { cell(kind) }
      row("Color:") { cell(color) }
        .visibleIf(kind.selectedValueMatches { it != ProjectIconKind.Emoji })
      row("Icon:") { cell(lucide).comment("A Lucide icon name, like folder-code") }
        .visibleIf(kind.selectedValueIs(ProjectIconKind.Lucide))
      row("Emoji:") { cell(emoji).comment("Paste an emoji") }
        .visibleIf(kind.selectedValueIs(ProjectIconKind.Emoji))
      row("Letters:") { cell(letters).comment("One or two letters or numbers.") }
        .visibleIf(kind.selectedValueIs(ProjectIconKind.Monogram))
    }
  }

  private fun picked(): ProjectIconDraft = draft.copy(
    kind = kind.item ?: draft.kind,
    lucide = lucide.text,
    emoji = emoji.text,
    letters = letters.text,
    color = color.item ?: draft.color,
  )

  override fun doValidate(): ValidationInfo? {
    if (blocked != null) return null
    if (name.text.isBlank()) return ValidationInfo(PROJECT_TITLE_EMPTY, name)
    if (!iconEdited) return null
    val error: String = projectIconError(picked()) ?: return null
    val field: JComponent = when (kind.item) {
      ProjectIconKind.Emoji -> emoji
      ProjectIconKind.Monogram -> letters
      else -> lucide
    }
    return ValidationInfo(error, field)
  }

  override fun doOKAction() {
    if (blocked != null) return
    update = projectSettingsUpdate(
      project = target,
      typedTitle = name.text,
      titleEdited = titleEdited,
      icon = if (iconEdited) projectIconOverride(picked()) else null,
      commandId = UUID.randomUUID().toString(),
    )
    super.doOKAction()
  }
}

/** The colour's swatch in the IDE's current theme, as the Sidebar draws it. */
private fun swatch(name: String): Color =
  Color(projectIconColor(name, dark = !JBColor.isBright()).toArgb())
