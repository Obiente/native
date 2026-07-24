package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

const val DEFAULT_UNIFIED_SEARCH_PAGE_SIZE = 20
const val MAX_UNIFIED_SEARCH_PAGE_SIZE = 25
const val MAX_UNIFIED_SEARCH_PAGES_PER_PROVIDER = 100

enum class UnifiedSearchFilterKind {
    Text,
    DateTime,
    Person,
    Integer,
    Boolean,
    User,
    Unknown,
}

data class UnifiedSearchFilterDefinition(
    val name: String,
    val wireType: String,
) {
    init {
        require(name.isSafeUnifiedSearchIdentifier()) { "Invalid unified-search filter name." }
        require(wireType.isSafeUnifiedSearchIdentifier()) { "Invalid unified-search filter type." }
    }

    val kind: UnifiedSearchFilterKind
        get() = when (wireType.lowercase()) {
            "string" -> UnifiedSearchFilterKind.Text
            "datetime" -> UnifiedSearchFilterKind.DateTime
            "person" -> UnifiedSearchFilterKind.Person
            "int", "integer" -> UnifiedSearchFilterKind.Integer
            "bool", "boolean" -> UnifiedSearchFilterKind.Boolean
            "user" -> UnifiedSearchFilterKind.User
            else -> UnifiedSearchFilterKind.Unknown
        }
}

data class UnifiedSearchProvider(
    val id: String,
    val appId: String,
    val name: String,
    val iconUrl: String?,
    val order: Int,
    val isExternal: Boolean,
    val triggers: List<String>,
    val filters: List<UnifiedSearchFilterDefinition>,
    val hasInAppSearch: Boolean,
) {
    init {
        require(id.isSafeUnifiedSearchIdentifier(allowPathPunctuation = true)) {
            "Invalid unified-search provider identifier."
        }
        require(appId.isSafeUnifiedSearchIdentifier(allowPathPunctuation = true)) {
            "Invalid unified-search app identifier."
        }
        require(name.isNotBlank() && name.length <= MAX_UNIFIED_SEARCH_LABEL_LENGTH && name.none(Char::isISOControl)) {
            "Invalid unified-search provider name."
        }
        require(filters.size <= MAX_UNIFIED_SEARCH_FILTERS_PER_PROVIDER) {
            "A unified-search provider advertises too many filters."
        }
    }

    fun supportsFilter(name: String): Boolean = filters.any { it.name == name }
}

data class UnifiedSearchCursor(val value: String) {
    init {
        require(value.isNotBlank()) { "A unified-search cursor cannot be blank." }
        require(value.length <= MAX_UNIFIED_SEARCH_CURSOR_LENGTH) { "The unified-search cursor is too long." }
    }
}

data class UnifiedSearchEntry(
    val thumbnailUrl: String?,
    val title: String,
    val subline: String?,
    val resourceUrl: String?,
    val icon: String?,
    val roundedThumbnail: Boolean,
    val attributes: Map<String, String>,
) {
    /** Provider-scoped identity used to suppress duplicate rows across adjacent pages. */
    fun stableKey(): String = listOf(resourceUrl.orEmpty(), title, subline.orEmpty(), attributes.toString())
        .joinToString("\u001f")
}

data class UnifiedSearchPage(
    val name: String,
    val entries: List<UnifiedSearchEntry>,
    val isPaginated: Boolean,
    val nextCursor: UnifiedSearchCursor?,
)

enum class UnifiedSearchPaginationStopReason {
    Complete,
    EmptyPage,
    RepeatedCursor,
    PageLimit,
}

data class UnifiedSearchGroup(
    val provider: UnifiedSearchProvider,
    val displayName: String,
    val entries: List<UnifiedSearchEntry>,
    val nextCursor: UnifiedSearchCursor?,
    val consumedCursors: Set<UnifiedSearchCursor>,
    val loadedPages: Int,
    val stopReason: UnifiedSearchPaginationStopReason?,
) {
    val canLoadMore: Boolean get() = nextCursor != null && stopReason == null
}

data class UnifiedSearchRequest(
    val term: String,
    val from: String = "",
    val limit: Int = DEFAULT_UNIFIED_SEARCH_PAGE_SIZE,
    val sortOrder: Int? = null,
    val filters: Map<String, String> = emptyMap(),
) {
    init {
        require(term.isNotBlank()) { "A unified-search term cannot be blank." }
        require(limit in 1..MAX_UNIFIED_SEARCH_PAGE_SIZE) {
            "Unified-search page size must be between 1 and $MAX_UNIFIED_SEARCH_PAGE_SIZE."
        }
        require(term.length <= MAX_UNIFIED_SEARCH_TERM_LENGTH) { "The unified-search term is too long." }
        require(from.length <= MAX_UNIFIED_SEARCH_CONTEXT_LENGTH) { "The unified-search context is too long." }
        require(term.none(Char::isISOControl) && from.none(Char::isISOControl)) {
            "Unified-search text cannot contain control characters."
        }
        require(filters.size <= MAX_UNIFIED_SEARCH_REQUEST_FILTERS) {
            "A unified-search request has too many filters."
        }
        require(filters.all { (name, value) ->
            name.isSafeUnifiedSearchIdentifier() &&
                value.length <= MAX_UNIFIED_SEARCH_FILTER_VALUE_LENGTH &&
                value.none(Char::isISOControl)
        }) { "A unified-search filter is invalid or too long." }
    }
}

sealed interface UnifiedSearchProviderOutcome {
    val provider: UnifiedSearchProvider

    data class Results(
        override val provider: UnifiedSearchProvider,
        val group: UnifiedSearchGroup,
    ) : UnifiedSearchProviderOutcome

    data class Failure(
        override val provider: UnifiedSearchProvider,
        val message: String,
    ) : UnifiedSearchProviderOutcome
}

data class UnifiedSearchSelection(
    val provider: UnifiedSearchProvider,
    val entry: UnifiedSearchEntry,
)

class UnifiedSearchException(message: String) : IllegalStateException(message)

/**
 * Generic client for Nextcloud's provider-based unified search API.
 *
 * It deliberately knows nothing about Files, Mail, Deck, or any other app. Any app that registers a
 * server-side provider is discovered and searched through the same contract.
 */
class NextcloudUnifiedSearchClient(
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
) {
    constructor(services: NextcloudPlatformServices, session: NextcloudSession) : this(
        execute = { request -> services.executeNextcloudApi(session, request) },
    )

    suspend fun discoverProviders(from: String = ""): List<UnifiedSearchProvider> =
        parseUnifiedSearchProviders(execute(unifiedSearchProvidersRequest(from)))

    suspend fun searchProvider(
        provider: UnifiedSearchProvider,
        request: UnifiedSearchRequest,
        cursor: UnifiedSearchCursor? = null,
    ): UnifiedSearchPage = parseUnifiedSearchPage(
        execute(unifiedSearchProviderRequest(provider, request, cursor)),
    )

    /**
     * Searches providers concurrently and publishes completed groups serially as soon as each
     * provider responds. External providers are opt-in because their queries can leave Nextcloud.
     */
    suspend fun searchAll(
        providers: List<UnifiedSearchProvider>,
        request: UnifiedSearchRequest,
        includeExternalProviders: Boolean = false,
        onProviderCompleted: suspend (UnifiedSearchProviderOutcome) -> Unit = {},
    ): List<UnifiedSearchProviderOutcome> = coroutineScope {
        val eligible = providers
            .asSequence()
            .filter { includeExternalProviders || !it.isExternal }
            .filter { it.supportsFilter(TERM_FILTER) }
            .distinctBy(UnifiedSearchProvider::id)
            .sortedWith(compareBy(UnifiedSearchProvider::order, UnifiedSearchProvider::name))
            .take(MAX_UNIFIED_SEARCH_PROVIDERS)
            .toList()
        val completed = Channel<UnifiedSearchProviderOutcome>(Channel.UNLIMITED)
        eligible.forEach { provider ->
            launch {
                val outcome = runCatching { searchProvider(provider, request) }
                    .fold(
                        onSuccess = { page ->
                            UnifiedSearchProviderOutcome.Results(provider, firstUnifiedSearchGroup(provider, page))
                        },
                        onFailure = { failure ->
                            UnifiedSearchProviderOutcome.Failure(
                                provider,
                                unifiedSearchFailureMessage(
                                    failure,
                                    "${provider.name} could not be searched.",
                                ),
                            )
                        },
                    )
                completed.send(outcome)
            }
        }

        val outcomes = buildList {
            repeat(eligible.size) {
                val outcome = completed.receive()
                add(outcome)
                onProviderCompleted(outcome)
            }
        }
        completed.close()
        outcomes.sortedBy { outcome -> eligible.indexOfFirst { it.id == outcome.provider.id } }
    }

    suspend fun loadNextPage(group: UnifiedSearchGroup, request: UnifiedSearchRequest): UnifiedSearchGroup {
        val cursor = group.nextCursor ?: return group
        if (!group.canLoadMore) return group
        val page = searchProvider(group.provider, request, cursor)
        return mergeUnifiedSearchPage(group, page, cursor)
    }
}

fun unifiedSearchProvidersRequest(from: String = ""): NextcloudApiRequest {
    require(from.length <= MAX_UNIFIED_SEARCH_CONTEXT_LENGTH && from.none(Char::isISOControl)) {
        "The unified-search context is invalid or too long."
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = UNIFIED_SEARCH_PROVIDERS_PATH,
        queryParameters = mapOfNotNull("from" to from.takeIf(String::isNotBlank)),
        ocsApiRequest = true,
        maximumResponseBytes = UNIFIED_SEARCH_RESPONSE_LIMIT_BYTES,
    )
}

fun unifiedSearchProviderRequest(
    provider: UnifiedSearchProvider,
    request: UnifiedSearchRequest,
    cursor: UnifiedSearchCursor? = null,
): NextcloudApiRequest {
    require(provider.supportsFilter(TERM_FILTER)) { "${provider.name} does not advertise term search." }
    val reserved = setOf(TERM_FILTER, "cursor", "limit", "sortOrder", "from")
    val unsupported = request.filters.keys.filterNot(provider::supportsFilter)
    require(unsupported.isEmpty()) { "${provider.name} does not support filters: ${unsupported.joinToString()}." }
    require(request.filters.keys.none { it in reserved }) { "Reserved unified-search filters cannot be overridden." }

    val query = buildMap {
        put(TERM_FILTER, request.term)
        put("limit", request.limit.toString())
        request.from.takeIf(String::isNotBlank)?.let { put("from", it) }
        request.sortOrder?.let { put("sortOrder", it.toString()) }
        cursor?.let { put("cursor", it.value) }
        putAll(request.filters.filterValues(String::isNotBlank))
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "$UNIFIED_SEARCH_PROVIDERS_PATH/${provider.id.encodeUnifiedSearchPathSegment()}/search",
        queryParameters = query,
        ocsApiRequest = true,
        maximumResponseBytes = UNIFIED_SEARCH_RESPONSE_LIMIT_BYTES,
    )
}

fun parseUnifiedSearchProviders(response: NextcloudApiResponse): List<UnifiedSearchProvider> {
    val data = response.requireUnifiedSearchOcsData() as? JsonArray
        ?: throw UnifiedSearchException("The unified-search provider response has no provider list.")
    return data.asSequence().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.string("id")
            ?.takeIf { it.isSafeUnifiedSearchIdentifier(allowPathPunctuation = true) }
            ?: return@mapNotNull null
        val filters = (item["filters"] as? JsonObject).orEmpty().asSequence()
            .take(MAX_UNIFIED_SEARCH_FILTERS_PER_PROVIDER)
            .mapNotNull { (name, typeElement) ->
            val type = (typeElement as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!name.isSafeUnifiedSearchIdentifier() || !type.isSafeUnifiedSearchIdentifier()) return@mapNotNull null
            UnifiedSearchFilterDefinition(name, type)
        }.toList()
        val appId = item.string("appId")
            ?.takeIf { it.isSafeUnifiedSearchIdentifier(allowPathPunctuation = true) }
            ?: id.substringBefore('_').substringBefore('-')
        val name = item.string("name")?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_LABEL_LENGTH)
            ?: id
        UnifiedSearchProvider(
            id = id,
            appId = appId,
            name = name,
            iconUrl = item.string("icon")?.safeUnifiedSearchUrl(),
            order = item.int("order") ?: 50,
            isExternal = item.boolean("isExternalProvider") ?: false,
            triggers = (item["triggers"] as? JsonArray).orEmpty().asSequence()
                .take(MAX_UNIFIED_SEARCH_TRIGGERS_PER_PROVIDER)
                .mapNotNull { trigger ->
                    (trigger as? JsonPrimitive)?.contentOrNull
                        ?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_LABEL_LENGTH)
                }.toList(),
            filters = filters,
            hasInAppSearch = item.boolean("inAppSearch") ?: false,
        )
    }.distinctBy(UnifiedSearchProvider::id)
        .sortedWith(compareBy(UnifiedSearchProvider::order, UnifiedSearchProvider::name))
        .take(MAX_UNIFIED_SEARCH_PROVIDERS)
        .toList()
}

fun parseUnifiedSearchPage(response: NextcloudApiResponse): UnifiedSearchPage {
    val data = response.requireUnifiedSearchOcsData() as? JsonObject
        ?: throw UnifiedSearchException("The unified-search response has no result group.")
    val entries = (data["entries"] as? JsonArray).orEmpty().asSequence()
        .take(MAX_UNIFIED_SEARCH_PAGE_SIZE)
        .mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val title = item.string("title")
            ?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_TITLE_LENGTH)
            ?: return@mapNotNull null
        UnifiedSearchEntry(
            thumbnailUrl = item.string("thumbnailUrl")?.safeUnifiedSearchUrl(),
            title = title,
            subline = item.string("subline")?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_SUBLINE_LENGTH),
            resourceUrl = item.string("resourceUrl")?.safeUnifiedSearchUrl(),
            icon = item.string("icon")?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_LABEL_LENGTH),
            roundedThumbnail = item.boolean("rounded") ?: false,
            attributes = parseUnifiedSearchAttributes(item["attributes"]),
        )
    }.toList()
    val isPaginated = data.boolean("isPaginated") ?: false
    val cursor = data["cursor"].toUnifiedSearchCursor()
    return UnifiedSearchPage(
        name = data.string("name")?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_LABEL_LENGTH) ?: "Results",
        entries = entries.distinctBy(UnifiedSearchEntry::stableKey),
        isPaginated = isPaginated,
        nextCursor = cursor.takeIf { isPaginated },
    )
}

fun firstUnifiedSearchGroup(
    provider: UnifiedSearchProvider,
    page: UnifiedSearchPage,
): UnifiedSearchGroup {
    val stop = when {
        !page.isPaginated || page.nextCursor == null -> UnifiedSearchPaginationStopReason.Complete
        page.entries.isEmpty() -> UnifiedSearchPaginationStopReason.EmptyPage
        else -> null
    }
    return UnifiedSearchGroup(
        provider = provider,
        displayName = page.name,
        entries = page.entries,
        nextCursor = page.nextCursor.takeIf { stop == null },
        consumedCursors = emptySet(),
        loadedPages = 1,
        stopReason = stop,
    )
}

fun mergeUnifiedSearchPage(
    group: UnifiedSearchGroup,
    page: UnifiedSearchPage,
    requestedCursor: UnifiedSearchCursor,
): UnifiedSearchGroup {
    if (requestedCursor != group.nextCursor || requestedCursor in group.consumedCursors || !group.canLoadMore) return group
    val consumed = group.consumedCursors + requestedCursor
    val loadedPages = group.loadedPages + 1
    val stop = when {
        loadedPages >= MAX_UNIFIED_SEARCH_PAGES_PER_PROVIDER -> UnifiedSearchPaginationStopReason.PageLimit
        page.entries.isEmpty() -> UnifiedSearchPaginationStopReason.EmptyPage
        !page.isPaginated || page.nextCursor == null -> UnifiedSearchPaginationStopReason.Complete
        page.nextCursor == requestedCursor || page.nextCursor in consumed -> UnifiedSearchPaginationStopReason.RepeatedCursor
        else -> null
    }
    val known = group.entries.mapTo(mutableSetOf(), UnifiedSearchEntry::stableKey)
    return group.copy(
        displayName = page.name.takeIf(String::isNotBlank) ?: group.displayName,
        entries = group.entries + page.entries.filter { known.add(it.stableKey()) },
        nextCursor = page.nextCursor.takeIf { stop == null },
        consumedCursors = consumed,
        loadedPages = loadedPages,
        stopReason = stop,
    )
}

private fun NextcloudApiResponse.requireUnifiedSearchOcsData(): JsonElement {
    if (status !in 200..299) {
        throw UnifiedSearchException("Unified search returned HTTP $status.")
    }
    val root = runCatching { unifiedSearchJson.parseToJsonElement(body.decodeToString()) as? JsonObject }
        .getOrNull() ?: throw UnifiedSearchException("Unified search returned invalid JSON.")
    val ocs = root["ocs"] as? JsonObject ?: throw UnifiedSearchException("Unified search returned no OCS envelope.")
    val meta = ocs["meta"] as? JsonObject
    val statusCode = meta?.int("statuscode")
    val statusText = meta?.string("status")
    if ((statusCode != null && statusCode !in setOf(100, 200)) || statusText.equals("failure", ignoreCase = true)) {
        val message = meta?.string("message")
            ?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_ERROR_LENGTH)
            ?: "The search provider rejected the request."
        throw UnifiedSearchException(message)
    }
    return ocs["data"] ?: throw UnifiedSearchException("Unified search returned no OCS data.")
}

private fun parseUnifiedSearchAttributes(element: JsonElement?): Map<String, String> = when (element) {
    is JsonObject -> element.asSequence()
        .take(MAX_UNIFIED_SEARCH_ATTRIBUTES)
        .mapNotNull { (key, value) ->
            if (!key.isSafeUnifiedSearchIdentifier(allowPathPunctuation = true)) return@mapNotNull null
            value.scalarContent()
                ?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_ATTRIBUTE_VALUE_LENGTH)
                ?.let { key to it }
        }.toMap()
    // PHP serializes an empty associative array as []; tolerate scalar arrays from older apps too.
    is JsonArray -> element.take(MAX_UNIFIED_SEARCH_ATTRIBUTES).mapIndexedNotNull { index, value ->
        value.scalarContent()?.let { "attribute.$index" to it }
    }.toMap()
    else -> emptyMap()
}

private fun JsonElement.scalarContent(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    JsonNull -> null
    else -> null
}

private fun JsonElement?.toUnifiedSearchCursor(): UnifiedSearchCursor? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val value = primitive.contentOrNull?.takeIf(String::isNotBlank) ?: return null
    return runCatching { UnifiedSearchCursor(value) }.getOrNull()
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.let { primitive ->
    primitive.intOrNull ?: primitive.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

private fun JsonObject.boolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

private fun String.encodeUnifiedSearchPathSegment(): String = buildString {
    for (byte in this@encodeUnifiedSearchPathSegment.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned == '-'.code || unsigned == '.'.code ||
            unsigned == '_'.code || unsigned == '~'.code
        if (unreserved) append(unsigned.toChar()) else {
            append('%')
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }
}

internal fun unifiedSearchFailureMessage(failure: Throwable, fallback: String): String =
    failure.message?.boundedUnifiedSearchDisplayText(MAX_UNIFIED_SEARCH_ERROR_LENGTH)
        ?: fallback

private fun String.isSafeUnifiedSearchIdentifier(allowPathPunctuation: Boolean = false): Boolean {
    if (isBlank() || length > MAX_UNIFIED_SEARCH_IDENTIFIER_LENGTH || any(Char::isISOControl)) return false
    return all { character ->
        character.isLetterOrDigit() || character in "-_." || allowPathPunctuation && character in "/:"
    }
}

private fun String.boundedUnifiedSearchDisplayText(maxLength: Int): String? {
    val normalized = buildString(minOf(length, maxLength)) {
        var pendingSpace = false
        for (character in this@boundedUnifiedSearchDisplayText) {
            if (character.isISOControl() || character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace && length < maxLength) append(' ')
                if (length >= maxLength) break
                append(character)
                pendingSpace = false
            }
        }
    }.trim()
    return normalized.takeIf(String::isNotBlank)
}

private fun String.safeUnifiedSearchUrl(): String? = takeIf {
    isNotBlank() && length <= MAX_UNIFIED_SEARCH_URL_LENGTH && none(Char::isISOControl)
}

private fun <K, V : Any> mapOfNotNull(vararg pairs: Pair<K, V?>): Map<K, V> = buildMap {
    pairs.forEach { (key, value) -> value?.let { put(key, it) } }
}

private val unifiedSearchJson = Json { ignoreUnknownKeys = true }

private const val UNIFIED_SEARCH_PROVIDERS_PATH = "/ocs/v2.php/search/providers"
private const val TERM_FILTER = "term"
private const val UNIFIED_SEARCH_RESPONSE_LIMIT_BYTES = 4L * 1024L * 1024L
private const val MAX_UNIFIED_SEARCH_CURSOR_LENGTH = 2_048
private const val MAX_UNIFIED_SEARCH_TERM_LENGTH = 512
private const val MAX_UNIFIED_SEARCH_CONTEXT_LENGTH = 2_048
private const val MAX_UNIFIED_SEARCH_PROVIDERS = 64
private const val MAX_UNIFIED_SEARCH_FILTERS_PER_PROVIDER = 32
private const val MAX_UNIFIED_SEARCH_TRIGGERS_PER_PROVIDER = 32
private const val MAX_UNIFIED_SEARCH_REQUEST_FILTERS = 16
private const val MAX_UNIFIED_SEARCH_IDENTIFIER_LENGTH = 256
private const val MAX_UNIFIED_SEARCH_FILTER_VALUE_LENGTH = 2_048
private const val MAX_UNIFIED_SEARCH_LABEL_LENGTH = 256
private const val MAX_UNIFIED_SEARCH_TITLE_LENGTH = 1_024
private const val MAX_UNIFIED_SEARCH_SUBLINE_LENGTH = 2_048
private const val MAX_UNIFIED_SEARCH_URL_LENGTH = 8_192
private const val MAX_UNIFIED_SEARCH_ATTRIBUTES = 32
private const val MAX_UNIFIED_SEARCH_ATTRIBUTE_VALUE_LENGTH = 4_096
private const val MAX_UNIFIED_SEARCH_ERROR_LENGTH = 320
private const val HEX = "0123456789ABCDEF"
