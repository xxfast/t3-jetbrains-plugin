package io.github.xxfast.t3code.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.intellij.openapi.project.Project
import io.github.xxfast.t3code.plugin.ide.chooseAttachmentFiles
import io.github.xxfast.t3code.plugin.ui.AttachmentActions

/** The composer's attachment and context actions for [threadId], wired to [controller]. */
@Composable
fun rememberAttachmentActions(
  project: Project,
  threadId: String,
  controller: ChatController,
): AttachmentActions = remember(project, threadId, controller) {
  AttachmentActions(
    onFiles = { paths -> controller.attachFiles(threadId, paths) },
    onPasteImage = { image -> controller.attachImage(threadId, image) },
    onPickFiles = {
      chooseAttachmentFiles(project) { paths -> controller.attachFiles(threadId, paths) }
    },
    onRemoveAttachment = { id -> controller.removeAttachment(threadId, id) },
    onRetryAttachment = { id -> controller.retryAttachment(threadId, id) },
    onRemoveContext = { contextId -> controller.removeContext(threadId, contextId) },
    onRefused = { reason -> controller.refuse(threadId, reason) },
  )
}
