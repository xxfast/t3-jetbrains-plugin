package io.github.xxfast.t3code.plugin.review

import io.github.xxfast.t3code.plugin.fixtures.Scenario
import io.github.xxfast.t3code.plugin.ui.ReviewCommentContext
import io.github.xxfast.t3code.protocol.Checkpoint
import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.ModelSelection
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ReviewCommentLogicTest {
  // before: a b c d e      after: a B c d x e
  // line 2 changed (b -> B), x inserted before e
  private val before: List<String> = listOf("a", "b", "c", "d", "e")
  private val after: List<String> = listOf("a", "B", "c", "d", "x", "e")
  private val changes: List<LineChange> = listOf(LineChange(1, 2, 1, 2), LineChange(4, 4, 4, 5))
  private val rows: List<DiffReviewLine> = diffReviewLines(before, after, changes)

  @Test
  fun `rows are the whole file, removals before additions`() {
    assertEquals(
      listOf(
        "  1 1 a",
        "- 2 _ b",
        "+ _ 2 B",
        "  3 3 c",
        "  4 4 d",
        "+ _ 5 x",
        "  5 6 e",
      ),
      rows.map { row ->
        val marker: String = when (row.change) {
          Change.ADD -> "+"
          Change.DELETE -> "-"
          Change.CONTEXT -> " "
        }
        "$marker ${row.oldLineNumber ?: "_"} ${row.newLineNumber ?: "_"} ${row.content}"
      },
    )
  }

  @Test
  fun `an added or deleted file is all additions or all removals`() {
    val added: List<DiffReviewLine> =
      diffReviewLines(emptyList(), listOf("x", "y"), listOf(LineChange(0, 1, 0, 2)))
    assertEquals(listOf(Change.ADD, Change.ADD), added.map { it.change })
    val deleted: List<DiffReviewLine> =
      diffReviewLines(listOf("x"), emptyList(), listOf(LineChange(0, 1, 0, 0)))
    assertEquals(listOf(Change.DELETE), deleted.map { it.change })
  }

  @Test
  fun `a line is found on its side, else on the other`() {
    assertEquals(1, findDiffReviewLineIndex(rows, 2, SelectionSide.DELETIONS))
    assertEquals(2, findDiffReviewLineIndex(rows, 2, SelectionSide.ADDITIONS))
    assertEquals(5, findDiffReviewLineIndex(rows, 5, SelectionSide.ADDITIONS))
    assertEquals(6, findDiffReviewLineIndex(rows, 5, SelectionSide.DELETIONS), "Old 5 is context")
    assertEquals(6, findDiffReviewLineIndex(rows, 6, SelectionSide.DELETIONS), "Only new has 6")
    assertEquals(-1, findDiffReviewLineIndex(rows, 9, SelectionSide.ADDITIONS))
  }

  @Test
  fun `a comment on added lines is zero-based rows with a plus label and a diff fence`() {
    val comment: ReviewCommentContext = assertNotNull(
      buildDiffReviewComment(
        id = "c1",
        sectionId = "turn:r1",
        sectionTitle = "Turn 1",
        filePath = "src/A.kt",
        rows = rows,
        range = SelectedLineRange(5, SelectionSide.ADDITIONS, 5),
        text = "  why x?  ",
      ),
    )
    assertEquals(5, comment.startIndex)
    assertEquals(5, comment.endIndex)
    assertEquals("+5", comment.rangeLabel)
    assertEquals("why x?", comment.text)
    assertEquals("@@ -0,0 +5,1 @@\n+x", comment.diff)
    assertEquals("diff", comment.fenceLanguage)
    assertEquals("turn:r1", comment.sectionId)
    assertEquals("Turn 1", comment.sectionTitle)
  }

  @Test
  fun `a mixed range has no marker and carries every selected row`() {
    val comment: ReviewCommentContext = assertNotNull(
      buildDiffReviewComment(
        id = "c1",
        sectionId = "s",
        sectionTitle = "S",
        filePath = "A.kt",
        rows = rows,
        range = SelectedLineRange(3, SelectionSide.ADDITIONS, 1, SelectionSide.ADDITIONS),
        text = "t",
      ),
    )
    assertEquals(0, comment.startIndex, "Swapped so start comes first")
    assertEquals(3, comment.endIndex)
    assertEquals("1 to 3", comment.rangeLabel)
    assertEquals("@@ -1,3 +1,3 @@\n a\n-b\n+B\n c", comment.diff)
  }

  @Test
  fun `removed lines label with a minus`() {
    val deleted: List<DiffReviewLine> =
      diffReviewLines(listOf("a", "b", "c"), listOf("a"), listOf(LineChange(1, 3, 1, 1)))
    val comment: ReviewCommentContext = assertNotNull(
      buildDiffReviewComment(
        id = "c1",
        sectionId = "s",
        sectionTitle = "S",
        filePath = "A.kt",
        rows = deleted,
        range = SelectedLineRange(2, SelectionSide.DELETIONS, 3),
        text = "t",
      ),
    )
    assertEquals("-2 to -3", comment.rangeLabel)
    assertEquals("@@ -2,2 +0,0 @@\n-b\n-c", comment.diff)
  }

  @Test
  fun `a line the rows don't have makes no comment`() {
    assertNull(
      buildDiffReviewComment(
        id = "c1",
        sectionId = "s",
        sectionTitle = "S",
        filePath = "A.kt",
        rows = rows,
        range = SelectedLineRange(40, SelectionSide.ADDITIONS, 41),
        text = "t",
      ),
    )
  }

  @Test
  fun `range labels follow formatDiffReviewRangeLabel`() {
    assertEquals("line", formatDiffReviewRangeLabel(emptyList()))
    assertEquals("+2", formatDiffReviewRangeLabel(rows.subList(2, 3)))
    assertEquals("1", formatDiffReviewRangeLabel(rows.subList(0, 1)))
  }

  @Test
  fun `a turn's diff is its own section, anything else is Changes`() {
    assertEquals(ReviewSection("turn:r1", "Turn 3"), reviewSection("r1", 3))
    assertEquals(ReviewSection("changes", "Changes"), reviewSection(null, null))
  }

  @Test
  fun `a turn rolls back to its last checkpoint, and only a ready one`() {
    val run: Run = run(ordinal = 2)
    val view: ThreadView = view(
      checkpoint("k1", 2, "ready"),
      checkpoint("k2", 2, "ready"),
      checkpoint("k3", 3, "ready"),
    )
    assertEquals("k2", checkpointForRun(view, run)?.id)
    val lastNotReady: ThreadView =
      view(checkpoint("k1", 2, "ready"), checkpoint("k2", 2, "capturing"))
    assertNull(checkpointForRun(lastNotReady, run), "The last one counts, and it isn't ready")
    assertNull(checkpointForRun(view(checkpoint("k3", 3, "ready")), run))
  }

  @Test
  fun `no roll back while a turn runs, and the tip says what it keeps`() {
    val idle: ThreadView = view().copy(runs = listOf(run(1)))
    val running: ThreadView = view().copy(runs = listOf(run(1), run(2, status = "running")))
    assertNull(rollbackBlockReason(null, idle))
    assertEquals(INTERRUPT_FIRST, rollbackBlockReason(null, running))
    assertEquals("Not connected", rollbackBlockReason("Not connected", running))
    assertEquals("Roll back to the state after turn 1", rollbackTooltip(run(1), null))
    assertEquals(INTERRUPT_FIRST, rollbackTooltip(run(1), INTERRUPT_FIRST))
  }

  private val thread: Thread by lazy {
    val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))
    Scenario.decode(scenarios.resolve("default").resolve("list.json").readText()).threads.first()
  }

  private fun view(vararg checkpoints: Checkpoint): ThreadView =
    ThreadView(
      thread = thread,
      runs = emptyList(),
      items = emptyList(),
      checkpoints = checkpoints.toList(),
    )
}

private fun checkpoint(id: String, ordinal: Long?, status: String): Checkpoint = Checkpoint(
  id = id,
  threadId = "t1",
  scopeId = "scope",
  runId = null,
  nodeId = "n",
  parentCheckpointId = null,
  ordinalWithinScope = 0,
  appRunOrdinal = ordinal,
  ref = "ref",
  status = status,
  files = emptyList(),
  capturedAt = "2026-10-20T10:00:00Z",
)

private fun run(ordinal: Long, status: String = "completed"): Run = Run(
  id = "r$ordinal",
  threadId = "t1",
  ordinal = ordinal,
  providerInstanceId = "codex",
  modelSelection = ModelSelection(instanceId = JsonPrimitive("codex"), model = JsonPrimitive("m")),
  providerThreadId = null,
  userMessageId = "m",
  rootNodeId = null,
  activeAttemptId = null,
  status = status,
  requestedAt = "2026-10-20T10:00:00Z",
  startedAt = null,
  completedAt = null,
  checkpointId = null,
  contextHandoffId = null,
)
