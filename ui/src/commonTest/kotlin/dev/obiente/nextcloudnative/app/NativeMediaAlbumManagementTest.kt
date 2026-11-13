package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMediaAlbumManagementTest {
    private fun album(name: String, owner: String) = NativeMediaCollection(
        key = "album:$owner/$name",
        type = NativeMediaCollectionType.Album,
        name = name,
        serverReference = "$owner/$name",
        itemCount = 3,
        cover = null,
        ownerUserId = owner,
    )

    @Test
    fun renameMovesOwnedAlbumWithoutOverwrite() {
        val plan = planRenameMediaAlbum(album("Trip 2024", "alice"), " Summer trip ", "alice")
        val request = assertNotNull(plan.request)
        assertEquals(NativeMediaCollectionTransportMethod.MOVE, request.method)
        assertEquals("/remote.php/dav/photos/alice/albums/Trip%202024", request.relativePath)
        assertEquals("/remote.php/dav/photos/alice/albums/Summer%20trip", request.destinationRelativePath)
        assertEquals(false, request.overwrite)
        assertTrue(plan.enabled)
    }

    @Test
    fun renameIsUnavailableForSharedAlbumsDuplicatesAndUnsafeNames() {
        assertNull(planRenameMediaAlbum(album("Team", "bob"), "Other", "alice").request)
        val existing = listOf(album("Trip", "alice"), album("Beach", "alice"))
        assertEquals("An album with this name already exists.", planRenameMediaAlbum(existing[0], "beach", "alice", existing).disabledReason)
        assertNull(planRenameMediaAlbum(album("Trip", "alice"), "a/b", "alice").request)
        assertNull(planRenameMediaAlbum(album("Trip", "alice"), "Trip", "alice").request)
        assertNull(planRenameMediaAlbum(album("Trip", "alice"), "New", null).request)
    }

    @Test
    fun ownedAlbumIsDeletedAndSharedAlbumIsLeft() {
        val delete = planDeleteOrLeaveMediaAlbum(album("Trip", "alice"), "alice")
        assertEquals(NativeMediaCollectionAction.DeleteCollection, delete.action)
        assertEquals("/remote.php/dav/photos/alice/albums/Trip", delete.request!!.relativePath)
        val leave = planDeleteOrLeaveMediaAlbum(album("Team", "bob"), "alice")
        assertEquals(NativeMediaCollectionAction.LeaveSharedCollection, leave.action)
        assertEquals("/remote.php/dav/photos/alice/sharedalbums/Team%20%28bob%29", leave.request!!.relativePath)
        assertTrue(leave.confirmation.message.contains("owner"))
    }

    @Test
    fun transportRejectsMovesAcrossUsersOrIntoSharedAlbums() {
        assertFailsWith<IllegalArgumentException> {
            NativeMediaCollectionTransportRequest(
                NativeMediaCollectionTransportMethod.MOVE,
                "/remote.php/dav/photos/alice/albums/A",
                destinationRelativePath = "/remote.php/dav/photos/bob/albums/A",
                overwrite = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeMediaCollectionTransportRequest(
                NativeMediaCollectionTransportMethod.MOVE,
                "/remote.php/dav/photos/alice/albums/A",
                destinationRelativePath = "/remote.php/dav/photos/alice/albums/B",
                overwrite = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeMediaCollectionTransportRequest(NativeMediaCollectionTransportMethod.DELETE, "/remote.php/dav/files/alice/A")
        }
    }

    @Test
    fun executionRequiresConfirmationAndReportsRejection(): Unit = runBlocking {
        val sent = mutableListOf<NativeMediaCollectionTransportRequest>()
        var status = 201
        val service = NativeMediaCollectionMutationService { _, request ->
            sent += request
            NextcloudApiResponse(status, ByteArray(0), null, null)
        }
        val session = NextcloudSession("https://cloud.example.test", "alice", "synthetic-app-password")
        val plan = planRenameMediaAlbum(album("Trip", "alice"), "Beach", "alice")
        assertFailsWith<IllegalArgumentException> { service.executeConfirmed(session, plan, confirmed = false) }
        assertTrue(sent.isEmpty())
        service.executeConfirmed(session, plan, confirmed = true)
        assertEquals(1, sent.size)
        status = 403
        val failure = assertFailsWith<IllegalArgumentException> {
            service.executeConfirmed(session, planDeleteOrLeaveMediaAlbum(album("Trip", "alice"), "alice"), confirmed = true)
        }
        assertEquals("You do not have permission to change this album.", failure.message)
        assertFalse(sent.last().relativePath.contains("/files/"))
    }
}
