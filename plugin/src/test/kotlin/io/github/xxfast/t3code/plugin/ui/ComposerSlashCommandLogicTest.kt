package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderAuth
import io.github.xxfast.t3code.protocol.generated.ServerProviderSkill
import io.github.xxfast.t3code.protocol.generated.ServerProviderSlashCommand
import io.github.xxfast.t3code.protocol.generated.ServerProviderSlashCommandInput
import io.github.xxfast.t3code.protocol.generated.ServerProviderWorkspaceSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ComposerSlashCommandLogicTest {
  private fun skill(
    name: String,
    path: String = "/tmp/$name/SKILL.md",
    enabled: Boolean = true,
    description: String? = null,
  ): ServerProviderSkill = ServerProviderSkill(
    name = name,
    path = path,
    enabled = enabled,
    description = description,
  )

  private val projectA = "/workspace/project-a"

  private fun provider(
    slashCommands: List<ServerProviderSlashCommand> = listOf(ServerProviderSlashCommand("global")),
    skills: List<ServerProviderSkill> = listOf(skill("global", path = "/global/SKILL.md")),
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
    slashCommands = slashCommands,
    skills = skills,
    workspaceSnapshots = snapshots,
  )

  private fun slash(query: String, rangeStart: Int = 0): ComposerTrigger = ComposerTrigger(
    kind = ComposerTriggerKind.SLASH_COMMAND,
    query = query,
    rangeStart = rangeStart,
    rangeEnd = rangeStart + 1 + query.length,
  )

  // packages/client-runtime/src/providerSkills.test.ts

  @Test
  fun `keeps the skill alias when the provider also exposes it as a slash command`() {
    val askMatt: ServerProviderSkill =
      skill("ask-matt", path = "/Users/matt/.agents/skills/ask-matt/SKILL.md")
    assertEquals(
      listOf("ask-matt"),
      getProviderSkillsForSlashMenu(listOf(askMatt), true).map { it.name },
    )
  }

  @Test
  fun `shows one row when enabled skills share a name`() {
    val skills: List<ServerProviderSkill> = listOf(
      skill("babysit-pr", path = "/Users/matt/.codex/skills/babysit-pr/SKILL.md"),
      skill("browser", path = "/Users/matt/.agents/skills/browser/SKILL.md"),
      skill("babysit-pr", path = "/Users/matt/.agents/skills/babysit-pr/SKILL.md"),
    )
    assertEquals(
      listOf("babysit-pr", "browser"),
      getProviderSkillsForSlashMenu(skills, true).map { it.name },
    )
  }

  @Test
  fun `keeps an enabled skill when a disabled duplicate appears first`() {
    val enabledSkill: ServerProviderSkill =
      skill("babysit-pr", path = "/Users/matt/.agents/skills/babysit-pr/SKILL.md")
    val skills: List<ServerProviderSkill> = listOf(
      skill("babysit-pr", path = "/Users/matt/.codex/skills/babysit-pr/SKILL.md", enabled = false),
      enabledSkill,
    )
    assertEquals(listOf(enabledSkill), getProviderSkillsForSlashMenu(skills, true))
  }

  @Test
  fun `slash commands come from the cwd snapshot, else the machine`() {
    assertEquals(
      listOf(ServerProviderSlashCommand("project")),
      resolveProviderSlashCommandsForCwd(provider(), projectA),
    )
    assertEquals(provider().slashCommands, resolveProviderSlashCommandsForCwd(provider(), null))
  }

  @Test
  fun `uses partial workspace commands`() {
    val partial: ServerProvider = provider(
      snapshots = provider().workspaceSnapshots.map { snapshot ->
        snapshot.copy(
          slashCommands = listOf(ServerProviderSlashCommand("compact")),
          slashCommandsPending = true,
        )
      },
    )
    assertEquals(
      listOf(ServerProviderSlashCommand("compact")),
      resolveProviderSlashCommandsForCwd(partial, projectA),
    )
  }

  // Behaviour of the ported functions

  @Test
  fun `no skills in the slash menu when the setting is off`() {
    assertEquals(emptyList(), getProviderSkillsForSlashMenu(listOf(skill("a")), false))
  }

  @Test
  fun `provider commands drop names a visible skill takes, case and space blind`() {
    val commands: List<ServerProviderSlashCommand> =
      listOf(ServerProviderSlashCommand(" Review "), ServerProviderSlashCommand("init"))
    assertEquals(
      listOf("init"),
      getProviderSlashCommandsForSlashMenu(commands, listOf(skill("review"))).map { it.name },
    )
  }

  @Test
  fun `provider commands only at the start of the prompt`() {
    val items: List<SlashSearchItem> = slashCommandMenuItems(
      trigger = slash("", rangeStart = 4),
      provider = provider(skills = emptyList()),
      cwd = null,
      planModeUiEnabled = true,
      showSkillsInSlashMenu = true,
      compactAvailable = true,
    )
    assertEquals(listOf("slash:model", "slash:plan", "slash:default"), items.map { it.id })
  }

  @Test
  fun `the full menu in order for an empty query`() {
    val items: List<SlashSearchItem> = slashCommandMenuItems(
      trigger = slash(""),
      provider = provider(),
      cwd = null,
      planModeUiEnabled = true,
      showSkillsInSlashMenu = true,
      compactAvailable = true,
    )
    // The provider's `global` command loses to the `global` skill of the same name
    assertEquals(
      listOf("slash:model", "slash:plan", "slash:default", "skill:codex:global"),
      items.map { it.id },
    )
  }

  @Test
  fun `plan and default only with plan mode`() {
    val items: List<SlashSearchItem> = slashCommandMenuItems(
      trigger = slash(""),
      provider = null,
      cwd = null,
      planModeUiEnabled = false,
      showSkillsInSlashMenu = true,
      compactAvailable = false,
    )
    assertEquals(listOf("slash:model"), items.map { it.id })
  }

  @Test
  fun `compact only when available`() {
    val compact: ServerProvider = provider(
      slashCommands = listOf(ServerProviderSlashCommand("compact", "Compact the thread")),
      skills = emptyList(),
    )
    fun ids(available: Boolean): List<String> = slashCommandMenuItems(
      trigger = slash("comp"),
      provider = compact,
      cwd = null,
      planModeUiEnabled = false,
      showSkillsInSlashMenu = true,
      compactAvailable = available,
    ).map { it.id }
    assertEquals(listOf("provider-slash-command:codex:compact"), ids(true))
    assertEquals(emptyList(), ids(false))
  }

  @Test
  fun `provider command description falls back to the input hint, then a default`() {
    val items: List<SlashSearchItem> = slashCommandMenuItems(
      trigger = slash(""),
      provider = provider(
        slashCommands = listOf(
          ServerProviderSlashCommand("a", input = ServerProviderSlashCommandInput("<file>")),
          ServerProviderSlashCommand("b"),
        ),
        skills = emptyList(),
      ),
      cwd = null,
      planModeUiEnabled = false,
      showSkillsInSlashMenu = true,
      compactAvailable = false,
    )
    assertEquals(
      listOf("Switch response model for this thread", "<file>", "Run provider command"),
      items.map { it.description },
    )
  }

  @Test
  fun `ranks exact before prefix before description, built-ins first on ties`() {
    val items: List<SlashSearchItem> = listOf(
      SlashSearchItem.ProviderSlashCommand(
        "codex",
        ServerProviderSlashCommand("plans"),
        "/plans",
        "x",
      ),
      SlashSearchItem.SlashCommand(ComposerSlashCommand.MODEL, "/model", "Plan the model"),
      SlashSearchItem.SlashCommand(ComposerSlashCommand.PLAN, "/plan", "Switch"),
    )
    assertEquals(
      listOf("slash:plan", "provider-slash-command:codex:plans", "slash:model"),
      searchSlashCommandItems(items, "/plan").map { it.id },
    )
  }

  @Test
  fun `skill rows match by skill name, the skill prefix, or sink for a prefix of skill`() {
    val review = SlashSearchItem.Skill("codex", skill("review"), "/skill:review", "")
    val model = SlashSearchItem.SlashCommand(ComposerSlashCommand.MODEL, "/model", "m")
    assertEquals(listOf(review), searchSlashCommandItems(listOf(model, review), "rev"))
    assertEquals(listOf(review), searchSlashCommandItems(listOf(model, review), "skill:rev"))
    assertEquals(listOf(review), searchSlashCommandItems(listOf(model, review), "skill"))
    // "sk" matches no skill name, but is a prefix of "skill"
    assertEquals(listOf(review), searchSlashCommandItems(listOf(model, review), "sk"))
  }

  @Test
  fun `compact availability wants the token alone and nothing else`() {
    val trigger = ComposerTrigger(ComposerTriggerKind.SLASH_COMMAND, "comp", 2, 7)
    assertTrue(compactSlashCommandAvailable("  /comp ", trigger, false, false))
    assertFalse(compactSlashCommandAvailable("x /comp", trigger, false, false))
    assertFalse(compactSlashCommandAvailable("  /comp y", trigger, false, false))
    assertFalse(compactSlashCommandAvailable("  /comp", trigger, true, false))
    assertFalse(compactSlashCommandAvailable("  /comp", trigger, false, true))
    assertFalse(compactSlashCommandAvailable("  /comp", null, false, false))
  }

  @Test
  fun `standalone plan or default`() {
    assertEquals(ComposerSlashCommand.PLAN, parseStandaloneComposerSlashCommand(" /PLAN  "))
    assertEquals(ComposerSlashCommand.DEFAULT, parseStandaloneComposerSlashCommand("/default"))
    assertNull(parseStandaloneComposerSlashCommand("/plan this"))
    assertNull(parseStandaloneComposerSlashCommand("/model"))
  }

  @Test
  fun `picks`() {
    val model = SlashSearchItem.SlashCommand(ComposerSlashCommand.MODEL, "/model", "")
    val plan = SlashSearchItem.SlashCommand(ComposerSlashCommand.PLAN, "/plan", "")
    val usage = SlashSearchItem.ProviderSlashCommand(
      "codex",
      ServerProviderSlashCommand(USAGE_LIMITS_COMMAND_NAME),
      "/usage-limits",
      "",
    )
    val skill = SlashSearchItem.Skill("codex", skill("review"), "/skill:review", "")
    assertEquals(SlashCommandPick.OpenModelPicker, slashCommandPick(model, false, false))
    assertEquals(SlashCommandPick.InteractionMode("plan"), slashCommandPick(plan, true, false))
    assertEquals(SlashCommandPick.Ignore, slashCommandPick(plan, false, false))
    assertEquals(SlashCommandPick.UsageLimits, slashCommandPick(usage, false, true))
    assertEquals(SlashCommandPick.Replace("/usage-limits "), slashCommandPick(usage, false, false))
    assertEquals(SlashCommandPick.Replace("\$review "), slashCommandPick(skill, false, false))
  }
}
