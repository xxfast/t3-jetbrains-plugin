package io.github.xxfast.t3code.protocol

import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlInput
import io.github.xxfast.t3code.protocol.generated.AttachmentCreateUploadUrlResult
import io.github.xxfast.t3code.protocol.generated.PersistChatAttachmentsInput
import io.github.xxfast.t3code.protocol.generated.PersistChatAttachmentsResult
import io.github.xxfast.t3code.protocol.generated.TerminalAttachInput
import io.github.xxfast.t3code.protocol.generated.TerminalAttachStreamEvent
import io.github.xxfast.t3code.protocol.generated.VcsStatusStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Where the client is with the server. The tool window header renders this. */
public sealed interface ConnectionState {
  /** Not trying. Initial state, and after [T3Client.disconnect]. */
  public data object Disconnected : ConnectionState

  /** No credential for this server yet. The UI shows the pairing flow. */
  public data object Unpaired : ConnectionState

  public data object Connecting : ConnectionState

  public data class Connected(val session: ServerSession) : ConnectionState

  /** The server rejected the credential. Pair again. */
  public data class AuthError(val message: String) : ConnectionState

  /** Transport trouble. The client retries on its own; [message] is for the header. */
  public data class Error(val message: String) : ConnectionState
}

/**
 * A client of one T3 server. The plugin container holds exactly one, picked at startup: the real
 * one over the WebSocket, or a fixture client that replays JSON from disk. Composables never see
 * this type; they take the state it exposes.
 *
 * State is a mirror of what the server projects. Nothing here reimplements T3 logic.
 */
public interface T3Client {
  public val connection: StateFlow<ConnectionState>

  /** Projects from the shell snapshot, in server order. */
  public val projects: StateFlow<List<Project>>

  /** Active and archived threads, in server order. Filter and sort in the UI. */
  public val threads: StateFlow<List<Thread>>

  /**
   * One thread, live. Subscribes on collect, unsubscribes when the collector goes away. The
   * first emission is the snapshot; later ones are the same view with items upserted by id.
   */
  public fun thread(id: String): Flow<ThreadView>

  /** `projects.readFile`. [path] is workspace-relative or absolute. */
  public suspend fun readFile(cwd: String, path: String): FileContents

  /**
   * The project's favicon as encoded image bytes, or null when the server has none for it.
   * `assets.createUrl` then a GET of the signed URL it answers with; either failing throws.
   */
  public suspend fun projectFavicon(cwd: String, path: String?): ByteArray?

  /** `orchestration.getTurnDiff`: a unified patch for runs [fromTurn] to [toTurn], exclusive. */
  public suspend fun turnDiff(threadId: String, fromTurn: Long, toTurn: Long): String

  /** `orchestration.getFullThreadDiff`: everything up to run [toTurn] as a unified patch. */
  public suspend fun fullThreadDiff(threadId: String, toTurn: Long): String

  /** `orchestration.dispatchCommand`. Returns the server's sequence. */
  public suspend fun dispatch(command: OrchestrationV2Command): Long

  /**
   * `terminal.attach`: a snapshot first (replay its `history`), then live events. Cancelling
   * detaches; it never kills the server's terminal.
   */
  public fun terminal(input: TerminalAttachInput): Flow<TerminalAttachStreamEvent>

  /**
   * `terminal.observe`: the same events as [terminal], for a session that can only read. Needs
   * `terminal:read` where attach needs `terminal:operate`, and never starts a terminal.
   */
  public fun terminalObserve(threadId: String, terminalId: String): Flow<TerminalAttachStreamEvent>

  /**
   * `terminal.close`: ends the server's terminal, and with [deleteHistory] forgets its output, so
   * the next attach starts a fresh shell. Detaching is cancelling [terminal], not this.
   */
  public suspend fun terminalClose(threadId: String, terminalId: String, deleteHistory: Boolean)

  /** `terminal.write`. */
  public suspend fun terminalWrite(threadId: String, terminalId: String, data: String)

  /** `terminal.resize`. */
  public suspend fun terminalResize(threadId: String, terminalId: String, cols: Int, rows: Int)

  /** `subscribeVcsStatus` for [cwd]. */
  public fun vcsStatus(cwd: String): Flow<VcsStatusStreamEvent>

  /**
   * `attachments.createUploadUrl`. Then [upload] the bytes to the signed `relativeUrl`, then
   * [persistAttachments]. Three calls so the UI can show progress and failures per step.
   */
  public suspend fun createUploadUrl(
    input: AttachmentCreateUploadUrlInput,
  ): AttachmentCreateUploadUrlResult

  /**
   * An HTTP POST of [bytes] to [relativeUrl] with `Content-Type: mimeType`, what the web app's
   * `uploadBytes` in `apps/web/src/lib/attachmentUploadQueue.ts` does.
   */
  public suspend fun upload(relativeUrl: String, mimeType: String, bytes: ByteArray)

  /**
   * `attachments.delete`: releases an attachment minted by [createUploadUrl] that no message
   * took, what `deletePendingAttachmentUpload` in
   * `packages/client-runtime/src/state/attachments.ts` does when a draft drops it.
   */
  public suspend fun deleteAttachment(attachmentId: String)

  /** `assets.persistChatAttachments`. */
  public suspend fun persistAttachments(
    input: PersistChatAttachmentsInput,
  ): PersistChatAttachmentsResult

  /** `server.getConfig`, the parts the plugin uses. Cached per connection. */
  public suspend fun serverInfo(): ServerInfo

  /** Start, or start over. Safe to call in any state. */
  public fun connect()

  public fun disconnect()
}

/** Failures the client surfaces to callers of the suspend functions. */
public sealed class T3Exception(message: String) : Exception(message) {
  /** `EnvironmentAuthInvalidError`: the credential is missing or no longer valid. */
  public class AuthInvalid(message: String, public val reason: String?) : T3Exception(message)

  /** `EnvironmentAuthorizationError` or the HTTP 403: scope missing on the token. */
  public class ScopeRequired(
    message: String,
    public val requiredScope: String?,
    public val requiredPermission: String?,
  ) : T3Exception(message)

  /** A typed `Fail` from an RPC. [tag] is upstream's `_tag`. */
  public class Rpc(public val tag: String, message: String) : T3Exception(message)

  /** Transport, decoding, or the socket closing under a request. */
  public class Transport(message: String, cause: Throwable? = null) : T3Exception(message) {
    init {
      if (cause != null) initCause(cause)
    }
  }
}
