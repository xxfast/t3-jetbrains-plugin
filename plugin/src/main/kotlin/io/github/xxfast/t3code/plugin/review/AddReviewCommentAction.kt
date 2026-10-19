package io.github.xxfast.t3code.plugin.review

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import io.github.xxfast.t3code.plugin.ChatController
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.plugin.ui.label
import io.github.xxfast.t3code.plugin.ui.operateBlockReason
import io.github.xxfast.t3code.plugin.ui.selectedLines
import java.util.UUID

const val ADD_REVIEW_COMMENT: String = "T3Code.AddReviewComment"

private const val TITLE: String = "Add T3 Review Comment"

/** Why a diff of excerpts can't be commented on. */
const val EXCERPT_REASON: String = "This diff shows excerpts, not whole files, so its line " +
  "numbers aren't the file's. Open the file to comment on it."

/**
 * Leaves a review comment on the lines selected in a T3 diff (`T3Opener` marks those with
 * [T3_DIFF_SOURCE]). The comment is built the way upstream's `AnnotatableCodeView.tsx` builds one
 * on a diff range, and goes with the thread's next message as a context record. In the diff
 * viewer's popup and toolbar, and on Ctrl+Alt+Shift+C.
 */
class AddReviewCommentAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val source: T3DiffSource? = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(T3_DIFF_SOURCE)
    // Only on T3's diffs
    e.presentation.isVisible = source != null
    val reason: String? = operateBlockReason(T3.get().client.connection.value)
      ?: EXCERPT_REASON.takeIf { source?.excerpt == true }
    e.presentation.isEnabled = source != null && reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val request: DiffRequest = e.getData(DiffDataKeys.DIFF_REQUEST) ?: return
    val source: T3DiffSource =
      request.getUserData(T3_DIFF_SOURCE)?.takeUnless { it.excerpt } ?: return
    val contents: List<DiffContent> = (request as? ContentDiffRequest)?.contents ?: return
    val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return

    // Before is upstream's deletions side, after its additions side
    val index: Int = contents.indexOfFirst { (it as? DocumentContent)?.document == editor.document }
    if (index !in 0..1) {
      Messages.showInfoMessage(project, "Switch to the side-by-side viewer to comment.", TITLE)
      return
    }

    val side: SelectionSide = if (index == 0) SelectionSide.DELETIONS else SelectionSide.ADDITIONS
    val lines: IntRange = selection(editor)
    val where: String =
      if (lines.first == lines.last) "line ${lines.first}"
      else "lines ${lines.first} to ${lines.last}"
    val text: String = Messages.showMultilineInputDialog(
      project,
      "Comment on ${source.path}, $where:",
      TITLE,
      "",
      null,
      null,
    )?.takeIf { it.isNotBlank() } ?: return

    val before: List<String> = lines(contents[0])
    val after: List<String> = lines(contents[1])
    // Comparing two whole files is too slow for the EDT
    val fragments: List<LineFragment> = try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(
        ThrowableComputable<List<LineFragment>, RuntimeException> {
          val indicator: ProgressIndicator =
            ProgressManager.getInstance().progressIndicator ?: DumbProgressIndicator.INSTANCE
          ComparisonManager.getInstance().compareLines(
            before.joinToString("\n"),
            after.joinToString("\n"),
            ComparisonPolicy.DEFAULT,
            indicator,
          )
        },
        "Reading the diff",
        true,
        project,
      )
    } catch (_: ProcessCanceledException) {
      return
    }

    val changes: List<LineChange> = fragments.map { fragment ->
      LineChange(fragment.startLine1, fragment.endLine1, fragment.startLine2, fragment.endLine2)
    }
    val section: ReviewSection = reviewSection(source.runId, source.runOrdinal)
    val comment: ReviewCommentContext? = buildDiffReviewComment(
      id = UUID.randomUUID().toString(),
      sectionId = section.id,
      sectionTitle = section.title,
      filePath = source.path,
      rows = diffReviewLines(before, after, changes),
      range = SelectedLineRange(lines.first, side, lines.last),
      text = text,
    )
    if (comment == null) {
      val outside = "${source.path} $where isn't part of this diff, so the comment wasn't added"
      notify(project, outside, NotificationType.WARNING)
      return
    }

    ChatController.getInstance(project).addContext(source.threadId, comment)
    val added = "Review comment on ${comment.label} goes with the next message"
    notify(project, added, NotificationType.INFORMATION)
  }
}

/** The 1-based lines selected, or the caret's line. */
private fun selection(editor: Editor): IntRange {
  val document: Document = editor.document
  val selection: SelectionModel = editor.selectionModel
  if (!selection.hasSelection()) {
    val line: Int = document.getLineNumber(editor.caretModel.offset) + 1
    return line..line
  }

  // selectedLines takes 0-based lines and answers 1-based ones
  val endLine: Int = document.getLineNumber(selection.selectionEnd)
  val endsAtLineStart: Boolean = selection.selectionEnd == document.getLineStartOffset(endLine)
  return selectedLines(document.getLineNumber(selection.selectionStart), endLine, endsAtLineStart)
}

/** A side's lines; a missing side (added or deleted file) has none. */
private fun lines(content: DiffContent): List<String> {
  val text: String = (content as? DocumentContent)?.document?.text ?: return emptyList()
  return if (text.isEmpty()) emptyList() else text.split("\n")
}
