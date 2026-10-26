package io.github.xxfast.t3code.cli

import io.github.xxfast.t3code.protocol.Credential
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What `t3 pair` saves and the other commands connect with. */
@Serializable
data class CliConfig(
  val server: String,
  val accessToken: String,
  /** ISO instant, absent when the server didn't say. */
  val expiresAt: String? = null,
)

val DefaultConfigPath: Path = Path.of(System.getProperty("user.home"), ".t3code-cli.json")

private val ConfigJson: Json = Json {
  prettyPrint = true
  ignoreUnknownKeys = true
}

fun configOf(server: String, credential: Credential, now: Instant): CliConfig = CliConfig(
  server = server,
  accessToken = credential.accessToken,
  expiresAt = credential.expiresInSeconds?.let { now.plusSeconds(it).toString() },
)

fun CliConfig.expired(now: Instant): Boolean =
  expiresAt?.let { !Instant.parse(it).isAfter(now) } ?: false

fun readConfig(path: Path): CliConfig? =
  if (path.exists()) ConfigJson.decodeFromString(CliConfig.serializer(), path.readText()) else null

/** Owner-only (0600) before the token goes in, where the file system has POSIX permissions. */
fun writeConfig(path: Path, config: CliConfig) {
  if (!path.exists()) path.createFile()
  if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
  }
  path.writeText(ConfigJson.encodeToString(CliConfig.serializer(), config))
}
