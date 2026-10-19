package io.github.xxfast.t3code.plugin.terminal

import com.jediterm.core.util.TermSize
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

// Real threads, not runTest: jediterm reads on its own thread and read() blocks on a queue
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class T3TtyConnectorTest {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val client: FakeTerminalClient = FakeTerminalClient()
  private val listener: RecordingListener = RecordingListener()

  @AfterTest
  fun stop() = scope.cancel()

  private fun connector(
    readOnly: Boolean = false,
    canObserve: Boolean = true,
  ) = T3TtyConnector(
    client = client,
    input = attachInput("thread-1", null, "/repo"),
    title = "Write the real client",
    readOnly = readOnly,
    canObserve = canObserve,
    parent = scope,
    listener = listener,
  )

  @Test
  fun readsResetAndHistoryThenOutput() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    client.send(output("ls\r\nREADME.md\r\n$ "))

    val expected = "$RESET$ ls\r\nREADME.md\r\n$ "
    assertEquals(expected, connector.readText(expected.length))
    assertEquals("Write the real client", connector.name)
    assertTrue(connector.isConnected)
    val input: TerminalAttachInput = client.attached.single()
    assertEquals("/repo", input.cwd)
  }

  @Test
  fun smallReadsGetTheRestNext() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(output("abcdef"))

    val buffer = CharArray(4)
    assertEquals(4, connector.read(buffer, 0, 4))
    assertEquals("abcd", String(buffer))
    assertTrue(connector.ready())
    assertEquals(2, connector.read(buffer, 0, 4))
    assertEquals("ef", String(buffer, 0, 2))
  }

  @Test
  fun writesReachTheClientInOrder() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    connector.readText(RESET.length + 2)

    connector.write("echo ")
    connector.write("live-ok\r".toByteArray())

    eventually { client.writes.joinToString("") == "echo live-ok\r" }
    assertEquals(0, listener.readOnly.get())
  }

  @Test
  fun attachesUnsizedAndResizesAfterEverySnapshot() {
    val connector: T3TtyConnector = connector()
    connector.resize(TermSize(120, 40))
    connector.start()
    eventually { client.attached.isNotEmpty() }
    val input: TerminalAttachInput = client.attached.single()
    assertEquals(null to null, input.cols to input.rows)
    Thread.sleep(100)
    assertTrue(client.resizes.isEmpty(), "Nothing to resize before the snapshot")

    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    eventually { client.resizes == listOf(120 to 40) }

    connector.resize(TermSize(100, 30))
    eventually { client.resizes == listOf(120 to 40, 100 to 30) }

    // A re-attach replays the first size, so the current one goes again
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    eventually { client.resizes == listOf(120 to 40, 100 to 30, 100 to 30) }
  }

  @Test
  fun closeDetachesAndEnds() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    connector.readText(RESET.length + 2)

    connector.close()

    runBlocking { withTimeout(5.seconds) { client.detached.await() } }
    assertFalse(connector.isConnected)
    assertEquals(-1, connector.read(CharArray(8), 0, 8))
    assertEquals(-1, connector.read(CharArray(8), 0, 8), "The end stays the end")
    assertEquals(0, connector.waitFor())
    connector.write("ignored")
    assertTrue(client.writes.isEmpty())
  }

  @Test
  fun closedByTheServerEndsAfterSayingSo() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(TerminalAttachStreamEvent.Closed(threadId = "thread-1", terminalId = "term-1"))

    assertEquals("\r\n[terminal] Terminal closed\r\n", connector.readText(100))
    assertFalse(connector.isConnected)
    assertTrue(listener.failures.isEmpty())
  }

  @Test
  fun anEndedStreamSaysClosedAndEnds() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    client.finish()

    assertEquals("$RESET$ \r\n[terminal] Terminal closed\r\n", connector.readText(1_000))
    assertFalse(connector.isConnected)
    assertTrue(listener.failures.isEmpty())
  }

  @Test
  fun anExitedShellIsClosedSoTheNextOpenStartsFresh() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    client.send(
      TerminalAttachStreamEvent.Exited(
        threadId = "thread-1",
        terminalId = DEFAULT_TERMINAL_ID,
        exitCode = 3,
        exitSignal = null,
      ),
    )

    val expected = "$RESET$ \r\n[terminal] Process exited\r\n\r\n[terminal] Terminal closed\r\n"
    assertEquals(expected, connector.readText(1_000))
    assertEquals(listOf(true), client.closes, "Closed once, history deleted, like upstream")
    assertFalse(connector.isConnected)
    assertEquals(3, connector.waitFor())
  }

  @Test
  fun aSnapshotOfAnExitedShellIsClosedToo() {
    val connector: T3TtyConnector = connector()
    connector.start()
    client.send(
      TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "bye", status = "exited")),
    )

    eventually { !connector.isConnected }
    assertEquals(listOf(true), client.closes)
  }

  @Test
  fun readOnlyNeverClosesAnExitedShell() {
    val connector: T3TtyConnector = connector(readOnly = true)
    connector.start()
    client.send(
      TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "bye", status = "exited")),
    )

    connector.readText(RESET.length + 3)
    Thread.sleep(200)
    assertTrue(client.closes.isEmpty())
    assertTrue(connector.isConnected)
  }

  @Test
  fun readOnlyObservesAndDropsKeysWithOneNotice() {
    val connector: T3TtyConnector = connector(readOnly = true)
    connector.start()
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))
    connector.readText(RESET.length + 2)
    connector.resize(TermSize(100, 30))

    connector.write("rm -rf /\r")
    connector.write("again\r")

    assertEquals(1, client.observed.get())
    assertTrue(client.attached.isEmpty())
    assertEquals(1, listener.readOnly.get())
    Thread.sleep(200)
    assertTrue(client.writes.isEmpty())
    assertTrue(client.resizes.isEmpty(), "Resizing needs terminal:operate too")
  }

  @Test
  fun attachWithoutOperateFallsBackToObserving() {
    client.attachFailure = T3Exception.ScopeRequired(
      message = "The authenticated token is missing required scope: terminal:operate.",
      requiredScope = TERMINAL_OPERATE,
      requiredPermission = TERMINAL_OPERATE,
    )
    val connector: T3TtyConnector = connector()
    connector.start()
    eventually { client.observed.get() == 1 }
    client.send(output("watching\r\n"))

    assertEquals("watching\r\n", connector.readText(10))
    connector.write("x")
    assertEquals(1, listener.readOnly.get())
    assertTrue(listener.failures.isEmpty())
  }

  @Test
  fun keysTypedBeforeTheFallbackNeverReachTheServer() {
    client.attachFailure = T3Exception.ScopeRequired("No operate", TERMINAL_OPERATE, null)
    val connector: T3TtyConnector = connector()
    connector.write("typed early\r")
    connector.start()
    eventually { client.observed.get() == 1 }
    client.send(TerminalAttachStreamEvent.Snapshot(snapshot = snapshot(history = "$ ")))

    connector.readText(RESET.length + 2)
    Thread.sleep(200)
    assertTrue(client.writes.isEmpty())
    assertEquals(1, listener.readOnly.get())
  }

  @Test
  fun otherFailuresEndAndReport() {
    client.attachFailure = T3Exception.Rpc("TerminalSessionLookupError", "No terminal")
    val connector: T3TtyConnector = connector()
    connector.start()

    assertEquals(-1, connector.read(CharArray(8), 0, 8))
    assertFalse(connector.isConnected)
    eventually { listener.failures.isNotEmpty() }
    assertIs<T3Exception.Rpc>(listener.failures.single())
    assertEquals(0, client.observed.get())
  }

  @Test
  fun scopeRequiredWithoutReadFails() {
    client.attachFailure = T3Exception.ScopeRequired("No terminals", TERMINAL_OPERATE, null)
    val connector: T3TtyConnector = connector(canObserve = false)
    connector.start()

    assertEquals(-1, connector.read(CharArray(8), 0, 8))
    eventually { listener.failures.isNotEmpty() }
    assertIs<T3Exception.ScopeRequired>(listener.failures.single())
  }

  private fun output(data: String) = TerminalAttachStreamEvent.Output(
    threadId = "thread-1",
    terminalId = DEFAULT_TERMINAL_ID,
    data = data,
  )
}

/** Reads until [count] chars have arrived or the stream ends. */
private fun T3TtyConnector.readText(count: Int): String = buildString {
  val buffer = CharArray(64)
  while (length < count) {
    val read: Int = read(buffer, 0, buffer.size)
    if (read < 0) break
    append(buffer, 0, read)
  }
}

private fun eventually(condition: () -> Boolean) {
  val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
  while (!condition()) {
    check(System.nanoTime() < deadline) { "Timed out waiting" }
    Thread.sleep(10)
  }
}

private class RecordingListener : T3TtyConnector.Listener {
  val readOnly: AtomicInteger = AtomicInteger()
  val failures: MutableList<Throwable> = CopyOnWriteArrayList()

  override fun readOnly() {
    readOnly.incrementAndGet()
  }

  override fun failed(error: Throwable) {
    failures += error
  }
}

// Only the terminal surface is real, anything else fails loudly
private val unused: T3Client = Proxy.newProxyInstance(
  T3Client::class.java.classLoader,
  arrayOf(T3Client::class.java),
) { _, method, _ -> error("${method.name} isn't part of a terminal") } as T3Client

private class FakeTerminalClient : T3Client by unused {
  // Null ends the stream the way a server Exit does, without a closed event
  private val events: MutableSharedFlow<TerminalAttachStreamEvent?> =
    MutableSharedFlow(replay = 64)

  val attached: MutableList<TerminalAttachInput> = CopyOnWriteArrayList()
  val observed: AtomicInteger = AtomicInteger()
  val writes: MutableList<String> = CopyOnWriteArrayList()
  val resizes: MutableList<Pair<Int, Int>> = CopyOnWriteArrayList()
  val closes: MutableList<Boolean> = CopyOnWriteArrayList()
  val detached: CompletableDeferred<Unit> = CompletableDeferred()

  @Volatile var attachFailure: Throwable? = null

  fun send(event: TerminalAttachStreamEvent) = check(events.tryEmit(event))

  fun finish() = check(events.tryEmit(null))

  override fun terminal(input: TerminalAttachInput): Flow<TerminalAttachStreamEvent> = flow {
    attached += input
    attachFailure?.let { throw it }
    stream()
  }

  override fun terminalObserve(
    threadId: String,
    terminalId: String,
  ): Flow<TerminalAttachStreamEvent> = flow {
    observed.incrementAndGet()
    stream()
  }

  private suspend fun FlowCollector<TerminalAttachStreamEvent>.stream() {
    try {
      emitAll(events.takeWhile { it != null }.filterNotNull())
    } finally {
      detached.complete(Unit)
    }
  }

  // What the server does: the session goes, attached clients hear it closed
  override suspend fun terminalClose(threadId: String, terminalId: String, deleteHistory: Boolean) {
    closes += deleteHistory
    send(TerminalAttachStreamEvent.Closed(threadId = threadId, terminalId = terminalId))
  }

  override suspend fun terminalWrite(threadId: String, terminalId: String, data: String) {
    writes += data
  }

  override suspend fun terminalResize(threadId: String, terminalId: String, cols: Int, rows: Int) {
    resizes += cols to rows
  }
}
