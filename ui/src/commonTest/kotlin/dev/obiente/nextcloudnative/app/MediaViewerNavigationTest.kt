package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaViewerNavigationTest {
    @Test
    fun routeCarriesOnlyOpaqueKeyAndIndexWhileRepositoryOwnsTheFiles() {
        val repository = MediaViewerNavigationRepository(maximumRoutes = 2, maximumItemsPerRoute = 3)
        val accountId = session("route").accountId
        val files = (1L..3L).map(::file)

        val route = assertNotNull(repository.register(accountId, files, files[1]))
        val snapshot = assertNotNull(repository.resolve(accountId, route))

        assertEquals(1, route.selectedIndex)
        assertEquals(files, snapshot.media)
        assertEquals(files[1], snapshot.selected)
        val next = assertNotNull(repository.select(accountId, route, files[2]))
        assertEquals(files[2], assertNotNull(repository.resolve(accountId, next)).selected)
    }

    @Test
    fun routesAndItemsAreBoundedAndReleasedRoutesCannotResolve() {
        val repository = MediaViewerNavigationRepository(maximumRoutes = 1, maximumItemsPerRoute = 2)
        val accountId = session("bounded").accountId
        val first = assertNotNull(repository.register(accountId, listOf(file(1L)), file(1L)))
        val secondFiles = listOf(file(2L), file(3L), file(4L))
        val second = assertNotNull(repository.register(accountId, secondFiles, secondFiles.last()))

        assertNull(repository.resolve(accountId, first))
        val snapshot = assertNotNull(repository.resolve(accountId, second))
        assertEquals(2, snapshot.media.size)
        assertEquals(secondFiles.last(), snapshot.selected)
        repository.release(accountId, second.key)
        assertNull(repository.resolve(accountId, second))
    }

    @Test
    fun routeSelectsRawStackSourcesWithoutMakingThemSeparateNavigationItems() {
        val repository = MediaViewerNavigationRepository(maximumRoutes = 1, maximumItemsPerRoute = 2)
        val accountId = session("raw-stack").accountId
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

        val route = assertNotNull(repository.register(
            accountId = accountId,
            media = listOf(rendered, nextRendered),
            selected = rendered,
            sourceMembers = listOf(rendered, rawSibling, nextRendered, unrelatedRaw),
            navigationIdentityBySourceIdentity = mapOf(
                mediaViewerFileIdentity(rawSibling) to mediaViewerFileIdentity(rendered),
            ),
        ))
        val snapshot = assertNotNull(repository.resolve(accountId, route))

        assertEquals(listOf(rendered, nextRendered), snapshot.media)
        assertEquals(listOf(rendered, nextRendered, rawSibling), snapshot.sourceMembers)
        assertEquals(rendered, snapshot.selected)

        val rawRoute = assertNotNull(repository.select(accountId, route, rawSibling))
        val rawSnapshot = assertNotNull(repository.resolve(accountId, rawRoute))
        assertEquals(0, rawSnapshot.selectedIndex)
        assertEquals(rawSibling, rawSnapshot.selected)
        assertEquals(listOf(rendered, nextRendered), rawSnapshot.media)

        val nextRoute = assertNotNull(repository.select(accountId, rawRoute, nextRendered))
        val nextSnapshot = assertNotNull(repository.resolve(accountId, nextRoute))
        assertEquals(1, nextSnapshot.selectedIndex)
        assertEquals(nextRendered, nextSnapshot.selected)

        val renderedRoute = assertNotNull(repository.select(accountId, nextRoute, rendered))
        assertEquals(0, renderedRoute.selectedIndex)
        assertEquals(rendered, assertNotNull(repository.resolve(accountId, renderedRoute)).selected)
        assertNull(repository.select(accountId, route, unrelatedRaw))
    }

    @Test
    fun retirementPurgesOnlyTheRemovedAccountRoutes() {
        val gate = AccountPrivateMemoryGate()
        val repository = MediaViewerNavigationRepository(gate)
        val removed = session("removed")
        val retained = session("retained")
        val removedRoute = assertNotNull(
            repository.register(removed.accountId, listOf(file(1L)), file(1L)),
        )
        val retainedRoute = assertNotNull(
            repository.register(retained.accountId, listOf(file(2L)), file(2L)),
        )

        gate.retireAccount(removed.accountId.storageKey) {
            repository.purgeRetiredAccount(removed.accountId.storageKey)
        }
        gate.activateAccount(removed.accountId.storageKey)

        assertNull(repository.resolve(removed.accountId, removedRoute))
        assertNotNull(repository.resolve(retained.accountId, retainedRoute))
        assertNull(repository.resolve(retained.accountId, removedRoute))
    }

    @Test
    fun staleProducerCannotRegisterRoutesAfterAccountReactivation() {
        val gate = AccountPrivateMemoryGate()
        val repository = MediaViewerNavigationRepository(gate)
        val account = session("reactivated")
        val staleProducer = assertNotNull(repository.producer(account.accountId))

        gate.retireAccount(account.accountId.storageKey) {
            repository.purgeRetiredAccount(account.accountId.storageKey)
        }
        gate.activateAccount(account.accountId.storageKey)

        assertNull(
            repository.register(
                account.accountId,
                listOf(file(1L)),
                file(1L),
                producer = staleProducer,
            ),
        )
        val currentProducer = assertNotNull(repository.producer(account.accountId))
        assertNotNull(
            repository.register(
                account.accountId,
                listOf(file(2L)),
                file(2L),
                producer = currentProducer,
            ),
        )
    }

    @Test
    fun `navigation waits for every viewer mutation state`() {
        assertTrue(
            canConfirmMediaViewerNavigation(
                editing = false,
                tagSaving = false,
                activeActionMutations = 0,
            ),
        )
        assertFalse(
            canConfirmMediaViewerNavigation(
                editing = true,
                tagSaving = false,
                activeActionMutations = 0,
            ),
        )
        assertFalse(
            canConfirmMediaViewerNavigation(
                editing = false,
                tagSaving = true,
                activeActionMutations = 0,
            ),
        )
        assertFalse(
            canConfirmMediaViewerNavigation(
                editing = false,
                tagSaving = false,
                activeActionMutations = 1,
            ),
        )
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

    private fun session(name: String) = NextcloudSession(
        serverUrl = "https://$name.media-viewer.example.test",
        loginName = name,
        appPassword = "password",
    )
}
