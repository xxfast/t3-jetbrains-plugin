package io.github.xxfast.t3code.plugin.ui

// Port of upstream's `packages/shared/src/hostClassification.ts`, function for function. The
// point is privacy: a host name only goes to a third-party favicon service when it's public, and
// a false negative (no favicon) is always preferable to leaking an internal name.

private val HEXTET: Regex = Regex("^[\\da-f]{1,4}$")

fun normalizeHostname(host: String): String =
  host.lowercase().replace(Regex("^\\[|]$"), "").replace(Regex("\\.+$"), "")

/**
 * JS's `Number(part)` for an IPv4 label: an empty label is 0, digits parse, anything else isn't
 * an integer. Fractions, exponents and hex spellings are left out on purpose.
 */
private fun ipv4Label(part: String): Int? = when {
  part.isEmpty() -> 0
  part.all { it.isDigit() } -> part.toIntOrNull()
  else -> null
}

private fun parseIpv4Address(host: String): List<Int>? {
  val parts: List<Int?> = normalizeHostname(host).split(".").map(::ipv4Label)
  if (parts.size != 4 || parts.any { it == null || it !in 0..255 }) return null
  return parts.map { it!! }
}

private fun parseIpv4MappedIpv6Address(host: String): List<Int>? {
  val normalized: String = normalizeHostname(host)
  if (!normalized.startsWith("::ffff:")) return null
  val suffix: String = normalized.removePrefix("::ffff:")
  parseIpv4Address(suffix)?.let { return it }
  val hextets: List<String> = suffix.split(":")
  if (hextets.size != 2 || hextets.any { !HEXTET.matches(it) }) return null
  val high: Int = hextets[0].toInt(16)
  val low: Int = hextets[1].toInt(16)
  return listOf(high ushr 8, high and 0xff, low ushr 8, low and 0xff)
}

private fun parseIpv6Address(host: String): List<Int>? {
  val normalized: String = normalizeHostname(host)
  if (!normalized.contains(":")) return null
  val halves: List<String> = normalized.split("::")
  if (halves.size > 2) return null
  val head: List<String> = if (halves[0].isNotEmpty()) halves[0].split(":") else emptyList()
  val tail: List<String> =
    if (halves.size == 2 && halves[1].isNotEmpty()) halves[1].split(":") else emptyList()
  if ((head + tail).any { !HEXTET.matches(it) }) return null
  val missing: Int = 8 - head.size - tail.size
  if ((halves.size == 1 && missing != 0) || (halves.size == 2 && missing < 1)) return null
  return (head + List(missing) { "0" } + tail).map { it.toInt(16) }
}

private fun ipv6PrefixMatches(address: List<Int>, prefix: List<Int>, prefixLength: Int): Boolean {
  val fullHextets: Int = prefixLength / 16
  if ((0 until fullHextets).any { address[it] != prefix[it] }) return false
  val remainingBits: Int = prefixLength % 16
  if (remainingBits == 0) return true
  val mask: Int = (0xffff shl (16 - remainingBits)) and 0xffff
  return (address[fullHextets] and mask) == (prefix[fullHextets] and mask)
}

private fun isPrivateIpv4Address(parts: List<Int>): Boolean =
  parts[0] == 0 ||
    parts[0] == 10 ||
    parts[0] == 127 ||
    (parts[0] == 100 && parts[1] in 64..127) ||
    (parts[0] == 172 && parts[1] in 16..31) ||
    (parts[0] == 192 && parts[1] == 168) ||
    (parts[0] == 169 && parts[1] == 254) ||
    (parts[0] == 198 && parts[1] in 18..19)

private fun isSpecialPurposeIpv4Address(parts: List<Int>): Boolean =
  isPrivateIpv4Address(parts) ||
    parts[0] >= 224 ||
    // Deliberately suppress the whole protocol-assignment block. IANA marks .9 and .10 globally
    // reachable, but privacy-safe false negatives are preferable to disclosing another
    // special-purpose address by mistake.
    (parts[0] == 192 && parts[1] == 0 && parts[2] == 0) ||
    (parts[0] == 192 && parts[1] == 0 && parts[2] == 2) ||
    (parts[0] == 192 && parts[1] == 88 && parts[2] == 99) ||
    (parts[0] == 198 && parts[1] == 51 && parts[2] == 100) ||
    (parts[0] == 203 && parts[1] == 0 && parts[2] == 113)

fun isLocalLoopbackHost(host: String): Boolean {
  val normalized: String = normalizeHostname(host)
  if (normalized == "localhost" || normalized == "::1") return true
  return parseIpv4Address(normalized)?.get(0) == 127
}

/**
 * A name or address only Tailscale uses: a MagicDNS name or its IPv6 range fd7a:115c:a1e0::/48.
 * Its IPv4 addresses come from the shared 100.64.0.0/10 range, so they prove nothing on their own.
 */
fun isTailnetHost(host: String): Boolean {
  val normalized: String = normalizeHostname(host)
  if (normalized.endsWith(".ts.net")) return true
  val ipv6: List<Int> = parseIpv6Address(normalized) ?: return false
  return ipv6PrefixMatches(ipv6, listOf(0xfd7a, 0x115c, 0xa1e0, 0, 0, 0, 0, 0), 48)
}

fun isSharedAddressSpaceHost(host: String): Boolean {
  val parts: List<Int> = parseIpv4Address(host) ?: return false
  return parts[0] == 100 && parts[1] in 64..127
}

fun isPrivateNetworkHost(host: String): Boolean {
  val normalized: String = normalizeHostname(host)
  if (
    normalized == "::" ||
    isLocalLoopbackHost(normalized) ||
    normalized.endsWith(".localhost") ||
    normalized.endsWith(".local") ||
    normalized == "home.arpa" ||
    normalized.endsWith(".home.arpa") ||
    (!normalized.contains(".") && !normalized.contains(":"))
  ) {
    return true
  }
  if (normalized.endsWith(".ts.net")) return true
  val parts: List<Int>? = parseIpv4Address(normalized) ?: parseIpv4MappedIpv6Address(normalized)
  if (parts != null) return isPrivateIpv4Address(parts)
  val firstIpv6Token: String = normalized.substringBefore(":")
  if (!normalized.contains(":") || !HEXTET.matches(firstIpv6Token)) return false
  val firstIpv6Hextet: Int = firstIpv6Token.toInt(16)
  return (firstIpv6Hextet and 0xfe00) == 0xfc00 || (firstIpv6Hextet and 0xffc0) == 0xfe80
}

// Reserved and special-use names, plus the private TLDs RFC 6762 Appendix G records as common on
// internal networks.
private val PRIVATE_FAVICON_TLDS: List<String> = listOf(
  ".alt",
  ".corp",
  ".example",
  ".home",
  ".internal",
  ".intranet",
  ".invalid",
  ".lan",
  ".onion",
  ".private",
  ".test",
)
private val INTERNAL_HOST_LABELS: Set<String> = setOf("corp", "internal", "intranet")

fun isPublicFaviconHost(host: String): Boolean {
  // A single trailing dot is a valid absolute DNS name. Repeated trailing dots are malformed and
  // can conceal legacy numeric forms such as 127.1.
  if (host.endsWith("..")) return false
  val normalized: String = normalizeHostname(host)
  if (isPrivateNetworkHost(normalized)) return false
  if (PRIVATE_FAVICON_TLDS.any { normalized == it.drop(1) || normalized.endsWith(it) }) {
    return false
  }
  // Company networks often nest internal services under a public domain
  // (`grafana.internal.acme.com`); never send those names to a third party.
  if (normalized.split(".").any { it in INTERNAL_HOST_LABELS }) return false
  val ipv4: List<Int>? = parseIpv4Address(normalized) ?: parseIpv4MappedIpv6Address(normalized)
  if (ipv4 != null) return !isSpecialPurposeIpv4Address(ipv4)
  if (!normalized.contains(":")) return true
  val ipv6: List<Int> = parseIpv6Address(normalized) ?: return false
  if (ipv6PrefixMatches(ipv6, listOf(0x0064, 0xff9b, 0, 0, 0, 0, 0, 0), 96)) {
    val embeddedIpv4: List<Int> =
      listOf(ipv6[6] ushr 8, ipv6[6] and 0xff, ipv6[7] ushr 8, ipv6[7] and 0xff)
    return !isSpecialPurposeIpv4Address(embeddedIpv4)
  }
  val first: Int = ipv6[0]
  if ((first and 0xe000) != 0x2000) return false
  if (ipv6PrefixMatches(ipv6, listOf(0x2001, 0, 0, 0, 0, 0, 0, 0), 23)) {
    val publicProtocolAssignment: Boolean =
      (ipv6[1] == 1 && ipv6.subList(2, 7).all { it == 0 } && ipv6[7] in 1..3) ||
        ipv6PrefixMatches(ipv6, listOf(0x2001, 3, 0, 0, 0, 0, 0, 0), 32) ||
        ipv6PrefixMatches(ipv6, listOf(0x2001, 4, 0x0112, 0, 0, 0, 0, 0), 48) ||
        ipv6PrefixMatches(ipv6, listOf(0x2001, 0x20, 0, 0, 0, 0, 0, 0), 28) ||
        ipv6PrefixMatches(ipv6, listOf(0x2001, 0x30, 0, 0, 0, 0, 0, 0), 28)
    return publicProtocolAssignment
  }
  if (ipv6PrefixMatches(ipv6, listOf(0x2001, 0x0db8, 0, 0, 0, 0, 0, 0), 32)) return false
  if (ipv6PrefixMatches(ipv6, listOf(0x2002, 0, 0, 0, 0, 0, 0, 0), 16)) return false
  if (first == 0x3fff && (ipv6[1] and 0xf000) == 0) return false
  return true
}
