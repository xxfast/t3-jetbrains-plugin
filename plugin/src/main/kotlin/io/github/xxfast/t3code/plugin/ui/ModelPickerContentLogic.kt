package io.github.xxfast.t3code.plugin.ui

import io.github.xxfast.t3code.protocol.generated.ServerProvider
import io.github.xxfast.t3code.protocol.generated.ServerProviderModel

// The model picker's popover, upstream's `ModelPickerContent.tsx`, `ModelPickerSidebar.tsx`,
// `modelPickerSearch.ts` and `modelOrdering.ts`: which providers sit in the sidebar, which models
// list for the chosen one, how a search ranks them, and where the legacy ones fold.

/** The Favorites tab, upstream's `"favorites"` sentinel for `selectedInstanceId`. */
const val FAVORITES_TAB: String = "favorites"

/** `providerModelKey`, `apps/web/src/modelOrdering.ts`: how a favourite is keyed. */
fun providerModelKey(instanceId: String, slug: String): String = "$instanceId:$slug"

/**
 * One provider in the sidebar, upstream's `ProviderInstanceEntry` as far as the picker reads it.
 * [ready] is `isProviderInstancePickerReady`; [tooltip] is `describeUnavailableInstance`.
 */
data class ModelPickerEntry(
  val instanceId: String,
  val driver: String,
  val displayName: String,
  val provider: ServerProvider,
  val ready: Boolean,
  val tooltip: String,
)

/** One model the picker can list, upstream's `ModelPickerItem`. */
data class ModelPickerModel(
  val instanceId: String,
  val driver: String,
  val slug: String,
  val name: String,
  val shortName: String?,
  val subProvider: String?,
  val isNew: Boolean,
  val isLegacy: Boolean,
  val providerDisplayName: String,
)

/** One row of the list: a model, or the fold that hides a provider's legacy models. */
sealed interface ModelPickerRow {
  data class Model(val model: ModelPickerModel) : ModelPickerRow

  data class LegacySection(val instanceId: String, val count: Int, val expanded: Boolean) :
    ModelPickerRow
}

/**
 * `describeUnavailableInstance`, `ModelPickerSidebar.tsx:14`, reworded without the dash: the
 * display name, then why it can't be picked.
 */
fun describeUnavailableInstance(provider: ServerProvider): String {
  val label: String = resolveProviderInstanceDisplayName(provider)
  if (!provider.enabled || provider.status == "disabled") return "$label. Disabled in settings."
  if (provider.status == "ready" && provider.availability != "unavailable") return label
  val kind: String = when (provider.status) {
    "error" -> "Unavailable"
    "warning" -> "Limited"
    else -> "Not ready"
  }
  val message: String? = provider.message?.trim()?.takeIf { it.isNotEmpty() }
  return if (message != null) "$label. $kind. $message" else "$label. $kind."
}

/**
 * The sidebar's providers: the enabled ones (`isProviderInstancePickerVisible`), in the server's
 * order.
 */
fun modelPickerEntries(providers: List<ServerProvider>): List<ModelPickerEntry> = providers
  .filter { it.enabled }
  .map { provider ->
    ModelPickerEntry(
      instanceId = provider.instanceId,
      driver = provider.driver,
      displayName = resolveProviderInstanceDisplayName(provider),
      provider = provider,
      ready = provider.pickerReady(),
      tooltip = describeUnavailableInstance(provider),
    )
  }

private const val ANTIGRAVITY_DEFAULT: String = "antigravity-default"

/**
 * `shouldIncludeModelPickerOption`, `ModelPickerContent.tsx:101`. Antigravity's default slug
 * never lists; a ready provider lists everything. The branch for an unavailable current model
 * on OpenCode and Antigravity needs `isUnavailable`, which the generated model doesn't carry.
 */
private fun shouldIncludeModelPickerOption(
  entry: ModelPickerEntry,
  option: ServerProviderModel,
): Boolean {
  if (entry.driver == "antigravity" && option.slug == ANTIGRAVITY_DEFAULT) return false
  return entry.ready
}

/** `flatModels`, `ModelPickerContent.tsx:555`: every listable model of every sidebar provider. */
fun modelPickerFlatModels(entries: List<ModelPickerEntry>): List<ModelPickerModel> =
  entries.flatMap { entry ->
    entry.provider.models
      .filter { option -> shouldIncludeModelPickerOption(entry, option) }
      .map { option ->
        ModelPickerModel(
          instanceId = entry.instanceId,
          driver = entry.driver,
          slug = option.slug,
          name = option.name,
          shortName = option.shortName,
          subProvider = option.subProvider,
          isNew = option.badge == "new",
          isLegacy = option.isLegacy == true,
          providerDisplayName = entry.displayName,
        )
      }
  }

/**
 * `getDisplayModelName`, `apps/web/src/components/chat/providerIconUtils.ts:28`: the short name
 * when asked for and there is one, with a leading sub-provider qualifier stripped.
 */
fun getDisplayModelName(model: ModelPickerModel, preferShortName: Boolean = false): String {
  val name: String = if (preferShortName && model.shortName != null) model.shortName else model.name
  return stripLeadingQualifier(name, model.subProvider)
}

private fun stripLeadingQualifier(value: String, qualifier: String?): String {
  val trimmed: String = qualifier?.trim().orEmpty()
  if (trimmed.isEmpty()) return value
  val pattern = Regex("^${Regex.escape(trimmed)}(?:\\s*[.:/-]\\s*|\\s+)", RegexOption.IGNORE_CASE)
  return value.replace(pattern, "").trim().ifEmpty { value }
}

/** `getProviderRowLabel`, `providerIconUtils.ts:35`: the provider, with its sub-provider. */
fun getProviderRowLabel(providerDisplayName: String, subProvider: String?): String {
  val provider: String = providerDisplayName.trim()
  val sub: String = subProvider?.trim().orEmpty()
  if (sub.isEmpty()) return providerDisplayName
  val startsWithProvider = Regex("^${Regex.escape(provider)}\\s+\\S", RegexOption.IGNORE_CASE)
  if (provider.isNotEmpty() && startsWithProvider.containsMatchIn(sub)) return sub
  return "$providerDisplayName · $sub"
}

// Search, `modelPickerSearch.ts`, on the helpers in SearchRanking.kt. Lower scores rank first;
// null is no match.

private const val FAVORITE_SCORE_BOOST: Int = 24
private const val EXACT_NAME_SCORE: Int = -1000

private fun searchFields(model: ModelPickerModel): List<String> = buildList {
  add(normalizeSearchQuery(model.name))
  model.shortName?.let { add(normalizeSearchQuery(it)) }
  model.subProvider?.let { add(normalizeSearchQuery(it)) }
  add(normalizeSearchQuery(model.driver))
  add(normalizeSearchQuery(model.providerDisplayName))
  add(
    normalizeSearchQuery(
      listOfNotNull(
        model.name,
        model.shortName,
        model.subProvider,
        model.driver,
        model.providerDisplayName,
      ).filter { it.isNotEmpty() }.joinToString(" "),
    ),
  )
}

private fun normalizeModelName(name: String): String =
  normalizeSearchQuery(name).replace(Regex("\\s+"), " ")

/**
 * `scoreModelPickerSearch`: every token must match some field; each token's best field score
 * adds up. An exact name wins outright and a favourite gets a head start.
 */
fun scoreModelPickerSearch(model: ModelPickerModel, query: String, isFavorite: Boolean): Int? {
  val tokens: List<String> =
    normalizeSearchQuery(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
  if (tokens.isEmpty()) return 0
  val normalized: String = tokens.joinToString(" ")
  if (
    normalized == normalizeModelName(model.name) ||
    normalized == normalizeModelName(model.shortName.orEmpty())
  ) {
    return EXACT_NAME_SCORE
  }
  val fields: List<String> = searchFields(model)
  var score = 0
  for (token in tokens) {
    val best: Int = fields
      .mapIndexedNotNull { index, field ->
        val base: Int = index * 10
        scoreQueryMatch(
          value = field,
          query = token,
          exactBase = base,
          prefixBase = base + 2,
          boundaryBase = base + 4,
          includesBase = base + 6,
          fuzzyBase = if (token.length >= 3) base + 100 else null,
          ignorePosition = true,
        )
      }
      .minOrNull() ?: return null
    score += best
  }
  return if (isFavorite) score - FAVORITE_SCORE_BOOST else score
}

/** `rankModelPickerSearch`: the matches by score, favourites first on a tie, then list order. */
fun rankModelPickerSearch(
  models: List<ModelPickerModel>,
  query: String,
  favorites: Set<String>,
): List<ModelPickerModel> = models
  .mapIndexedNotNull { order, model ->
    val favorite: Boolean = providerModelKey(model.instanceId, model.slug) in favorites
    scoreModelPickerSearch(model, query, favorite)?.let { score ->
      Ranked(model, score, favorite, order)
    }
  }
  .sortedWith(compareBy<Ranked> { it.score }.thenByDescending { it.favorite }.thenBy { it.order })
  .map { it.model }

private data class Ranked(
  val model: ModelPickerModel,
  val score: Int,
  val favorite: Boolean,
  val order: Int,
)

/**
 * `sortProviderModelItems`, `modelOrdering.ts:67`: favourites first when [groupFavorites], then
 * by [instanceOrder] (unlisted instances last), then the list's own order.
 */
fun sortProviderModelItems(
  models: List<ModelPickerModel>,
  favorites: Set<String>,
  groupFavorites: Boolean,
  instanceOrder: List<String>,
): List<ModelPickerModel> {
  val byInstance: Map<String, Int> = instanceOrder.withIndex().associate { it.value to it.index }
  val original: Map<ModelPickerModel, Int> = models.withIndex().associate { it.value to it.index }
  return models.sortedWith(
    compareBy<ModelPickerModel> { model ->
      if (groupFavorites && providerModelKey(model.instanceId, model.slug) in favorites) 0 else 1
    }
      .thenBy { byInstance[it.instanceId] ?: Int.MAX_VALUE }
      .thenBy { original.getValue(it) },
  )
}

/**
 * Which tab opens: the thread's provider when it's ready, else All (null). Upstream opens on
 * Favorites when there are any (`ModelPickerContent.tsx:420`); here favourites float to the top
 * of every tab instead, so the provider is the better start.
 */
fun initialModelPickerTab(activeInstanceId: String?, entries: List<ModelPickerEntry>): String? =
  activeInstanceId?.takeIf { id -> entries.any { it.instanceId == id && it.ready } }

/**
 * Which legacy sections start open, `ModelPickerContent.tsx:434`: the thread's provider when its
 * current model is one of the legacy ones.
 */
fun initialExpandedLegacy(
  models: List<ModelPickerModel>,
  activeInstanceId: String?,
  activeModel: String?,
): Set<String> = models
  .filter { it.instanceId == activeInstanceId && it.slug == activeModel && it.isLegacy }
  .map { it.instanceId }
  .toSet()

/**
 * The list for [tab] and [query], `filteredModels`, `legacySection` and `visibleModels` in
 * `ModelPickerContent.tsx:625`. A search ranks the tab's models and shows no fold. A provider
 * tab lists its models, favourites first, with the legacy ones folded. The Favorites tab is the
 * favourites in provider order, as upstream's. The All tab (null) is every provider's current
 * models in provider order, favourites first; legacy models only show there when favourited.
 */
fun modelPickerRows(
  models: List<ModelPickerModel>,
  tab: String?,
  query: String,
  favorites: Set<String>,
  expandedLegacy: Set<String>,
  instanceOrder: List<String>,
): List<ModelPickerRow> {
  val onAll: Boolean = tab == null
  val onFavorites: Boolean = tab == FAVORITES_TAB
  val inTab: List<ModelPickerModel> = models.filter { model ->
    when {
      onAll -> true
      onFavorites -> providerModelKey(model.instanceId, model.slug) in favorites
      else -> model.instanceId == tab
    }
  }
  if (query.isNotBlank()) {
    return rankModelPickerSearch(inTab, query, favorites).map(ModelPickerRow::Model)
  }
  val filtered: List<ModelPickerModel> = inTab.filter { model ->
    !onAll || !model.isLegacy || providerModelKey(model.instanceId, model.slug) in favorites
  }
  val sorted: List<ModelPickerModel> = sortProviderModelItems(
    models = filtered,
    favorites = favorites,
    groupFavorites = !onFavorites,
    instanceOrder = if (onAll || onFavorites) instanceOrder else emptyList(),
  )
  if (onAll || onFavorites) return sorted.map(ModelPickerRow::Model)
  val current: List<ModelPickerModel> = sorted.filter { !it.isLegacy }
  val legacy: List<ModelPickerModel> = sorted.filter { it.isLegacy }
  if (legacy.isEmpty()) return sorted.map(ModelPickerRow::Model)
  val expanded: Boolean = tab in expandedLegacy
  return buildList {
    current.forEach { add(ModelPickerRow.Model(it)) }
    add(ModelPickerRow.LegacySection(tab, legacy.size, expanded))
    if (expanded) legacy.forEach { add(ModelPickerRow.Model(it)) }
  }
}

/**
 * `makeModelDisabledReason`, `apps/web/src/components/ChatView.tsx:1013`: the block's
 * description with how to get past it, shown on a row that can't be picked in this thread.
 */
fun modelDisabledReason(block: ModelChangeBlock?): String? =
  block?.let { "${it.description} Start a new thread to use this model." }
