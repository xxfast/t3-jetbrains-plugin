package io.github.xxfast.t3code.plugin.ide

/** One `@@` hunk. [oldLines] is context plus removals, [newLines] is context plus additions. */
data class Hunk(
  val oldStart: Int,
  val newStart: Int,
  val oldLines: List<String>,
  val newLines: List<String>,
)

/** One file in a unified patch. A `null` path is `/dev/null`: an added or deleted file. */
data class FilePatch(val oldPath: String?, val newPath: String?, val hunks: List<Hunk>) {
  val path: String get() = newPath ?: oldPath ?: "unknown"
  val added: Boolean get() = oldPath == null
  val deleted: Boolean get() = newPath == null
}

/** Before and after text for one file, ready for a diff viewer. `null` means the side is absent. */
data class Sides(val before: String?, val after: String?, val afterIsBase: Boolean = false)

private val HUNK_HEADER: Regex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")
private val GIT_HEADER: Regex = Regex("""^diff --git a/(.+) b/(.+)$""")

/**
 * Parses a unified patch: a full `git diff` with any number of files, or bare hunks for one file,
 * in which case [fallbackPath] names it. Not a general patch tool, just enough to show a diff.
 */
fun parsePatch(text: String, fallbackPath: String? = null): List<FilePatch> {
  val lines: List<String> = text.lines()
  val patches: MutableList<FilePatch> = mutableListOf()
  var oldPath: String? = fallbackPath
  var newPath: String? = fallbackPath
  var hunks: MutableList<Hunk> = mutableListOf()
  var started = false

  fun flush() {
    if (started) patches += FilePatch(oldPath, newPath, hunks.toList())
    hunks = mutableListOf()
    started = false
  }

  var i = 0
  while (i < lines.size) {
    val line: String = lines[i]
    val git: MatchResult? = GIT_HEADER.matchEntire(line)
    val header: MatchResult? = HUNK_HEADER.matchEntire(line)
    when {
      git != null -> {
        flush()
        oldPath = git.groupValues[1]
        newPath = git.groupValues[2]
        started = true
      }

      line.startsWith("--- ") -> {
        if (hunks.isNotEmpty()) flush()
        oldPath = patchPath(line.removePrefix("--- "))
        started = true
      }

      line.startsWith("+++ ") -> {
        newPath = patchPath(line.removePrefix("+++ "))
        started = true
      }

      line.startsWith("new file mode") -> oldPath = null
      line.startsWith("deleted file mode") -> newPath = null
      line.startsWith("rename from ") -> oldPath = line.removePrefix("rename from ")
      line.startsWith("rename to ") -> newPath = line.removePrefix("rename to ")

      header != null -> {
        started = true
        val oldCount: Int = header.groupValues[2].ifEmpty { "1" }.toInt()
        val newCount: Int = header.groupValues[4].ifEmpty { "1" }.toInt()
        val old: MutableList<String> = mutableListOf()
        val new: MutableList<String> = mutableListOf()
        while (i + 1 < lines.size && (old.size < oldCount || new.size < newCount)) {
          val body: String = lines[i + 1]
          when {
            body.startsWith("\\") -> Unit
            body.startsWith("-") -> old += body.substring(1)
            body.startsWith("+") -> new += body.substring(1)
            body.startsWith(" ") || body.isEmpty() -> {
              old += body.drop(1)
              new += body.drop(1)
            }

            else -> break
          }
          i++
        }
        hunks += Hunk(header.groupValues[1].toInt(), header.groupValues[3].toInt(), old, new)
      }
    }
    i++
  }
  flush()
  return patches
}

private fun patchPath(raw: String): String? {
  val path: String = raw.substringBefore('\t').trim()
  return when {
    path == "/dev/null" -> null
    path.startsWith("a/") || path.startsWith("b/") -> path.substring(2)
    else -> path
  }
}

/** [base] with every hunk applied, or `null` when a hunk's old side isn't found in it. */
fun FilePatch.applyTo(base: String): String? = replay(base, hunks)

/** [base] with every hunk undone, or `null` when a hunk's new side isn't found in it. */
fun FilePatch.revertFrom(base: String): String? =
  replay(base, hunks.map { hunk -> hunk.reversed() })

private fun Hunk.reversed(): Hunk = Hunk(newStart, oldStart, newLines, oldLines)

/** Old side of every hunk, `...` between them. What we show when no full text fits. */
fun FilePatch.oldExcerpt(): String = excerpt(hunks.map { it.oldLines })

fun FilePatch.newExcerpt(): String = excerpt(hunks.map { it.newLines })

/**
 * Before and after for this patch given the file's current text, if we have it. The run usually
 * already wrote to disk, so undoing the patch on [current] comes first; applying it is second;
 * hunk excerpts are the honest fallback.
 */
fun FilePatch.sides(current: String?): Sides = when {
  added -> Sides(before = null, after = newExcerpt())
  deleted -> Sides(before = oldExcerpt(), after = null)
  current == null -> Sides(oldExcerpt(), newExcerpt())
  else -> revertFrom(current)?.let { before -> Sides(before, current, afterIsBase = true) }
    ?: applyTo(current)?.let { after -> Sides(current, after) }
    ?: Sides(oldExcerpt(), newExcerpt())
}

/**
 * Before text for an `oldStr`/`newStr` edit given the file's [current] text, by putting [oldStr]
 * back where [newStr] is. `null` when the edit can't be located.
 */
fun editBefore(current: String, oldStr: String?, newStr: String?): String? = when {
  newStr.isNullOrEmpty() -> null
  oldStr == null -> if (current == newStr) "" else null
  else -> current.indexOf(newStr).takeIf { it >= 0 }
    ?.let { at -> current.replaceRange(at, at + newStr.length, oldStr) }
}

private fun excerpt(blocks: List<List<String>>): String =
  blocks.joinToString("\n...\n") { block -> block.joinToString("\n") }

private fun replay(base: String, hunks: List<Hunk>): String? {
  val trailingNewline: Boolean = base.endsWith("\n")
  val lines: MutableList<String> = base.removeSuffix("\n").split("\n").toMutableList()
  if (base.isEmpty()) lines.clear()
  var cursor = 0
  var offset = 0
  for (hunk in hunks) {
    val expected: Int = (hunk.oldStart - 1 + offset).coerceIn(0, lines.size)
    val at: Int = if (hunk.oldLines.isEmpty()) {
      // `@@ -5,0 +6,2 @@` inserts after line 5, which is index 5
      (hunk.oldStart + offset).coerceIn(cursor, lines.size)
    } else {
      (cursor..lines.size - hunk.oldLines.size)
        .filter { start -> lines.subList(start, start + hunk.oldLines.size) == hunk.oldLines }
        .minByOrNull { start -> kotlin.math.abs(start - expected) }
        ?: return null
    }
    repeat(hunk.oldLines.size) { lines.removeAt(at) }
    lines.addAll(at, hunk.newLines)
    cursor = at + hunk.newLines.size
    offset += hunk.newLines.size - hunk.oldLines.size
  }
  if (lines.isEmpty()) return ""
  val joined: String = lines.joinToString("\n")
  return if (trailingNewline || base.isEmpty()) "$joined\n" else joined
}
