// Generated from v0.1.1-preview.20261021.3449 by contracts/generate.ts, do not edit.

package io.github.xxfast.t3code.protocol.generated

import kotlinx.serialization.Serializable

@Serializable
public data class ThreadLaunchInput(
  val commandId: String,
  val creationSource: String? = null,
  val threadId: String? = null,
  val reuseExistingThread: Boolean? = null,
  val projectId: String,
  val title: String,
  val generateTitle: Boolean? = null,
  val modelSelection: ModelSelection,
  val runtimeMode: String,
  val interactionMode: String,
  val workspaceStrategy: ThreadLaunchWorkspaceStrategy,
  val initialMessage: ThreadLaunchInput.InitialMessage? = null,
) {
  @Serializable
  public data class InitialMessage(
    val messageId: String? = null,
    val text: String,
    val context: MessageContext? = null,
    val attachments: List<ChatAttachment>,
  )
}
