package io.github.xxfast.t3code.plugin.terminal

import com.intellij.openapi.diagnostic.Logger
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A jediterm [TtyConnector] onto a terminal the T3 server owns. Nothing runs locally: the screen
 * comes from the attach stream, keys go back as `terminal.write`, the size as `terminal.resize`.
 * [close] detaches and a running terminal keeps running, same as hiding the desktop app's
 * drawer. A shell that exited gets `terminal.close`, as upstream does, so the next open starts a
 * fresh one.
 *
 * [readOnly] observes instead of attaching and drops keys. An attach the server refuses for want
 * of `terminal:operate` falls back to that when [canObserve].
 */
class T3TtyConnector(
  private val client: T3Client,
  private val input: TerminalAttachInput,
  private val title: String,
  readOnly: Boolean,
  private val canObserve: Boolean,
  parent: CoroutineScope,
  private val listener: Listener = Listener.NONE,
) : TtyConnector {
  interface Listener {
    /** A key was dropped because this terminal is read-only. Called once, on the first one. */
    fun readOnly() {}

    /** The stream failed for good. The connector has already ended. */
    fun failed(error: Throwable) {}

    companion object {
      val NONE: Listener = object : Listener {}
    }
  }

  private val threadId: String = input.threadId
  private val terminalId: String = input.terminalId

  private val scope: CoroutineScope = CoroutineScope(
    parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO,
  )

  @Volatile private var readOnly: Boolean = readOnly
  private val warned: AtomicBoolean = AtomicBoolean(false)
  private val closing: AtomicBoolean = AtomicBoolean(false)

  // Chunks of screen text for read(), in order. EOF, by identity, ends the stream.
  private val screen: LinkedBlockingQueue<CharArray> = LinkedBlockingQueue()
  @Volatile private var pending: CharArray? = null

  private val connected: AtomicBoolean = AtomicBoolean(true)
  private val done: CountDownLatch = CountDownLatch(1)
  @Volatile private var exitCode: Int = 0

  private val writes: Channel<String> = Channel(Channel.UNLIMITED)
  private val size: MutableStateFlow<TermSize?> = MutableStateFlow(null)

  // Bumped on every snapshot. Zero until the server has a terminal to write to or resize.
  private val generation: MutableStateFlow<Int> = MutableStateFlow(0)

  /** Starts streaming. Call once, after the widget has this connector. */
  fun start() {
    scope.launch { collect() }
    scope.launch { send() }
    scope.launch { resize() }
  }

  private suspend fun collect() {
    var failure: Exception? = null
    try {
      var closed = false
      events()
        .transformWhile { event ->
          emit(event)
          !ends(event)
        }
        .collect { event ->
          if (event is TerminalAttachStreamEvent.Exited) exitCode = event.exitCode?.toInt() ?: 0
          show(textOf(event))
          if (resets(event)) generation.update { it + 1 }
          if (exits(event)) closeExited()
          if (ends(event)) closed = true
        }
      // The server ended the stream without saying why: as good as closed
      if (!closed) show(CLOSED_LINE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LOG.info("Terminal $threadId/$terminalId failed", e)
      failure = e
    } finally {
      end()
    }
    failure?.let(listener::failed)
  }

  private fun events(): Flow<TerminalAttachStreamEvent> {
    if (readOnly) return client.terminalObserve(threadId, terminalId)
    return client.terminal(input)
      .catch { error ->
        if (error !is T3Exception.ScopeRequired || !canObserve) throw error
        LOG.info("Terminal $threadId/$terminalId can't attach, observing", error)
        readOnly = true
        dropQueuedWrites()
        emitAll(client.terminalObserve(threadId, terminalId))
      }
  }

  private fun show(text: String) {
    if (text.isNotEmpty()) screen.put(text.toCharArray())
  }

  // Upstream's onSessionExited: only a session that can write closes, and only once
  private fun closeExited() {
    if (readOnly || !closing.compareAndSet(false, true)) return
    scope.launch {
      try {
        client.terminalClose(threadId, terminalId, deleteHistory = true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Terminal $threadId/$terminalId didn't close after exiting", e)
      }
    }
  }

  // One sender, so keys reach the server in the order they were typed
  private suspend fun send() {
    generation.first { it > 0 }
    for (first in writes) {
      val batch: String = buildString {
        append(first)
        while (true) append(writes.tryReceive().getOrNull() ?: break)
      }
      if (readOnly) {
        warnReadOnly()
        continue
      }
      try {
        client.terminalWrite(threadId, terminalId, batch)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.info("Terminal $threadId/$terminalId dropped a write", e)
      }
    }
  }

  // Keys typed while the attach was being refused never reach the observer
  private fun dropQueuedWrites() {
    var dropped = false
    while (writes.tryReceive().isSuccess) dropped = true
    if (dropped) warnReadOnly()
  }

  private fun warnReadOnly() {
    if (warned.compareAndSet(false, true)) listener.readOnly()
  }

  // Again after every snapshot: a re-attach replays the size it first attached with, and an
  // attach carries none, same as `resendSize` on a new output generation in
  // apps/web/src/components/ThreadTerminalDrawer.tsx
  private suspend fun resize() {
    combine(size, generation) { latest, snapshots -> latest.takeIf { snapshots > 0 } }
      .filterNotNull()
      .collectLatest { latest ->
        if (readOnly) return@collectLatest
        try {
          client.terminalResize(threadId, terminalId, latest.columns, latest.rows)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          LOG.info("Terminal $threadId/$terminalId didn't resize", e)
        }
      }
  }

  override fun read(buf: CharArray, offset: Int, length: Int): Int {
    val chunk: CharArray = pending ?: next() ?: return -1
    val count: Int = minOf(length, chunk.size)
    chunk.copyInto(buf, offset, 0, count)
    pending = if (count < chunk.size) chunk.copyOfRange(count, chunk.size) else null
    return count
  }

  // Blocks until there's text, or null at the end. Never an empty chunk: jediterm reads 0 as EOF.
  private fun next(): CharArray? {
    val chunk: CharArray = try {
      screen.take()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return null
    }
    if (chunk !== EOF) return chunk
    // Leave it for the next read, every read after the end is the end
    screen.put(EOF)
    return null
  }

  override fun write(bytes: ByteArray) = write(String(bytes, Charsets.UTF_8))

  override fun write(string: String) {
    if (string.isEmpty() || !connected.get()) return
    if (readOnly) return warnReadOnly()
    writes.trySend(string)
  }

  override fun resize(termSize: TermSize) {
    size.value = termSize
  }

  override fun isConnected(): Boolean = connected.get()

  override fun ready(): Boolean {
    if (pending != null) return true
    val head: CharArray? = screen.peek()
    return head != null && head !== EOF
  }

  override fun waitFor(): Int {
    done.await()
    return exitCode
  }

  override fun getName(): String = title

  /** Detaches. Never `terminal.close`: a running terminal is the server's, we're a view onto it. */
  override fun close() {
    end()
    writes.close()
    scope.cancel()
  }

  // Disconnected before the EOF goes in, so jediterm never reads the end of a live connector
  private fun end() {
    if (!connected.compareAndSet(true, false)) return
    screen.put(EOF)
    done.countDown()
  }

  private companion object {
    val LOG: Logger = Logger.getInstance(T3TtyConnector::class.java)
    val EOF: CharArray = CharArray(0)
  }
}
