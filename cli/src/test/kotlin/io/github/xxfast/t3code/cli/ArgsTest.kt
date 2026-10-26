package io.github.xxfast.t3code.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArgsTest {
  @Test
  fun pairDefaultsToTheDesktopServer() {
    val command: Command = parse(listOf("pair", "ABCDEF123456"))
    assertEquals(Command.Pair("ABCDEF123456", DEFAULT_SERVER), command)
  }

  @Test
  fun pairTakesAServerEitherSideOfTheCode() {
    val expected: Command = Command.Pair("http://host:3773/pair#token=CODE", "http://host:3773")
    assertEquals(
      expected,
      parse(listOf("pair", "http://host:3773/pair#token=CODE", "--server", "http://host:3773/")),
    )
    assertEquals(
      expected,
      parse(listOf("pair", "--server", "http://host:3773", "http://host:3773/pair#token=CODE")),
    )
  }

  @Test
  fun threadsAndTail() {
    assertEquals(Command.Threads, parse(listOf("threads")))
    assertEquals(Command.Tail("thr_1"), parse(listOf("tail", "thr_1")))
  }

  @Test
  fun badArgumentsAreUsageErrors() {
    listOf(
      emptyList(),
      listOf("nope"),
      listOf("pair"),
      listOf("pair", "a", "b"),
      listOf("pair", "a", "--server"),
      listOf("pair", "a", "--verbose"),
      listOf("threads", "extra"),
      listOf("tail"),
      listOf("tail", "a", "b"),
    ).forEach { args ->
      assertFailsWith<UsageException>("$args") { parse(args) }
    }
  }
}
