package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.xxfast.t3code.plugin.ui.ComposerMentions
import io.github.xxfast.t3code.plugin.ui.ComposerThreadCandidate
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread

/**
 * What [threadId]'s composer mentions read, from [client] and the [controller]'s catalog: the
 * server's id, its threads as `@` candidates, and `projects.searchEntries`.
 */
@Composable
fun rememberComposerMentions(
  threadId: String,
  client: T3Client,
  controller: ChatController,
): ComposerMentions {
  val info: ServerInfo? by controller.catalog.collectAsState()
  val threads: List<Thread> by client.threads.collectAsState()
  val environmentId: String? = info?.environmentId
  return remember(threadId, client, controller, environmentId, threads) {
    ComposerMentions(
      environmentId = environmentId,
      activeThreadId = threadId,
      // One server per client, so every shell is on the composer's environment
      threads = environmentId?.let { id -> threads.map { it.toCandidate(id) } }.orEmpty(),
      searchEntries = client::searchEntries,
      onThreadContext = { record -> controller.addThreadContext(threadId, record) },
    )
  }
}

private fun Thread.toCandidate(environmentId: String): ComposerThreadCandidate =
  ComposerThreadCandidate(
    environmentId = environmentId,
    id = id,
    title = title,
    updatedAt = updatedAt,
    archivedAt = archivedAt,
  )
