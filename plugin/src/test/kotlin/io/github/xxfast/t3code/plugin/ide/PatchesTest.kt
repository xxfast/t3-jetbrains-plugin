package io.github.xxfast.t3code.plugin.ide

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PatchesTest {
  private val before: String = "a\nb\nc\nd\ne\n"
  private val after: String = "a\nb\nC\nd\ne\nf\n"

  private val gitPatch: String = """
    diff --git a/src/Foo.kt b/src/Foo.kt
    index 1111111..2222222 100644
    --- a/src/Foo.kt
    +++ b/src/Foo.kt
    @@ -2,4 +2,5 @@
     b
    -c
    +C
     d
     e
    +f
    diff --git a/src/New.kt b/src/New.kt
    new file mode 100644
    --- /dev/null
    +++ b/src/New.kt
    @@ -0,0 +1,2 @@
    +hello
    +world
    diff --git a/src/Old.kt b/src/Old.kt
    deleted file mode 100644
    --- a/src/Old.kt
    +++ /dev/null
    @@ -1 +0,0 @@
    -bye
  """.trimIndent() + "\n"

  @Test
  fun `parses every file in a git patch`() {
    val patches: List<FilePatch> = parsePatch(gitPatch)
    assertEquals(listOf("src/Foo.kt", "src/New.kt", "src/Old.kt"), patches.map { it.path })
    assertTrue(patches[1].added)
    assertTrue(patches[2].deleted)
    assertEquals(listOf("b", "c", "d", "e"), patches[0].hunks.single().oldLines)
    assertEquals(listOf("b", "C", "d", "e", "f"), patches[0].hunks.single().newLines)
  }

  @Test
  fun `bare hunks take the fallback path`() {
    val patch: FilePatch = parsePatch("@@ -1,2 +1,2 @@\n-x = 0\n+x: Int = 0\n y\n", "Foo.kt")
      .single()
    assertEquals("Foo.kt", patch.path)
    assertEquals(listOf("x = 0", "y"), patch.hunks.single().oldLines)
  }

  @Test
  fun `empty patch has no files`() {
    assertEquals(emptyList(), parsePatch(""))
  }

  @Test
  fun `apply and revert round trip`() {
    val patch: FilePatch = parsePatch(gitPatch).first()
    assertEquals(after, patch.applyTo(before))
    assertEquals(before, patch.revertFrom(after))
  }

  @Test
  fun `hunks that moved still apply`() {
    val patch: FilePatch = parsePatch(gitPatch).first()
    assertEquals("x\ny\n$after", patch.applyTo("x\ny\n$before"))
  }

  @Test
  fun `hunks that don't match give null`() {
    assertNull(parsePatch(gitPatch).first().applyTo("nothing\nlike\nit\n"))
  }

  @Test
  fun `sides undo the patch when the file already has it`() {
    val sides: Sides = parsePatch(gitPatch).first().sides(after)
    assertEquals(Sides(before, after, afterIsBase = true), sides)
  }

  @Test
  fun `sides apply the patch when the file doesn't have it yet`() {
    assertEquals(Sides(before, after), parsePatch(gitPatch).first().sides(before))
  }

  @Test
  fun `sides fall back to excerpts`() {
    val sides: Sides = parsePatch(gitPatch).first().sides(null)
    assertEquals(Sides("b\nc\nd\ne", "b\nC\nd\ne\nf"), sides)
  }

  @Test
  fun `added and deleted files have one side`() {
    val patches: List<FilePatch> = parsePatch(gitPatch)
    assertEquals(Sides(null, "hello\nworld"), patches[1].sides(null))
    assertEquals(Sides("bye", null), patches[2].sides(null))
  }

  @Test
  fun `edit before puts the old string back`() {
    assertEquals("val x = 0\n", editBefore("val x: Int = 0\n", "x = 0", "x: Int = 0"))
    assertEquals("", editBefore("new file", null, "new file"))
    assertNull(editBefore("unrelated", "a", "b"))
  }
}
