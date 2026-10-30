package io.github.xxfast.t3code.plugin.ide

import io.github.xxfast.t3code.protocol.generated.FileChangeDetail
import io.github.xxfast.t3code.protocol.generated.TurnItem
import kotlinx.serialization.json.JsonPrimitive

// Mirrors apps/web/src/lib/toolCallDiff.ts. Upstream renders the result in its diff panel; the
// plugin hands it to the IDE diff viewer instead.

const val RECORDED_CONTENT_BEFORE: String = "Recorded content before"
const val RECORDED_CONTENT_AFTER: String = "Recorded content after"

private const val TRANSPORT_TRUNCATION_SUFFIX: String = "\n… output truncated for transport"

/** One side of what a provider recorded without a diff, by its upstream label. */
data class RecordedContent(val label: String, val text: String)

/**
 * What a `file_change` shows: a unified [patch], or the [content] it recorded when it has no
 * diff. [truncated] when the server cut any of it for transport.
 */
data class ToolCallDiff(
  val patch: String?,
  val content: List<RecordedContent>,
  val truncated: Boolean,
)

/**
 * [diffStr] as a whole patch. Codex can record added or deleted file contents or bare hunks
 * instead, and those get a `diff --git` header from the first of [changes].
 */
fun toolCallPatch(
  diffStr: String?,
  fileName: String,
  changes: List<FileChangeDetail>? = null,
): String? {
  var patch: String = diffStr ?: return null
  if (patch.isEmpty()) return patch
  val change: FileChangeDetail? = changes?.firstOrNull()
  val rawFileOperation: Boolean = change?.operation == "add" || change?.operation == "delete"
  if (patch.startsWith("diff --git ") || patch.startsWith("--- ")) {
    if (!rawFileOperation) return patch
    val files: List<FilePatch> = parsePatch(patch.trim())
    val complete: Boolean = files.isNotEmpty() && files.all { file ->
      val path: String = resolveFileDiffPath(file).replace('\\', '/')
      file.hunks.isNotEmpty() && changes.orEmpty().any { recorded ->
        val recordedPath: String = recorded.path.replace('\\', '/')
        val fits: Boolean = when (recorded.operation) {
          "add" -> file.deletionLines().isEmpty()
          "delete" -> file.additionLines().isEmpty()
          else -> true
        }
        fits && (recordedPath == path || recordedPath.endsWith("/$path"))
      }
    }
    if (complete) return patch
  }
  // Codex can record added/deleted file contents or bare hunks instead of a complete patch.
  if (rawFileOperation || !patch.trimStart().startsWith("@@ ")) {
    if (!rawFileOperation) return patch
    val lines: MutableList<String> = patch.split("\n").toMutableList()
    val hasFinalNewline: Boolean = patch.endsWith("\n")
    if (hasFinalNewline) lines.removeAt(lines.lastIndex)
    val added: Boolean = change?.operation == "add"
    val sign: String = if (added) "+" else "-"
    val range = "1,${lines.size}"
    val header = "@@ -${if (added) "0,0" else range} +${if (added) range else "0,0"} @@"
    val body: String = lines.joinToString("\n") { line -> "$sign$line" }
    val ending: String = if (hasFinalNewline) "" else "\\ No newline at end of file\n"
    patch = "$header\n$body\n$ending"
  }
  val oldPath: String = change?.oldPath ?: fileName
  val newPath: String = change?.path ?: fileName
  val before: String = if (change?.operation == "add") "/dev/null" else quoted("a/$oldPath")
  val after: String = if (change?.operation == "delete") "/dev/null" else quoted("b/$newPath")
  return "diff --git ${quoted("a/$oldPath")} ${quoted("b/$newPath")}\n" +
    "--- $before\n+++ $after\n${patch.trimStart()}"
}

private data class RecordedText(val text: String?, val truncated: Boolean)

private fun recordedText(value: String?): RecordedText {
  val truncated: Boolean = value != null && value.endsWith(TRANSPORT_TRUNCATION_SUFFIX)
  val text: String? = if (truncated) value?.removeSuffix(TRANSPORT_TRUNCATION_SUFFIX) else value
  return RecordedText(text, truncated)
}

fun toolCallDiff(
  fileName: String,
  diffStr: String? = null,
  oldStr: String? = null,
  newStr: String? = null,
  changes: List<FileChangeDetail>? = null,
): ToolCallDiff {
  val diff: RecordedText = recordedText(diffStr)
  val operation: String? = changes?.firstOrNull()?.operation
  val emptyContent: String? = if (diff.text == "") "" else null
  val before: RecordedText =
    recordedText(oldStr ?: if (operation == "delete") emptyContent else null)
  val after: RecordedText = recordedText(newStr ?: if (operation == "add") emptyContent else null)
  val content: List<RecordedContent> = if (!diff.text?.trim().isNullOrEmpty()) {
    emptyList()
  } else {
    listOfNotNull(
      before.text?.let { text -> RecordedContent(RECORDED_CONTENT_BEFORE, text) },
      after.text?.let { text -> RecordedContent(RECORDED_CONTENT_AFTER, text) },
    )
  }
  return ToolCallDiff(
    patch = toolCallPatch(diff.text, fileName, changes),
    content = content,
    truncated = diff.truncated || before.truncated || after.truncated,
  )
}

fun TurnItem.FileChange.toolCallDiff(): ToolCallDiff =
  toolCallDiff(fileName, diffStr, oldStr, newStr, changes)

/** `resolveFileDiffPath` in `apps/web/src/lib/diffRendering.ts`: the new name, else the old. */
fun resolveFileDiffPath(file: FilePatch): String = file.newPath ?: file.oldPath ?: ""

// Pierre's `additionLines` and `deletionLines`: every hunk's new and old side, context included
private fun FilePatch.additionLines(): List<String> = hunks.flatMap { hunk -> hunk.newLines }

private fun FilePatch.deletionLines(): List<String> = hunks.flatMap { hunk -> hunk.oldLines }

/** `JSON.stringify` of a string, how upstream quotes the paths it writes into a header. */
private fun quoted(path: String): String = JsonPrimitive(path).toString()
