package io.github.xxfast.t3code.plugin.device

import io.github.xxfast.t3code.protocol.generated.DeviceDetail
import io.github.xxfast.t3code.protocol.generated.DeviceServiceState
import io.github.xxfast.t3code.protocol.generated.DeviceSettings
import io.github.xxfast.t3code.protocol.generated.DeviceSummary
import java.text.Collator
import java.util.Locale

// Ports of apps/web/src/components/device: DevicePanel.tsx, DeviceSetup.tsx, DeviceToolsPanel.tsx

/** `platformLabel` in DevicePanel.tsx. */
fun platformLabel(platform: String): String =
  if (platform == "ios") "iOS Simulators" else "Android Emulators"

/** One platform's devices, `groupDevices`' entries. */
class DeviceGroup(val platform: String, val devices: List<DeviceSummary>)

/**
 * `groupDevices` in DevicePanel.tsx: iOS then Android, running first, then by name. Upstream's
 * `localeCompare` is a root-locale [Collator] here.
 */
fun groupDevices(state: DeviceServiceState): List<DeviceGroup> {
  val collator: Collator = Collator.getInstance(Locale.ROOT)
  val order: Comparator<DeviceSummary> = compareByDescending<DeviceSummary> { it.booted }
    .thenComparator { a, b -> collator.compare(a.name, b.name) }
  return listOf("ios", "android").mapNotNull { platform ->
    val devices: List<DeviceSummary> = state.devices
      .filter { it.platform == platform }
      .sortedWith(order)
    if (devices.isEmpty()) null else DeviceGroup(platform, devices)
  }
}

/**
 * [hostId]'s label. EAS hosts travel apart as `easHosts`; upstream's `DeviceServiceState`
 * decode puts them back in `hosts`, so this looks in both.
 */
fun hostLabel(state: DeviceServiceState, hostId: String): String? =
  state.hosts.find { it.id == hostId }?.label
    ?: state.easHosts?.find { it.id == hostId }?.label

/**
 * A device row's second line in DevicePanel.tsx is `host · version · status`; the table gives
 * each its own column. The host falls back to "Device host", as its loading view does.
 */
fun deviceHostLabel(state: DeviceServiceState, device: DeviceSummary): String =
  hostLabel(state, device.hostId) ?: "Device host"

fun deviceStatusLabel(device: DeviceSummary): String = if (device.booted) "Running" else "Stopped"

/** `deviceHubReadiness` in DeviceSetup.tsx: remote hosts count, `hostStatus` is this machine. */
class DeviceHubReadiness(val ready: Boolean, val busy: Boolean)

fun deviceHubReadiness(state: DeviceServiceState): DeviceHubReadiness {
  val statuses: List<String> = state.hostStatuses.values.map { it.status }
  val ready: Boolean = state.hostStatus == "ready" || "ready" in statuses
  val busy: Boolean = !ready &&
    (listOf(state.hostStatus) + statuses).any { it == "installing" || it == "starting" }
  return DeviceHubReadiness(ready, busy)
}

/**
 * What the panel says instead of the device list, or null when it shows the list. Upstream
 * shows its setup wizard where the first line is; that's `device.configure`, `settings:write`,
 * which the plugin doesn't ask for.
 */
fun deviceStateMessage(state: DeviceServiceState): String? {
  val readiness: DeviceHubReadiness = deviceHubReadiness(state)
  return when {
    !state.onboardingCompleted || state.hostStatus == "disabled" ->
      "Device support is off on this server. Set it up in T3 Code's device panel."
    readiness.busy && state.hostStatus == "installing" ->
      state.hostStatusDetail ?: "Installing device support…"
    readiness.busy -> "Finding devices…"
    state.hostStatus == "failed" && state.devices.isEmpty() ->
      state.hostStatusDetail ?: "The device hub failed to start."
    state.devices.isEmpty() -> "No simulators or emulators were found on this environment."
    else -> null
  }
}

/** An unset value. Upstream's Foreground row shows a dash too. */
const val UNSET: String = "–"

/** `TEXT_SIZES` in DeviceToolsPanel.tsx. */
private val TEXT_SIZES: Map<String, String> = mapOf(
  "small" to "Small",
  "default" to "Default",
  "large" to "Large",
  "extra-large" to "Extra large",
)

/** `COLOR_FILTERS` in DeviceToolsPanel.tsx. */
private val COLOR_FILTERS: Map<String, String> = mapOf(
  "none" to "None",
  "grayscale" to "Grayscale",
  "red-green" to "Red / green (protanopia)",
  "green-red" to "Green / red (deuteranopia)",
  "blue-yellow" to "Blue / yellow (tritanopia)",
)

/**
 * [detail] as label and value rows, the rows DeviceToolsPanel.tsx shows for [platform] in its
 * order and with its labels, read-only. Unset settings read as [UNSET].
 */
fun deviceDetailRows(detail: DeviceDetail, platform: String): List<Pair<String, String>> {
  val isIos: Boolean = platform == "ios"
  val settings: DeviceSettings = detail.settings
  fun toggle(value: Boolean?): String = when (value) {
    true -> "On"
    false -> "Off"
    null -> UNSET
  }
  // Its toggle groups' labels: Light and Dark, Clear and Tinted
  fun choice(value: String?): String = value?.replaceFirstChar { it.uppercase() } ?: UNSET
  return buildList {
    add("Foreground" to (detail.foregroundApp?.id ?: UNSET))
    add("Appearance" to choice(settings.appearance))
    add("Text size" to (settings.textSize?.let { TEXT_SIZES[it] ?: it } ?: UNSET))
    if (isIos) {
      add("Liquid Glass" to choice(settings.liquidGlass))
      add("Color filter" to (settings.colorFilter?.let { COLOR_FILTERS[it] ?: it } ?: UNSET))
    }
    add("Reduce Motion" to toggle(settings.reduceMotion))
    if (isIos) {
      add("Increase Contrast" to toggle(settings.increaseContrast))
      add("Reduce Transparency" to toggle(settings.reduceTransparency))
      add("Show Borders" to toggle(settings.showBorders))
      add("VoiceOver" to toggle(settings.voiceOver))
    } else {
      add("Network" to toggle(settings.networkEnabled))
    }
    add("Location" to (settings.location?.let { "${it.latitude}, ${it.longitude}" } ?: UNSET))
    add("Read at" to detail.readAt)
  }
}
