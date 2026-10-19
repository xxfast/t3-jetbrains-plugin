package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/** A dim, centred line of text filling the space it's given. */
@Composable
fun Hint(text: String) {
  Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
    Text(text, color = JewelTheme.globalColors.text.info, textAlign = TextAlign.Center)
  }
}

/** Wall clock time, ticking every [periodMillis]. */
@Composable
fun rememberNow(periodMillis: Long): State<Instant> = produceState(Instant.now(), periodMillis) {
  while (true) {
    value = Instant.now()
    delay(periodMillis)
  }
}

/** A fill derived from the text colour, so it reads on light and dark themes alike. */
@Composable
fun subtle(alpha: Float): Color = JewelTheme.globalColors.text.normal.copy(alpha = alpha)

/** The accent: the focus outline, since the bridge theme's focused border is transparent. */
@Composable
fun accent(): Color = JewelTheme.globalColors.outlines.focused
