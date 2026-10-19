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
  fun `a loopback server shares this disk, anything else doesn't`() {
    assertTrue(serverIsLocal("http://127.0.0.1:3773"))
    assertTrue(serverIsLocal("http://localhost:3773/"))
    assertTrue(serverIsLocal("http://[::1]:3773"))
    assertFalse(serverIsLocal("https://t3.example.com"))
    assertFalse(serverIsLocal("http://192.168.1.20:3773"))
    assertFalse(serverIsLocal("not a url"))
  }

  @Test
  fun `a local server's existing paths are files here, wherever they are`() {
    val base: Path = Path.of("/work/kstore")
    val elsewhere: Path = Path.of("/work/router/Foo.kt")
    assertEquals(elsewhere, localPath(elsewhere, base, serverIsLocal = true) { true })
    assertEquals(null, localPath(elsewhere, base, serverIsLocal = true) { false })
    val inside: Path = Path.of("/work/kstore/Foo.kt")
    assertEquals(null, localPath(inside, base, serverIsLocal = true) { false }, "Not on disk")
  }

  @Test
  fun `a remote server's paths only count inside this IDE project`() {
    val base: Path = Path.of("/work/kstore")
    val inside: Path = Path.of("/work/kstore/Foo.kt")
    assertEquals(inside, localPath(inside, base, serverIsLocal = false) { false })
    assertEquals(null, localPath(Path.of("/work/router/Foo.kt"), base, false) { true })
    assertEquals(null, localPath(inside, base = null, serverIsLocal = false) { true })
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
