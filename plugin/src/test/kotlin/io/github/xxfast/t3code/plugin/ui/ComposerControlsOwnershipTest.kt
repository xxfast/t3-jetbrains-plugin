package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// `composerControlsOwnership.test.ts`, case for case
class ComposerControlsOwnershipTest {
  private fun resolve(
    matchesDefault: Boolean = true,
    hasTraits: Boolean = true,
    planModeUiEnabled: Boolean = false,
    collapsed: Boolean = false,
    measuredHiddenBlockCount: Int = 0,
  ): ComposerControlsOwnership = resolveComposerControlsOwnership(
    matchesDefault = matchesDefault,
    hasTraits = hasTraits,
    planModeUiEnabled = planModeUiEnabled,
    collapsed = collapsed,
    measuredHiddenBlockCount = measuredHiddenBlockCount,
  )

  @Test
  fun `hides the chip at the default and lets the effort menu answer composer mode`() {
    val result: ComposerControlsOwnership = resolve()
    assertEquals(AccessDisplay.HIDDEN, result.accessDisplay)
    assertFalse(result.showModeBlock)
    assertEquals(emptyList(), result.hiddenBlockIds)
    assertTrue(result.effortOwnsModeShortcut)
  }

  @Test
  fun `shows a labelled chip that owns composer mode when the mode differs`() {
    val result: ComposerControlsOwnership = resolve(matchesDefault = false)
    assertEquals(AccessDisplay.LABEL, result.accessDisplay)
    assertTrue(result.showModeBlock)
    assertFalse(result.effortOwnsModeShortcut)
  }

  @Test
  fun `never leaves an empty overflow menu, the effort chip overflows with the chip`() {
    val result: ComposerControlsOwnership =
      resolve(matchesDefault = false, measuredHiddenBlockCount = 1)
    assertEquals(listOf(ComposerBlock.TRAITS, ComposerBlock.MODE), result.hiddenBlockIds)
    assertTrue(result.overflowShowsPermissions)
    // One composer.mode target: the overflow menu, not the overflowed effort chip.
    assertFalse(result.effortOwnsModeShortcut)
  }

  @Test
  fun `keeps the Plan toggle block at the default and overflows it before the effort chip`() {
    val result: ComposerControlsOwnership =
      resolve(planModeUiEnabled = true, measuredHiddenBlockCount = 1)
    assertTrue(result.showModeBlock)
    assertEquals(listOf(ComposerBlock.MODE), result.hiddenBlockIds)
    assertFalse(result.overflowShowsPermissions)
    assertTrue(result.effortOwnsModeShortcut)
  }

  @Test
  fun `overflows only the chip when the Plan toggle gives the overflow menu content`() {
    val result: ComposerControlsOwnership =
      resolve(matchesDefault = false, planModeUiEnabled = true, measuredHiddenBlockCount = 1)
    assertEquals(listOf(ComposerBlock.MODE), result.hiddenBlockIds)
    // Permissions stay in the visible effort menu, which now answers composer.mode.
    assertFalse(result.overflowShowsPermissions)
    assertTrue(result.effortOwnsModeShortcut)
  }

  @Test
  fun `keeps a permissions icon for models without traits, even at the default`() {
    assertEquals(AccessDisplay.ICON, resolve(hasTraits = false).accessDisplay)
    val overflowed: ComposerControlsOwnership =
      resolve(hasTraits = false, measuredHiddenBlockCount = 1)
    assertEquals(listOf(ComposerBlock.MODE), overflowed.hiddenBlockIds)
    assertTrue(overflowed.overflowShowsPermissions)
  }

  @Test
  fun `shows the non-default chip as an icon in the compacted row`() {
    val result: ComposerControlsOwnership = resolve(matchesDefault = false, collapsed = true)
    assertEquals(AccessDisplay.ICON, result.accessDisplay)
  }
}
