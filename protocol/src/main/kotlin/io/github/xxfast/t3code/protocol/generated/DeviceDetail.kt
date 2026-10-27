// Generated from v0.1.2 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class DeviceDetail(
  val hostId: String,
  val deviceId: String,
  val settings: DeviceSettings,
  val foregroundApp: DeviceForegroundApp?,
  val readAt: String,
)
