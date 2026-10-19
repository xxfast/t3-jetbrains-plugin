package io.github.xxfast.t3code.protocol

import kotlinx.serialization.json.Json

public val T3Json: Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  encodeDefaults = false
  explicitNulls = false
}
