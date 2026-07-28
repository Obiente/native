package dev.obiente.nextcloudnative.app

data class MediaViewerNavigationRoute(
    val key: String,
    val selectedIndex: Int,
)

data class MediaViewerNavigationSnapshot(
    val media: List<NextcloudFile>,
    val selectedIndex: Int,
) {
    init {
        require(media.isNotEmpty())
        require(selectedIndex in media.indices)
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
    private val routes = linkedMapOf<String, List<NextcloudFile>>()
    private var nextRouteId = 1L

    init {
        require(maximumRoutes > 0)
        require(maximumItemsPerRoute in 1..MAX_PHOTO_TIMELINE_RETAINED_ITEMS)
    }

    fun register(
        media: List<NextcloudFile>,
        selected: NextcloudFile,
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
        routes[key] = bounded
        while (routes.size > maximumRoutes) {
            routes.remove(routes.keys.first())
        }
        return MediaViewerNavigationRoute(key, selectedIndex)
    }

    fun resolve(route: MediaViewerNavigationRoute): MediaViewerNavigationSnapshot? {
        val media = routes[route.key] ?: return null
        if (route.selectedIndex !in media.indices) return null
        return MediaViewerNavigationSnapshot(media, route.selectedIndex)
    }

    fun select(
        route: MediaViewerNavigationRoute,
        selected: NextcloudFile,
    ): MediaViewerNavigationRoute? {
        val media = routes[route.key] ?: return null
        val index = media.indexOfFirst { candidate ->
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
