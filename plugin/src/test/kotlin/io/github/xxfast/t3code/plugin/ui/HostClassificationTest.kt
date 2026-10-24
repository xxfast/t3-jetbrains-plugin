package io.github.xxfast.t3code.plugin.ui

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Upstream's `packages/shared/src/hostClassification.test.ts`, case for case. */
class HostClassificationTest {
  private fun public(vararg hosts: String) =
    hosts.forEach { assertTrue(isPublicFaviconHost(it), it) }

  private fun private(vararg hosts: String) =
    hosts.forEach { assertFalse(isPublicFaviconHost(it), it) }

  @Test
  fun `treats public hosts as public`() = public(
    "github.com", "www.google.com", "t3.chat", "sub.domain.example.co.uk", "8.8.8.8", "1.1.1.1",
    "100.200.1.1", "172.32.0.1", "192.167.1.1", "11.0.0.1",
  )

  @Test
  fun `detects private IPv4 ranges`() = private(
    "0.0.0.0", "10.0.0.1", "10.255.255.255", "127.0.0.1", "192.168.1.10", "172.16.0.1",
    "172.31.255.255", "169.254.1.1",
  )

  @Test
  fun `detects the Tailscale 100_64_0_0 slash 10 range`() {
    private("100.64.0.1", "100.100.100.100", "100.126.17.15", "100.127.255.255")
    public("100.63.255.255", "100.128.0.1")
  }

  @Test
  fun `detects private host names and suffixes`() = private(
    "localhost", "air", "printer.local", "api.internal", "router.home.arpa", "home.arpa",
    "box.tailnet.ts.net", "AIR.TAILE8BEA7.TS.NET",
  )

  @Test
  fun `detects private IPv6 addresses`() {
    private("::1", "[::1]", "fd00::1", "fc00::1", "fe80::1", "FD12:3456::1")
    public("2606:4700:4700::1111")
  }

  @Test
  fun `detects IPv4-mapped IPv6 addresses in both spellings`() {
    private(
      "::ffff:192.168.1.10", "::ffff:10.0.0.1", "::ffff:100.126.17.15", "[::ffff:192.168.1.10]",
      "::ffff:c0a8:010a", "::ffff:a00:1",
    )
    public("::ffff:8.8.8.8", "::ffff:808:808")
  }

  @Test
  fun `ignores a trailing DNS root label`() {
    private("localhost.", "printer.local.", "api.internal.", "box.tailnet.ts.net.", "air.")
    public("github.com.")
  }

  @Test
  fun `detects names under dot localhost`() = private("app.localhost", "api.app.localhost", "APP.LOCALHOST")

  @Test
  fun `treats an empty host as private`() = private("", "   ")

  @Test
  fun `rejects malformed IPv4 text as a public host`() = public("10.0.0.999", "10.0.0")

  @Test
  fun `never sends an internal label under a public domain`() = private("grafana.internal.acme.com")
}
