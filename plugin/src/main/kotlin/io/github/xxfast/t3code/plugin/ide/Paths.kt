package io.github.xxfast.t3code.plugin.ide

import com.intellij.openapi.util.SystemInfoRt
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlin.io.path.exists

/** Whether paths compare case-insensitively here, as on Windows and default macOS volumes. */
val IGNORE_CASE: Boolean = !SystemInfoRt.isFileSystemCaseSensitive

/** Resolves [path] against [cwd]. Absolute paths stand. */
fun resolve(cwd: String, path: String): Path {
  val given: Path = Path.of(path)
  val resolved: Path = if (given.isAbsolute) given else Path.of(cwd).resolve(given)
  return resolved.normalize()
}

/**
 * Whether [path] is [root] or inside it, by whole segments after normalising both. Compared as
 * strings, so [ignoreCase] means the same on every OS (`Path` itself folds case on Windows only).
 */
fun isUnder(root: Path, path: Path, ignoreCase: Boolean = IGNORE_CASE): Boolean {
  val outer: List<String> = segments(root, ignoreCase)
  val inner: List<String> = segments(path, ignoreCase)
  return inner.size >= outer.size && inner.subList(0, outer.size) == outer
}

fun samePath(a: Path, b: Path, ignoreCase: Boolean = IGNORE_CASE): Boolean =
  segments(a, ignoreCase) == segments(b, ignoreCase)

private fun segments(path: Path, ignoreCase: Boolean): List<String> {
  val normal: Path = path.normalize()
  val parts: List<String> = listOfNotNull(normal.root?.toString()) + normal.map { it.toString() }
  return if (ignoreCase) parts.map { it.lowercase() } else parts
}

/**
 * Whether the server at [serverUrl] shares this machine's disk, so its paths are paths here. A
 * loopback host says so; good enough while a remote server is a phase 2 concern, when a tunnel to
 * a remote machine would also look like loopback.
 */
fun serverIsLocal(serverUrl: String): Boolean {
  val host: String = try {
    URI(serverUrl).host
  } catch (_: URISyntaxException) {
    null
  }?.lowercase()?.removeSurrounding("[", "]") ?: return false
  return host == "localhost" || host.startsWith("127.") || host == "::1" ||
    host == "0:0:0:0:0:0:0:1"
}

/**
 * The file on this disk that a thread's [resolved] path is, if any. With the server on this
 * machine, any existing path is it, this IDE project's or another's. Otherwise only paths under
 * this IDE project's [base] count; the rest come from the server.
 */
fun localPath(
  resolved: Path,
  base: Path?,
  serverIsLocal: Boolean,
  exists: (Path) -> Boolean = { it.exists() },
): Path? = resolved.takeIf {
  if (serverIsLocal) exists(it) else base != null && isUnder(base, it)
}

/** The thread's working directory: its worktree when it has one, else the project root. */
fun cwdOf(worktreePath: String?, workspaceRoot: String): String = worktreePath ?: workspaceRoot

/** 1-based line from the server to the editor's 0-based line. */
fun editorLine(line: Int?): Int = ((line ?: 1) - 1).coerceAtLeast(0)
