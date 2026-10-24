package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.ui.DraftThread
import io.github.xxfast.t3code.plugin.ui.draftThreadView
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One thread as the UI sees it: the latest view, and why the subscription stopped if it did.
 * Both can be set: a stream that fails after rendering keeps what it rendered.
 */
data class ThreadLoad(val view: ThreadView? = null, val error: String? = null)

/**
 * [T3Client.thread] that never throws, for both hosts. Every collection starts with an empty
 * [ThreadLoad], so switching threads or retrying never shows the last one's view or error. The
 * server can end the stream (`Die`, `Fail`, a thread that doesn't exist); that lands in
 * [ThreadLoad.error] next to the last view, instead of in Compose or a `stateIn`.
 */
fun T3Client.threadLoad(id: String): Flow<ThreadLoad> = flow {
  emit(ThreadLoad())
  var last: ThreadView? = null
  val loads: Flow<ThreadLoad> = thread(id)
    .map { view ->
      last = view
      return@map ThreadLoad(view = view)
    }
    .catch { e ->
      if (e is CancellationException) throw e
      emit(ThreadLoad(view = last, error = e.message ?: e.javaClass.simpleName))
    }
  emitAll(loads)
}

/**
 * [threadLoad] for a thread that may still be a [draft]: the draft's empty view while it is
 * one, upstream's `localDraftThread`, then the server's. The switch skips the server's empty
 * first load, so the first message stays on screen while the thread comes in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun T3Client.threadLoad(id: String, draft: Flow<DraftThread?>): Flow<ThreadLoad> = flow {
  var drafted = false
  val loads: Flow<ThreadLoad> = draft.flatMapLatest { current ->
    if (current != null) {
      drafted = true
      return@flatMapLatest flowOf(ThreadLoad(view = draftThreadView(current)))
    }
    threadLoad(id).drop(if (drafted) 1 else 0)
  }
  emitAll(loads)
}
