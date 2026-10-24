// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ServerProvider(
  val instanceId: String,
  val driver: String,
  val displayName: String? = null,
  val accentColor: String? = null,
  val iconUrl: String? = null,
  val badgeLabel: String? = null,
  val continuation: ServerProviderContinuation? = null,
  val showInteractionModeToggle: Boolean? = null,
  val reportsContextWindow: Boolean? = null,
  val supportedRuntimeModes: List<String?>? = null,
  val requiresNewThreadForModelChange: Boolean? = null,
  val supportsConversationRollback: Boolean? = null,
  val supportsTextGeneration: Boolean? = null,
  val setup: ServerProvider.Setup? = null,
  val nativeSessions: ServerProvider.NativeSessions? = null,
  val configurableProviders: Boolean? = null,
  val runtimePaths: ServerProvider.RuntimePaths? = null,
  val enabled: Boolean,
  val installed: Boolean,
  val version: String?,
  val installedVersion: String? = null,
  val status: String,
  val auth: ServerProviderAuth,
  val checkedAt: String,
  val message: String? = null,
  val availability: String? = null,
  val unavailableReason: String? = null,
  val models: List<ServerProviderModel>,
  val updateRequiredModels: List<ServerProviderUpdateRequiredModel> = emptyList(),
  val slashCommands: List<ServerProviderSlashCommand>? = null,
  val skills: List<ServerProviderSkill>? = null,
  val workspaceSnapshots: List<ServerProviderWorkspaceSnapshot> = emptyList(),
  val usageLimits: ServerProviderUsageLimits? = null,
  val versionAdvisory: ServerProviderVersionAdvisory? = null,
  val compatibilityAdvisory: ServerProviderCompatibilityAdvisory? = null,
  val updateState: ServerProviderUpdateState? = null,
) {
  @Serializable
  public data class Setup(
    val canAuthenticate: Boolean,
    val canInstall: Boolean,
    val documentationUrl: String? = null,
  )

  @Serializable
  public data class NativeSessions(
    val canList: Boolean,
    val canLoad: Boolean,
    val canResume: Boolean,
    val canDelete: Boolean? = null,
  )

  @Serializable
  public data class RuntimePaths(
    val homePath: String,
    val shadowHomePath: String?,
  )
}
