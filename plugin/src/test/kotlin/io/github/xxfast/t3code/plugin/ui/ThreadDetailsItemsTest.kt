package io.github.xxfast.t3code.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Upstream's threadDetailsItems.test.ts, without the retired Labs flag's rows. */
class ThreadDetailsItemsTest {
  private val usage: ThreadDetailsItem = ThreadDetailsItem.Usage
  private val servers: ThreadDetailsItem = ThreadDetailsItem.Servers

  @Test
  fun `resolves Usage from overrides`() {
    listOf(
      emptyMap<String, Boolean>() to false,
      mapOf("usage" to true, "retired" to false) to true,
      mapOf("usage" to false) to false,
    ).forEach { (items, expected) ->
      val visibility: ThreadDetailsVisibility = resolveThreadDetailsVisibility(items)
      assertEquals(expected, visibility[usage], "$items")
      assertTrue(visibility[servers], "$items")
    }
  }

  @Test
  fun `stores only choices that differ from the default`() {
    assertEquals(mapOf("usage" to true), setThreadDetailsItem(emptyMap(), usage, true))
    assertEquals(emptyMap(), setThreadDetailsItem(mapOf("usage" to true), usage, false))
    assertEquals(emptyMap(), setThreadDetailsItem(mapOf("servers" to false), servers, true))
  }

  @Test
  fun `unknown ids survive a change`() {
    assertEquals(
      mapOf("retired" to false, "lineage" to false),
      setThreadDetailsItem(mapOf("retired" to false), ThreadDetailsItem.Lineage, false),
    )
  }

  @Test
  fun `ids and defaults match upstream`() {
    assertEquals(
      listOf(
        "environment", "openIn", "scripts", "branch", "gitActions", "changes", "servers",
        "automations", "lineage", "usage",
      ),
      THREAD_DETAILS_ITEMS.map { it.id },
    )
    assertEquals(listOf(usage), THREAD_DETAILS_ITEMS.filterNot { it.defaultVisible })
  }

  @Test
  fun `the default is every item at its default`() {
    assertTrue(isDefaultThreadDetailsVisibility(resolveThreadDetailsVisibility(emptyMap())))
    assertTrue(
      isDefaultThreadDetailsVisibility(resolveThreadDetailsVisibility(mapOf("retired" to false))),
    )
    assertFalse(
      isDefaultThreadDetailsVisibility(resolveThreadDetailsVisibility(mapOf("lineage" to false))),
    )
    assertTrue(
      isDefaultThreadDetailsVisibility(
        resolveThreadDetailsVisibility(RESET_THREAD_DETAILS_ITEMS),
      ),
    )
  }

  @Test
  fun `sections follow the rows that render`() {
    val all: ThreadDetailsVisibility = resolveThreadDetailsVisibility(emptyMap())
    val shown: ThreadDetailsShow = resolveThreadDetailsShow(all, true, "/repo", isGitRepo = true)
    assertTrue(shown.workspace && shown.versionControl)

    val workspaceHidden: ThreadDetailsVisibility = resolveThreadDetailsVisibility(
      mapOf("environment" to false, "openIn" to false, "scripts" to false),
    )
    assertFalse(resolveThreadDetailsShow(workspaceHidden, true, "/repo", true).workspace)
    // Open in only counts while there's a picker to show
    val onlyOpenIn: ThreadDetailsVisibility =
      resolveThreadDetailsVisibility(mapOf("environment" to false, "scripts" to false))
    assertFalse(resolveThreadDetailsShow(onlyOpenIn, false, "/repo", true).workspace)

    // Changes alone needs a repo; git actions alone doesn't, it offers to initialise one
    val onlyChanges: ThreadDetailsVisibility =
      resolveThreadDetailsVisibility(mapOf("branch" to false, "gitActions" to false))
    assertTrue(resolveThreadDetailsShow(onlyChanges, true, "/repo", true).versionControl)
    assertFalse(resolveThreadDetailsShow(onlyChanges, true, "/repo", false).versionControl)
    val onlyActions: ThreadDetailsVisibility =
      resolveThreadDetailsVisibility(mapOf("branch" to false, "changes" to false))
    assertTrue(resolveThreadDetailsShow(onlyActions, true, "/repo", false).versionControl)
    assertFalse(resolveThreadDetailsShow(all, true, null, true).versionControl)
  }
}
