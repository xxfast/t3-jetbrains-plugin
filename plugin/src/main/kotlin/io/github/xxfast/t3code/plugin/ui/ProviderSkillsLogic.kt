package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import io.github.xxfast.t3code.protocol.generated.ServerProviderWorkspaceSnapshot
import java.time.Instant

// The provider's skills for the `$` menu: upstream's
// `packages/client-runtime/src/providerSkills.ts`, `apps/web/src/providerSkillSearch.ts`,
// `packages/shared/src/inlineSkills.ts`, the snapshot TTL
// in `packages/contracts/src/server.ts` and `useProviderWorkspaceSnapshotRefresh` in
// `ChatComposer.tsx`.

/** `PROVIDER_WORKSPACE_SNAPSHOT_TTL_MS`, contracts/src/server.ts:141. */
const val PROVIDER_WORKSPACE_SNAPSHOT_TTL_MS: Long = 5 * 60_000

/** `WORKSPACE_SNAPSHOT_RETRY_COOLDOWN_MS`, ChatComposer.tsx:1121. */
const val WORKSPACE_SNAPSHOT_RETRY_COOLDOWN_MS: Long = 10_000

/** `ProviderSkillSourceKind`, with `SKILL_SOURCE_LABEL_BY_KIND` (ComposerCommandMenu.tsx:258). */
enum class ProviderSkillSourceKind(val label: String) {
  APP("App"),
  REPO("Repo"),
  PROJECT("Project"),
  PERSONAL("Personal"),
  SYSTEM("System"),
  OTHER("Provider"),
}

private fun normalizePathSeparators(path: String): String = path.replace("\\", "/")

/** `dedupeProviderSkillsByName`, providerSkills.ts:14: the first of each lowercased name. */
fun dedupeProviderSkillsByName(skills: List<ServerProviderSkill>): List<ServerProviderSkill> {
  val seen: MutableSet<String> = mutableSetOf()
  return skills.filter { skill -> seen.add(skill.name.trim().lowercase()) }
}

/**
 * `isProviderSkillUserInvocable`, providerSkills.ts:35. A skill switched off in the provider's
 * settings won't run, and one the provider keeps for the agent (Claude Code's
 * `user-invocable: false`) rejects a user invocation. Everything else is fair game.
 */
fun isProviderSkillUserInvocable(skill: ServerProviderSkill): Boolean =
  skill.enabled && skill.userInvocable != false

/** `resolveProviderSkillSourceKind`, providerSkills.ts:58. */
fun resolveProviderSkillSourceKind(skill: ServerProviderSkill): ProviderSkillSourceKind {
  val path: String = normalizePathSeparators(skill.path)
  if (path.contains("/.codex/plugins/") || path.contains("/.agents/plugins/")) {
    return ProviderSkillSourceKind.APP
  }
  return when (skill.scope?.trim()?.lowercase()) {
    "repo", "repository" -> ProviderSkillSourceKind.REPO
    "project", "workspace", "local" -> ProviderSkillSourceKind.PROJECT
    "user", "personal" -> ProviderSkillSourceKind.PERSONAL
    "system" -> ProviderSkillSourceKind.SYSTEM
    else -> ProviderSkillSourceKind.OTHER
  }
}

/** `resolveProviderWorkspaceSnapshot`, providerSkills.ts:88. */
private fun resolveProviderWorkspaceSnapshot(
  provider: ServerProvider,
  cwd: String?,
): ServerProviderWorkspaceSnapshot? {
  if (cwd.isNullOrEmpty()) return null
  return provider.workspaceSnapshots.find { snapshot -> snapshot.cwd == cwd }
}

/** `isProviderWorkspaceSnapshotCurrent`, contracts/src/server.ts:143. */
fun isProviderWorkspaceSnapshotCurrent(
  snapshot: ServerProviderWorkspaceSnapshot,
  nowMs: Long,
): Boolean {
  // `Date.parse` of a bad date is NaN, and NaN is never less than the TTL
  val checkedAt: Long = runCatching { Instant.parse(snapshot.checkedAt).toEpochMilli() }
    .getOrNull() ?: return false
  return nowMs - checkedAt < PROVIDER_WORKSPACE_SNAPSHOT_TTL_MS
}

/** `hasCompleteProviderWorkspaceSnapshot`, providerSkills.ts:96: one for [cwd], not pending. */
fun hasCompleteProviderWorkspaceSnapshot(provider: ServerProvider?, cwd: String?): Boolean {
  val snapshot: ServerProviderWorkspaceSnapshot? =
    provider?.let { resolveProviderWorkspaceSnapshot(it, cwd) }
  return snapshot != null && snapshot.slashCommandsPending != true
}

/** `hasCurrentProviderWorkspaceSnapshot`, providerSkills.ts:104: complete and inside the TTL. */
fun hasCurrentProviderWorkspaceSnapshot(
  provider: ServerProvider?,
  cwd: String?,
  nowMs: Long,
): Boolean {
  val snapshot: ServerProviderWorkspaceSnapshot? =
    provider?.let { resolveProviderWorkspaceSnapshot(it, cwd) }
  return snapshot != null &&
    snapshot.slashCommandsPending != true &&
    isProviderWorkspaceSnapshotCurrent(snapshot, nowMs)
}

/**
 * `resolveProviderSkillsForCwd`, providerSkills.ts:141: the workspace snapshot's skills for
 * [cwd], else the machine's.
 */
fun resolveProviderSkillsForCwd(provider: ServerProvider, cwd: String?): List<ServerProviderSkill> =
  resolveProviderWorkspaceSnapshot(provider, cwd)?.skills ?: provider.skills.orEmpty()

/** `titleCaseWords`, inlineSkills.ts:5. */
private fun titleCaseWords(value: String): String = value
  .split(Regex("[\\s:_-]+"))
  .filter { it.isNotEmpty() }
  .joinToString(" ") { word -> word.substring(0, 1).uppercase() + word.substring(1) }

/** `formatProviderSkillDisplayName`, inlineSkills.ts:14: the display name, else the name in caps. */
fun formatProviderSkillDisplayName(skill: ServerProviderSkill): String {
  val displayName: String? = skill.displayName?.trim()
  if (!displayName.isNullOrEmpty()) return displayName
  return titleCaseWords(skill.name)
}

/** `scoreProviderSkill`, providerSkillSearch.ts:13: the best of name, label, texts and scope. */
fun scoreProviderSkill(skill: ServerProviderSkill, query: String): Int? {
  val name: String = skill.name.lowercase()
  val label: String = formatProviderSkillDisplayName(skill).lowercase()
  val shortDescription: String = skill.shortDescription?.lowercase().orEmpty()
  val description: String = skill.description?.lowercase().orEmpty()
  val scope: String = skill.scope?.lowercase().orEmpty()
  return listOfNotNull(
    scoreQueryMatch(
      value = name,
      query = query,
      exactBase = 0,
      prefixBase = 2,
      boundaryBase = 4,
      includesBase = 6,
      fuzzyBase = 100,
      boundaryMarkers = listOf("-", "_", "/"),
    ),
    scoreQueryMatch(
      value = label,
      query = query,
      exactBase = 1,
      prefixBase = 3,
      boundaryBase = 5,
      includesBase = 7,
      fuzzyBase = 110,
    ),
    scoreQueryMatch(
      value = shortDescription,
      query = query,
      exactBase = 20,
      prefixBase = 22,
      boundaryBase = 24,
      includesBase = 26,
    ),
    scoreQueryMatch(
      value = description,
      query = query,
      exactBase = 30,
      prefixBase = 32,
      boundaryBase = 34,
      includesBase = 36,
    ),
    scoreQueryMatch(
      value = scope,
      query = query,
      exactBase = 40,
      prefixBase = 42,
      includesBase = 44,
    ),
  ).minOrNull()
}

private val LEADING_SIGILS: Regex = Regex("^\\p{Sc}+")

/**
 * `searchProviderSkills`, providerSkillSearch.ts:72: the user-invocable skills, one per name;
 * all of them in order for an empty query, else the matches ranked by [scoreProviderSkill] with
 * the label then the name breaking ties. A leading sigil in [query] is ignored.
 */
fun searchProviderSkills(
  skills: List<ServerProviderSkill>,
  query: String,
  limit: Int = Int.MAX_VALUE,
): List<ServerProviderSkill> {
  val enabled: List<ServerProviderSkill> =
    dedupeProviderSkillsByName(skills.filter(::isProviderSkillUserInvocable))
  val normalized: String = normalizeSearchQuery(query, trimLeadingPattern = LEADING_SIGILS)
  if (normalized.isEmpty()) return enabled
  val ranked: MutableList<RankedSearchResult<ServerProviderSkill>> = mutableListOf()
  for (skill in enabled) {
    val score: Int = scoreProviderSkill(skill, normalized) ?: continue
    val tieBreaker = "${formatProviderSkillDisplayName(skill).lowercase()}\u0000${skill.name}"
    insertRankedSearchResult(ranked, RankedSearchResult(skill, score, tieBreaker), limit)
  }
  return ranked.map { it.item }
}

/**
 * The `$` menu's rows, ChatComposer.tsx:2738: the label, a description falling back to the
 * scope, and the source badge with its " Skill" suffix (`showSkillSuffix` under `$`).
 */
fun skillMenuItems(
  skills: List<ServerProviderSkill>,
  query: String,
  provider: String,
  onPick: (ServerProviderSkill) -> Unit,
): List<ComposerMenuItem> = searchProviderSkills(skills, query).map { skill ->
  val source: ProviderSkillSourceKind = resolveProviderSkillSourceKind(skill)
  ComposerMenuItem(
    id = "skill:$provider:${skill.name}",
    label = formatProviderSkillDisplayName(skill),
    description = skill.shortDescription
      ?: skill.description
      ?: skill.scope?.let { "$it skill" }
      ?: "Run provider skill",
    badge = ComposerMenuBadge("${source.label} Skill", source.icon),
    onPick = { onPick(skill) },
  )
}

/** The replacement a skill pick types, ChatComposer.tsx:3898. Sent as plain text. */
fun skillReplacement(skill: ServerProviderSkill): String = "\$${skill.name} "

/**
 * `useProviderWorkspaceSnapshotRefresh`'s gate, ChatComposer.tsx:1666: whether to ask
 * `server.refreshProviders` for the workspace snapshot of an instance and cwd. Not twice inside
 * the TTL for the same scope (a client clock ahead of the server's can't loop rescans), not
 * while a current snapshot is there, not inside a failed scan's cooldown. Losing a snapshot
 * lifts both, so the next check rescans at once.
 */
class ProviderWorkspaceSnapshotRefresh {
  /** One scan asked for: the `instanceId:cwd` scope and when. */
  class Request(val key: String, val requestedAt: Long)

  private class Retry(val key: String, val notBefore: Long)

  private var lastRequest: Request? = null
  private var retry: Retry? = null
  private var hadSnapshot: Boolean? = null

  /** A request to send for [instanceId] at [cwd] on [provider], or null when none is due. */
  @Synchronized
  fun begin(provider: ServerProvider?, instanceId: String?, cwd: String?, nowMs: Long): Request? {
    val hasSnapshot: Boolean = hasCompleteProviderWorkspaceSnapshot(provider, cwd)
    if (hadSnapshot == true && !hasSnapshot) {
      lastRequest = null
      retry = null
    }
    hadSnapshot = hasSnapshot
    if (instanceId == null || cwd.isNullOrEmpty()) return null
    val key = "$instanceId:$cwd"
    val last: Request? = lastRequest
    if (last?.key == key && nowMs - last.requestedAt < PROVIDER_WORKSPACE_SNAPSHOT_TTL_MS) {
      return null
    }
    if (hasCurrentProviderWorkspaceSnapshot(provider, cwd, nowMs)) return null
    val pending: Retry? = retry
    if (pending?.key == key && nowMs < pending.notBefore) return null
    return Request(key, nowMs).also { lastRequest = it }
  }

  /** `retryLater`: [request] came back without a complete snapshot, or failed. */
  @Synchronized
  fun retryLater(request: Request, nowMs: Long) {
    if (lastRequest !== request) return
    lastRequest = null
    retry = Retry(request.key, nowMs + WORKSPACE_SNAPSHOT_RETRY_COOLDOWN_MS)
  }
}
