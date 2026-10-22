package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import io.github.xxfast.t3code.protocol.generated.ServerProviderSlashCommand
import io.github.xxfast.t3code.protocol.generated.ServerProviderWorkspaceSnapshot
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProviderSkillsLogicTest {
  private fun skill(
    name: String,
    path: String = "/tmp/$name/SKILL.md",
    enabled: Boolean = true,
    displayName: String? = null,
    scope: String? = null,
    shortDescription: String? = null,
    description: String? = null,
    userInvocable: Boolean? = null,
  ): ServerProviderSkill = ServerProviderSkill(
    name = name,
    description = description,
    path = path,
    scope = scope,
    enabled = enabled,
    displayName = displayName,
    shortDescription = shortDescription,
    userInvocable = userInvocable,
  )

  private val projectA = "/workspace/project-a"

  private fun provider(
    snapshots: List<ServerProviderWorkspaceSnapshot> = listOf(
      ServerProviderWorkspaceSnapshot(
        cwd = projectA,
        checkedAt = "2026-01-01T00:01:00.000Z",
        slashCommands = listOf(ServerProviderSlashCommand("project")),
        skills = listOf(skill("project", path = "/workspace/project-a/SKILL.md")),
      ),
    ),
  ): ServerProvider = ServerProvider(
    instanceId = "codex",
    driver = "codex",
    enabled = true,
    installed = true,
    version = "1.0.0",
    status = "ready",
    auth = ServerProviderAuth(status = "authenticated"),
    checkedAt = "2026-01-01T00:00:00.000Z",
    models = emptyList(),
    slashCommands = listOf(ServerProviderSlashCommand("global")),
    skills = listOf(skill("global", path = "/global/SKILL.md")),
    workspaceSnapshots = snapshots,
  )

  // packages/client-runtime/src/providerSkills.test.ts

  @Test
  fun `uses the cwd snapshot after a provider session has populated it`() {
    assertEquals(
      listOf(skill("project", path = "/workspace/project-a/SKILL.md")),
      resolveProviderSkillsForCwd(provider(), projectA),
    )
  }

  @Test
  fun `keeps the machine snapshot before this cwd has a provider snapshot`() {
    assertEquals(provider().skills, resolveProviderSkillsForCwd(provider(), "/workspace/project-b"))
    assertEquals(provider().skills, resolveProviderSkillsForCwd(provider(), null))
  }

  @Test
  fun `uses partial workspace skills while keeping discovery retryable`() {
    val partial: ServerProvider = provider(
      provider().workspaceSnapshots.map { it.copy(slashCommandsPending = true) },
    )
    assertEquals(
      provider().workspaceSnapshots.first().skills,
      resolveProviderSkillsForCwd(partial, projectA),
    )
    assertFalse(hasCompleteProviderWorkspaceSnapshot(partial, projectA))
    assertTrue(hasCompleteProviderWorkspaceSnapshot(provider(), projectA))
    assertFalse(hasCompleteProviderWorkspaceSnapshot(provider(), "/workspace/project-b"))
    assertFalse(hasCompleteProviderWorkspaceSnapshot(null, projectA))
    assertFalse(hasCompleteProviderWorkspaceSnapshot(provider(), null))
  }

  @Test
  fun `a snapshot is current for five minutes`() {
    val checked: Long = Instant.parse("2026-01-01T00:01:00.000Z").toEpochMilli()
    assertTrue(hasCurrentProviderWorkspaceSnapshot(provider(), projectA, checked + 299_999))
    assertFalse(hasCurrentProviderWorkspaceSnapshot(provider(), projectA, checked + 300_000))
    val bad: ServerProvider =
      provider(provider().workspaceSnapshots.map { it.copy(checkedAt = "yesterday") })
    assertFalse(hasCurrentProviderWorkspaceSnapshot(bad, projectA, checked))
  }

  @Test
  fun `shows one row when enabled skills share a name, and skips the disabled`() {
    val enabled: ServerProviderSkill = skill("babysit-pr", path = "/a/babysit-pr/SKILL.md")
    val skills: List<ServerProviderSkill> = listOf(
      skill("babysit-pr", path = "/b/babysit-pr/SKILL.md", enabled = false),
      enabled,
      skill("browser"),
      skill("Babysit-PR ", path = "/c/babysit-pr/SKILL.md"),
    )
    assertEquals(
      listOf(enabled, skill("browser")),
      searchProviderSkills(skills, ""),
    )
  }

  @Test
  fun `agent-only skills never list`() {
    val skills: List<ServerProviderSkill> =
      listOf(skill("sweep", userInvocable = false), skill("ask", userInvocable = true))
    assertEquals(listOf("ask"), searchProviderSkills(skills, "").map { it.name })
  }

  @Test
  fun `source kinds`() {
    fun kind(path: String = "/x/SKILL.md", scope: String? = null): ProviderSkillSourceKind =
      resolveProviderSkillSourceKind(skill("s", path = path, scope = scope))

    assertEquals(ProviderSkillSourceKind.APP, kind(path = "C:\\u\\.codex\\plugins\\p\\SKILL.md"))
    assertEquals(ProviderSkillSourceKind.APP, kind(path = "/u/.agents/plugins/p/SKILL.md"))
    assertEquals(ProviderSkillSourceKind.REPO, kind(scope = " Repository "))
    assertEquals(ProviderSkillSourceKind.PROJECT, kind(scope = "workspace"))
    assertEquals(ProviderSkillSourceKind.PROJECT, kind(scope = "local"))
    assertEquals(ProviderSkillSourceKind.PERSONAL, kind(scope = "user"))
    assertEquals(ProviderSkillSourceKind.SYSTEM, kind(scope = "system"))
    assertEquals(ProviderSkillSourceKind.OTHER, kind(scope = ""))
    assertEquals(ProviderSkillSourceKind.OTHER, kind())
    assertEquals(ProviderSkillSourceKind.OTHER, kind(scope = "admin"))
  }

  @Test
  fun `display names`() {
    assertEquals("Gh Fix Ci", formatProviderSkillDisplayName(skill("gh-fix-ci")))
    assertEquals("Skill Review Now", formatProviderSkillDisplayName(skill("skill:review_now")))
    assertEquals("Mine", formatProviderSkillDisplayName(skill("x", displayName = " Mine ")))
    assertEquals("X", formatProviderSkillDisplayName(skill("x", displayName = "  ")))
  }

  // apps/web/src/providerSkillSearch.test.ts

  @Test
  fun `searches skills with any currency prefix`() {
    listOf("$", "€", "£", "¥", "₹", "₩", "₿", "\uD807\uDFDD").forEach { prefix ->
      val found: List<ServerProviderSkill> =
        searchProviderSkills(listOf(skill("review")), "${prefix}review")
      assertEquals(listOf(skill("review")), found, prefix)
    }
  }

  @Test
  fun `uses fuzzy ranking for abbreviated queries`() {
    val skills: List<ServerProviderSkill> = listOf(
      skill("gh-fix-ci", displayName = "Gh Fix Ci"),
      skill("github", displayName = "Github"),
      skill("agent-browser", displayName = "Agent Browser"),
    )
    assertEquals(listOf("gh-fix-ci"), searchProviderSkills(skills, "gfc").map { it.name })
  }

  @Test
  fun `returns the first enabled definition for each skill name`() {
    val skills: List<ServerProviderSkill> = listOf(
      skill("branch-audit", path = "/Users/matt/.codex/skills/branch-audit/SKILL.md"),
      skill("browser"),
      skill("branch-audit", path = "/Users/matt/.agents/skills/branch-audit/SKILL.md"),
    )
    assertEquals(
      listOf("/Users/matt/.codex/skills/branch-audit/SKILL.md", "/tmp/browser/SKILL.md"),
      searchProviderSkills(skills, "").map { it.path },
    )
  }

  @Test
  fun `name beats label beats description beats scope, ties by label`() {
    val skills: List<ServerProviderSkill> = listOf(
      skill("deploy", scope = "review"),
      skill("lint", description = "review"),
      skill("triage", displayName = "Review Queue"),
      skill("review-pr"),
      skill("review"),
    )
    assertEquals(
      listOf("review", "review-pr", "triage", "lint", "deploy"),
      searchProviderSkills(skills, "review").map { it.name },
    )
    val top: List<ServerProviderSkill> = searchProviderSkills(skills, "review", limit = 1)
    assertEquals(listOf("review"), top.map { it.name })
  }

  @Test
  fun `menu rows`() {
    val picked: MutableList<String> = mutableListOf()
    val rows: List<ComposerMenuItem> = skillMenuItems(
      skills = listOf(
        skill("a", shortDescription = "short", description = "long", scope = "repo"),
        skill("b", description = "long", scope = "user"),
        skill("c", scope = "system"),
        skill("d"),
      ),
      query = "",
      provider = "codex",
      onPick = { picked += it.name },
    )
    assertEquals(
      listOf("skill:codex:a", "skill:codex:b", "skill:codex:c", "skill:codex:d"),
      rows.map { it.id },
    )
    assertEquals(
      listOf("short", "long", "system skill", "Run provider skill"),
      rows.map { it.description },
    )
    assertEquals(
      listOf("Repo Skill", "Personal Skill", "System Skill", "Provider Skill"),
      rows.map { it.badge?.label },
    )
    rows[1].onPick()
    assertEquals(listOf("b"), picked)
    assertEquals("\$review ", skillReplacement(skill("review")))
  }

  // useProviderWorkspaceSnapshotRefresh's gate

  @Test
  fun `refreshes a missing snapshot once per TTL`() {
    val refresh = ProviderWorkspaceSnapshotRefresh()
    val noSnapshot: ServerProvider = provider(emptyList())
    val first: ProviderWorkspaceSnapshotRefresh.Request? =
      refresh.begin(noSnapshot, "codex", projectA, 1_000)
    assertNotNull(first)
    assertEquals("codex:$projectA", first.key)
    assertNull(refresh.begin(noSnapshot, "codex", projectA, 1_000 + 299_999))
    assertNotNull(refresh.begin(noSnapshot, "codex", projectA, 1_000 + 300_000))
  }

  @Test
  fun `a current snapshot needs no refresh, a stale one does`() {
    val refresh = ProviderWorkspaceSnapshotRefresh()
    val checked: Long = Instant.parse("2026-01-01T00:01:00.000Z").toEpochMilli()
    assertNull(refresh.begin(provider(), "codex", projectA, checked + 1_000))
    assertNotNull(refresh.begin(provider(), "codex", projectA, checked + 300_000))
  }

  @Test
  fun `no scope, no refresh`() {
    val refresh = ProviderWorkspaceSnapshotRefresh()
    assertNull(refresh.begin(null, null, projectA, 0))
    assertNull(refresh.begin(null, "codex", null, 0))
  }

  @Test
  fun `a failed scan waits out the cooldown`() {
    val refresh = ProviderWorkspaceSnapshotRefresh()
    val noSnapshot: ServerProvider = provider(emptyList())
    val request: ProviderWorkspaceSnapshotRefresh.Request =
      assertNotNull(refresh.begin(noSnapshot, "codex", projectA, 0))
    refresh.retryLater(request, 100)
    assertNull(refresh.begin(noSnapshot, "codex", projectA, 100 + 9_999))
    assertNotNull(refresh.begin(noSnapshot, "codex", projectA, 100 + 10_000))
  }

  @Test
  fun `another scope asks at once`() {
    val refresh = ProviderWorkspaceSnapshotRefresh()
    val noSnapshot: ServerProvider = provider(emptyList())
    assertNotNull(refresh.begin(noSnapshot, "codex", projectA, 0))
    assertNotNull(refresh.begin(noSnapshot, "codex", "/workspace/project-b", 1))
  }

  @Test
  fun `losing a snapshot lifts the TTL`() {
    val refresh = ProviderWorkspaceSnapshotRefresh()
    val checked: Long = Instant.parse("2026-01-01T00:01:00.000Z").toEpochMilli()
    val stale: Long = checked + 300_000
    assertNotNull(refresh.begin(provider(), "codex", projectA, stale))
    assertNull(refresh.begin(provider(), "codex", projectA, stale + 1))
    assertNotNull(refresh.begin(provider(emptyList()), "codex", projectA, stale + 2))
  }
}
