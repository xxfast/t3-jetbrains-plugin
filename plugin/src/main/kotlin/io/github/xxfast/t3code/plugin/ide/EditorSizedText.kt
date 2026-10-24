package io.github.xxfast.t3code.plugin.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.LocalTypography
import org.jetbrains.jewel.ui.Typography
import org.jetbrains.jewel.ui.typography

/**
 * Text at the editor's font size, for content that sits in the editor area next to code. The UI
 * font stays; every style scales by the editor size over the label size, so captions and headings
 * keep their offsets.
 */
@Composable
fun EditorSizedText(content: @Composable () -> Unit) {
  // The bridge reads the scheme on every composition but only recomposes on a LaF change
  var revision: Int by remember { mutableIntStateOf(0) }
  DisposableEffect(Unit) {
    val connection = ApplicationManager.getApplication().messageBus.connect()
    connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { revision++ })
    connection.subscribe(UISettingsListener.TOPIC, UISettingsListener { revision++ })
    onDispose { connection.disconnect() }
  }

  revision
  val base: Typography = JewelTheme.typography
  val editor: TextUnit = base.editorTextStyle.fontSize
  val label: TextUnit = base.labelTextSize
  if (!editor.isSp || !label.isSp || label.value == 0f) return content()

  val typography = ScaledTypography(base, editor.value / label.value)
  CompositionLocalProvider(
    LocalTypography provides typography,
    LocalTextStyle provides JewelTheme.defaultTextStyle.scaled(typography.ratio),
    content = content,
  )
}

private class ScaledTypography(private val base: Typography, val ratio: Float) : Typography {
  override val labelTextStyle: TextStyle @Composable get() = base.labelTextStyle.scaled(ratio)
  override val labelTextSize: TextUnit @Composable get() = base.labelTextSize * ratio
  override val h0TextStyle: TextStyle @Composable get() = base.h0TextStyle.scaled(ratio)
  override val h1TextStyle: TextStyle @Composable get() = base.h1TextStyle.scaled(ratio)
  override val h2TextStyle: TextStyle @Composable get() = base.h2TextStyle.scaled(ratio)
  override val h3TextStyle: TextStyle @Composable get() = base.h3TextStyle.scaled(ratio)
  override val h4TextStyle: TextStyle @Composable get() = base.h4TextStyle.scaled(ratio)
  override val regular: TextStyle @Composable get() = base.regular.scaled(ratio)
  override val medium: TextStyle @Composable get() = base.medium.scaled(ratio)
  override val small: TextStyle @Composable get() = base.small.scaled(ratio)

  // Already at the editor's size
  override val editorTextStyle: TextStyle @Composable get() = base.editorTextStyle
  override val consoleTextStyle: TextStyle @Composable get() = base.consoleTextStyle
}

private fun TextStyle.scaled(ratio: Float): TextStyle = copy(
  fontSize = if (fontSize.isSp) fontSize * ratio else fontSize,
  lineHeight = if (lineHeight.isSp) lineHeight * ratio else lineHeight,
)
