package io.github.xxfast.t3code.protocol

import kotlinx.serialization.json.Json

/**
 * Drops defaults and nulls on the way out. Generated union branches mark their discriminator
 * `@EncodeDefault(ALWAYS)`, so `type` survives even though it's a default.
 */
public val T3Json: Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  encodeDefaults = false
  explicitNulls = false
}
