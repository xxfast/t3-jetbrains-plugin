// Generated from v0.1.5 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class DeviceServiceState(
  val supportsHostRetry: Boolean? = null,
  val supportsToolUpdate: Boolean? = null,
  val supportsToolInspection: Boolean? = null,
  val supportsEasHosts: Boolean? = null,
  val supportsSimlockHosts: Boolean? = null,
  val hostStatus: String,
  val hostStatusDetail: String? = null,
  val hostStatuses: Map<String, DeviceServiceState.HostStatuses>,
  val devices: List<DeviceSummary>,
  val sessions: List<DeviceSession>,
  val bootingDevices: List<DeviceServiceState.BootingDevice>? = null,
  val onboardingCompleted: Boolean,
  val agentAccessEnabled: Boolean,
  val hubBasePath: String,
  val revision: Long,
  val hosts: List<DeviceServiceState.Host>,
  val easHosts: List<DeviceServiceState.EasHost>? = null,
  val simlockHosts: List<DeviceServiceState.SimlockHost>? = null,
) {
  @Serializable
  public data class HostStatuses(
    val status: String,
    val detail: String? = null,
    val androidDiscoveryIncomplete: Boolean? = null,
  )

  @Serializable
  public data class BootingDevice(
    val hostId: String,
    val id: String,
    val platform: String,
    val name: String,
    val version: String,
    val booted: Boolean,
    val physical: Boolean,
    val threadId: String,
  )

  @Serializable
  public data class Host(
    val id: String,
    val label: String,
    val platforms: List<DevicePlatformAvailability>,
    val tools: DeviceToolVersions? = null,
    val toolInspectionError: String? = null,
    val hubInstalled: Boolean,
    val agentDeviceInstalled: Boolean,
    val kind: String,
  )

  @Serializable
  public data class EasHost(
    val id: String,
    val label: String,
    val platforms: List<DevicePlatformAvailability>,
    val tools: DeviceToolVersions? = null,
    val toolInspectionError: String? = null,
    val hubInstalled: Boolean,
    val agentDeviceInstalled: Boolean,
    val kind: String,
  )

  @Serializable
  public data class SimlockHost(
    val id: String,
    val label: String,
    val platforms: List<DevicePlatformAvailability>,
    val tools: DeviceToolVersions? = null,
    val toolInspectionError: String? = null,
    val hubInstalled: Boolean,
    val agentDeviceInstalled: Boolean,
    val kind: String,
  )
}
