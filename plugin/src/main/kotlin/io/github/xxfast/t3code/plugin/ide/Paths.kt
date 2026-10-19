package io.github.xxfast.t3code.plugin.ide

import java.nio.file.Path

/** Resolves [path] against [cwd]. Absolute paths stand. */
fun resolve(cwd: String, path: String): Path {
  val given: Path = Path.of(path)
  val resolved: Path = if (given.isAbsolute) given else Path.of(cwd).resolve(given)
  return resolved.normalize()
}

/** Whether [path] is [root] or inside it, after normalising both. */
fun isUnder(root: Path, path: Path): Boolean = path.normalize().startsWith(root.normalize())

/** The thread's working directory: its worktree when it has one, else the project root. */
fun cwdOf(worktreePath: String?, workspaceRoot: String): String = worktreePath ?: workspaceRoot

/** 1-based line from the server to the editor's 0-based line. */
fun editorLine(line: Int?): Int = ((line ?: 1) - 1).coerceAtLeast(0)
