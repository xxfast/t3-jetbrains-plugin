package io.github.xxfast.t3code.plugin.ide

import io.github.xxfast.t3code.plugin.ui.TimelineExpansion
import io.github.xxfast.t3code.plugin.ui.TimelineMemory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jdom.Element
import org.junit.jupiter.api.Test

class ThreadEditorStateTest {
  @Test
  fun `round trips through the workspace`() {
    val state = ThreadEditorState(
      index = 12,
      offset = 340,
      atEnd = false,
      runIds = setOf("run-1", "run-2"),
      workGroupIds = setOf("group-1"),
      open = setOf("item-9"),
    )
    val element = Element("state")
    state.write(element)
    assertEquals(state, ThreadEditorState.read(element))
  }

  @Test
  fun `an empty or garbled element opens at the end`() {
    assertEquals(ThreadEditorState(), ThreadEditorState.read(Element("state")))
    val garbled = Element("state").setAttribute("index", "x").setAttribute("atEnd", "maybe")
    assertEquals(ThreadEditorState(), ThreadEditorState.read(garbled))
  }

  @Test
  fun `applies to a tab's memory and back`() {
    val memory = TimelineMemory()
    memory.open["stale"] = true
    val state = ThreadEditorState(index = 3, offset = 40, atEnd = false, runIds = setOf("r"))
    state.applyTo(memory)
    assertEquals(3, memory.index)
    assertEquals(40, memory.offset)
    assertFalse(memory.atEnd)
    assertEquals(TimelineExpansion(runIds = setOf("r")), memory.expansion)
    assertEquals(emptySet(), memory.openIds())
    assertEquals(state, ThreadEditorState.of(memory))
  }

  @Test
  fun `following counts as at the end, as rememberTimelinePosition has it`() {
    val memory = TimelineMemory()
    memory.record(index = 5, offset = 0, following = true, atEnd = false)
    assertTrue(memory.atEnd)
    memory.record(index = 5, offset = 0, following = false, atEnd = false)
    assertFalse(memory.atEnd)
  }
}
