package dev.obiente.nextcloudnative.app

private const val MAX_MEDIA_VIEWER_SOURCE_MEMBERS_PER_ITEM = 8

data class MediaViewerNavigationRoute(
    val key: String,
    val selectedIndex: Int,
    val selectedSourceIndex: Int,
)

data class MediaViewerNavigationSnapshot(
    val media: List<NextcloudFile>,
    val selectedIndex: Int,
    val sourceMembers: List<NextcloudFile> = media,
    val selectedSourceIndex: Int = selectedIndex,
) {
    init {
        require(media.isNotEmpty())
        require(selectedIndex in media.indices)
        require(sourceMembers.isNotEmpty())
        require(selectedSourceIndex in sourceMembers.indices)
        require(
            media.all { navigationItem ->
                sourceMembers.any { source ->
                    mediaViewerFileIdentity(source) == mediaViewerFileIdentity(navigationItem)
                }
            },
        ) { "Every media viewer navigation item must remain available as a source." }
    }

    val selected: NextcloudFile
        get() = sourceMembers[selectedSourceIndex]
}

/**
 * Keeps large viewer sequences out of Android saved instance state.
 *
 * Routes carry only an opaque key and an index. The bounded process-local repository preserves
 * navigation across activity recreation; after process death the caller safely returns to the
 * source screen, whose timeline repository can reload the account-scoped window.
 */
internal class MediaViewerNavigationRepository private constructor(
    private val maximumRoutes: Int = 8,
    private val maximumItemsPerRoute: Int = MAX_PHOTO_TIMELINE_RETAINED_ITEMS,
    private val gate: AccountPrivateMemoryGate,
) {
    private data class RouteContent(
        val accountId: NextcloudAccountId,
        val media: List<NextcloudFile>,
        val sourceMembers: List<NextcloudFile>,
        val navigationIdentityBySourceIdentity: Map<String, String>,
    )

    constructor(
        maximumRoutes: Int = 8,
        maximumItemsPerRoute: Int = MAX_PHOTO_TIMELINE_RETAINED_ITEMS,
    ) : this(maximumRoutes, maximumItemsPerRoute, AccountPrivateMemoryGate())

    internal constructor(gate: AccountPrivateMemoryGate) : this(
        maximumRoutes = 8,
        maximumItemsPerRoute = MAX_PHOTO_TIMELINE_RETAINED_ITEMS,
        gate = gate,
    )

    private val lock = DynamicNativeMemoryCacheLock()
    private val routes = linkedMapOf<String, RouteContent>()
    private var nextRouteId = 1L

    init {
        require(maximumRoutes > 0)
        require(maximumItemsPerRoute in 1..MAX_PHOTO_TIMELINE_RETAINED_ITEMS)
    }

    internal fun producer(accountId: NextcloudAccountId): AccountPrivateMemoryProducer? =
        gate.producer(accountId.storageKey)

    fun register(
        accountId: NextcloudAccountId,
        media: List<NextcloudFile>,
        selected: NextcloudFile,
        sourceMembers: List<NextcloudFile> = media,
        navigationIdentityBySourceIdentity: Map<String, String> = emptyMap(),
        producer: AccountPrivateMemoryProducer? = producer(accountId),
    ): MediaViewerNavigationRoute? {
        require(!selected.isDirectory) { "A media viewer route cannot select a directory." }
        val unique = media
            .filterNot(NextcloudFile::isDirectory)
            .distinctBy(::mediaViewerFileIdentity)
        val selectedIdentity = mediaViewerFileIdentity(selected)
        val selectedInSequence = unique.indexOfFirst { candidate ->
            mediaViewerFileIdentity(candidate) == selectedIdentity
        }
        val bounded = when {
            unique.isEmpty() -> listOf(selected)
            unique.size <= maximumItemsPerRoute -> unique
            selectedInSequence < 0 -> unique.take(maximumItemsPerRoute - 1) + selected
            else -> {
                val start = (selectedInSequence - maximumItemsPerRoute / 2)
                    .coerceIn(0, unique.size - maximumItemsPerRoute)
                unique.subList(start, start + maximumItemsPerRoute)
            }
        }
        val selectedIndex = bounded.indexOfFirst { candidate ->
            mediaViewerFileIdentity(candidate) == selectedIdentity
        }
        check(selectedIndex >= 0) { "The selected media item is missing from its viewer route." }
        val retainedIdentities = bounded
            .mapTo(mutableSetOf(), ::mediaViewerFileIdentity)
        val boundedSources = (bounded + sourceMembers)
            .filterNot(NextcloudFile::isDirectory)
            .distinctBy(::mediaViewerFileIdentity)
            .filter { source ->
                mediaViewerFileIdentity(source) in retainedIdentities ||
                    bounded.any { navigationItem -> navigationItem.sharesMediaStackWith(source) }
            }
            .take(maximumItemsPerRoute * MAX_MEDIA_VIEWER_SOURCE_MEMBERS_PER_ITEM)
        val sourceNavigationIdentities = buildMap {
            boundedSources.forEach { source ->
                val sourceIdentity = mediaViewerFileIdentity(source)
                val declaredNavigationIdentity =
                    navigationIdentityBySourceIdentity[sourceIdentity]
                val navigationIdentity = declaredNavigationIdentity
                    ?.takeIf(retainedIdentities::contains)
                    ?: bounded.firstOrNull { navigationItem ->
                        navigationItem.sharesMediaStackWith(source)
                    }?.let(::mediaViewerFileIdentity)
                if (navigationIdentity != null) {
                    put(sourceIdentity, navigationIdentity)
                }
            }
        }
        val selectedSourceIndex = boundedSources.indexOfFirst { candidate ->
            mediaViewerFileIdentity(candidate) == selectedIdentity
        }
        check(selectedSourceIndex >= 0) {
            "The selected media source is missing from its viewer route."
        }
        var route: MediaViewerNavigationRoute? = null
        gate.mutate(accountId.storageKey, producer) {
            lock.withLock {
                val key = "media-${nextRouteId++}"
                routes[key] = RouteContent(
                    accountId = accountId,
                    media = bounded,
                    sourceMembers = boundedSources,
                    navigationIdentityBySourceIdentity = sourceNavigationIdentities,
                )
                while (routes.size > maximumRoutes) {
                    routes.remove(routes.keys.first())
                }
                route = MediaViewerNavigationRoute(key, selectedIndex, selectedSourceIndex)
            }
        }
        return route
    }

    fun resolve(
        accountId: NextcloudAccountId,
        route: MediaViewerNavigationRoute,
    ): MediaViewerNavigationSnapshot? = gate.read(accountId.storageKey, null) {
        lock.withLock {
            val content = routes[route.key]?.takeIf { it.accountId == accountId }
                ?: return@withLock null
            if (route.selectedIndex !in content.media.indices) return@withLock null
            if (route.selectedSourceIndex !in content.sourceMembers.indices) return@withLock null
            MediaViewerNavigationSnapshot(
                media = content.media,
                selectedIndex = route.selectedIndex,
                sourceMembers = content.sourceMembers,
                selectedSourceIndex = route.selectedSourceIndex,
            )
        }
    }

    fun select(
        accountId: NextcloudAccountId,
        route: MediaViewerNavigationRoute,
        selected: NextcloudFile,
    ): MediaViewerNavigationRoute? = gate.read(accountId.storageKey, null) {
        lock.withLock {
            val content = routes[route.key]?.takeIf { it.accountId == accountId }
                ?: return@withLock null
            val selectedIdentity = mediaViewerFileIdentity(selected)
            val sourceIndex = content.sourceMembers.indexOfFirst { candidate ->
                mediaViewerFileIdentity(candidate) == selectedIdentity
            }
            if (sourceIndex < 0) return@withLock null
            val mappedNavigationIdentity =
                content.navigationIdentityBySourceIdentity[selectedIdentity]
            val navigationIndex = content.media.indexOfFirst { candidate ->
                val candidateIdentity = mediaViewerFileIdentity(candidate)
                candidateIdentity == selectedIdentity ||
                    candidateIdentity == mappedNavigationIdentity ||
                    candidate.sharesMediaStackWith(selected)
            }
            if (navigationIndex < 0) return@withLock null
            route.copy(
                selectedIndex = navigationIndex,
                selectedSourceIndex = sourceIndex,
            )
        }
    }

    fun release(accountId: NextcloudAccountId, routeKey: String) {
        gate.read(accountId.storageKey, Unit) {
            lock.withLock {
                if (routes[routeKey]?.accountId == accountId) {
                    routes.remove(routeKey)
                }
            }
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) = lock.withLock {
        routes.entries.removeAll { (_, content) ->
            content.accountId.storageKey == accountStorageKey
        }
    }
}

internal val sharedMediaViewerNavigationRepository =
    MediaViewerNavigationRepository(sharedAccountPrivateMemoryGate)

internal fun mediaViewerFileIdentity(file: NextcloudFile): String =
    file.fileId?.takeIf { it > 0L }?.let { "file:$it" }
        ?: "path:${file.path.trim('/')}"
