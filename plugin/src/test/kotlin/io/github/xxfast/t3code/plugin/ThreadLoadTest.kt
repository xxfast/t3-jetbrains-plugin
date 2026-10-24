package io.github.xxfast.t3code.plugin

import io.github.xxfast.t3code.plugin.fixtures.FixtureClient
import io.github.xxfast.t3code.plugin.ui.DraftThread
import io.github.xxfast.t3code.plugin.ui.draftThreadView
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.ThreadView
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ThreadLoadTest {
  private val scenarios: Path = Path.of(System.getProperty("t3code.scenarios"))

  @Test
  fun `starts empty, then the views, then the error next to the last view`() = runTest {
    val fixtures = FixtureClient(scenarios.resolve("default"), backgroundScope)
    val view: ThreadView = fixtures.thread(IDLE).first()
    val client: T3Client = object : T3Client by fixtures {
      override fun thread(id: String): Flow<ThreadView> = flow {
        emit(view)
        throw IllegalStateException("thread not found")
      }
    }

    val loads: List<ThreadLoad> = client.threadLoad(IDLE).toList()

    assertEquals(
      listOf(ThreadLoad(), ThreadLoad(view = view), ThreadLoad(view, "thread not found")),
      loads,
    )
  }

  @Test
  fun `a failure before any view is just the error`() = runTest {
    val fixtures = FixtureClient(scenarios.resolve("default"), backgroundScope)
    val client: T3Client = object : T3Client by fixtures {
      override fun thread(id: String): Flow<ThreadView> = flow { error("Die") }
    }

    val last: ThreadLoad = client.threadLoad(IDLE).toList().last()

    assertNull(last.view)
    assertEquals("Die", last.error)
  }

  @Test
  fun `an empty stream is only the empty load`() = runTest {
    val fixtures = FixtureClient(scenarios.resolve("default"), backgroundScope)
    val client: T3Client = object : T3Client by fixtures {
      override fun thread(id: String): Flow<ThreadView> = emptyFlow()
    }

    assertSame(null, client.threadLoad(IDLE).toList().single().view)
  }

  @Test
  fun `a draft's view, then the server's without the empty load between`() = runTest {
    val fixtures = FixtureClient(scenarios.resolve("default"), backgroundScope)
    val view: ThreadView = fixtures.thread(IDLE).first()
    val client: T3Client = object : T3Client by fixtures {
      override fun thread(id: String): Flow<ThreadView> = flowOf(view)
    }
    val draft = DraftThread(IDLE, "p", "1970-01-01T00:00:00Z", view.thread.modelSelection)

    // Promoted a moment later, once the draft's view is out
    val drafts: Flow<DraftThread?> = flow {
      emit(draft)
      delay(1)
      emit(null)
    }

    val loads: List<ThreadLoad> = client.threadLoad(IDLE, drafts).toList()

    assertEquals(listOf(ThreadLoad(draftThreadView(draft)), ThreadLoad(view = view)), loads)
  }

  private companion object {
    const val IDLE = "thr_01JC3QX81VIDLE001"
  }
}
