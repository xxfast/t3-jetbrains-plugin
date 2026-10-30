package io.github.xxfast.t3code.cli

const val DEFAULT_SERVER: String = "http://127.0.0.1:3773"

const val USAGE: String = """usage:
  t3 pair <code-or-link> [--server $DEFAULT_SERVER]
  t3 threads
  t3 tail <threadId>"""

sealed interface Command {
  data class Pair(val codeOrLink: String, val server: String = DEFAULT_SERVER) : Command

  data object Threads : Command

  data class Tail(val threadId: String) : Command
}

/** Bad arguments. Prints with [USAGE]. */
class UsageException(message: String) : Exception(message)

fun parse(args: List<String>): Command {
  val name: String = args.firstOrNull() ?: throw UsageException("no command")
  val rest: List<String> = args.drop(1)
  return when (name) {
    "pair" -> {
      var server: String = DEFAULT_SERVER
      val positional: MutableList<String> = mutableListOf()
      val each: Iterator<String> = rest.iterator()
      while (each.hasNext()) {
        val arg: String = each.next()
        when {
          arg == "--server" -> {
            if (!each.hasNext()) throw UsageException("--server needs a url")
            server = each.next()
          }

          arg.startsWith("--") -> throw UsageException("unknown option $arg")
          else -> positional += arg
        }
      }
      val code: String = positional.singleOrNull()
        ?: throw UsageException("pair takes one pairing code or link")
      Command.Pair(code, server.trimEnd('/'))
    }

    "threads" -> {
      if (rest.isNotEmpty()) throw UsageException("threads takes no arguments")
      Command.Threads
    }

    "tail" -> Command.Tail(rest.singleOrNull() ?: throw UsageException("tail takes one thread id"))
    else -> throw UsageException("unknown command $name")
  }
}
