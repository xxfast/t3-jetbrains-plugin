package io.github.xxfast.t3code.plugin.ui

// Where the permissions control lives, ported from upstream's
// `apps/web/src/components/chat/composerControlsOwnership.ts`.

/** How the permissions chip shows, `accessDisplay`. */
enum class AccessDisplay { LABEL, ICON, HIDDEN }

/** A footer control group, upstream's resting block ids. */
enum class ComposerBlock { TRAITS, MODE }

/** What [resolveComposerControlsOwnership] answers. */
data class ComposerControlsOwnership(
  val accessDisplay: AccessDisplay,
  val showModeBlock: Boolean,
  val hiddenBlockCount: Int,
  val hiddenBlockIds: List<ComposerBlock>,
  val traitsHidden: Boolean,
  val modeHidden: Boolean,
  /**
   * The effort chip answers composer.mode while the chip is hidden or overflowed, until the
   * effort chip overflows too; then the overflow menu (which holds Permissions) answers it.
   */
  val effortOwnsModeShortcut: Boolean,
  val overflowShowsPermissions: Boolean,
)

/**
 * `resolveComposerControlsOwnership`: where the permissions control lives, which blocks
 * overflow, and which menu answers the composer.mode shortcut. Blocks are ordered
 * [traits, mode]; the last block overflows first. The rule: Permissions have exactly one visible
 * home and composer.mode exactly one target.
 *
 * [matchesDefault]: permissions hide while the mode is the default, or while the new-thread
 * strip names it. [hasTraits]: the model has an effort (traits) menu, which then always holds
 * Permissions. [measuredHiddenBlockCount]: how many trailing blocks the width measurement moved
 * into the overflow menu.
 */
fun resolveComposerControlsOwnership(
  matchesDefault: Boolean,
  hasTraits: Boolean,
  planModeUiEnabled: Boolean,
  collapsed: Boolean,
  measuredHiddenBlockCount: Int,
): ComposerControlsOwnership {
  val accessDisplay: AccessDisplay = when {
    !matchesDefault -> if (collapsed) AccessDisplay.ICON else AccessDisplay.LABEL
    // Without traits there is no effort menu to hold Permissions, so keep an icon.
    hasTraits -> AccessDisplay.HIDDEN
    else -> AccessDisplay.ICON
  }
  val showModeBlock: Boolean = !(accessDisplay == AccessDisplay.HIDDEN && !planModeUiEnabled)
  val blockIds: List<ComposerBlock> = buildList {
    if (hasTraits) add(ComposerBlock.TRAITS)
    if (showModeBlock) add(ComposerBlock.MODE)
  }
  fun hiddenFor(count: Int): List<ComposerBlock> = blockIds.drop(blockIds.size - count)
  fun overflowShowsPermissionsFor(hidden: List<ComposerBlock>): Boolean =
    (if (hasTraits) ComposerBlock.TRAITS else ComposerBlock.MODE) in hidden
  fun overflowHasContent(hidden: List<ComposerBlock>): Boolean =
    ComposerBlock.TRAITS in hidden ||
      (planModeUiEnabled && ComposerBlock.MODE in hidden) ||
      overflowShowsPermissionsFor(hidden)

  var hiddenBlockCount: Int = minOf(measuredHiddenBlockCount, blockIds.size)
  // An overflow menu with nothing in it must not exist: overflow one more block instead.
  while (
    hiddenBlockCount > 0 &&
    hiddenBlockCount < blockIds.size &&
    !overflowHasContent(hiddenFor(hiddenBlockCount))
  ) {
    hiddenBlockCount += 1
  }
  val hiddenBlockIds: List<ComposerBlock> = hiddenFor(hiddenBlockCount)
  val modeHidden: Boolean = ComposerBlock.MODE in hiddenBlockIds
  val traitsHidden: Boolean = ComposerBlock.TRAITS in hiddenBlockIds
  return ComposerControlsOwnership(
    accessDisplay = accessDisplay,
    showModeBlock = showModeBlock,
    hiddenBlockCount = hiddenBlockCount,
    hiddenBlockIds = hiddenBlockIds,
    traitsHidden = traitsHidden,
    modeHidden = modeHidden,
    effortOwnsModeShortcut = (matchesDefault || modeHidden) && !traitsHidden,
    overflowShowsPermissions = overflowShowsPermissionsFor(hiddenBlockIds),
  )
}
