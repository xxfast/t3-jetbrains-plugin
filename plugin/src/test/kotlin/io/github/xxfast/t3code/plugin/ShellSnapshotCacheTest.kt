package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.plugin.ui.staleSnapshotLine
import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.Thread
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ShellSnapshotCacheTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))

  @TempDir
  lateinit var directory: Path

  @Test
  fun `the file round trips per server, and anything unreadable is no file`() = runTest {
    val (projects, threads) = rows()
    val store = ShellSnapshotStore(directory.resolve("t3code"))
    val snapshot = ShellSnapshot(URL, SAVED.toString(), projects, threads)

    store.save(snapshot)

    assertEquals(snapshot, store.load(URL))
    assertNull(store.load("http://elsewhere:3773"))
    directory.resolve("t3code").listDirectoryEntries().single().writeText("{not json")
    assertNull(store.load(URL))
  }

  @Test
  fun `the file while disconnected, live once connected, the drop's moment after`() = runTest {
    val (projects, threads) = rows()
    val store = ShellSnapshotStore(directory)
    store.save(ShellSnapshot(URL, SAVED.toString(), projects, threads))
    val fake = FakeShellClient(fixtures())
    var now: Instant = SAVED.plusSeconds(3_600)
    val cache: ShellSnapshotCache = cache(fake, store) { now }
    settle()

    assertEquals(threads, cache.threads.value)
    assertEquals(projects, cache.projects.value)
    assertEquals(SAVED, cache.stale.value)

    val live: List<Thread> = threads.take(2)
    fake.connection.value = connected()
    fake.projects.value = projects
    fake.threads.value = live
    settle()

    assertEquals(live, cache.threads.value)
    assertNull(cache.stale.value)
    assertEquals(now.toString(), store.load(URL)?.savedAt)
    assertEquals(live, store.load(URL)?.threads)

    now = now.plusSeconds(600)
    fake.connection.value = ConnectionState.Error("socket closed")
    settle()

    // The socket's list, not the file's, and as old as the drop
    assertEquals(live, cache.threads.value)
    assertEquals(now, cache.stale.value)
    assertEquals(now.toString(), store.load(URL)?.savedAt)
  }

  @Test
  fun `threads that arrive while disconnected are stale, and never written`() = runTest {
    val (projects, threads) = rows()
    val store = ShellSnapshotStore(directory)
    val fake = FakeShellClient(fixtures())
    val cache: ShellSnapshotCache = cache(fake, store) { SAVED }
    fake.projects.value = projects
    fake.threads.value = threads
    settle()

    assertEquals(threads, cache.threads.value)
    assertEquals(SAVED, cache.stale.value)
    assertNull(store.load(URL))
  }

  @Test
  fun `nothing to show is not stale`() = runTest {
    val cache: ShellSnapshotCache =
      cache(FakeShellClient(fixtures()), ShellSnapshotStore(directory)) { SAVED }
    settle()

    assertTrue(cache.threads.value.isEmpty())
    assertNull(cache.stale.value)
  }

  @Test
  fun `unpaired never shows the file`() = runTest {
    val (projects, threads) = rows()
    val store = ShellSnapshotStore(directory)
    store.save(ShellSnapshot(URL, SAVED.toString(), projects, threads))
    val cache: ShellSnapshotCache =
      cache(FakeShellClient(fixtures(), ConnectionState.Unpaired), store) { SAVED }
    settle()

    assertTrue(cache.threads.value.isEmpty())
    assertNull(cache.stale.value)
  }

  @Test
  fun `the stale line says how old`() {
    assertEquals("Showing the last snapshot from just now.", staleSnapshotLine(SAVED, SAVED))
    assertEquals(
      "Showing the last snapshot from 5m ago.",
      staleSnapshotLine(SAVED, SAVED.plusSeconds(300)),
    )
  }

  private suspend fun TestScope.rows(): Pair<List<Project>, List<Thread>> {
    val fixtures = FixtureClient(scenarios.resolve("default"), backgroundScope)
    val threads: List<Thread> = fixtures.threads.first { it.isNotEmpty() }
    return fixtures.projects.value to threads
  }

  private fun TestScope.fixtures(): T3Client =
    FixtureClient(scenarios.resolve("empty-server"), backgroundScope)

  private fun TestScope.cache(
    fake: T3Client,
    store: ShellSnapshotStore,
    clock: () -> Instant,
  ): ShellSnapshotCache = ShellSnapshotCache(
    inner = fake,
    serverUrl = { URL },
    store = store,
    scope = backgroundScope,
    clock = clock,
    io = StandardTestDispatcher(testScheduler),
    debounce = 1.seconds,
  )

  // Past the debounce, without running the fixture client's poll forever
  private fun TestScope.settle() {
    testScheduler.advanceTimeBy(2_000)
    testScheduler.runCurrent()
  }

  private companion object {
    const val URL: String = "http://127.0.0.1:3773"
    val SAVED: Instant = Instant.parse("2026-10-27T09:00:00Z")
  }
}
