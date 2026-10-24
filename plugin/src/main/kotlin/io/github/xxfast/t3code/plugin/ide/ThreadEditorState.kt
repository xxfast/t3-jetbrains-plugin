package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import io.github.xxfast.t3code.plugin.ui.TimelineExpansion
import io.github.xxfast.t3code.plugin.ui.TimelineMemory
import org.jdom.Element

/**
 * A thread tab's [TimelineMemory] as the IDE keeps editor state: in its navigation history and
 * the workspace, through [ThreadFileEditorProvider.writeState] and `readState`, the way a text
 * editor's caret and scroll come back.
 */
data class ThreadEditorState(
  val index: Int = 0,
  val offset: Int = 0,
  val atEnd: Boolean = true,
  val runIds: Set<String> = emptySet(),
  val workGroupIds: Set<String> = emptySet(),
  val open: Set<String> = emptySet(),
) : FileEditorState {
  override fun canBeMergedWith(other: FileEditorState, level: FileEditorStateLevel): Boolean =
    other is ThreadEditorState

  fun applyTo(memory: TimelineMemory) {
    memory.index = index
    memory.offset = offset
    memory.atEnd = atEnd
    memory.expansion = TimelineExpansion(runIds = runIds, workGroupIds = workGroupIds)
    memory.open.clear()
    open.forEach { id -> memory.open[id] = true }
  }

  fun write(element: Element) {
    element.setAttribute(INDEX, index.toString())
    element.setAttribute(OFFSET, offset.toString())
    element.setAttribute(AT_END, atEnd.toString())
    element.setAttribute(RUNS, runIds.joinToString(SEPARATOR))
    element.setAttribute(GROUPS, workGroupIds.joinToString(SEPARATOR))
    element.setAttribute(OPEN, open.joinToString(SEPARATOR))
  }

  companion object {
    private const val INDEX: String = "index"
    private const val OFFSET: String = "offset"
    private const val AT_END: String = "atEnd"
    private const val RUNS: String = "runs"
    private const val GROUPS: String = "groups"
    private const val OPEN: String = "open"
    private const val SEPARATOR: String = " "

    fun of(memory: TimelineMemory): ThreadEditorState = ThreadEditorState(
      index = memory.index,
      offset = memory.offset,
      atEnd = memory.atEnd,
      runIds = memory.expansion.runIds,
      workGroupIds = memory.expansion.workGroupIds,
      open = memory.openIds(),
    )

    /** A missing or garbled attribute falls back to its default, so an old workspace opens. */
    fun read(element: Element): ThreadEditorState {
      fun ids(name: String): Set<String> = element.getAttributeValue(name).orEmpty()
        .split(SEPARATOR)
        .filter { it.isNotEmpty() }
        .toSet()
      return ThreadEditorState(
        index = element.getAttributeValue(INDEX)?.toIntOrNull() ?: 0,
        offset = element.getAttributeValue(OFFSET)?.toIntOrNull() ?: 0,
        atEnd = element.getAttributeValue(AT_END)?.toBooleanStrictOrNull() ?: true,
        runIds = ids(RUNS),
        workGroupIds = ids(GROUPS),
        open = ids(OPEN),
      )
    }
  }
}
