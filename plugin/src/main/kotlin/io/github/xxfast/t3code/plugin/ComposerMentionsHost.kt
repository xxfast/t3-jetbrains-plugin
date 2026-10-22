package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.xxfast.t3code.plugin.ui.ComposerMentions
import io.github.xxfast.t3code.plugin.ui.ComposerThreadCandidate
import io.github.xxfast.t3code.plugin.ui.PULL_REQUESTS
import io.github.xxfast.t3code.plugin.ui.sourceControlRepositorySelector
import io.github.xxfast.t3code.plugin.ui.supports
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.ServerInfo
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.generated.ThreadPullRequestLink

/**
 * What [threadId]'s composer mentions read, from [client] and the [controller]'s catalog: the
 * server's id, its threads as `@` candidates, `projects.searchEntries`, and for `#` the
 * project's pull requests while the server does them and the project has a repository.
 */
@Composable
fun rememberComposerMentions(
  threadId: String,
  client: T3Client,
  controller: ChatController,
): ComposerMentions {
  val info: ServerInfo? by controller.catalog.collectAsState()
  val threads: List<Thread> by client.threads.collectAsState()
  val projects: List<Project> by client.projects.collectAsState()
  val environmentId: String? = info?.environmentId
  val thread: Thread? = threads.find { it.id == threadId }
  val project: Project? = projects.find { it.id == thread?.projectId }
  // `supportsPullRequests` gates both, ChatView.tsx:2256 and 8540
  val supportsPullRequests: Boolean = info.supports(PULL_REQUESTS)
  val pullRequestProjectId: String? = project?.id?.takeIf { supportsPullRequests }
  val pullRequestRepository: String? = project
    ?.let { sourceControlRepositorySelector(it.repositoryIdentity) }
    ?.takeIf { supportsPullRequests }
  val links: List<ThreadPullRequestLink> = thread?.pullRequests.orEmpty()
  return remember(
    threadId,
    client,
    controller,
    environmentId,
    threads,
    pullRequestProjectId,
    pullRequestRepository,
    links,
  ) {
    ComposerMentions(
      environmentId = environmentId,
      activeThreadId = threadId,
      // One server per client, so every shell is on the composer's environment
      threads = environmentId?.let { id -> threads.map { it.toCandidate(id) } }.orEmpty(),
      searchEntries = client::searchEntries,
      onThreadContext = { record -> controller.addThreadContext(threadId, record) },
      pullRequestProjectId = pullRequestProjectId,
      pullRequestRepository = pullRequestRepository,
      linkedPullRequests = links,
      listPullRequests = client::pullRequests,
      pullRequestDetail = client::pullRequestDetail,
      onPullRequestContext = { comment -> controller.addContext(threadId, comment) },
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
