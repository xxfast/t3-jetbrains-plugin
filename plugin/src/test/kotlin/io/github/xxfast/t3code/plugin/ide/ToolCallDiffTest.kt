package io.github.xxfast.t3code.plugin.ide

import io.github.xxfast.t3code.protocol.generated.FileChangeDetail
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// Ports apps/web/src/lib/toolCallDiff.test.ts, with parsePatch standing in for getRenderablePatch
class ToolCallDiffTest {
  @Test
  fun `a bare hunk gets a header, renamed paths included`() {
    for (changes in listOf(null, listOf(change("update", "new.ts", oldPath = "old.ts")))) {
      val diffStr = "@@ -1 +1 @@\n-before\n+after\n"
      val patch: String? =
        toolCallDiff(fileName = "old.ts", diffStr = diffStr, changes = changes).patch

      val file: FilePatch = parsePatch(patch!!.trim()).single()
      assertEquals(if (changes != null) "new.ts" else "old.ts", resolveFileDiffPath(file))
      assertEquals(listOf("after"), file.hunks.flatMap { it.newLines })
      assertEquals(listOf("before"), file.hunks.flatMap { it.oldLines })
    }
  }

  @Test
  fun `added and deleted file contents become a file diff`() {
    val contents: List<Pair<String, String>> = listOf(
      "first\nsecond" to "",
      "@@ -0,0 +1 @@\n+content" to "\n",
      "@@ -1 +0,0 @@\n-content" to "\n",
      "first\nsecond" to "\n",
      "--- notes\nsecond" to "\n",
      "diff --git notes\nsecond" to "\n",
      "--- a/embedded.ts\n+++ b/embedded.ts\n@@ -1 +1 @@\n-before\n+after" to "\n",
      "diff --git a/embedded.ts b/embedded.ts\n--- a/embedded.ts\n+++ b/embedded.ts\n" +
        "@@ -1 +1 @@\n-before\n+after" to "\n",
    )
    for (operation in listOf("add", "delete")) {
      for ((content, ending) in contents) {
        val recorded = "$content$ending"
        val patch: String? = toolCallDiff(
          fileName = "file with spaces.ts",
          diffStr = recorded,
          changes = listOf(change(operation, "file with spaces.ts")),
        ).patch

        val file: FilePatch = parsePatch(patch!!.trim()).single()
        assertEquals("file with spaces.ts", resolveFileDiffPath(file))
        assertEquals(operation == "add", file.added)
        assertEquals(operation == "delete", file.deleted)
        val lines: Int = content.split("\n").size
        val additions: List<String> = file.hunks.flatMap { it.newLines }
        val deletions: List<String> = file.hunks.flatMap { it.oldLines }
        assertEquals(if (operation == "add") lines else 0, additions.size)
        assertEquals(if (operation == "delete") lines else 0, deletions.size)
        val written: List<String> = if (operation == "add") additions else deletions
        assertEquals(recorded.removeSuffix("\n"), written.joinToString("\n"))
      }
    }
  }

  @Test
  fun `content-only writes stay out of the patch, empty content included`() {
    for (operation in listOf("add", "delete")) {
      val label: String =
        if (operation == "add") RECORDED_CONTENT_AFTER else RECORDED_CONTENT_BEFORE
      assertEquals(
        listOf(RecordedContent(label, "")),
        toolCallDiff(
          fileName = "empty.ts",
          diffStr = "",
          changes = listOf(change(operation, "empty.ts")),
        ).content,
      )
    }
    assertEquals(
      ToolCallDiff(
        patch = null,
        content = listOf(
          RecordedContent(RECORDED_CONTENT_BEFORE, "before"),
          RecordedContent(RECORDED_CONTENT_AFTER, ""),
        ),
        truncated = false,
      ),
      toolCallDiff(fileName = "a.ts", oldStr = "before", newStr = ""),
    )
    assertEquals(
      listOf(RecordedContent(RECORDED_CONTENT_AFTER, "after")),
      toolCallDiff(fileName = "a.ts", newStr = "after").content,
    )
  }

  @Test
  fun `the transport truncation marker is cut and noted`() {
    val suffix = "\n… output truncated for transport"
    val diff: ToolCallDiff = toolCallDiff(
      fileName = "a.ts",
      diffStr = "first\nsecond$suffix",
      changes = listOf(change("add", "a.ts")),
    )

    assertTrue(diff.truncated)
    assertFalse(diff.patch!!.contains("output truncated"))
    assertEquals(1, parsePatch(diff.patch!!.trim()).size)
    val content: ToolCallDiff = toolCallDiff(fileName = "a.ts", newStr = "content$suffix")
    assertEquals(listOf(RecordedContent(RECORDED_CONTENT_AFTER, "content")), content.content)
    assertTrue(content.truncated)
  }

  @Test
  fun `whole patches and unsupported provider output pass through`() {
    for (operation in listOf("add", "delete")) {
      val diffStr: String = listOf(
        "diff --git a/a.ts b/a.ts",
        "--- ${if (operation == "add") "/dev/null" else "a/a.ts"}",
        "+++ ${if (operation == "add") "b/a.ts" else "/dev/null"}",
        if (operation == "add") "@@ -0,0 +1 @@" else "@@ -1 +0,0 @@",
        if (operation == "add") "+content" else "-content",
        "",
      ).joinToString("\n")
      val changes: List<FileChangeDetail> = listOf(change(operation, "a.ts"))
      val patch: String? =
        toolCallDiff(fileName = "a.ts", diffStr = diffStr, changes = changes).patch
      assertEquals(diffStr, patch)
    }
    val unsupported: List<String?> =
      listOf(null, "", "provider output", "--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-a\n+b\n")
    for (diffStr in unsupported) {
      assertEquals(diffStr, toolCallDiff(fileName = "a.ts", diffStr = diffStr).patch)
    }
  }

  @Test
  fun `a raw add is written with quoted paths and no trailing newline marker`() {
    assertEquals(
      "diff --git \"a/a b.ts\" \"b/a b.ts\"\n--- /dev/null\n+++ \"b/a b.ts\"\n" +
        "@@ -0,0 +1,2 @@\n+one\n+two\n\\ No newline at end of file\n",
      toolCallPatch("one\ntwo", "a b.ts", listOf(change("add", "a b.ts"))),
    )
  }

  @Test
  fun `quoted paths keep characters outside the basic plane`() {
    val emoji = "\uD83D\uDE00.ts"
    val patch: String? = toolCallPatch("one\n", emoji, listOf(change("add", emoji)))
    assertEquals("\uD83D\uDE00.ts", resolveFileDiffPath(parsePatch(patch!!).single()))
    val escaped = "diff --git \"a/\\ud83d\\ude00.ts\" \"b/\\ud83d\\ude00.ts\"\n@@ -0,0 +1 @@\n+x\n"
    assertEquals("\uD83D\uDE00.ts", resolveFileDiffPath(parsePatch(escaped).single()))
    val octal = "diff --git \"a/\\303\\251.ts\" \"b/\\303\\251.ts\"\n@@ -0,0 +1 @@\n+x\n"
    assertEquals("\u00E9.ts", resolveFileDiffPath(parsePatch(octal).single()))
  }

  private fun change(operation: String, path: String, oldPath: String? = null): FileChangeDetail =
    FileChangeDetail(operation = operation, path = path, oldPath = oldPath)
}
