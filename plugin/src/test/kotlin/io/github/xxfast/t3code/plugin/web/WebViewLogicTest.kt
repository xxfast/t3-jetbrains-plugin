package io.github.xxfast.t3code.plugin.web

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WebViewLogicTest {
  private val server: String = "http://127.0.0.1:3773"

  @Test
  fun `settings routes are upstream's file routes`() {
    assertEquals("/settings/general", webViewRoute(WebViewKind.Settings, "env"))
    assertEquals(
      "/settings/providers?environmentId=env&instanceId=codex",
      webViewRoute(WebViewKind.Providers, "env", instanceId = "codex"),
    )
    assertEquals(
      "/settings/scheduled-tasks?environmentId=env&taskId=task_1",
      webViewRoute(WebViewKind.ScheduledTasks, "env", taskId = "task_1"),
    )
  }

  @Test
  fun `missing ids leave their search params out`() {
    assertEquals("/settings/providers", webViewRoute(WebViewKind.Providers, null))
    assertEquals(
      "/settings/scheduled-tasks?environmentId=env",
      webViewRoute(WebViewKind.ScheduledTasks, "env", taskId = " "),
    )
  }

  @Test
  fun `a thread is its environment and id, and needs both`() {
    assertEquals(
      "/58a89301/thr_01JC4A1F0QWMIGRATE",
      webViewRoute(WebViewKind.Thread, "58a89301", threadId = "thr_01JC4A1F0QWMIGRATE"),
    )
    assertNull(webViewRoute(WebViewKind.Thread, null, threadId = "thr_1"))
    assertNull(webViewRoute(WebViewKind.Thread, "env"))
  }

  @Test
  fun `ids are encoded`() {
    assertEquals("/a%20b/c%2Fd", webViewRoute(WebViewKind.Thread, "a b", threadId = "c/d"))
    assertEquals(
      "/settings/providers?environmentId=a+b%26c",
      webViewRoute(WebViewKind.Providers, "a b&c"),
    )
  }

  @Test
  fun `url joins the server and the route`() {
    assertEquals("$server/settings/general", webViewUrl("$server/", "/settings/general"))
  }

  @Test
  fun `the server's own requests carry the bearer`() {
    assertTrue(isServerOrigin("$server/api/auth/session", server))
    assertTrue(isServerOrigin("http://127.0.0.1:3773", "$server/"))
    assertTrue(isServerOrigin("HTTP://127.0.0.1:3773/assets/index.js", server))
  }

  @Test
  fun `the socket upgrade counts as the server's`() {
    assertTrue(isServerOrigin("ws://127.0.0.1:3773/ws?orchestrationProtocol=2", server))
    assertTrue(isServerOrigin("wss://t3.example.com/ws", "https://t3.example.com"))
    assertFalse(isServerOrigin("wss://127.0.0.1:3773/ws", server))
  }

  @Test
  fun `default ports are filled in`() {
    assertTrue(isServerOrigin("https://t3.example.com:443/x", "https://t3.example.com"))
    assertTrue(isServerOrigin("http://t3.example.com/x", "http://t3.example.com:80"))
    assertFalse(isServerOrigin("https://t3.example.com:8443/x", "https://t3.example.com"))
  }

  @Test
  fun `anything else doesn't`() {
    assertFalse(isServerOrigin("http://localhost:3773/api", server))
    assertFalse(isServerOrigin("http://127.0.0.1:3774/api", server))
    assertFalse(isServerOrigin("https://127.0.0.1:3773/api", server))
    assertFalse(isServerOrigin("https://fonts.googleapis.com/css", server))
    assertFalse(isServerOrigin("http://127.0.0.1.evil.com:3773/", server))
    assertFalse(isServerOrigin("data:text/html,hi", server))
    assertFalse(isServerOrigin("not a url", server))
  }
}
