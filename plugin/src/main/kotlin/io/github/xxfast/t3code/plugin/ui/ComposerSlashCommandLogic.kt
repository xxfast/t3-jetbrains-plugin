package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import io.github.xxfast.t3code.protocol.generated.ServerProviderSlashCommand
import io.github.xxfast.t3code.protocol.generated.TurnItem

// The `/` menu: upstream's `apps/web/src/components/chat/composerSlashCommandSearch.ts`, the
// slash branch of `composerMenuItems` (ChatComposer.tsx:2674), `compactSlashCommandAvailable`
// (ChatComposer.tsx:2585) and `parseStandaloneComposerSlashCommand` (composer-logic.ts:350).

/** `ComposerSlashCommand`: the built-ins the client handles itself. */
enum class ComposerSlashCommand(val command: String) {
  MODEL("model"),
  PLAN("plan"),
  DEFAULT("default"),
}

/** `SlashSearchItem`: the `slash-command`, `provider-slash-command` and `skill` menu items. */
sealed interface SlashSearchItem {
  val id: String
  val label: String
  val description: String

  /** `type: "slash-command"`, a built-in. */
  data class SlashCommand(
    val command: ComposerSlashCommand,
    override val label: String,
    override val description: String,
  ) : SlashSearchItem {
    override val id: String get() = "slash:${command.command}"
  }

  /** `type: "provider-slash-command"`, one the provider expands. */
  data class ProviderSlashCommand(
    val provider: String,
    val command: ServerProviderSlashCommand,
    override val label: String,
    override val description: String,
  ) : SlashSearchItem {
    override val id: String get() = "provider-slash-command:$provider:${command.name}"
  }

  /** `type: "skill"`, a provider skill listed as `/skill:<name>`. */
  data class Skill(
    val provider: String,
    val skill: ServerProviderSkill,
    override val label: String,
    override val description: String,
  ) : SlashSearchItem {
    override val id: String get() = "skill:$provider:${skill.name}"
  }
}

/**
 * `slashCommandItemsForPromptPosition`, composerSlashCommandSearch.ts:20. A provider expands a
 * slash command only when it opens the whole message; anywhere else it reaches the agent as
 * literal text, so it is not offered there.
 */
fun slashCommandItemsForPromptPosition(
  items: List<SlashSearchItem>,
  isAtPromptStart: Boolean,
): List<SlashSearchItem> {
  if (isAtPromptStart) return items
  return items.filter { item -> item !is SlashSearchItem.ProviderSlashCommand }
}

/** `scoreSlashCommandItem`, composerSlashCommandSearch.ts:30. */
private fun scoreSlashCommandItem(item: SlashSearchItem, query: String): Int? {
  if (item is SlashSearchItem.Skill) {
    if (query == "skill") return 0
    val skillQuery: String = query.removePrefix("skill:")
    val skillScore: Int? =
      if (skillQuery.isNotEmpty()) scoreProviderSkill(item.skill, skillQuery) else 0
    if (skillScore != null) return skillScore
    // `Number.MAX_SAFE_INTEGER`: still listed for a prefix of "skill", after everything else
    return if ("skill".startsWith(query)) Int.MAX_VALUE else null
  }

  val primaryValue: String = when (item) {
    is SlashSearchItem.SlashCommand -> item.command.command.lowercase()
    is SlashSearchItem.ProviderSlashCommand -> item.command.name.lowercase()
    is SlashSearchItem.Skill -> error("handled above")
  }
  val description: String = item.description.lowercase()
  return listOfNotNull(
    scoreQueryMatch(
      value = primaryValue,
      query = query,
      exactBase = 0,
      prefixBase = 2,
      boundaryBase = 4,
      includesBase = 6,
      fuzzyBase = 100,
      boundaryMarkers = listOf("-", "_", "/"),
    ),
    scoreQueryMatch(
      value = description,
      query = query,
      exactBase = 20,
      prefixBase = 22,
      boundaryBase = 24,
      includesBase = 26,
    ),
  ).minOrNull()
}

private val LEADING_SLASHES: Regex = Regex("^/+")

/**
 * `searchSlashCommandItems`, composerSlashCommandSearch.ts:76: everything in order for an empty
 * query, else the matches by [scoreSlashCommandItem], built-ins then provider commands then
 * skills breaking ties by name. Leading slashes in [query] are ignored.
 */
fun searchSlashCommandItems(items: List<SlashSearchItem>, query: String): List<SlashSearchItem> {
  val normalizedQuery: String = normalizeSearchQuery(query, trimLeadingPattern = LEADING_SLASHES)
  if (normalizedQuery.isEmpty()) return items
  val ranked: MutableList<RankedSearchResult<SlashSearchItem>> = mutableListOf()
  for (item in items) {
    val score: Int = scoreSlashCommandItem(item, normalizedQuery) ?: continue
    val tieBreaker: String = when (item) {
      is SlashSearchItem.SlashCommand -> "0\u0000${item.command.command}"
      is SlashSearchItem.ProviderSlashCommand -> "1\u0000${item.command.name}\u0000${item.provider}"
      is SlashSearchItem.Skill -> "2\u0000${item.skill.name}\u0000${item.provider}"
    }
    insertRankedSearchResult(ranked, RankedSearchResult(item, score, tieBreaker), Int.MAX_VALUE)
  }
  return ranked.map { it.item }
}

/**
 * `compactSlashCommandAvailable`, ChatComposer.tsx:2585: the `/` token is the whole prompt, the
 * thread can compact, and nothing else rides along.
 */
fun compactSlashCommandAvailable(
  prompt: String,
  trigger: ComposerTrigger?,
  compactThreadUnavailable: Boolean,
  hasNonPromptContent: Boolean,
): Boolean {
  if (trigger?.kind != ComposerTriggerKind.SLASH_COMMAND) return false
  val start: Int = trigger.rangeStart.coerceIn(0, prompt.length)
  val end: Int = trigger.rangeEnd.coerceIn(start, prompt.length)
  return prompt.substring(0, start).isBlank() &&
    !compactThreadUnavailable &&
    prompt.substring(end).isBlank() &&
    !hasNonPromptContent
}

/**
 * `activeThreadHasCompactableConversation`, ChatView.tsx:5518: a user message that isn't only
 * a `/compact`.
 */
fun hasCompactableConversation(items: List<TurnItem>): Boolean = items.any { item ->
  item is TurnItem.UserMessage &&
    (item.text.trim().lowercase() != COMPACT_COMMAND || item.attachments.isNotEmpty())
}

/**
 * `compactThreadUnavailable`, ChatView.tsx:5535, for what the plugin knows: no operate scope,
 * nothing to compact, a provider without `/compact`, a run or a send in flight, or a request
 * waiting. Upstream's checkpoint revert, detail loading, worktree setup, feedback upload and
 * plan follow-up checks have no counterpart here.
 */
fun compactThreadUnavailable(
  canOperateThread: Boolean,
  hasCompactableConversation: Boolean,
  manualCompactionProviderAvailable: Boolean,
  isWorking: Boolean,
  hasPendingRequest: Boolean,
): Boolean = !canOperateThread ||
  !hasCompactableConversation ||
  !manualCompactionProviderAvailable ||
  isWorking ||
  hasPendingRequest

/**
 * The slash branch of `composerMenuItems`, ChatComposer.tsx:2674: `/model`, then `/plan` and
 * `/default` when [planModeUiEnabled], the provider's commands for [cwd] minus the visible
 * skills' names and minus `/compact` unless [compactAvailable], then `/skill:<name>` rows.
 * Provider commands only when the token opens the prompt, ranked for the [trigger]'s query.
 */
fun slashCommandMenuItems(
  trigger: ComposerTrigger,
  provider: ServerProvider?,
  cwd: String?,
  planModeUiEnabled: Boolean,
  showSkillsInSlashMenu: Boolean,
  compactAvailable: Boolean,
): List<SlashSearchItem> {
  val builtIns: List<SlashSearchItem> = buildList {
    add(
      SlashSearchItem.SlashCommand(
        command = ComposerSlashCommand.MODEL,
        label = "/model",
        description = "Switch response model for this thread",
      ),
    )
    if (planModeUiEnabled) {
      add(
        SlashSearchItem.SlashCommand(
          command = ComposerSlashCommand.PLAN,
          label = "/plan",
          description = "Switch this thread into plan mode",
        ),
      )
      add(
        SlashSearchItem.SlashCommand(
          command = ComposerSlashCommand.DEFAULT,
          label = "/default",
          description = "Switch this thread back to normal build mode",
        ),
      )
    }
  }
  val selectedProvider: String = provider?.instanceId.orEmpty()
  val skills: List<ServerProviderSkill> =
    provider?.let { resolveProviderSkillsForCwd(it, cwd) }.orEmpty()
  val commands: List<ServerProviderSlashCommand> =
    provider?.let { resolveProviderSlashCommandsForCwd(it, cwd) }.orEmpty()
  val slashMenuSkills: List<ServerProviderSkill> =
    getProviderSkillsForSlashMenu(skills, showSkillsInSlashMenu)
  val providerItems: List<SlashSearchItem> =
    getProviderSlashCommandsForSlashMenu(commands, slashMenuSkills)
      .filter { command -> command.name != "compact" || compactAvailable }
      .map { command ->
        SlashSearchItem.ProviderSlashCommand(
          provider = selectedProvider,
          command = command,
          label = "/${command.name}",
          description = command.description ?: command.input?.hint ?: "Run provider command",
        )
      }
  val skillItems: List<SlashSearchItem> = slashMenuSkills.map { skill ->
    SlashSearchItem.Skill(
      provider = selectedProvider,
      skill = skill,
      label = "/skill:${skill.name}",
      description = skill.shortDescription
        ?: skill.description
        ?: skill.scope?.let { "$it skill" }
        ?: "",
    )
  }
  val positioned: List<SlashSearchItem> = slashCommandItemsForPromptPosition(
    items = builtIns + providerItems + skillItems,
    isAtPromptStart = trigger.rangeStart == 0,
  )
  return searchSlashCommandItems(positioned, trigger.query.trim().lowercase())
}

private val STANDALONE_SLASH_COMMAND: Regex =
  Regex("^/(plan|default)\\s*$", RegexOption.IGNORE_CASE)

/**
 * `parseStandaloneComposerSlashCommand`, composer-logic.ts:350: a prompt that is only `/plan`
 * or `/default`.
 */
fun parseStandaloneComposerSlashCommand(text: String): ComposerSlashCommand? {
  val match: MatchResult = STANDALONE_SLASH_COMMAND.find(text.trim()) ?: return null
  return if (match.groupValues[1].lowercase() == "plan") {
    ComposerSlashCommand.PLAN
  } else {
    ComposerSlashCommand.DEFAULT
  }
}

/** `USAGE_LIMITS_COMMAND.name`, packages/shared/src/usageLimits.ts:588. */
const val USAGE_LIMITS_COMMAND_NAME: String = "usage-limits"

/** What picking a `/` row does, the slash branches of `onSelectComposerItem` (CC:3849). */
sealed interface SlashCommandPick {
  /** Remove the token, then open the model picker. */
  data object OpenModelPicker : SlashCommandPick

  /** Switch the interaction mode, then remove the token. */
  data class InteractionMode(val mode: String) : SlashCommandPick

  /** Remove the token, then open the usage limits. */
  data object UsageLimits : SlashCommandPick

  /** Replace the token with [replacement]. */
  data class Replace(val replacement: String) : SlashCommandPick

  /** Nothing: a plan or default pick with plan mode off. */
  data object Ignore : SlashCommandPick
}

/**
 * `onSelectComposerItem` for a `/` row, ChatComposer.tsx:3849: `/model` opens the picker,
 * `/plan` and `/default` switch the mode, `/usage-limits` opens the limits only when the host
 * can ([canShowUsageLimits], upstream's `onUsageLimitsCommand`), any other provider command
 * leaves `/<name> ` and a skill leaves `$<name> `.
 */
fun slashCommandPick(
  item: SlashSearchItem,
  planModeUiEnabled: Boolean,
  canShowUsageLimits: Boolean,
): SlashCommandPick = when (item) {
  is SlashSearchItem.SlashCommand -> when {
    item.command == ComposerSlashCommand.MODEL -> SlashCommandPick.OpenModelPicker
    !planModeUiEnabled -> SlashCommandPick.Ignore
    else -> SlashCommandPick.InteractionMode(
      if (item.command == ComposerSlashCommand.PLAN) "plan" else "default",
    )
  }

  is SlashSearchItem.ProviderSlashCommand ->
    if (item.command.name == USAGE_LIMITS_COMMAND_NAME && canShowUsageLimits) {
      SlashCommandPick.UsageLimits
    } else {
      SlashCommandPick.Replace("/${item.command.name} ")
    }

  is SlashSearchItem.Skill -> SlashCommandPick.Replace(skillReplacement(item.skill))
}
