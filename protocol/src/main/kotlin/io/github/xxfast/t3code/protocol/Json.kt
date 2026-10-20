package io.github.xxfast.t3code.protocol

import kotlinx.serialization.json.Json

/**
 * Decoding, and the few encodes that never cross the wire. Drops defaults and nulls on the way
 * out. Generated union branches mark their discriminator `@EncodeDefault(ALWAYS)`, so `type`
 * survives even though it's a default.
 */
public val T3Json: Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  encodeDefaults = false
  explicitNulls = false
}

/**
 * What goes to the server. Upstream's `optional` fields are generated with a null default and
 * stay absent; its `NullOr` fields have no default and must be present, as null. The server
 * answers `Missing key at ["branch"]` to a `thread.create` without them (seen 2026-10-21).
 */
public val T3JsonOut: Json = Json(T3Json) {
  explicitNulls = true
}
