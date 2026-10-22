package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ChatFileAttachment
import io.github.xxfast.t3code.protocol.generated.ChatImageAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ComposerDraftStoreLogicTest {
  private val ready: DraftAttachment = draft(
    "notes.txt",
    upload = Upload.Ready(
      ChatFileAttachment(id = "att_1", name = "notes.txt", mimeType = "text/plain", sizeBytes = 1),
    ),
  )
  private val uploading: DraftAttachment =
    draft("a.png", kind = AttachmentKind.IMAGE, mimeType = "image/png")
  private val comment: ReviewCommentContext =
    fileReviewComment("c1", "src/a.kt", 1, 2, "look", "one\ntwo")

  @Test
  fun anEmptyDraftIsNotKeptButWhitespaceIs() {
    assertNull(persistedDraft("", DraftExtras()))
    // Upstream checks `prompt.length`, not the trimmed prompt
    assertEquals(PersistedComposerDraft(prompt = " "), persistedDraft(" ", DraftExtras()))
  }

  @Test
  fun aDraftRoundTripsThroughJson() {
    val extras = DraftExtras(
      attachments = listOf(ready, uploading),
      comments = listOf(comment),
      notice = "transient",
    )
    val saved: PersistedComposerDraft = persistedDraft("hello", extras)!!
    val decoded: PersistedComposerDraft = decodeDraft(encodeDraft(saved))!!
    assertEquals(saved, decoded)

    val restored: DraftExtras = hydratedExtras(decoded)
    assertNull(restored.notice, "The notice doesn't persist")
    assertEquals(listOf(comment), restored.comments)
    assertEquals(ready.copy(preview = null), restored.attachments[0])
    // No bytes persist, so one that wasn't up comes back asking to be attached again
    assertEquals(Upload.Failed(ATTACH_AGAIN), restored.attachments[1].upload)
    assertEquals(AttachmentKind.IMAGE, restored.attachments[1].kind)
  }

  @Test
  fun anUploadedImageComesBackAsItsServerReference() {
    val image: DraftAttachment = uploading.copy(
      upload = Upload.Ready(
        ChatImageAttachment(id = "att_2", name = "a.png", mimeType = "image/png", sizeBytes = 1),
      ),
    )
    val saved: PersistedComposerDraft = persistedDraft("", DraftExtras(listOf(image)))!!
    assertEquals("att_2", saved.attachments.single().attachmentId)
    assertEquals(image, hydratedExtras(saved).attachments.single())
  }

  @Test
  fun unreadableJsonIsDropped() {
    assertNull(decodeDraft("{not json"))
    assertEquals(emptyList(), decodeStash("[{\"id\":1}"))
    assertEquals(emptyList(), decodeStash(null))
  }

  @Test
  fun unknownKeysFromANewerBuildAreIgnored() {
    val decoded: PersistedComposerDraft? = decodeDraft("{\"prompt\":\"hi\",\"runtimeMode\":\"x\"}")
    assertEquals(PersistedComposerDraft(prompt = "hi"), decoded)
  }

  @Test
  fun draftsAreKeyedByServerAndThread() {
    val one: String = composerDraftKey("http://127.0.0.1:3773", "t1")
    val other: String = composerDraftKey("http://10.0.0.2:3773", "t1")
    assertNotEquals(one, other)
  }

  @Test
  fun theStashKeyStashesARealDraft() {
    assertEquals(StashKey.Stash, stashKey("hi", DraftExtras(), emptyList()))
    assertEquals(StashKey.Stash, stashKey("", DraftExtras(listOf(ready)), emptyList()))
  }

  @Test
  fun theStashKeyOnAnEmptyComposerRestoresTheOnlyEntryOrTogglesTheMenu() {
    val entry: PromptStashEntry = entry("e1")
    assertEquals(StashKey.ToggleMenu, stashKey("  ", DraftExtras(), emptyList()))
    assertEquals(StashKey.Restore(entry), stashKey("", DraftExtras(), listOf(entry)))
    assertEquals(StashKey.ToggleMenu, stashKey("", DraftExtras(), listOf(entry, entry("e2"))))
    // Context alone doesn't count as something to stash
    val onlyContext = DraftExtras(comments = listOf(comment))
    assertEquals(StashKey.ToggleMenu, stashKey("", onlyContext, emptyList()))
  }

  @Test
  fun theStashKeyWaitsOnUploads() {
    val waiting: StashKey = stashKey("hi", DraftExtras(listOf(uploading)), emptyList())
    assertEquals(StashKey.Refused(STASH_WAIT_FOR_UPLOADS), waiting)
    val dropped = uploading.copy(upload = Upload.Failed(ATTACH_AGAIN))
    val reattach: StashKey = stashKey("hi", DraftExtras(listOf(dropped)), emptyList())
    assertEquals(StashKey.Refused(STASH_REATTACH_FIRST), reattach)
  }

  @Test
  fun aStashEntryTrimsThePromptAndKeepsReferences() {
    val extras = DraftExtras(attachments = listOf(ready), comments = listOf(comment))
    val entry: PromptStashEntry = stashEntry("e1", 5, "s", "  hi  \n", extras)
    assertEquals("hi", entry.prompt)
    assertEquals("att_1", entry.attachments.single().attachmentId)
    assertEquals(listOf(comment), entry.reviewComments)
  }

  @Test
  fun theStashIsNewestFirstAndEvictsTheOldestPastTheCap() {
    var entries: List<PromptStashEntry> = emptyList()
    repeat(MAX_STASH_ENTRIES) { index -> entries = stashed(entries, entry("e$index")).entries }
    assertEquals("e${MAX_STASH_ENTRIES - 1}", entries.first().id)
    val result: Stashed = stashed(entries, entry("new"))
    assertEquals(MAX_STASH_ENTRIES, result.entries.size)
    assertEquals("new", result.entries.first().id)
    assertEquals("e0", result.evicted?.id)
    assertNull(stashed(emptyList(), entry("x")).evicted)
  }

  @Test
  fun theStashRoundTripsThroughJson() {
    val entries: List<PromptStashEntry> = listOf(
      stashEntry("e1", 5, "s", "hi", DraftExtras(listOf(ready), listOf(comment))),
      entry("e2"),
    )
    assertEquals(entries, decodeStash(encodeStash(entries)))
  }

  @Test
  fun filesOnlyRestoreOnTheServerThatHasThem() {
    val withFiles: PromptStashEntry =
      stashEntry("e1", 5, "server-a", "hi", DraftExtras(listOf(ready)))
    assertNull(stashRestoreBlock(withFiles, "server-a"))
    assertEquals(STASH_OTHER_ENVIRONMENT, stashRestoreBlock(withFiles, "server-b"))
    assertNull(stashRestoreBlock(entry("e2"), "server-b"), "Text alone restores anywhere")
  }

  @Test
  fun aRestoredPromptGoesAfterWhatsThereABlankLineApart() {
    assertEquals("stashed", restoredPrompt("", "stashed"))
    assertEquals("stashed", restoredPrompt("  \n", "stashed"))
    assertEquals("mine\n\nstashed", restoredPrompt("mine  \n", "stashed"))
    assertEquals("mine", restoredPrompt("mine", ""))
  }

  @Test
  fun restoredExtrasMergeWithoutDuplicates() {
    val entry: PromptStashEntry =
      stashEntry("e1", 5, "s", "hi", DraftExtras(listOf(ready), listOf(comment)))
    val current = DraftExtras(attachments = listOf(ready), comments = listOf(comment))
    assertEquals(current, restoredExtras(current, entry))

    val empty: DraftExtras = restoredExtras(DraftExtras(), entry)
    assertEquals(listOf(ready), empty.attachments)
    assertEquals(listOf(comment), empty.comments)
  }

  @Test
  fun restoredAttachmentsStopAtTheCap() {
    val full = DraftExtras(attachments = List(MAX_ATTACHMENTS) { draft("f$it") })
    val entry: PromptStashEntry = stashEntry("e1", 5, "s", "", DraftExtras(listOf(ready)))
    assertEquals(full.attachments.size, restoredExtras(full, entry).attachments.size)
  }

  @Test
  fun theSnippetIsOneLineOrWhatTheEntryCarries() {
    assertEquals("a b", stashEntrySnippet(entry("e", prompt = " a \n\n b ")))
    val long: String = "x".repeat(STASH_SNIPPET_MAX_CHARS + 5)
    assertEquals("x".repeat(STASH_SNIPPET_MAX_CHARS) + "…", stashEntrySnippet(entry("e", long)))
    assertEquals("(empty)", stashEntrySnippet(entry("e", prompt = "")))
    val image: PersistedComposerAttachment = PersistedComposerAttachment(
      id = "i",
      name = "a.png",
      kind = "image",
      mimeType = "image/png",
      sizeBytes = 1,
    )
    val file: PersistedComposerAttachment = image.copy(id = "f", kind = "file")
    val one: PromptStashEntry = entry("e", prompt = "").copy(attachments = listOf(file))
    assertEquals("(1 file)", stashEntrySnippet(one))
    val two: PromptStashEntry = one.copy(attachments = listOf(image, image))
    assertEquals("(2 images)", stashEntrySnippet(two))
    val mixed: PromptStashEntry = one.copy(attachments = listOf(image, file))
    assertEquals("(2 attachments)", stashEntrySnippet(mixed))
  }

  @Test
  fun aHydratedUnknownKindFallsBackToAFile() {
    val saved = PersistedComposerDraft(
      prompt = "",
      attachments = listOf(
        PersistedComposerAttachment("i", "x", "video", "video/mp4", 1, attachmentId = "a"),
      ),
    )
    val restored: DraftAttachment = hydratedExtras(saved).attachments.single()
    assertEquals(AttachmentKind.FILE, restored.kind)
    assertIs<Upload.Ready>(restored.upload)
  }

  private fun entry(id: String, prompt: String = "p $id"): PromptStashEntry =
    PromptStashEntry(id = id, createdAt = 0, server = "s", prompt = prompt)
}
