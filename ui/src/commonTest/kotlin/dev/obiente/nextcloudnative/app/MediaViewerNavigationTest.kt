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
