package io.github.xxfast.t3code.plugin.device

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import io.github.xxfast.t3code.plugin.T3
import io.github.xxfast.t3code.plugin.actions.ThreadActionScope
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.DeviceDetail
import io.github.xxfast.t3code.protocol.generated.DeviceDetailInput
import io.github.xxfast.t3code.protocol.generated.DeviceServiceState
import io.github.xxfast.t3code.protocol.generated.DeviceSummary
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tools > T3 Code > Device panel. */
class OpenDevicePanelAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    DevicePanelDialog(project).show()
  }
}

/**
 * Upstream's `DevicePanel` (apps/web/src/components/device/DevicePanel.tsx), read-only: the
 * server's simulators and emulators from `subscribeDeviceState`, a `device.list` rescan when it
 * opens and on Refresh, and a running device's `device.detail` below. Upstream's live view is
 * the device hub's stream (`hubBasePath`), not an RPC, and a screenshot is only the agent's
 * `device_screenshot` tool, so there's no picture here. Opening, starting and the tools'
 * actions are `orchestration:operate` with a thread; this panel has none and does none.
 *
 * One table where upstream has a section per platform (`groupDevices`): the rows keep its
 * order and the Platform column says the section.
 */
class DevicePanelDialog(project: Project) : DialogWrapper(project) {
  private val client: T3Client = T3.get().client
  private val scope: CoroutineScope = ThreadActionScope.getInstance(project).scope
  private val jobs: MutableList<Job> = mutableListOf()
  private var detailJob: Job? = null
  private var state: DeviceServiceState? = null
  private var listed: Boolean = false

  private val message = JBLabel("Finding devices…")

  private val devices: ListTableModel<DeviceSummary> = ListTableModel(
    column<DeviceSummary>("Name") { it.name },
    column<DeviceSummary>("Platform") { platformLabel(it.platform) },
    column<DeviceSummary>("Host") { device ->
      state?.let { deviceHostLabel(it, device) }.orEmpty()
    },
    column<DeviceSummary>("Version") { it.version },
    column<DeviceSummary>("Status") { deviceStatusLabel(it) },
  )
  private val deviceTable = TableView(devices).apply {
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  }

  private val detail: ListTableModel<Pair<String, String>> = ListTableModel(
    column<Pair<String, String>>("Setting") { it.first },
    column<Pair<String, String>>("Value") { it.second },
  )
  private val detailTable = TableView(detail).apply {
    emptyText.text = "Select a running device to read its settings"
  }

  init {
    title = "T3 Device Panel"
    isModal = false
    setOKButtonText("Close")
    init()
    deviceTable.selectionModel.addListSelectionListener { event ->
      if (!event.valueIsAdjusting) showDetail(deviceTable.selectedObject)
    }
    jobs += scope.launch {
      client.deviceState().collect { next -> withContext(Dispatchers.EDT) { show(next) } }
    }
  }

  override fun createCenterPanel(): JComponent {
    val splitter = JBSplitter(true, 0.55f).apply {
      firstComponent = JBScrollPane(deviceTable)
      secondComponent = JBScrollPane(detailTable)
    }
    return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
      add(message, BorderLayout.NORTH)
      add(splitter, BorderLayout.CENTER)
      preferredSize = JBUI.size(640, 480)
    }
  }

  override fun createActions(): Array<Action> = arrayOf(okAction)

  override fun createLeftSideActions(): Array<Action> = arrayOf(
    object : AbstractAction("Refresh devices") {
      override fun actionPerformed(e: ActionEvent) = rescan()
    },
  )

  override fun dispose() {
    jobs.forEach { it.cancel() }
    detailJob?.cancel()
    super.dispose()
  }

  private fun show(next: DeviceServiceState) {
    val selected: DeviceSummary? = deviceTable.selectedObject
    state = next
    val text: String? = deviceStateMessage(next)
    message.text = text.orEmpty()
    message.isVisible = text != null
    devices.items = groupDevices(next).flatMap { it.devices }
    val again: DeviceSummary? = selected?.let { before ->
      devices.items.find { it.hostId == before.hostId && it.id == before.id }
    }
    if (again != null) deviceTable.setSelection(listOf(again))
    // DevicePanel.tsx lists once it's shown, never while device support is off
    if (!listed && next.onboardingCompleted && next.hostStatus != "disabled") {
      listed = true
      rescan()
    }
  }

  private fun rescan() {
    jobs += scope.launch {
      val next: DeviceServiceState = try {
        client.devices()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        withContext(Dispatchers.EDT) {
          message.text = failure.message ?: "Couldn't refresh devices"
          message.isVisible = true
        }
        return@launch
      }
      withContext(Dispatchers.EDT) { show(next) }
    }
  }

  private fun showDetail(device: DeviceSummary?) {
    detailJob?.cancel()
    detail.items = emptyList()
    detailTable.emptyText.text = when {
      device == null -> "Select a running device to read its settings"
      !device.booted -> "${device.name} isn't running"
      else -> "Reading ${device.name}…"
    }
    if (device == null || !device.booted) return
    detailJob = scope.launch {
      val input = DeviceDetailInput(hostId = device.hostId, deviceId = device.id)
      val read: DeviceDetail = try {
        client.deviceDetail(input)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        withContext(Dispatchers.EDT) {
          detailTable.emptyText.text = failure.message ?: "Couldn't read ${device.name}"
        }
        return@launch
      }
      withContext(Dispatchers.EDT) { detail.items = deviceDetailRows(read, device.platform) }
    }
  }
}

private fun <T> column(name: String, value: (T) -> String): ColumnInfo<T, String> =
  object : ColumnInfo<T, String>(name) {
    override fun valueOf(item: T): String = value(item)
  }
