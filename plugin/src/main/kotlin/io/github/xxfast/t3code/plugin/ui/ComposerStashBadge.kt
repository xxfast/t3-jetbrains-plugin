package io.github.xxfast.t3code.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import javax.swing.JComponent
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography

/**
 * Upstream's `ComposerStashBadge`: "Stash" and the count, at the right over the composer, only
 * while something is stashed. A click opens the stash above it.
 */
@Composable
fun ComposerStashBadge(count: Int, onOpen: (at: RelativePoint) -> Unit) {
  if (count == 0) return
  val component: JComponent = LocalComponent.current
  val density: Density = LocalDensity.current
  var origin: Offset by remember { mutableStateOf(Offset.Zero) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp)
      .onGloballyPositioned { coordinates -> origin = coordinates.positionInRoot() },
    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(AllIconsKeys.FileTypes.Text, contentDescription = null)
    Link(
      text = "Stash $count",
      onClick = {
        // Compose pixels to the hosting component's Swing coordinates
        val point = Point(
          (origin.x / density.density).toInt(),
          (origin.y / density.density).toInt(),
        )
        onOpen(RelativePoint(component, point))
      },
      textStyle = JewelTheme.typography.small,
    )
  }
}
