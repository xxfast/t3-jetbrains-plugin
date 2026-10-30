package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.Thread

// The local half of packages/client-runtime/src/state/threadShell.ts at the pinned tag: which
// parents a subagent's request flags (`localSubagentRequestsAtom`). Cross-environment
// delegation (`delegatedSubagentRequestsAtom`) isn't ported, the plugin talks to one server.

/** `PendingSubagentRequest`, models.ts. */
enum class PendingSubagentRequest { Approval, UserInput }

/** Run statuses `isChildRunLive` reads from the thread when there's no activity run. */
private val CHILD_RUN_LIVE: Set<String> = setOf("preparing", "starting", "running", "waiting")

/**
 * `isChildRunLive`: an async question outlives the subagent's run, but once that run ends the
 * parent no longer waits on the answer. Upstream reads an absent `activityRunStatus` (an older
 * server) as "ask the status" and a null one as not live; the shell's optional and null
 * collapse here, so both ask the status.
 */
private fun Thread.childRunLive(): Boolean = activityRunStatus != null || status in CHILD_RUN_LIVE

/** `isProviderNativeSubagentThread`, packages/contracts/src/orchestrationV2.ts. */
private val Thread.providerNativeSubagent: Boolean
  get() = lineage.relationshipToParent == "subagent" && creationSource == "provider"

/**
 * `subagentRequest`: what a subagent's live request asks of its parent. Provider-native
 * subagents ask on their parent, so they're skipped. `delegatedFrom` isn't on the shell at the
 * pinned contracts, so only subagents count.
 */
private fun subagentRequest(thread: Thread): PendingSubagentRequest? {
  val kind: String = thread.pendingRuntimeRequest?.kind ?: return null
  if (kind == "auth_refresh" ||
    thread.lineage.relationshipToParent != "subagent" ||
    thread.providerNativeSubagent ||
    !thread.childRunLive()
  ) {
    return null
  }
  return when (kind) {
    "user_input" -> PendingSubagentRequest.UserInput
    else -> PendingSubagentRequest.Approval
  }
}

/**
 * `pendingSubagentRequestsByThread`: the threads to flag for a subagent's live approval, up its
 * subagent chain, by thread id. The coordinator answers child questions, so only approvals
 * flag a parent.
 */
fun pendingSubagentRequestsByThread(threads: List<Thread>): Map<String, PendingSubagentRequest> {
  val requests: MutableMap<String, PendingSubagentRequest> = mutableMapOf()
  val byId: Map<String, Thread> by lazy { threads.associateBy { it.id } }
  fun flag(firstParentId: String?, request: PendingSubagentRequest, seen: MutableSet<String>) {
    if (request == PendingSubagentRequest.UserInput) return
    var parentId: String? = firstParentId
    while (parentId != null && seen.add(parentId)) {
      if (requests[parentId] != PendingSubagentRequest.Approval) requests[parentId] = request
      val parent: Thread? = byId[parentId]
      val chained: Boolean = parent?.lineage?.relationshipToParent == "subagent"
      parentId = if (chained) parent?.lineage?.parentThreadId else null
    }
  }
  for (child in threads) {
    if (child.lineage.relationshipToParent != "subagent") continue
    val request: PendingSubagentRequest = subagentRequest(child) ?: continue
    flag(child.lineage.parentThreadId, request, mutableSetOf(child.id))
  }
  return requests
}
