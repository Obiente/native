package dev.obiente.nextcloudnative.app

private const val MAX_MEDIA_VIEWER_SOURCE_MEMBERS_PER_ITEM = 8

data class MediaViewerNavigationRoute(
    val key: String,
    val selectedIndex: Int,
)

data class MediaViewerNavigationSnapshot(
    val media: List<NextcloudFile>,
    val selectedIndex: Int,
    val sourceMembers: List<NextcloudFile> = media,
) {
    init {
        require(media.isNotEmpty())
        require(selectedIndex in media.indices)
        require(sourceMembers.isNotEmpty())
        require(
            media.all { navigationItem ->
                sourceMembers.any { source ->
                    mediaViewerFileIdentity(source) == mediaViewerFileIdentity(navigationItem)
                }
            },
        ) { "Every media viewer navigation item must remain available as a source." }
    }

    val selected: NextcloudFile
        get() = media[selectedIndex]
}

/**
 * Keeps large viewer sequences out of Android saved instance state.
 *
 * Routes carry only an opaque key and an index. The bounded process-local repository preserves
 * navigation across activity recreation; after process death the caller safely returns to the
 * source screen, whose timeline repository can reload the account-scoped window.
 */
class MediaViewerNavigationRepository(
    private val maximumRoutes: Int = 8,
    private val maximumItemsPerRoute: Int = MAX_PHOTO_TIMELINE_RETAINED_ITEMS,
) {
    private data class RouteContent(
        val media: List<NextcloudFile>,
        val sourceMembers: List<NextcloudFile>,
    )

    private val routes = linkedMapOf<String, RouteContent>()
    private var nextRouteId = 1L

    init {
        require(maximumRoutes > 0)
        require(maximumItemsPerRoute in 1..MAX_PHOTO_TIMELINE_RETAINED_ITEMS)
    }

    fun register(
        media: List<NextcloudFile>,
        selected: NextcloudFile,
        sourceMembers: List<NextcloudFile> = media,
    ): MediaViewerNavigationRoute {
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
        val key = "media-${nextRouteId++}"
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
        routes[key] = RouteContent(
            media = bounded,
            sourceMembers = boundedSources,
        )
        while (routes.size > maximumRoutes) {
            routes.remove(routes.keys.first())
        }
        return MediaViewerNavigationRoute(key, selectedIndex)
    }

    fun resolve(route: MediaViewerNavigationRoute): MediaViewerNavigationSnapshot? {
        val content = routes[route.key] ?: return null
        if (route.selectedIndex !in content.media.indices) return null
        return MediaViewerNavigationSnapshot(
            media = content.media,
            selectedIndex = route.selectedIndex,
            sourceMembers = content.sourceMembers,
        )
    }

    fun select(
        route: MediaViewerNavigationRoute,
        selected: NextcloudFile,
    ): MediaViewerNavigationRoute? {
        val content = routes[route.key] ?: return null
        val index = content.media.indexOfFirst { candidate ->
            mediaViewerFileIdentity(candidate) == mediaViewerFileIdentity(selected)
        }
        return index.takeIf { it >= 0 }?.let { route.copy(selectedIndex = it) }
    }

    fun release(routeKey: String) {
        routes.remove(routeKey)
    }
}

internal fun mediaViewerFileIdentity(file: NextcloudFile): String =
    file.fileId?.takeIf { it > 0L }?.let { "file:$it" }
        ?: "path:${file.path.trim('/')}"
