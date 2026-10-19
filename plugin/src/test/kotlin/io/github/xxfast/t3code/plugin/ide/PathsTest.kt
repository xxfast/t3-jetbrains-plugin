package io.github.xxfast.t3code.plugin.ide

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PathsTest {
  @Test
  fun `relative paths resolve against cwd`() {
    assertEquals(Path.of("/work/kstore/src/Foo.kt"), resolve("/work/kstore", "src/Foo.kt"))
  }

  @Test
  fun `absolute paths stand`() {
    assertEquals(Path.of("/elsewhere/Foo.kt"), resolve("/work/kstore", "/elsewhere/Foo.kt"))
  }

  @Test
  fun `dot segments normalise`() {
    assertEquals(Path.of("/work/other/Foo.kt"), resolve("/work/kstore", "../other/./Foo.kt"))
  }

  @Test
  fun `under root`() {
    assertTrue(isUnder(Path.of("/work/kstore"), Path.of("/work/kstore/src/Foo.kt")))
    assertTrue(isUnder(Path.of("/work/kstore"), Path.of("/work/kstore")))
  }

  @Test
  fun `sibling with a shared prefix is not under root`() {
    assertFalse(isUnder(Path.of("/work/kstore"), Path.of("/work/kstore-file/Foo.kt")))
  }

  @Test
  fun `escaping with dot dot is not under root`() {
    assertFalse(isUnder(Path.of("/work/kstore"), Path.of("/work/kstore/../router/Foo.kt")))
  }

  @Test
  fun `worktree wins over workspace root`() {
    assertEquals("/wt/a", cwdOf("/wt/a", "/work/kstore"))
    assertEquals("/work/kstore", cwdOf(null, "/work/kstore"))
  }

  @Test
  fun `server lines are 1-based, editor lines 0-based`() {
    assertEquals(0, editorLine(null))
    assertEquals(0, editorLine(1))
    assertEquals(41, editorLine(42))
    assertEquals(0, editorLine(0))
  }
}
