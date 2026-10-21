// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AuthAccessTokenResult(
  @SerialName("access_token") val accessToken: String,
  @SerialName("issued_token_type") val issuedTokenType: String,
  @SerialName("token_type") val tokenType: String,
  @SerialName("expires_in") val expiresIn: Double,
  val scope: String,
)
