package io.github.xxfast.t3code.plugin.fixtures

import io.github.xxfast.t3code.protocol.Run
import io.github.xxfast.t3code.protocol.RuntimeRequest
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.ThreadView
import io.github.xxfast.t3code.protocol.generated.RuntimeRequest.ResponseCapability
import io.github.xxfast.t3code.protocol.generated.TurnItem

/**
 * What dispatched commands changed, laid over the scenario on disk. The files stay read-only;
 * this resets whenever the directory reloads.
 */
data class Overlay(
  /** Rows that replace the scenario's, by thread id. */
  val threads: Map<String, Thread> = emptyMap(),
  /** Per thread, upserted over the scenario's items by id. */
  val items: Map<String, List<TurnItem>> = emptyMap(),
  /** Per thread, upserted over the scenario's runs by id. */
  val runs: Map<String, List<Run>> = emptyMap(),
  /** Per thread, upserted over the scenario's requests by id. */
  val runtimeRequests: Map<String, List<RuntimeRequest>> = emptyMap(),
  /** Provider session ids that `provider-session.detach` removed. */
  val detachedSessions: Set<String> = emptySet(),
) {
  fun row(thread: Thread): Thread = threads[thread.id] ?: thread

  fun upsertItem(threadId: String, item: TurnItem): Overlay =
    copy(items = items + (threadId to items[threadId].orEmpty().upsert(item, TurnItem::id)))

  fun upsertRun(threadId: String, run: Run): Overlay =
    copy(runs = runs + (threadId to runs[threadId].orEmpty().upsert(run, Run::id)))

  fun upsertRequest(threadId: String, request: RuntimeRequest): Overlay = copy(
    runtimeRequests = runtimeRequests +
      (threadId to runtimeRequests[threadId].orEmpty().upsert(request, RuntimeRequest::id)),
  )

  /** [base], the scenario's view of one thread, with everything dispatched since laid over it. */
  fun over(base: ThreadView): ThreadView {
    val id: String = base.thread.id
    val items: List<TurnItem> = base.items.upsertAll(items[id].orEmpty(), TurnItem::id)
    val listed: List<RuntimeRequest> =
      base.runtimeRequests.upsertAll(runtimeRequests[id].orEmpty(), RuntimeRequest::id)
    return base.copy(
      thread = threads[id] ?: base.thread,
      runs = base.runs.upsertAll(runs[id].orEmpty(), Run::id),
      items = items,
      runtimeRequests = listed + requestsOf(items).filter { request ->
        listed.none { it.id == request.id }
      },
      providerSessions = base.providerSessions.filterNot { it.id in detachedSessions },
    )
  }
}

/**
 * Scenarios rarely list runtime requests, so every approval or question item stands for one,
 * pending until the item completes.
 */
private fun requestsOf(items: List<TurnItem>): List<RuntimeRequest> = items.mapNotNull { item ->
  val (requestId: String, kind: String) = when (item) {
    is TurnItem.ApprovalRequest -> item.requestId to item.requestKind
    is TurnItem.UserInputRequest -> item.requestId to "user_input"
    else -> return@mapNotNull null
  }
  val resolved: Boolean = item.status == "completed"
  return@mapNotNull RuntimeRequest(
    id = requestId,
    nodeId = item.nodeId ?: "node_fixture",
    providerTurnId = item.providerTurnId,
    nativeRequestRef = null,
    kind = kind,
    status = if (resolved) "resolved" else "pending",
    responseCapability = ResponseCapability.Message(),
    createdAt = item.startedAt ?: item.updatedAt,
    resolvedAt = if (resolved) item.completedAt ?: item.updatedAt else null,
  )
}

internal fun <T> List<T>.upsertAll(items: List<T>, id: (T) -> String): List<T> =
  items.fold(this) { acc, item -> acc.upsert(item, id) }

internal fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
  val index: Int = indexOfFirst { id(it) == id(item) }
  return if (index < 0) this + item else toMutableList().apply { set(index, item) }
}
