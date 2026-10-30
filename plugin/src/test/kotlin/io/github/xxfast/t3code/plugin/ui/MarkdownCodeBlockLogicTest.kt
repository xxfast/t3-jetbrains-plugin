package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.jewel.foundation.code.MimeType
import org.junit.jupiter.api.Test

class MarkdownCodeBlockLogicTest {
  @Test
  fun `a fence's language is its first word`() {
    assertEquals("kotlin", extractFenceLanguage("kotlin"))
    assertEquals("ts", extractFenceLanguage("  ts title=\"fileLinks.ts\""))
    assertEquals("sh", extractFenceLanguage("sh\t# from the repo root"))
  }

  @Test
  fun `a fence without a language is text, and gitignore reads as ini`() {
    assertEquals("text", extractFenceLanguage(""))
    assertEquals("text", extractFenceLanguage("   "))
    assertEquals("ini", extractFenceLanguage("gitignore"))
  }

  @Test
  fun `the processor gets the fence's language as written, plain text none`() {
    assertEquals(MimeType("tsx"), fenceMimeType("tsx"))
    assertEquals(MimeType("brainfuck"), fenceMimeType("brainfuck"))
    assertNull(fenceMimeType(""))
    assertNull(fenceMimeType("text"))
  }
}
