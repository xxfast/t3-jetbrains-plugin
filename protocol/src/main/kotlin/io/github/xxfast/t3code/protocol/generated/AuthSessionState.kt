// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class AuthSessionState(
  val authenticated: Boolean,
  val auth: ServerAuthDescriptor,
  val scopes: List<String> = emptyList(),
  val permissions: List<String?> = emptyList(),
  val sessionMethod: String? = null,
  val expiresAt: String? = null,
)
