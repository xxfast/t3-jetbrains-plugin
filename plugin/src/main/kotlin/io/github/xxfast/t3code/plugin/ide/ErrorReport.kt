package io.github.xxfast.t3code.plugin.ide

import java.net.URLEncoder

/** Where "Report to T3 Code" in the IDE's error dialog sends people. */
const val ERROR_REPORT_ISSUES_URL: String =
  "https://github.com/xxfast/t3-jetbrains-plugin/issues/new"

/** Browsers and GitHub both cope with a URL this long; past it the trace moves to the clipboard. */
const val ERROR_REPORT_URL_LIMIT: Int = 8_000

/** Frames to keep after the last one of ours. The IDE's dispatch is where the bulk is. */
const val ERROR_REPORT_FRAMES_AFTER_OWN: Int = 3

private const val OWN_PACKAGE: String = "io.github.xxfast"
private const val TITLE_LIMIT: Int = 120

/** What the IDE knows about where it crashed, as plain strings so the builder has no IDE types. */
data class ErrorReportEnvironment(
  val pluginVersion: String,
  val ide: String,
  val os: String,
  val jbr: String,
)

/**
 * The prefilled issue link, and the text to put on the clipboard when the trace didn't fit in it.
 */
data class ErrorReportUrl(val url: String, val clipboard: String?)

/** The exception's simple class name and message, cut to a line. */
fun errorReportTitle(trace: String): String {
  val header: String = trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Error"
  val colon: Int = header.indexOf(": ")
  val className: String = if (colon >= 0) header.substring(0, colon) else header
  val message: String? = if (colon >= 0) header.substring(colon + 2).trim() else null
  val simpleName: String = className.substringAfterLast('.')
  val title: String = if (message.isNullOrEmpty()) simpleName else "$simpleName: $message"
  return if (title.length <= TITLE_LIMIT) title else title.take(TITLE_LIMIT - 1) + "…"
}

/**
 * Keeps every frame up to the last [OWN_PACKAGE] one plus [keepAfter] after it, for the exception
 * and each `Caused by`. A section with none of our frames keeps its first [keepAfter]. What's cut
 * is replaced by one "(N frames trimmed)" line.
 */
fun trimTrace(trace: String, keepAfter: Int = ERROR_REPORT_FRAMES_AFTER_OWN): String {
  val out: MutableList<String> = mutableListOf()
  val frames: MutableList<String> = mutableListOf()

  fun flush() {
    if (frames.isEmpty()) return
    val lastOwn: Int = frames.indexOfLast { it.trimStart().startsWith("at ") && OWN_PACKAGE in it }
    val wanted: Int = if (lastOwn >= 0) lastOwn + 1 + keepAfter else keepAfter
    val keep: Int = wanted.coerceAtMost(frames.size)
    out += frames.take(keep)
    if (keep < frames.size) out += "\t... (${frames.size - keep} frames trimmed)"
    frames.clear()
  }

  for (line in trace.lines()) {
    if (line.startsWith("\t") || line.startsWith("    at ") || line.startsWith("    ... ")) {
      frames += line
    } else {
      flush()
      if (line.isNotBlank() || out.isNotEmpty()) out += line
    }
  }
  flush()
  return out.joinToString("\n").trimEnd()
}

/**
 * Builds the issue link. The body carries [comment], the [environment] and the trimmed [trace].
 * When that goes past [limit], the body keeps only the exception lines, the full trace goes to
 * the clipboard, and the body says so.
 */
fun errorReportUrl(
  trace: String,
  environment: ErrorReportEnvironment,
  comment: String? = null,
  limit: Int = ERROR_REPORT_URL_LIMIT,
): ErrorReportUrl {
  val title: String = errorReportTitle(trace)

  fun body(traceText: String, note: String?): String = buildString {
    comment?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
    append("**Plugin** ").append(environment.pluginVersion).append('\n')
    append("**IDE** ").append(environment.ide).append('\n')
    append("**OS** ").append(environment.os).append('\n')
    append("**JBR** ").append(environment.jbr).append("\n\n")
    note?.let { append(it).append("\n\n") }
    append("```\n").append(traceText).append("\n```")
  }

  fun url(bodyText: String): String {
    val query: String = listOf("title" to title, "labels" to "bug", "body" to bodyText)
      .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    return "$ERROR_REPORT_ISSUES_URL?$query"
  }

  val full: String = url(body(trimTrace(trace), null))
  if (full.length <= limit) return ErrorReportUrl(full, clipboard = null)

  val headers: String = trace.lines()
    .filter { it.isNotBlank() && !it.startsWith("\t") && !it.startsWith("    ") }
    .joinToString("\n")
  val short: String = url(
    body(headers, "The trace didn't fit in the link. Full trace on your clipboard, paste it here."),
  )
  return ErrorReportUrl(short, clipboard = trace)
}
