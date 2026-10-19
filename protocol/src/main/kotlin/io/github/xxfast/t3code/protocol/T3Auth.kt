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

  /** Exchange a one-time pairing code, or a pairing link carrying one, for a bearer token. */
  public suspend fun pair(
    codeOrLink: String,
    label: String = "JetBrains",
    scopes: List<String> = listOf("orchestration:read", "filesystem:read"),
  ): Credential {
    val form: Map<String, String> = mapOf(
      "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
      "subject_token" to pairingCode(codeOrLink),
      "subject_token_type" to "urn:t3:params:oauth:token-type:environment-bootstrap",
      "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token",
      "scope" to scopes.joinToString(" "),
      "client_label" to label,
      "client_device_type" to "desktop",
    )
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

internal fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
  .connectTimeout(Duration.ofSeconds(10))
  .build()

private val TokenParameter: Regex = Regex("[#?&]token=([^&#]+)")

/** A raw code passes through; a `.../pair#token=CODE` or `?token=CODE` link yields `CODE`. */
internal fun pairingCode(codeOrLink: String): String {
  val input: String = codeOrLink.trim()
  val token: String = TokenParameter.find(input)?.groupValues?.get(1) ?: return input
  return URLDecoder.decode(token, Charsets.UTF_8)
}

