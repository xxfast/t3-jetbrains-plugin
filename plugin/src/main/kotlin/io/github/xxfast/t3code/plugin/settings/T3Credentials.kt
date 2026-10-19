package io.github.xxfast.t3code.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import io.github.xxfast.t3code.protocol.Credential
import io.github.xxfast.t3code.protocol.T3Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bearer credential per server, in the IDE's password safe. Blocking calls go to IO. */
object T3Credentials {
  suspend fun load(serverUrl: String): Credential? = withContext(Dispatchers.IO) {
    PasswordSafe.instance.get(attributes(serverUrl))
      ?.getPasswordAsString()
      ?.let { json -> runCatching { T3Json.decodeFromString(Credential.serializer(), json) } }
      ?.getOrNull()
  }

  suspend fun save(serverUrl: String, credential: Credential): Unit = withContext(Dispatchers.IO) {
    val json: String = T3Json.encodeToString(Credential.serializer(), credential)
    PasswordSafe.instance.set(attributes(serverUrl), Credentials(serverUrl, json))
  }

  suspend fun clear(serverUrl: String): Unit = withContext(Dispatchers.IO) {
    PasswordSafe.instance.set(attributes(serverUrl), null)
  }

  private fun attributes(serverUrl: String): CredentialAttributes =
    CredentialAttributes(generateServiceName("T3 Code", serverUrl))
}
