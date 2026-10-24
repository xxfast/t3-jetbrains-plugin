package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import io.github.xxfast.t3code.plugin.ui.UsageLevel
import io.github.xxfast.t3code.plugin.ui.UsageLimits
import io.github.xxfast.t3code.plugin.ui.UsageSegment
import io.github.xxfast.t3code.plugin.ui.limitsNotice
import io.github.xxfast.t3code.plugin.ui.providersWithLimits
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
import javax.swing.Icon
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel

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
  private val providers: MutableStateFlow<List<ServerProvider>> = MutableStateFlow(emptyList())
  private var popup: JBPopup? = null

  private val label: SimpleColoredComponent = SimpleColoredComponent().apply {
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
      providers.collectLatest { latest ->
        // Ticks so "resets in" stays current between reads
        while (true) {
          withContext(Dispatchers.EDT) { render(latest) }
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
      if (state !is ConnectionState.Connected) {
        providers.value = emptyList()
        return@collectLatest
      }
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

  private fun render(latest: List<ServerProvider>) {
    val now: Long = System.currentTimeMillis()
    val segments: List<UsageSegment> = usageSegments(latest)
    label.clear()
    label.isVisible = segments.isNotEmpty()
    if (segments.isEmpty()) return
    label.icon = ICON
    segments.forEachIndexed { index, segment ->
      label.append(if (index == 0) segment.label else "  ${segment.label}")
      label.append(" ${segment.remaining}%", attributesFor(segment.level))
    }
    label.toolTipText = segments.joinToString("<br>", "<html>", "</html>") { segment ->
      usageSegmentSummary(segment, now)
    }
    label.revalidate()
    label.repaint()
  }

  private fun showPopup() {
    popup?.takeIf { it.isVisible }?.let { it.cancel(); return }
    val panel: JComponent = JewelComposePanel {
      val latest by providers.collectAsState()
      var now: Long by remember { mutableLongStateOf(System.currentTimeMillis()) }
      LaunchedEffect(Unit) {
        while (true) {
          delay(TICK)
          now = System.currentTimeMillis()
        }
      }
      UsageLimits(providers = latest, now = now, onOpenUrl = BrowserUtil::browse)
    }
    val size = Dimension(JBUI.scale(POPUP_WIDTH), JBUI.scale(popupHeight(providers.value)))
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
    private val ICON: Icon =
      IconLoader.getIcon("/icons/t3UsageLimits.svg", UsageLimitsWidget::class.java)
    private val TICK = 15.seconds
    private const val POPUP_WIDTH: Int = 440

    private fun attributesFor(level: UsageLevel): SimpleTextAttributes {
      val color: Color = when (level) {
        UsageLevel.Normal -> return SimpleTextAttributes.REGULAR_ATTRIBUTES
        UsageLevel.Low -> JBUI.CurrentTheme.Label.warningForeground()
        UsageLevel.Exhausted -> JBUI.CurrentTheme.Label.errorForeground()
      }
      return SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
    }

    /**
     * A Swing popup doesn't follow its Compose content's size, so this sizes it up front, as
     * the command palette does, from [UsageLimits]'s layout: the padding and title, then per
     * provider the gap, its label, a 16 px row per window (2 apart) or the notice, and the link.
     * The content scrolls if this comes up short.
     */
    private fun popupHeight(providers: List<ServerProvider>): Int =
      12 + 16 + providersWithLimits(providers).sumOf { provider ->
        val limits = provider.usageLimits ?: return@sumOf 0
        val windows: Int = limits.windows.filterNotNull().size
        val rows: Int = if (limitsNotice(limits) != null) 13 else windows * 18 - 2
        val link: Int = if (limits.externalUsage != null) 4 + 15 else 0
        12 + 13 + 4 + rows + link
      } + 12
  }
}
