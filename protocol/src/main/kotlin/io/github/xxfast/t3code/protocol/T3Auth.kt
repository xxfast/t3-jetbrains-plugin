package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.AuthAccessTokenResult
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/** The HTTP half of the handshake: probing a server and pairing with it. */
public class T3Auth(
  baseUrl: String,
  private val http: HttpClient = defaultHttpClient(),
) {
  private val baseUrl: String = baseUrl.trimEnd('/')

  /** `GET /api/auth/session`. With a [credential] the answer carries the real grant. */
  public suspend fun probe(credential: Credential? = null): ServerSession {
    val request: HttpRequest.Builder = HttpRequest.newBuilder(URI("$baseUrl/api/auth/session"))
      .GET()
    if (credential != null) request.header("Authorization", "Bearer ${credential.accessToken}")
    return send(request.build(), ServerSession.serializer())
  }

  /**
   * Exchange a one-time pairing code, or a pairing link carrying one, for a bearer token.
   *
   * The server grants [scopes] intersected with the code's own and only refuses when that's
   * empty (`exchangeBootstrapCredentialForAccessToken` in `EnvironmentAuth.ts`). So a read-only
   * code still pairs, it just yields a read-only token, and the UI gates writes on
   * [ServerSession.has]. An empty [scopes] leaves the `scope` field out, and the token gets
   * everything the code carries.
   */
  public suspend fun pair(
    codeOrLink: String,
    label: String = "JetBrains",
    scopes: List<String> = PLUGIN_SCOPES,
  ): Credential {
    val form: Map<String, String> = buildMap {
      put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
      put("subject_token", pairingCode(codeOrLink))
      put("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
      put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
      if (scopes.isNotEmpty()) put("scope", scopes.joinToString(" "))
      put("client_label", label)
      put("client_device_type", "desktop")
    }
    val body: String = form.entries.joinToString("&") { (key, value) ->
      "$key=${URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")}"
    }
    val request: HttpRequest = HttpRequest.newBuilder(URI("$baseUrl/oauth/token"))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val token: AuthAccessTokenResult = send(request, AuthAccessTokenResult.serializer())
    return Credential(
      accessToken = token.accessToken,
      tokenType = token.tokenType,
      scopes = token.scope.split(" ").filter { it.isNotBlank() },
      expiresInSeconds = token.expiresIn.toLong(),
    )
  }

  public companion object {
    /** What the plugin uses, and so all it asks for: least privilege. */
    public val PLUGIN_SCOPES: List<String> = listOf(
      "orchestration:read",
      "orchestration:operate",
      "terminal:read",
      "terminal:operate",
      "filesystem:read",
    )
  }

  private suspend fun <T> send(
    request: HttpRequest,
    serializer: KSerializer<T>,
  ): T {
    val response: HttpResponse<String> = try {
      http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      throw T3Exception.Transport("Can't reach $baseUrl: ${e.message ?: e.javaClass.simpleName}", e)
    }
    val status: Int = response.statusCode()
    if (status !in 200..299) {
      val error: JsonObject? = parseObject(response.body())
      throw error?.toT3Exception("HTTP $status from ${request.uri().path}")
        ?: T3Exception.Transport("HTTP $status from ${request.uri().path}")
    }
    return try {
      T3Json.decodeFromString(serializer, response.body())
    } catch (e: SerializationException) {
      throw T3Exception.Transport("Unexpected answer from ${request.uri().path}", e)
    }
  }
}

// HTTP/1.1 on purpose: on plain http the JDK client first asks for an h2c upgrade, and the
// server's WebSocket upgrade handler answers that with an empty 400. Seen on 2026-10-19.
internal fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
  .version(HttpClient.Version.HTTP_1_1)
  .connectTimeout(Duration.ofSeconds(10))
  .build()

private val TokenParameter: Regex = Regex("[#?&]token=([^&#]+)")

/** A raw code passes through; a `.../pair#token=CODE` or `?token=CODE` link yields `CODE`. */
internal fun pairingCode(codeOrLink: String): String {
  val input: String = codeOrLink.trim()
  val token: String = TokenParameter.find(input)?.groupValues?.get(1) ?: return input
  return URLDecoder.decode(token, Charsets.UTF_8)
}

