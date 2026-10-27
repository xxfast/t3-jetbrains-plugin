package io.github.xxfast.t3code.plugin.device

import io.github.xxfast.t3code.protocol.generated.DeviceDetail
import io.github.xxfast.t3code.protocol.generated.DeviceForegroundApp
import io.github.xxfast.t3code.protocol.generated.DeviceServiceState
import io.github.xxfast.t3code.protocol.generated.DeviceSettings
import io.github.xxfast.t3code.protocol.generated.DeviceSummary
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DevicePanelLogicTest {
  private fun device(name: String, platform: String, booted: Boolean, hostId: String = "local") =
    DeviceSummary(
      hostId = hostId,
      id = name.lowercase().replace(' ', '-'),
      platform = platform,
      name = name,
      version = "1",
      booted = booted,
      physical = false,
    )

  private fun host(id: String, label: String) = DeviceServiceState.Host(
    id = id,
    label = label,
    platforms = emptyList(),
    hubInstalled = true,
    agentDeviceInstalled = true,
    kind = "ssh",
  )

  private fun state(
    devices: List<DeviceSummary> = emptyList(),
    hostStatus: String = "ready",
    hostStatusDetail: String? = null,
    hostStatuses: Map<String, DeviceServiceState.HostStatuses> = emptyMap(),
    onboardingCompleted: Boolean = true,
  ) = DeviceServiceState(
    hostStatus = hostStatus,
    hostStatusDetail = hostStatusDetail,
    hostStatuses = hostStatuses,
    devices = devices,
    sessions = emptyList(),
    onboardingCompleted = onboardingCompleted,
    agentAccessEnabled = true,
    hubBasePath = "/hub",
    revision = 1,
    hosts = listOf(host("local", "This machine")),
    easHosts = listOf(
      DeviceServiceState.EasHost(
        id = "eas",
        label = "Expo",
        platforms = emptyList(),
        hubInstalled = true,
        agentDeviceInstalled = true,
        kind = "eas",
      ),
    ),
  )

  @Test
  fun `groups are iOS then Android, running first, then by name`() {
    val groups: List<DeviceGroup> = groupDevices(
      state(
        devices = listOf(
          device("Pixel 9", "android", booted = false),
          device("iPhone SE", "ios", booted = false),
          device("iPad Air", "ios", booted = false),
          device("iPhone 17", "ios", booted = true),
        ),
      ),
    )
    assertEquals(listOf("ios", "android"), groups.map { it.platform })
    assertEquals(listOf("iPhone 17", "iPad Air", "iPhone SE"), groups[0].devices.map { it.name })
    val labels: List<String> = groups.map { platformLabel(it.platform) }
    assertEquals(listOf("iOS Simulators", "Android Emulators"), labels)
  }

  @Test
  fun `a platform with no devices has no group`() {
    val groups: List<DeviceGroup> =
      groupDevices(state(devices = listOf(device("Pixel", "android", true))))
    assertEquals(listOf("android"), groups.map { it.platform })
  }

  @Test
  fun `host labels come from hosts and EAS hosts, else Device host`() {
    val state: DeviceServiceState = state()
    assertEquals("This machine", deviceHostLabel(state, device("A", "ios", true)))
    assertEquals("Expo", deviceHostLabel(state, device("B", "ios", true, hostId = "eas")))
    assertEquals("Device host", deviceHostLabel(state, device("C", "ios", true, hostId = "gone")))
    assertEquals("Running", deviceStatusLabel(device("A", "ios", true)))
    assertEquals("Stopped", deviceStatusLabel(device("A", "ios", false)))
  }

  @Test
  fun `a remote host that's ready makes the hub ready`() {
    val remote = mapOf("mac" to DeviceServiceState.HostStatuses(status = "ready"))
    val readiness: DeviceHubReadiness =
      deviceHubReadiness(state(hostStatus = "idle", hostStatuses = remote))
    assertEquals(true, readiness.ready)
    assertEquals(false, readiness.busy)
  }

  @Test
  fun `the panel says what upstream's does in place of the list`() {
    val off = "Device support is off on this server. Set it up in T3 Code's device panel."
    assertEquals(off, deviceStateMessage(state(hostStatus = "disabled")))
    assertEquals(off, deviceStateMessage(state(onboardingCompleted = false)))
    assertEquals("Installing device support…", deviceStateMessage(state(hostStatus = "installing")))
    assertEquals("Finding devices…", deviceStateMessage(state(hostStatus = "starting")))
    val failed: String? = deviceStateMessage(state(hostStatus = "failed"))
    assertEquals("The device hub failed to start.", failed)
    assertEquals(
      "xcrun not found",
      deviceStateMessage(state(hostStatus = "failed", hostStatusDetail = "xcrun not found")),
    )
    assertEquals(
      "No simulators or emulators were found on this environment.",
      deviceStateMessage(state()),
    )
    assertNull(deviceStateMessage(state(devices = listOf(device("A", "ios", true)))))
  }

  @Test
  fun `detail rows follow the tools panel per platform`() {
    val detail = DeviceDetail(
      hostId = "local",
      deviceId = "a",
      settings = DeviceSettings(
        appearance = "dark",
        textSize = "extra-large",
        colorFilter = "red-green",
        reduceMotion = true,
        networkEnabled = false,
      ),
      foregroundApp = DeviceForegroundApp(id = "io.github.xxfast.sample"),
      readAt = "2026-10-27T00:00:00.000Z",
    )
    val ios: Map<String, String> = deviceDetailRows(detail, "ios").toMap()
    assertEquals("io.github.xxfast.sample", ios["Foreground"])
    assertEquals("Dark", ios["Appearance"])
    assertEquals("Extra large", ios["Text size"])
    assertEquals("Red / green (protanopia)", ios["Color filter"])
    assertEquals(UNSET, ios["Liquid Glass"])
    assertEquals("On", ios["Reduce Motion"])
    assertNull(ios["Network"])
    val android: Map<String, String> = deviceDetailRows(detail, "android").toMap()
    assertEquals("Off", android["Network"])
    assertNull(android["VoiceOver"])
    assertNull(android["Color filter"])
  }
}
