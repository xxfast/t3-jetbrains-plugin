package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.instance
import io.github.xxfast.t3code.plugin.ui.slug
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.Command
import io.github.xxfast.t3code.protocol.generated.ModelSelection

/** The thread settings the composer's footer picks. */
enum class ThreadSetting { RUNTIME_MODE, INTERACTION_MODE, MODEL_SELECTION }

/** A picked value, and the sequence its dispatch answered once it has. */
data class PendingSetting(val value: Any, val sequence: Long? = null)

/**
 * The thread settings picked in the composer that the thread row may not show yet. A pick shows
 * at once and stays until its dispatch is answered; then it gives way once the row agrees or the
 * view is past that sequence. A refusal takes it back. The server owns the values; this only
 * bridges the round trip.
 */
data class PendingSettings(val picks: Map<ThreadSetting, PendingSetting> = emptyMap()) {
  /** [command] is on its way. Anything that isn't a thread setting changes nothing. */
  fun picked(command: Command): PendingSettings {
    val (setting: ThreadSetting, value: Any) = command.pick() ?: return this
    return copy(picks = picks + (setting to PendingSetting(value)))
  }

  /** The server took [command] at [sequence]. A newer pick of the same setting stays as is. */
  fun delivered(command: Command, sequence: Long): PendingSettings =
    update(command) { pick -> pick.copy(sequence = sequence) }

  /** The server refused [command]: show the row's value again, unless something newer is picked. */
  fun failed(command: Command): PendingSettings = update(command) { null }

  /**
   * Drops the delivered picks [thread] agrees with or the view at [sequence] is past. An
   * undelivered pick always stays: the row may agree only because an earlier pick hasn't landed.
   */
  fun reconciled(thread: Thread, sequence: Long): PendingSettings {
    val left: Map<ThreadSetting, PendingSetting> = picks.filterNot { (setting, pick) ->
      val at: Long = pick.sequence ?: return@filterNot false
      sequence >= at || setting.shows(thread, pick.value)
    }
    return if (left.size == picks.size) this else copy(picks = left)
  }

  fun runtimeMode(thread: Thread): String =
    picks[ThreadSetting.RUNTIME_MODE]?.value as? String ?: thread.runtimeMode

  fun interactionMode(thread: Thread): String =
    picks[ThreadSetting.INTERACTION_MODE]?.value as? String ?: thread.interactionMode

  fun modelSelection(thread: Thread): ModelSelection =
    picks[ThreadSetting.MODEL_SELECTION]?.value as? ModelSelection ?: thread.modelSelection

  /** Applies [change] to the pick [command] made, if it's still the one picked. */
  private fun update(
    command: Command,
    change: (PendingSetting) -> PendingSetting?,
  ): PendingSettings {
    val (setting: ThreadSetting, value: Any) = command.pick() ?: return this
    val pick: PendingSetting = picks[setting]?.takeIf { it.value == value } ?: return this
    val next: PendingSetting? = change(pick)
    return copy(picks = if (next == null) picks - setting else picks + (setting to next))
  }
}

/** The setting [this] command sets, and to what. */
private fun Command.pick(): Pair<ThreadSetting, Any>? = when (this) {
  is Command.ThreadRuntimeModeSet -> ThreadSetting.RUNTIME_MODE to runtimeMode
  is Command.ThreadInteractionModeSet -> ThreadSetting.INTERACTION_MODE to interactionMode
  is Command.ThreadModelSelectionSet -> ThreadSetting.MODEL_SELECTION to modelSelection
  is Command.ProviderSwitch -> ThreadSetting.MODEL_SELECTION to modelSelection
  else -> null
}

/** Whether [thread]'s row shows [value] for this setting. Models compare by instance and slug. */
private fun ThreadSetting.shows(thread: Thread, value: Any): Boolean = when (this) {
  ThreadSetting.RUNTIME_MODE -> thread.runtimeMode == value
  ThreadSetting.INTERACTION_MODE -> thread.interactionMode == value
  ThreadSetting.MODEL_SELECTION -> (value as? ModelSelection)?.let { picked ->
    picked.instance == thread.modelSelection.instance && picked.slug == thread.modelSelection.slug
  } == true
}
