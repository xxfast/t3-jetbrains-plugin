package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.generated.ProjectSearchEntriesInput
import io.github.xxfast.t3code.protocol.generated.ProjectSearchEntriesResult
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FixtureEntriesTest {
  private val default: Path = Path.of(System.getProperty("t3code.scenarios")).resolve("default")
  private val scenario: Scenario = Scenario.decode(default.resolve("files.json").readText())

  private fun search(query: String, limit: Long = 80): ProjectSearchEntriesResult =
    fixtureSearchEntries(scenario.projectEntries, ProjectSearchEntriesInput("/repo", query, limit))

  @Test
  fun `the default scenario has files and folders to mention`() {
    assertTrue(scenario.projectEntries.any { it.kind == "file" })
    assertTrue(scenario.projectEntries.any { it.kind == "directory" })
  }

  @Test
  fun `a name that starts with the query comes first`() {
    val paths: List<String> = search("kstore").entries.map { it.path }
    assertEquals("kstore", paths.first())
    assertTrue("kstore/src/commonMain/kotlin/io/github/xxfast/kstore/KStore.kt" in paths)
  }

  @Test
  fun `the limit truncates`() {
    val result: ProjectSearchEntriesResult = search("k", limit = 2)
    assertEquals(2, result.entries.size)
    assertTrue(result.truncated)
  }
}
