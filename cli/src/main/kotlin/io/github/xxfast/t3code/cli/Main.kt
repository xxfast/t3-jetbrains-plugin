package io.github.xxfast.t3code.cli

import io.github.xxfast.t3code.protocol.ConnectionState
import io.github.xxfast.t3code.protocol.Credential
import io.github.xxfast.t3code.protocol.Project
import io.github.xxfast.t3code.protocol.T3Auth
import io.github.xxfast.t3code.protocol.T3Client
import io.github.xxfast.t3code.protocol.T3Exception
import io.github.xxfast.t3code.protocol.Thread
import io.github.xxfast.t3code.protocol.WebSocketT3Client
import io.github.xxfast.t3code.protocol.archived
import io.github.xxfast.t3code.protocol.generated.TurnItem
import java.time.Instant
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** A failure worth one line and exit 1. */
class CliException(message: String) : Exception(message)

/** How long `threads` waits for the shell snapshot before calling the server empty. */
private const val SNAPSHOT_TIMEOUT_MILLIS: Long = 10_000

/** How many of the thread's items `tail` prints before following it, like `tail`'s 10 lines. */
private const val BACKLOG: Int = 10

fun main(args: Array<String>) {
  val code: Int = runBlocking(Dispatchers.Default) { run(args.toList()) }
  exitProcess(code)
}

private suspend fun run(args: List<String>): Int {
  val command: Command = try {
    parse(args)
  } catch (e: UsageException) {
    System.err.println("t3: ${e.message}")
    System.err.println(USAGE)
    return 2
  }
  return try {
    when (command) {
      is Command.Pair -> pair(command)
      Command.Threads -> connected { client -> threads(client) }
      is Command.Tail -> connected { client -> tail(client, command.threadId) }
    }
    0
  } catch (e: T3Exception) {
    System.err.println("t3: ${e.message ?: e.javaClass.simpleName}")
    1
  } catch (e: CliException) {
    System.err.println("t3: ${e.message}")
    1
  }
}

private suspend fun pair(command: Command.Pair) {
  val credential: Credential = T3Auth(command.server).pair(command.codeOrLink, label = "CLI")
  writeConfig(DefaultConfigPath, configOf(command.server, credential, Instant.now()))
  println("Paired with ${command.server}, saved to $DefaultConfigPath")
  println("Scopes: ${credential.scopes.joinToString(" ").ifEmpty { "none listed" }}")
}

/** Runs [block] on a client connected with the saved token, and tears it down after. */
private suspend fun connected(block: suspend (T3Client) -> Unit) {
  val config: CliConfig = readConfig(DefaultConfigPath)
    ?: throw CliException("not paired, run `t3 pair <code-or-link>` first")
  if (config.expired(Instant.now())) {
    throw CliException("token expired at ${config.expiresAt}, pair again")
  }
  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val client: T3Client = WebSocketT3Client(
    baseUrl = { config.server },
    credential = { Credential(accessToken = config.accessToken) },
    scope = scope,
  )
  try {
    client.connect()
    // The client retries an Error on its own; a CLI gives up on the first one
    when (val state: ConnectionState = client.connection.first { it.settled }) {
      is ConnectionState.Connected -> block(client)
      is ConnectionState.AuthError -> throw CliException("${state.message}, pair again")
      is ConnectionState.Error -> throw CliException(state.message)
      else -> throw CliException("not paired, run `t3 pair <code-or-link>` first")
    }
  } finally {
    client.disconnect()
    scope.cancel()
  }
}

private val ConnectionState.settled: Boolean
  get() = this !is ConnectionState.Disconnected && this !is ConnectionState.Connecting

/**
 * Every thread that isn't archived, newest `updatedAt` first. Not the Sidebar's order, that's
 * `sections` in the plugin and not worth porting here.
 */
private suspend fun threads(client: T3Client) {
  // Connected lands before the shell snapshot does, and T3Client has nothing that says the
  // snapshot is in. So an empty list after the timeout is an empty server, or a slow one.
  val threads: List<Thread> = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) {
    client.threads.first { it.isNotEmpty() }
  }.orEmpty()
  val projects: Map<String, Project> = client.projects.value.associateBy { it.id }
  val rows: List<Thread> = threads.filterNot { it.archived }.sortedByDescending { it.updatedAt }
  if (rows.isEmpty()) println("No threads")
  rows.forEach { thread -> println(threadLine(thread, projects[thread.projectId]?.title)) }
}

fun threadLine(thread: Thread, projectTitle: String?): String =
  listOf(thread.id, thread.status, projectTitle ?: thread.projectId, thread.title)
    .joinToString("\t")

/**
 * The last [BACKLOG] items, then each new or changed one as it lands, until Ctrl+C. A message
 * still streaming prints once it's done, not on every tick.
 */
private suspend fun tail(client: T3Client, threadId: String) {
  val printed: MutableMap<String, String> = mutableMapOf()
  var first = true
  client.thread(threadId).collect { view ->
    val lines: List<Pair<String, String>> = view.items.mapNotNull { item ->
      val line: String = itemLine(item) ?: return@mapNotNull null
      if (printed[item.id] == line) null else item.id to line
    }
    lines.forEach { (id, line) -> printed[id] = line }
    (if (first) lines.takeLast(BACKLOG) else lines).forEach { (_, line) -> println(line) }
    first = false
  }
  throw CliException("the server ended the thread stream")
}

/** `type status text`, text on one line. Null while a text item is still streaming. */
fun itemLine(item: TurnItem): String? {
  val text: String? = when (item) {
    is TurnItem.UserMessage -> item.text
    is TurnItem.AssistantMessage -> if (item.streaming) return null else item.text
    is TurnItem.Reasoning -> if (item.streaming) return null else item.text
    is TurnItem.ProposedPlan -> if (item.streaming) return null else item.title
    // Tools mostly come without a title; what they ran or touched says more
    is TurnItem.CommandExecution -> item.title ?: item.input
    is TurnItem.FileChange -> item.title ?: item.fileName
    else -> item.title
  }
  val oneLine: String? = text?.replace(Whitespace, " ")?.trim()?.ifEmpty { null }
  val clipped: String? = oneLine
    ?.let { if (it.length > MAX_TEXT) it.take(MAX_TEXT - 3) + "..." else it }
  return listOfNotNull(item.type, item.status, clipped).joinToString(" ")
}

private val Whitespace: Regex = Regex("\\s+")

private const val MAX_TEXT: Int = 200
