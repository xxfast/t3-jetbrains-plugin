package io.github.xxfast.t3code.plugin

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.xxfast.t3code.plugin.ui.UsageLevel
import io.github.xxfast.t3code.plugin.ui.UsageLimits
import io.github.xxfast.t3code.plugin.ui.UsageSegment
import io.github.xxfast.t3code.plugin.ui.collectExternalUsageLinks
import io.github.xxfast.t3code.plugin.ui.collectLimitAccounts
import io.github.xxfast.t3code.plugin.ui.collectLimitNotices
import io.github.xxfast.t3code.plugin.ui.collectLimitPools
import io.github.xxfast.t3code.plugin.ui.cursorUsageWindowDetails
import io.github.xxfast.t3code.plugin.ui.displayLimitWindows
import io.github.xxfast.t3code.plugin.ui.usageSegmentSummary
import io.github.xxfast.t3code.plugin.ui.usageSegments
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.generated.ServerProvider
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Not upstream's: a glance at every provider's usage limits in the status bar, and upstream's
 * usage limits banner ([UsageLimits]) on a click.
 */
class UsageLimitsWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = ID

  override fun getDisplayName(): String = "T3 Usage Limits"

  override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
    UsageLimitsWidget(scope)

  companion object {
    const val ID: String = "T3Code.UsageLimits"
  }
}

private class UsageLimitsWidget(private val scope: CoroutineScope) : CustomStatusBarWidget {
  private val client: T3Client = T3.get().client
  /** The last providers read, kept through a disconnect so the widget keeps its place. */
  private val providers: MutableStateFlow<List<ServerProvider>> = MutableStateFlow(emptyList())
  private val connected: MutableStateFlow<Boolean> = MutableStateFlow(false)
  private var popup: JBPopup? = null

  /** A provider icon and its percent per segment; the labels take no mouse, so this gets it. */
  private val label: JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    border = JBUI.CurrentTheme.StatusBar.Widget.border()
    isOpaque = false
    isVisible = false
    // The status bar paints the hover and pressed effect itself, as for its own widgets
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = showPopup()
    })
  }

  override fun ID(): String = UsageLimitsWidgetFactory.ID

  override fun getComponent(): JComponent = label

  override fun install(statusBar: StatusBar) {
    scope.launch { follow() }
    scope.launch {
      combine(providers, connected, ::Pair).collectLatest { (latest, online) ->
        // Ticks so "resets in" stays current between reads
        while (true) {
          withContext(Dispatchers.EDT) { render(latest, online) }
          delay(TICK)
        }
      }
    }
  }

  /**
   * Reads the providers while connected. A poll of `server.getConfig` for now; the live
   * `subscribeServerConfig` stream replaces it once the design settles.
   */
  private suspend fun follow() {
    client.connection.collectLatest { state ->
      connected.value = state is ConnectionState.Connected
      if (state !is ConnectionState.Connected) return@collectLatest
      while (true) {
        try {
          providers.value = client.serverInfo().providers
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          LOG.info("Couldn't read usage limits", e)
        }
        delay(TICK)
      }
    }
  }

  /**
   * A provider icon and what's left per segment. Offline, the last connection's providers with
   * a dash, since their numbers can't be trusted; hidden until a first connection has any.
   */
  private fun render(latest: List<ServerProvider>, online: Boolean) {
    val now: Long = System.currentTimeMillis()
    val segments: List<UsageSegment> = usageSegments(latest)
    label.removeAll()
    label.isVisible = segments.isNotEmpty()
    segments.forEachIndexed { index, segment ->
      val provider: ServerProvider = latest.first { it.instanceId == segment.instanceId }
      if (index > 0) label.add(Box.createHorizontalStrut(JBUI.scale(SEGMENT_GAP)))
      label.add(
        JBLabel(if (online) "${segment.remaining}%" else "–").apply {
          icon = providerSwingIcon(provider, latest, JBUI.CurrentTheme.StatusBar.BACKGROUND)
          iconTextGap = JBUI.scale(3)
          foreground = if (online) foregroundFor(segment.level) else UIUtil.getInactiveTextColor()
        },
      )
    }
    label.toolTipText = if (online) {
      segments.joinToString("<br>", "<html>", "</html>") { segment ->
        usageSegmentSummary(segment, now)
      }
    } else {
      OFFLINE
    }
    label.revalidate()
    label.repaint()
  }

  private fun showPopup() {
    popup?.takeIf { it.isVisible }?.let { it.cancel(); return }
    val panel: JComponent = JewelComposePanel {
      val latest by providers.collectAsState()
      val online by connected.collectAsState()
      var now: Long by remember { mutableLongStateOf(System.currentTimeMillis()) }
      LaunchedEffect(Unit) {
        while (true) {
          delay(TICK)
          now = System.currentTimeMillis()
        }
      }
      if (online) {
        UsageLimits(providers = latest, now = now, onOpenUrl = BrowserUtil::browse)
      } else {
        Text(OFFLINE, Modifier.padding(12.dp), color = JewelTheme.globalColors.text.info)
      }
    }
    val shown: List<ServerProvider> = if (connected.value) providers.value else emptyList()
    val size = Dimension(JBUI.scale(POPUP_WIDTH), JBUI.scale(popupHeight(shown)))
    panel.preferredSize = size
    val opened: JBPopup = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, null)
      .setCancelOnClickOutside(true)
      .setCancelKeyEnabled(true)
      .setResizable(false)
      .setMovable(false)
      .createPopup()
    popup = opened
    opened.show(above(size.width, size.height))
  }

  /** Above the widget, its right edge on the widget's. */
  private fun above(width: Int, height: Int): RelativePoint =
    RelativePoint(label, Point(label.width - width, -height - JBUI.scale(4)))

  override fun dispose() {
    popup?.cancel()
  }

  companion object {
    private val LOG: Logger = Logger.getInstance(UsageLimitsWidget::class.java)
    private val TICK = 15.seconds
    private const val POPUP_WIDTH: Int = 480
    private const val MAX_POPUP_HEIGHT: Int = 600
    private const val SEGMENT_GAP: Int = 8
    private const val OFFLINE: String =
      "Not connected to T3 Code. Usage limits are back once it is."

    private fun foregroundFor(level: UsageLevel): Color = when (level) {
      UsageLevel.Normal -> JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
      UsageLevel.Low -> JBUI.CurrentTheme.Label.warningForeground()
      UsageLevel.Exhausted -> JBUI.CurrentTheme.Label.errorForeground()
    }

    /**
     * A Swing popup doesn't follow its Compose content's size, so this sizes it up front, as
     * the command palette does, from [UsageLimits]'s layout: the padding, then per driver its
     * header and a card per window (a label, the large share, the refill line when shared, a
     * description for Cursor's), the link sections and the notices, 16 apart. At most
     * [MAX_POPUP_HEIGHT]; the content scrolls past that or if this comes up short.
     */
    private fun popupHeight(providers: List<ServerProvider>): Int {
      val now: Long = System.currentTimeMillis()
      val sections: List<Int> =
        collectLimitPools(collectLimitAccounts(providers), now).map { pool ->
          val cards: List<Int> = displayLimitWindows(pool).map { window ->
            val refill: Int = if (pool.accounts.size > 1) 18 else 0
            val description: Int =
              if (pool.driver == "cursor" && cursorUsageWindowDetails(window.id) != null) 22 else 0
            24 + 16 + 2 + 24 + refill + description
          }
          18 + cards.sumOf { it + 8 }
        } + collectExternalUsageLinks(providers).map { 24 + 16 + 16 } +
          collectLimitNotices(providers).let { notices ->
            if (notices.isEmpty()) emptyList() else listOf(notices.size * 18)
          }
      val content: Int = sections.sum() + 16 * (sections.size - 1).coerceAtLeast(0)
      return (24 + content).coerceIn(80, MAX_POPUP_HEIGHT)
    }
  }
}
