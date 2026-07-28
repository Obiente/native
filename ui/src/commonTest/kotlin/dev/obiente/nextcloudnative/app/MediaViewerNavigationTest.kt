package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaViewerNavigationTest {
    @Test
    fun routeCarriesOnlyOpaqueKeyAndIndexWhileRepositoryOwnsTheFiles() {
        val repository = MediaViewerNavigationRepository(maximumRoutes = 2, maximumItemsPerRoute = 3)
        val files = (1L..3L).map(::file)

        val route = repository.register(files, files[1])
        val snapshot = assertNotNull(repository.resolve(route))

        assertEquals(1, route.selectedIndex)
        assertEquals(files, snapshot.media)
        assertEquals(files[1], snapshot.selected)
        val next = assertNotNull(repository.select(route, files[2]))
        assertEquals(files[2], assertNotNull(repository.resolve(next)).selected)
    }

    @Test
    fun routesAndItemsAreBoundedAndReleasedRoutesCannotResolve() {
        val repository = MediaViewerNavigationRepository(maximumRoutes = 1, maximumItemsPerRoute = 2)
        val first = repository.register(listOf(file(1L)), file(1L))
        val secondFiles = listOf(file(2L), file(3L), file(4L))
        val second = repository.register(secondFiles, secondFiles.last())

        assertNull(repository.resolve(first))
        val snapshot = assertNotNull(repository.resolve(second))
        assertEquals(2, snapshot.media.size)
        assertEquals(secondFiles.last(), snapshot.selected)
        repository.release(second.key)
        assertNull(repository.resolve(second))
    }

    @Test
    fun routeSelectsRawStackSourcesWithoutMakingThemSeparateNavigationItems() {
        val repository = MediaViewerNavigationRepository(maximumRoutes = 1, maximumItemsPerRoute = 2)
        val rendered = file(2L)
        val nextRendered = file(3L)
        val rawSibling = rendered.copy(
            path = "Photos/2.dng",
            name = "2.dng",
            mimeType = "image/x-adobe-dng",
            fileId = 22L,
        )
        val unrelatedRaw = rendered.copy(
            path = "Photos/99.dng",
            name = "99.dng",
            mimeType = "image/x-adobe-dng",
            fileId = 99L,
        )

        val route = repository.register(
            media = listOf(rendered, nextRendered),
            selected = rendered,
            sourceMembers = listOf(rendered, rawSibling, nextRendered, unrelatedRaw),
            navigationIdentityBySourceIdentity = mapOf(
                mediaViewerFileIdentity(rawSibling) to mediaViewerFileIdentity(rendered),
            ),
        )
        val snapshot = assertNotNull(repository.resolve(route))

        assertEquals(listOf(rendered, nextRendered), snapshot.media)
        assertEquals(listOf(rendered, nextRendered, rawSibling), snapshot.sourceMembers)
        assertEquals(rendered, snapshot.selected)

        val rawRoute = assertNotNull(repository.select(route, rawSibling))
        val rawSnapshot = assertNotNull(repository.resolve(rawRoute))
        assertEquals(0, rawSnapshot.selectedIndex)
        assertEquals(rawSibling, rawSnapshot.selected)
        assertEquals(listOf(rendered, nextRendered), rawSnapshot.media)

        val nextRoute = assertNotNull(repository.select(rawRoute, nextRendered))
        val nextSnapshot = assertNotNull(repository.resolve(nextRoute))
        assertEquals(1, nextSnapshot.selectedIndex)
        assertEquals(nextRendered, nextSnapshot.selected)

        val renderedRoute = assertNotNull(repository.select(nextRoute, rendered))
        assertEquals(0, renderedRoute.selectedIndex)
        assertEquals(rendered, assertNotNull(repository.resolve(renderedRoute)).selected)
        assertNull(repository.select(route, unrelatedRaw))
    }

    private fun file(id: Long) = NextcloudFile(
        path = "Photos/$id.jpg",
        name = "$id.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1L,
        lastModified = id.toString(),
        fileId = id,
        hasPreview = true,
    )
}
