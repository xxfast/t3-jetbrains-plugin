package io.github.xxfast.t3code.cli

import io.github.xxfast.t3code.protocol.Credential
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ConfigTest {
  @TempDir
  lateinit var directory: Path

  private val now: Instant = Instant.parse("2026-10-27T00:00:00Z")

  @Test
  fun roundTrips() {
    val path: Path = directory.resolve("config.json")
    val credential = Credential(accessToken = "token", expiresInSeconds = 2_592_000)
    val config: CliConfig = configOf("http://127.0.0.1:3773", credential, now)
    assertEquals("2026-11-26T00:00:00Z", config.expiresAt)

    writeConfig(path, config)
    assertEquals(config, readConfig(path))
    assertTrue("\"accessToken\": \"token\"" in path.readText())

    // Writing again replaces, it doesn't append
    writeConfig(path, config.copy(accessToken = "other"))
    assertEquals("other", readConfig(path)?.accessToken)
  }

  @Test
  fun missingFileIsNotPaired() {
    assertNull(readConfig(directory.resolve("absent.json")))
  }

  @Test
  fun expiry() {
    val config = CliConfig(server = "s", accessToken = "t", expiresAt = "2026-11-18T00:00:00Z")
    assertFalse(config.expired(now))
    assertTrue(config.expired(Instant.parse("2026-11-18T00:00:00Z")))
    assertFalse(config.copy(expiresAt = null).expired(now))
  }
}
