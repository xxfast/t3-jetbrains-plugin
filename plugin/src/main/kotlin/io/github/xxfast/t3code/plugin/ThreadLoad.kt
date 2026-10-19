package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
