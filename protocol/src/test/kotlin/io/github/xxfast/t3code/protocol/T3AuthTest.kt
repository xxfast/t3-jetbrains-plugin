package io.github.xxfast.t3code.protocol

import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class T3AuthTest {
  private val server: FakeHttpServer = FakeHttpServer()
  private val auth: T3Auth = T3Auth(server.baseUrl)

  @AfterTest
  fun stop() = server.close()

  @Test
  fun probeDecodesTheUnauthenticatedSession() = runTest {
    val session: ServerSession = auth.probe()
    assertFalse(session.authenticated)
    assertEquals("desktop-managed-local", session.auth.policy)
    assertTrue("bearer-access-token" in session.auth.sessionMethods)
  }

  @Test
  fun probeWithCredentialSeesTheGrant() = runTest {
    val session: ServerSession = auth.probe(Credential(accessToken = "test"))
    assertTrue(session.authenticated)
    assertEquals(listOf("orchestration:read", "filesystem:read"), session.permissions)
  }

  @Test
  fun pairExchangesTheCodeFromALink() = runTest {
    val credential: Credential = auth.pair("http://127.0.0.1:3773/pair#token=ABCD2345EFGH")
    assertEquals(
      Credential("test", "Bearer", listOf("orchestration:read", "filesystem:read"), 2592000),
      credential,
    )
    val (contentType: String?, body: String) = server.tokenRequests.single()
    assertEquals("application/x-www-form-urlencoded", contentType)
    val form: Map<String, String> = body.split("&").associate { pair ->
      val (key: String, value: String) = pair.split("=", limit = 2)
      key to URLDecoder.decode(value, Charsets.UTF_8)
    }
    assertEquals("urn:ietf:params:oauth:grant-type:token-exchange", form["grant_type"])
    assertEquals("urn:t3:params:oauth:token-type:environment-bootstrap", form["subject_token_type"])
    assertEquals("ABCD2345EFGH", form["subject_token"])
    assertEquals("orchestration:read filesystem:read", form["scope"])
    assertEquals("JetBrains", form["client_label"])
  }

  @Test
  fun rejectedCodeIsAuthInvalid() = runTest {
    val error: T3Exception.AuthInvalid = assertFailsWith { auth.pair("BADCODE23456") }
    assertEquals("missing_credential", error.reason)
  }

  @Test
  fun unreachableServerIsTransport() = runTest {
    val dead = FakeHttpServer()
    dead.close()
    assertFailsWith<T3Exception.Transport> { T3Auth(dead.baseUrl).probe() }
  }

  @Test
  fun pairingCodeParsing() {
    assertEquals("ABCD2345EFGH", pairingCode(" ABCD2345EFGH "))
    assertEquals("ABCD2345EFGH", pairingCode("https://host:5230/pair#token=ABCD2345EFGH"))
    assertEquals("ABCD2345EFGH", pairingCode("https://host:5230/pair?token=ABCD2345EFGH"))
    assertEquals("ABCD2345EFGH", pairingCode("https://relay/?host=x&token=ABCD2345EFGH"))
  }
}
