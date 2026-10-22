package io.github.xxfast.t3code.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.github.xxfast.t3code.plugin.actions.T3DataKeys
import org.jetbrains.jewel.foundation.actionSystem.provideData

/**
 * The composer controls upstream's `composer.*` keybindings open (`packages/shared/src/
 * keybindings.ts:202` to 205), each the control marked `data-composer-shortcut` with its name.
 */
enum class ComposerControlKind(val command: String) {
  EFFORT("composer.effort"),
  MODE("composer.mode"),
  WORKSPACE("composer.workspace"),
  BRANCH("composer.branch"),
}

/**
 * What the composer's keyboard shortcuts act on, upstream's `ChatComposerHandle` and the
 * `useChatShortcuts` handlers (ChatView.tsx:5859 to 5893). The focused prompt offers it as
 * [T3DataKeys.COMPOSER]; the `T3Code.Composer.*` actions read it.
 *
 * The controls register how to open themselves while they're on screen ([ComposerShortcutTarget]),
 * which is upstream's `openControl` finding the element by its `data-composer-shortcut`.
 */
class ComposerShortcuts {
  private val openers: MutableMap<ComposerControlKind, () -> Unit> = mutableMapOf()

  /** `toggleModelPicker`; null without a footer. */
  var onToggleModelPicker: (() -> Unit)? = null

  /**
   * `queuedRunsControl.steerNext`: true when it steered. Null while the ChatView has no queued
   * messages to act on.
   */
  var onSteerQueued: (() -> Boolean)? = null

  /** `queuedRunsControl.editLatest`: true when it took the latest queued message back. */
  var onEditQueued: (() -> Boolean)? = null

  /** `isCaretAtStart`: a collapsed caret at offset 0. */
  var isCaretAtStart: () -> Boolean = { false }

  fun canOpen(kind: ComposerControlKind): Boolean = kind in openers

  /** `openControl`: true when a control of [kind] is on screen and was asked to open. */
  fun open(kind: ComposerControlKind): Boolean {
    val opener: () -> Unit = openers[kind] ?: return false
    opener()
    return true
  }

  internal fun register(kind: ComposerControlKind, opener: () -> Unit) {
    openers[kind] = opener
  }

  internal fun unregister(kind: ComposerControlKind, opener: () -> Unit) {
    if (openers[kind] === opener) openers.remove(kind)
  }
}

/**
 * The [ComposerShortcuts] controls register with, or null where they shouldn't: the resting
 * strip's controls while the footer's are on screen, and the other way round.
 */
val LocalComposerShortcuts: ProvidableCompositionLocal<ComposerShortcuts?> =
  compositionLocalOf { null }

/**
 * Lets the composer's [kind] shortcut open this control while it's composed, [enabled] and
 * under a [LocalComposerShortcuts]. [onOpen] opens it.
 */
@Composable
fun ComposerShortcutTarget(kind: ComposerControlKind, enabled: Boolean, onOpen: () -> Unit) {
  val shortcuts: ComposerShortcuts = LocalComposerShortcuts.current ?: return
  if (!enabled) return
  val latest: State<() -> Unit> = rememberUpdatedState(onOpen)
  DisposableEffect(shortcuts, kind) {
    val opener: () -> Unit = { latest.value() }
    shortcuts.register(kind, opener)
    onDispose { shortcuts.unregister(kind, opener) }
  }
}

/**
 * Offers [shortcuts] to the IDE's action system while the prompt has focus, as
 * [attachmentTarget] does for paste: Jewel's `provideData` only answers for a focused node.
 */
fun Modifier.composerShortcutTarget(enabled: Boolean, shortcuts: ComposerShortcuts): Modifier =
  provideData { if (enabled) set(T3DataKeys.COMPOSER.name, shortcuts) }
