package io.github.xxfast.t3code.plugin.settings

import com.intellij.util.xmlb.XmlSerializer
import io.github.xxfast.t3code.plugin.ui.PersistedComposerDraft
import io.github.xxfast.t3code.plugin.ui.PromptStashEntry
import io.github.xxfast.t3code.plugin.ui.composerDraftKey
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jdom.Element

class ComposerDraftsTest {
  private val key: String = composerDraftKey("http://127.0.0.1:3773", "t1")
  private val draft = PersistedComposerDraft(prompt = "half <written> & \"quoted\"\nprompt")
  private val entry = PromptStashEntry(id = "e1", createdAt = 7, server = "s", prompt = "later")
  private val pick =
    ModelSelection(instanceId = JsonPrimitive("claude"), model = JsonPrimitive("opus"))

  @Test
  fun draftsAndTheStashSurviveARestart() {
    val before = ComposerDrafts()
    before.put(key, draft)
    before.putStash(listOf(entry))
    before.rememberModel(pick)

    // What the IDE writes to t3code-drafts.xml on exit, and reads back on start
    val xml: Element = XmlSerializer.serialize(before.state)
    val after = ComposerDrafts()
    after.loadState(XmlSerializer.deserialize(xml, ComposerDrafts.State::class.java))

    assertEquals(draft, after.draft(key))
    assertEquals(listOf(entry), after.stash.value)
    assertEquals(pick, after.stickyModel().selection)
    assertNull(after.draft(composerDraftKey("http://10.0.0.2:3773", "t1")))
  }

  @Test
  fun anEmptiedDraftIsForgotten() {
    val drafts = ComposerDrafts()
    drafts.put(key, draft)
    drafts.put(key, null)
    assertNull(drafts.draft(key))
    assertEquals(emptyMap(), drafts.state.drafts)
    drafts.putStash(listOf(entry))
    drafts.putStash(emptyList())
    assertNull(drafts.state.stash)
  }
}
