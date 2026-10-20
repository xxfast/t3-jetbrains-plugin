package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The context window part of the composer's footer: the meter's [usage], what "Compact context"
 * does ([onCompact], null when the provider has no `/compact`, [compactDisabledReason] when it
 * can't now) and the compact-before-send offer, [compactBeforeSendTokens] (upstream's
 * `resumeCompactionTokens`) and whether you chose to [keepFullHistory].
 */
data class ComposerContextWindow(
  val usage: ContextWindowSnapshot?,
  val modelDisplayName: String? = null,
  val onCompact: (() -> Unit)? = null,
  val compactDisabledReason: String? = null,
  val compactBeforeSendTokens: Long? = null,
  val keepFullHistory: Boolean = false,
  val onToggleKeepFullHistory: () -> Unit = {},
)

/**
 * `ContextWindowMeter.tsx`: a ring showing how full the context window is, red past 90%. Upstream
 * opens its card on hover; here a click does, so the card's "Compact context" can be reached.
 * The card has the usage, a bar when the size is known, the total processed, the cost, the
 * auto-compaction note and, when the provider can, [onCompact].
 */
@Composable
fun ContextWindowMeter(
  usage: ContextWindowSnapshot,
  modelDisplayName: String?,
  onCompact: (() -> Unit)?,
  compactDisabledReason: String?,
) {
  var open: Boolean by remember { mutableStateOf(false) }
  val used: Double = (usage.usedPercentage ?: 0.0).coerceIn(0.0, 100.0)
  val track: Color = JewelTheme.globalColors.text.info.copy(alpha = 0.24f)
  // `color-mix(muted-foreground 72%)`, else `--color-error` when overloaded
  val tone: Color = if (used > 90) {
    JewelTheme.globalColors.text.error
  } else {
    JewelTheme.globalColors.text.info.copy(alpha = 0.72f)
  }
  Box {
    ComposerControl(onClick = { open = !open }, square = true) {
      Canvas(Modifier.size(20.dp)) {
        // r 9.75 and a 3 wide stroke in a 24 box
        val unit: Float = size.width / 24f
        val stroke = Stroke(width = 3f * unit, cap = StrokeCap.Round)
        val inset: Float = (12f - 9.75f) * unit
        val arc = Size(19.5f * unit, 19.5f * unit)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(width = 3f * unit))
        if (used > 0) {
          drawArc(tone, -90f, (used * 3.6).toFloat(), false, topLeft, arc, style = stroke)
        }
      }
    }
    if (open) {
      Popup(
        popupPositionProvider = AboveEnd,
        onDismissRequest = { open = false },
        properties = PopupProperties(focusable = true),
      ) {
        ContextWindowCard(usage, used, tone, modelDisplayName, onCompact, compactDisabledReason)
      }
    }
  }
}

/** Above the anchor, right edges aligned, `side="top" align="end"`. */
private object AboveEnd : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val gap = 8
    val x: Int = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
    val y: Int = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
    return IntOffset(x, y)
  }
}

@Composable
private fun ContextWindowCard(
  usage: ContextWindowSnapshot,
  used: Double,
  tone: Color,
  modelDisplayName: String?,
  onCompact: (() -> Unit)?,
  compactDisabledReason: String?,
) {
  val shape = RoundedCornerShape(8.dp)
  val muted: Color = JewelTheme.globalColors.text.info
  val percentage: String? = formatContextWindowPercentage(usage.usedPercentage)
  Column(
    modifier = Modifier
      .width(256.dp)
      .clip(shape)
      .background(JewelTheme.globalColors.panelBackground)
      .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "Context Window",
        modifier = Modifier.weight(1f),
        style = small(),
        fontWeight = FontWeight.Medium,
        color = muted,
      )
      val amount: String = if (usage.maxTokens != null && percentage != null) {
        val used: String = formatContextWindowTokens(usage.usedTokens)
        "$percentage · $used/${formatContextWindowTokens(usage.maxTokens)}"
      } else {
        formatContextWindowTokens(usage.usedTokens)
      }
      Text(amount, style = small(), color = composerControlLabel())
    }
    if (usage.maxTokens != null) {
      Box(
        Modifier
          .fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(muted.copy(alpha = 0.15f)),
      ) {
        Box(
          Modifier
            .fillMaxWidth((used / 100).toFloat())
            .fillMaxHeight()
            .clip(RoundedCornerShape(3.dp))
            .background(tone),
        )
      }
    }
    val total: Long? = usage.totalProcessedTokens
    if (total != null && total > 0) CardLine("Total processed", formatContextWindowTokens(total))
    usage.cost?.let { cost -> CardLine("Cost", formatContextWindowCost(cost)) }
    if (usage.compactsAutomatically == true) {
      Text(
        text = formatContextWindowCompactionMessage(modelDisplayName, usage.autoCompactThreshold),
        modifier = Modifier.padding(top = 4.dp),
        style = small(),
        fontWeight = FontWeight.Medium,
        color = composerControlLabel(),
      )
    }
    if (onCompact != null) {
      OutlinedButton(
        onClick = onCompact,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        enabled = compactDisabledReason == null,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Glyph("minimize-2", JewelTheme.globalColors.text.normal, 14.dp)
          Text("Compact context")
        }
      }
      compactDisabledReason?.let { reason -> Text(reason, style = small(), color = muted) }
    }
  }
}

@Composable
private fun CardLine(label: String, value: String) {
  Row {
    Text(label, modifier = Modifier.weight(1f), style = small(), color = composerControlLabel())
    Text(value, style = small(), fontWeight = FontWeight.Medium, color = composerControlLabel())
  }
}
