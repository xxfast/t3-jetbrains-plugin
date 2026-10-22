package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.generated.PullRequestListEntry
import io.github.xxfast.t3code.protocol.generated.PullRequestListInput
import io.github.xxfast.t3code.protocol.generated.PullRequestListResult
import io.github.xxfast.t3code.protocol.generated.PullRequestRef
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FixturePullRequestsTest {
  private val default: Path = Path.of(System.getProperty("t3code.scenarios")).resolve("default")
  private val scenario: Scenario =
    Scenario.decode(default.resolve("pull-requests.json").readText())
  private val kstore: String = "prj_01JB2K7Q9V3XKSTORE"

  private fun list(query: String? = null, limit: Long = 99): PullRequestListResult =
    fixtureListPullRequests(
      scenario.pullRequests,
      PullRequestListInput(state = "all", projectId = kstore, limit = limit, query = query),
    )

  @Test
  fun `the list is the project's, newest first, searched and cut at the limit`() {
    val all: List<PullRequestListEntry> = list().entries
    assertEquals(scenario.pullRequests.size, all.size)
    assertEquals(all.sortedByDescending { it.updatedAt }, all)
    assertTrue(list("release").entries.all { it.title.contains("Release") })
    assertEquals(3, list(limit = 3).entries.size)
    assertTrue(list(limit = 3).truncated)
    val other = PullRequestListInput(state = "all", projectId = "prj_other", limit = 99)
    assertEquals(emptyList(), fixtureListPullRequests(scenario.pullRequests, other).entries)
  }

  @Test
  fun `the detail finds a pull request past the list`() {
    val known: List<PullRequestListEntry> = scenario.pullRequests + scenario.olderPullRequests
    val number: Long = scenario.olderPullRequests.first().number
    assertTrue(list().entries.none { it.number == number })
    val ref = PullRequestRef(projectId = kstore, repository = "XXFAST/KStore", number = number)
    assertEquals(number, fixturePullRequestDetail(known, ref).number)
    assertFailsWith<T3Exception.Rpc> {
      fixturePullRequestDetail(known, ref.copy(number = 9_999))
    }
  }
}
