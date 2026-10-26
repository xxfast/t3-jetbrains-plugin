package io.github.xxfast.t3code.plugin.ui

// The rules behind the banners above the composer: `ComposerBannerStack.tsx` for their order and
// the branch mismatch notice's from `ChatView.logic.ts` and `useThreadLifecycle.tsx`. The
// mismatch itself is `branchMismatch` in SidebarRowDetails.kt.

/** `ComposerBannerVariant`, `ComposerBanner.tsx`. */
enum class ComposerBannerVariant { DEFAULT, ERROR, INFO, SUCCESS, WARNING }

/** `ComposerBannerStackItem.priority`. */
enum class ComposerBannerPriority { URGENT, ACTIVITY, NOTICE }

/** One banner in the stack, in the order `composerBannerItems` (ChatView.tsx) lists them. */
sealed interface ComposerBannerItem {
  val variant: ComposerBannerVariant
  val priority: ComposerBannerPriority?

  /** `backgroundWorkBannerItem`, `ThreadActivityBanners.tsx`. */
  class BackgroundWork(val state: BackgroundWorkState) : ComposerBannerItem {
    override val variant: ComposerBannerVariant = ComposerBannerVariant.DEFAULT
    override val priority: ComposerBannerPriority = ComposerBannerPriority.ACTIVITY
  }

  /** `branchMismatchBannerItem`, `ThreadActivityBanners.tsx`. */
  class BranchMismatch(val state: BranchMismatchBannerState) : ComposerBannerItem {
    override val variant: ComposerBannerVariant = ComposerBannerVariant.INFO
    override val priority: ComposerBannerPriority? = null
  }
}

/** `bannerPriority`, `ComposerBannerStack.tsx`: urgent, errors and warnings, then activity. */
fun bannerPriority(item: ComposerBannerItem): Int = when {
  item.priority == ComposerBannerPriority.URGENT ||
    item.variant == ComposerBannerVariant.ERROR ||
    item.variant == ComposerBannerVariant.WARNING -> 0
  item.priority == ComposerBannerPriority.ACTIVITY -> 1
  else -> 2
}

/** `orderedItems`: a stable sort, so equal priorities keep upstream's listing order. */
fun orderComposerBanners(items: List<ComposerBannerItem>): List<ComposerBannerItem> =
  items.sortedBy(::bannerPriority)

/** What the branch mismatch notice shows. */
class BranchMismatchBannerState(
  val mismatch: BranchMismatch,
  /** Upstream's `isRestoringThreadBranch`: a restore is on its way. */
  val restoring: Boolean = false,
  /** `canWriteSourceControl`: the pairing may switch branches. */
  val canRestore: Boolean = true,
)

/** What the notice's controls do; the host dispatches. */
class BranchMismatchBannerActions(
  val onRestore: () -> Unit = {},
  val onDismiss: () -> Unit = {},
)

/** `branchMismatchKey`, ChatView.logic.ts: what a dismissal remembers. */
fun branchMismatchKey(threadId: String?, mismatch: BranchMismatch?): String? {
  if (threadId == null || mismatch == null) return null
  return "$threadId:${mismatch.threadBranch}:${mismatch.currentBranch}"
}

/**
 * `shouldShowBranchMismatchBanner`, ChatView.logic.ts. Draft content is the intent signal, and
 * once shown for a mismatch it stays until dismissed or the mismatch changes.
 */
fun shouldShowBranchMismatchBanner(
  hasMismatch: Boolean,
  isDismissed: Boolean,
  composerHasContent: Boolean,
  wasShownForCurrentMismatch: Boolean,
): Boolean {
  if (!hasMismatch || isDismissed) return false
  return composerHasContent || wasShownForCurrentMismatch
}

/** `nextRevealedBranchMismatchKey`, useThreadLifecycle.tsx: the mismatch the notice latched on. */
fun nextRevealedBranchMismatchKey(show: Boolean, activeKey: String?, revealed: String?): String? =
  when {
    show -> activeKey
    revealed != activeKey -> null
    else -> revealed
  }

/**
 * `composerDraftHasUserContent`, composerDraftStore.ts: text, attachments, review comments or
 * mentioned threads. The editor selection chip isn't counted; it follows the editor, not intent.
 */
fun composerDraftHasUserContent(text: CharSequence, extras: DraftExtras): Boolean =
  text.isNotBlank() ||
    extras.attachments.isNotEmpty() ||
    extras.comments.isNotEmpty() ||
    extras.threads.isNotEmpty()
