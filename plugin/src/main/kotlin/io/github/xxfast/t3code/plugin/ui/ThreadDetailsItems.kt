package io.github.xxfast.t3code.plugin.ui

// Upstream's threadDetailsItems.ts (apps/web/src/components/chat/threadDetailsItems.ts), apart
// from the retired Labs flag `threadDetailsUsageEnabled`: the plugin never had it, so Usage's
// default is plain `defaultVisible` and a reset clears only `threadDetailsItems`.

/** One entry of `THREAD_DETAILS_ITEMS`. Ids are stored in client settings, so never rename one. */
enum class ThreadDetailsItem(val id: String, val label: String, val defaultVisible: Boolean) {
  Environment("environment", "Environment and worktree", true),
  OpenIn("openIn", "Open in editor", true),
  Scripts("scripts", "Project scripts", true),
  Branch("branch", "Branch and pull request", true),
  GitActions("gitActions", "Git actions", true),
  Changes("changes", "Changes", true),
  Servers("servers", "Servers", true),
  Automations("automations", "Automations", true),
  Lineage("lineage", "Lineage", true),
  Usage("usage", "Usage", false),
}

val THREAD_DETAILS_ITEMS: List<ThreadDetailsItem> = ThreadDetailsItem.entries

/** `ThreadDetailsVisibility`: every item, shown or not. */
class ThreadDetailsVisibility(private val visible: Map<ThreadDetailsItem, Boolean>) {
  operator fun get(item: ThreadDetailsItem): Boolean = visible.getValue(item)
}

fun resolveThreadDetailsVisibility(
  threadDetailsItems: Map<String, Boolean>,
): ThreadDetailsVisibility = ThreadDetailsVisibility(
  THREAD_DETAILS_ITEMS.associateWith { item -> threadDetailsItems[item.id] ?: item.defaultVisible },
)

/**
 * Stores only choices that differ from the default, so a later default change reaches everyone
 * else.
 */
fun setThreadDetailsItem(
  threadDetailsItems: Map<String, Boolean>,
  item: ThreadDetailsItem,
  visible: Boolean,
): Map<String, Boolean> {
  val rest: Map<String, Boolean> = threadDetailsItems - item.id
  return if (visible == item.defaultVisible) rest else rest + (item.id to visible)
}

fun isDefaultThreadDetailsVisibility(visibility: ThreadDetailsVisibility): Boolean =
  THREAD_DETAILS_ITEMS.all { item -> visibility[item] == item.defaultVisible }

/** What `threadDetailsItems` becomes on "Reset to default". */
val RESET_THREAD_DETAILS_ITEMS: Map<String, Boolean> = emptyMap()
