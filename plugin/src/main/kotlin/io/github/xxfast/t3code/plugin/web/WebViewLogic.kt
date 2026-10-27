package io.github.xxfast.t3code.plugin.web

import java.net.URI
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/** What a T3 web view tab shows, titled as the tab is. */
enum class WebViewKind(val title: String) {
  /** `settings.general.tsx`. */
  Settings("Server settings"),

  /** `settings.providers.tsx`. */
  Providers("Provider setup"),

  /** `settings.scheduled-tasks.tsx`. */
  ScheduledTasks("Scheduled tasks"),

  /**
   * `_chat.$environmentId.$threadId.tsx`. The PR review guide has no route upstream, it's right
   * panel state (`rightPanelStore.ts`), so it's opened from here.
   */
  Thread("Thread"),
}

/**
 * The web UI's route for [kind], from `apps/web/src/routes`. The settings routes take their ids
 * as search params, left out when there's none as their `validateSearch` does. A thread needs
 * both ids, so it's null without them. [environmentId] is the server's own, the descriptor's
 * `environmentId` (`connection/platform.ts`), which `server.getConfig` carries too.
 */
fun webViewRoute(
  kind: WebViewKind,
  environmentId: String?,
  instanceId: String? = null,
  taskId: String? = null,
  threadId: String? = null,
): String? = when (kind) {
  WebViewKind.Settings -> "/settings/general"
  WebViewKind.Providers ->
    "/settings/providers" + query("environmentId" to environmentId, "instanceId" to instanceId)
  WebViewKind.ScheduledTasks ->
    "/settings/scheduled-tasks" + query("environmentId" to environmentId, "taskId" to taskId)
  WebViewKind.Thread -> if (environmentId.isNullOrBlank() || threadId.isNullOrBlank()) {
    null
  } else {
    "/${segment(environmentId)}/${segment(threadId)}"
  }
}

/** [route] on the server at [serverUrl]. */
fun webViewUrl(serverUrl: String, route: String): String = serverUrl.trimEnd('/') + route

/**
 * Whether [requestUrl] goes to the server at [serverUrl], so it may carry the plugin's bearer.
 * Same scheme, host and port, with `ws` as `http`, `wss` as `https` and default ports filled
 * in, so the socket upgrade counts too. Anything that doesn't parse doesn't.
 */
fun isServerOrigin(requestUrl: String, serverUrl: String): Boolean {
  val request: Origin = origin(requestUrl) ?: return false
  val server: Origin = origin(serverUrl) ?: return false
  return request == server
}

private data class Origin(val scheme: String, val host: String, val port: Int)

private fun origin(url: String): Origin? {
  val uri: URI = runCatching { URI(url) }.getOrNull() ?: return null
  val scheme: String = when (uri.scheme?.lowercase()) {
    "http", "ws" -> "http"
    "https", "wss" -> "https"
    else -> return null
  }
  val host: String = uri.host?.lowercase() ?: return null
  val port: Int = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
  return Origin(scheme, host, port)
}

private fun query(vararg params: Pair<String, String?>): String {
  val present: List<String> = params
    .filter { (_, value) -> !value.isNullOrBlank() }
    .map { (key, value) -> "$key=${encode(value!!)}" }
  return if (present.isEmpty()) "" else present.joinToString("&", prefix = "?")
}

private fun segment(value: String): String = encode(value).replace("+", "%20")

private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)
