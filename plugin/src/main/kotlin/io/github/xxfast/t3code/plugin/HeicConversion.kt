package io.github.xxfast.t3code.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val LOG: Logger = Logger.getInstance("io.github.xxfast.t3code.plugin.HeicConversion")

/** How long a conversion may take before the photo counts as unreadable. */
private const val HEIC_CONVERSION_SECONDS: Long = 30

/**
 * [source], a HEIC or HEIF photo, as JPEG bytes, or null when it can't be converted. Upstream's
 * `prepareImageForAttachment` (apps/web/src/lib/imageCompression.ts:433) decodes it in the
 * browser with `heic-to`, libheif in WebAssembly, at quality 0.92. The JVM has no HEIC decoder,
 * so the plugin asks macOS's `sips` for the same JPEG. Elsewhere there's nothing to ask and the
 * photo is refused as unreadable, upstream's message for a failed conversion. Blocks: call it
 * off the EDT.
 */
fun convertHeicToJpeg(source: Path): ByteArray? {
  if (!SystemInfo.isMac) return null
  val target: Path = Files.createTempFile("t3code-heic", ".jpg")
  return try {
    val process: Process = ProcessBuilder(
      "/usr/bin/sips",
      "-s", "format", "jpeg",
      "-s", "formatOptions", "92",
      source.toString(),
      "--out", target.toString(),
    )
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    if (!process.waitFor(HEIC_CONVERSION_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return null
    }
    if (process.exitValue() != 0) return null
    Files.readAllBytes(target).takeIf { it.isNotEmpty() }
  } catch (e: Exception) {
    LOG.info("Couldn't convert $source from HEIC", e)
    null
  } finally {
    Files.deleteIfExists(target)
  }
}
