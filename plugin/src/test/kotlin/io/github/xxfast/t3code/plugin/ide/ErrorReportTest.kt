package io.github.xxfast.t3code.plugin.ide

import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ErrorReportTest {
  private val environment = ErrorReportEnvironment(
    pluginVersion = "0.1.0",
    ide = "Android Studio Quail 3 | 2026.1.3 (AI-261.26222.65.2613.15948027)",
    os = "macOS 26.1 aarch64",
    jbr = "21.0.8+9-b1138.52",
  )

  /** The #63 trace: our frame, then the Compose, Swing and EDT tail that never helps. */
  private val issue63: String = (listOf(
    "java.lang.NoSuchMethodError: 'void org.jetbrains.jewel.ui.component.ScrollableContainerKt" +
      ".VerticallyScrollableContainer(androidx.compose.ui.Modifier, androidx.compose.ui.Modifier," +
      " androidx.compose.foundation.ScrollState, org.jetbrains.jewel.ui.component.styling." +
      "ScrollbarStyle, boolean, boolean, boolean, androidx.compose.foundation.interaction." +
      "MutableInteractionSource, androidx.compose.foundation.v2.ScrollbarAdapter, kotlin.jvm." +
      "functions.Function3, androidx.compose.runtime.Composer, int, int)'",
    "\tat io.github.xxfast.t3code.plugin.ui.SidebarKt.Sections\$lambda\$6(Sidebar.kt:337)",
    "\tat io.github.xxfast.t3code.plugin.ui.SidebarKt.Sections(Sidebar.kt:290)",
    "\tat io.github.xxfast.t3code.plugin.ui.SidebarKt.Sidebar(Sidebar.kt:120)",
  ) + (1..40).map { "\tat androidx.compose.runtime.ComposerImpl.recompose(Composer.kt:$it)" } +
    (1..20).map { "\tat java.desktop/java.awt.EventDispatchThread.run(Thread.java:$it)" })
    .joinToString("\n")

  @Test
  fun `title is the simple class name and the message`() {
    assertEquals(
      "IllegalStateException: thread not found",
      errorReportTitle("java.lang.IllegalStateException: thread not found\n\tat a.b.C(C.kt:1)"),
    )
    assertEquals("NullPointerException", errorReportTitle("java.lang.NullPointerException"))
    assertEquals(120, errorReportTitle("x.Err: " + "m".repeat(500)).length)
  }

  @Test
  fun `keeps frames to the last of ours plus three, trims the rest with a count`() {
    val trimmed: List<String> = trimTrace(issue63).lines()

    assertEquals(
      listOf(
        "\tat io.github.xxfast.t3code.plugin.ui.SidebarKt.Sections\$lambda\$6(Sidebar.kt:337)",
        "\tat io.github.xxfast.t3code.plugin.ui.SidebarKt.Sections(Sidebar.kt:290)",
        "\tat io.github.xxfast.t3code.plugin.ui.SidebarKt.Sidebar(Sidebar.kt:120)",
        "\tat androidx.compose.runtime.ComposerImpl.recompose(Composer.kt:1)",
        "\tat androidx.compose.runtime.ComposerImpl.recompose(Composer.kt:2)",
        "\tat androidx.compose.runtime.ComposerImpl.recompose(Composer.kt:3)",
        "\t... (57 frames trimmed)",
      ),
      trimmed.drop(1),
    )
    assertTrue(trimmed.first().startsWith("java.lang.NoSuchMethodError"))
  }

  @Test
  fun `each cause is trimmed on its own`() {
    val trace: String = listOf(
      "java.lang.RuntimeException: outer",
      "\tat io.github.xxfast.t3code.plugin.T3.pair(T3.kt:60)",
      "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:1)",
      "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:2)",
      "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:3)",
      "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:4)",
      "Caused by: java.io.IOException: inner",
      "\tat java.net.Socket.connect(Socket.java:1)",
      "\tat java.net.Socket.connect(Socket.java:2)",
      "\tat java.net.Socket.connect(Socket.java:3)",
      "\tat java.net.Socket.connect(Socket.java:4)",
      "\tat io.github.xxfast.t3code.protocol.T3Auth.pair(T3Auth.kt:30)",
      "\t... 12 more",
    ).joinToString("\n")

    assertEquals(
      listOf(
        "java.lang.RuntimeException: outer",
        "\tat io.github.xxfast.t3code.plugin.T3.pair(T3.kt:60)",
        "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:1)",
        "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:2)",
        "\tat kotlinx.coroutines.BuildersKt.launch(Builders.kt:3)",
        "\t... (1 frames trimmed)",
        "Caused by: java.io.IOException: inner",
        "\tat java.net.Socket.connect(Socket.java:1)",
        "\tat java.net.Socket.connect(Socket.java:2)",
        "\tat java.net.Socket.connect(Socket.java:3)",
        "\tat java.net.Socket.connect(Socket.java:4)",
        "\tat io.github.xxfast.t3code.protocol.T3Auth.pair(T3Auth.kt:30)",
        "\t... 12 more",
      ),
      trimTrace(trace).lines(),
    )
  }

  @Test
  fun `a section with none of our frames keeps its first three`() {
    val trace: String = listOf(
      "java.lang.IllegalStateException: elsewhere",
      "\tat a.A.a(A.kt:1)",
      "\tat a.A.b(A.kt:2)",
      "\tat a.A.c(A.kt:3)",
      "\tat a.A.d(A.kt:4)",
    ).joinToString("\n")

    assertEquals(
      listOf(
        "java.lang.IllegalStateException: elsewhere",
        "\tat a.A.a(A.kt:1)",
        "\tat a.A.b(A.kt:2)",
        "\tat a.A.c(A.kt:3)",
        "\t... (1 frames trimmed)",
      ),
      trimTrace(trace).lines(),
    )
  }

  @Test
  fun `the issue63 trace fits in the link with the comment and environment`() {
    val report: ErrorReportUrl = errorReportUrl(issue63, environment, "Opened the Sidebar")

    assertNull(report.clipboard)
    assertTrue(report.url.length <= ERROR_REPORT_URL_LIMIT)
    assertTrue(report.url.startsWith("$ERROR_REPORT_ISSUES_URL?title="))

    val query: Map<String, String> = report.url.substringAfter('?').split('&')
      .associate { pair -> pair.substringBefore('=') to decode(pair.substringAfter('=')) }
    assertEquals("bug", query["labels"])
    assertTrue(query.getValue("title").startsWith("NoSuchMethodError: 'void org.jetbrains.jewel"))
    val body: String = query.getValue("body")
    assertTrue(body.startsWith("Opened the Sidebar\n\n**Plugin** 0.1.0\n**IDE** Android Studio"))
    assertTrue("**JBR** 21.0.8+9-b1138.52\n\n```\njava.lang.NoSuchMethodError" in body)
    assertTrue("\t... (57 frames trimmed)\n```" in body)
    assertFalse("Composer.kt:40" in body)
  }

  @Test
  fun `past the limit the body keeps the headers and the trace goes to the clipboard`() {
    // Every frame is ours, so trimming keeps all of them and the link can't shrink that way
    val trace: String = "java.lang.RuntimeException: deep\n" +
      (1..200).joinToString("\n") { "\tat io.github.xxfast.t3code.plugin.Deep.call(Deep.kt:$it)" } +
      "\nCaused by: java.io.IOException: inner\n" +
      "\tat io.github.xxfast.t3code.protocol.T3Auth.pair(T3Auth.kt:30)"

    val report: ErrorReportUrl = errorReportUrl(trace, environment, limit = 3_000)

    assertEquals(trace, report.clipboard)
    assertTrue(report.url.length <= 3_000, "was ${report.url.length}")
    val body: String = decode(report.url.substringAfter("&body="))
    assertTrue("Full trace on your clipboard, paste it here." in body)
    assertTrue("Caused by: java.io.IOException: inner" in body)
    assertFalse("T3Auth.kt:30" in body)
  }

  private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
}
