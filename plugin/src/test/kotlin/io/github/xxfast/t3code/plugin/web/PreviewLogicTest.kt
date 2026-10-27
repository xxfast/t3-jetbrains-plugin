package io.github.xxfast.t3code.plugin.web

import io.github.xxfast.t3code.plugin.actions.NO_THREAD
import io.github.xxfast.t3code.protocol.generated.PreviewListResult
import io.github.xxfast.t3code.protocol.generated.PreviewNavStatus
import io.github.xxfast.t3code.protocol.generated.PreviewSessionSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class PreviewLogicTest {
  private fun tab(threadId: String, tabId: String) = PreviewSessionSnapshot(
    threadId = threadId,
    tabId = tabId,
    navStatus = PreviewNavStatus.Idle(),
    canGoBack = false,
    canGoForward = false,
    updatedAt = "2026-10-27T00:00:00.000Z",
  )

  @Test
  fun `a preview belongs to the thread its snapshot names`() {
    val result = PreviewListResult(
      sessions = listOf(tab("thr_a", "browser-1"), tab("thr_b", "browser-2")),
      serverEpoch = "epoch",
      revision = 3,
    )
    assertEquals(listOf("browser-1"), previewSessionsFor(result, "thr_a").map { it.tabId })
    assertEquals(emptyList(), previewSessionsFor(result, "thr_c"))
  }

  @Test
  fun `open preview is on with a tab, or before the server has answered`() {
    assertNull(openPreviewDisabledReason(true, "env", listOf(tab("thr_a", "browser-1"))))
    assertNull(openPreviewDisabledReason(true, "env", null))
  }

  @Test
  fun `open preview says why it's off`() {
    assertEquals(NO_THREAD, openPreviewDisabledReason(false, "env", null))
    assertEquals("Waiting for the server's config", openPreviewDisabledReason(true, null, null))
    assertEquals(NO_PREVIEW, openPreviewDisabledReason(true, "env", emptyList()))
  }
}
