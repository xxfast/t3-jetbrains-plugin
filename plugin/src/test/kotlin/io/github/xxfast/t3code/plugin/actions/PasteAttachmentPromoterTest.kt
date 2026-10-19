package io.github.xxfast.t3code.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import io.github.xxfast.t3code.plugin.ui.AttachmentActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasteAttachmentPromoterTest {
  private val paste: AnAction = object : AnAction("Paste") {
    override fun actionPerformed(e: AnActionEvent) = Unit
  }
  private val ours: AnAction = PasteAttachmentAction()
  private val promoter = PasteAttachmentPromoter()

  // SimpleDataContext wants a running application; this doesn't
  private val composer: DataContext = DataContext { id ->
    AttachmentActions().takeIf { id == T3DataKeys.ATTACHMENTS.name }
  }

  @Test
  fun `over the composer the attachment paste goes first, the rest keep their order`() {
    val another: AnAction = object : AnAction("Other") {
      override fun actionPerformed(e: AnActionEvent) = Unit
    }
    assertEquals(
      listOf(ours, paste, another),
      promoter.promote(listOf(paste, another, ours), composer),
    )
  }

  @Test
  fun `anywhere else the promoter stays out of it`() {
    assertNull(promoter.promote(listOf(paste, ours), DataContext.EMPTY_CONTEXT))
  }
}
