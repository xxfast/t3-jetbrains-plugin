package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** Expected values come from running upstream's projectIdentity.ts on the same titles. */
class ProjectIdentityTest {
  @Test
  fun `monograms match upstream`() {
    assertEquals("T3", projectMonogram("t3-jetbrains-plugin"))
    assertEquals("KE", projectMonogram("KStore"))
    assertEquals("DR", projectMonogram("Decompose-Router"))
    assertEquals("KN", projectMonogram("kotlin-native-nuget"))
    assertEquals("CD", projectMonogram("Cupboard"))
    assertEquals("XX", projectMonogram("x"))
    assertEquals("R0", projectMonogram("Release 0.10.0"))
    assertEquals("PR", projectMonogram("--"))
  }

  @Test
  fun `colours match upstream`() {
    assertEquals("purple", projectColorName("t3-jetbrains-plugin"))
    assertEquals("yellow", projectColorName("KStore"))
    assertEquals("cyan", projectColorName("Decompose-Router"))
    assertEquals("cyan", projectColorName("kotlin-native-nuget"))
    assertEquals("gray", projectColorName("--"))
    assertEquals("orange", projectColorName("Cupboard"))
    assertEquals("indigo", projectColorName("x"))
    assertEquals("pink", projectColorName("Release 0.10.0"))
  }
}
