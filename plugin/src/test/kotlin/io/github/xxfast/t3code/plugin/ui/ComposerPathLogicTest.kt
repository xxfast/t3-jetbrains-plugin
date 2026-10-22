package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ComposerPathLogicTest {
  // serializeComposerFileLink, mirroring packages/shared/src/composerTrigger.test.ts:46

  @Test
  fun `encodes markdown-sensitive destination characters`() {
    assertEquals(
      "[My File (draft).md](docs/My%20File%20%28draft%29.md)",
      serializeComposerFileLink("docs/My File (draft).md"),
    )
  }

  @Test
  fun `supports windows paths`() {
    assertEquals(
      "[index.ts](C:%5Crepo%5Csrc%5Cindex.ts)",
      serializeComposerFileLink("C:\\repo\\src\\index.ts"),
    )
  }

  @Test
  fun `preserves paths that legitimately start with an at sign`() {
    assertEquals(
      "[package.json](@scope/package.json)",
      serializeComposerFileLink("@scope/package.json"),
    )
  }

  @Test
  fun `escapes the label and encodes hashes, queries and non-ascii`() {
    assertEquals(
      "[notes \\[1\\] #2?.md](a/notes%20%5B1%5D%20%232%3F.md)",
      serializeComposerFileLink("a/notes [1] #2?.md"),
    )
    assertEquals("[café.md](docs/caf%C3%A9.md)", serializeComposerFileLink("docs/café.md"))
  }

  @Test
  fun `a path row shows its basename and parent directory`() {
    assertEquals("KStore.kt", basenameOfPath("kstore/src/KStore.kt"))
    assertEquals("kstore/src", pathEntryDescription("kstore/src/KStore.kt"))
    assertEquals("README.md", basenameOfPath("README.md"))
    assertEquals("", pathEntryDescription("README.md"))
  }

  // isPathSuggestionCurrent, apps/web/src/composer-logic.ts:31

  @Test
  fun `a path suggestion is current only for the same query, server and cwd`() {
    val target = ComposerPathSearchTarget("env", "/repo", "kst")
    val trigger = ComposerTrigger(ComposerTriggerKind.PATH, "kst ", 0, 5)
    assertTrue(isPathSuggestionCurrent(target, trigger, "env", "/repo"))
    assertFalse(isPathSuggestionCurrent(target, trigger.copy(query = "ks"), "env", "/repo"))
    assertFalse(isPathSuggestionCurrent(target, trigger, "other", "/repo"))
    assertFalse(isPathSuggestionCurrent(target, trigger, "env", "/elsewhere"))
    val skill: ComposerTrigger = trigger.copy(kind = ComposerTriggerKind.SKILL)
    assertFalse(isPathSuggestionCurrent(target, skill, "env", "/repo"))
    assertFalse(isPathSuggestionCurrent(target, null, "env", "/repo"))
  }

  // matchComposerThreadItems, packages/client-runtime/src/composerThreadItems.ts:25

  private fun shell(
    id: String,
    title: String,
    updatedAt: String,
    environmentId: String = "env",
    archivedAt: String? = null,
  ) = ComposerThreadCandidate(environmentId, id, title, updatedAt, archivedAt)

  @Test
  fun `a bare query matches no threads`() {
    val shells = listOf(shell("a", "Release", "2026-10-01T00:00:00Z"))
    assertEquals(emptyList(), matchComposerThreadItems(shells, "env", null, "  "))
  }

  @Test
  fun `threads match by title on the same server, newest first, at most five`() {
    val shells = listOf(
      shell("old", "Release 0.1", "2026-10-01T00:00:00Z"),
      shell("new", "release 0.2", "2026-10-05T00:00:00Z"),
      shell("self", "Release 0.3", "2026-10-06T00:00:00Z"),
      shell("away", "Release 0.4", "2026-10-07T00:00:00Z", environmentId = "other"),
      shell("gone", "Release 0.5", "2026-10-08T00:00:00Z", archivedAt = "2026-10-09T00:00:00Z"),
      shell("miss", "Benchmark", "2026-10-09T00:00:00Z"),
    ) + (1..6).map { shell("r$it", "Release candidate $it", "2026-09-0${it}T00:00:00Z") }
    val items: List<ComposerThreadItem> =
      matchComposerThreadItems(shells, "env", "self", " RELEASE ")
    assertEquals(listOf("new", "old", "r6", "r5", "r4"), items.map { it.threadId })
    assertEquals("thread:env:new", items.first().id)
    assertEquals("Thread", items.first().description)
    assertEquals("release 0.2", items.first().label)
  }

  // threadContextRecord, apps/web/src/lib/composerContextRecords.ts:161

  @Test
  fun `a thread record is kind scoped and its label sanitised`() {
    val record: ThreadContextRecord = threadContextRecord("env", "thr_1", "Fix [the] bug\n")
    assertEquals("thread_thr_1", record.contextId)
    assertEquals("Fix the bug", record.label)
    assertEquals(record.label, record.title)
    assertEquals(
      "[Fix the bug](t3-context://v1/thread/thread_thr_1)",
      threadContextReference(record),
    )
    val json: JsonObject = record.toRecord()
    assertEquals(JsonPrimitive("thread"), json["kind"])
    assertEquals(JsonPrimitive("env"), json["environmentId"])
    assertEquals(JsonPrimitive("thr_1"), json["threadId"])
    assertEquals(JsonPrimitive(1), json["version"])
  }

  @Test
  fun `a thread record goes out only while the text cites it`() {
    val record: ThreadContextRecord = threadContextRecord("env", "thr_1", "Release")
    val extras: DraftExtras = DraftExtras().withThread(record).withThread(record)
    assertEquals(1, extras.threads.size)
    assertEquals(1, extras.records("see ${threadContextReference(record)} please").size)
    assertEquals(0, extras.records("see nothing").size)
  }
}
