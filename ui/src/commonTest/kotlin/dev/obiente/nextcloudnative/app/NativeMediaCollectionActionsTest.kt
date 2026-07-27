package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMediaCollectionActionsTest {
    @Test
    fun plansOfficialOwnAlbumMembershipDeleteWithoutTargetingOriginalFile() {
        val album = parsedAlbum(owner = "ada", name = "Summer trips")
        val item = parsedMedia(name = "Café 01.jpg")

        val plan = planRemoveItemFromMediaCollection(album, item, currentUserId = "ada")
        val request = requireNotNull(plan.request)

        assertTrue(plan.enabled)
        assertEquals(NativeMediaCollectionActionRisk.CollectionMembership, plan.risk)
        assertEquals(NativeMediaCollectionTransportMethod.DELETE, request.method)
        assertEquals(
            "/remote.php/dav/photos/ada/albums/Summer%20trips/41-Caf%C3%A9%2001.jpg",
            request.relativePath,
        )
        assertFalse("/remote.php/dav/files/" in request.relativePath)
        assertTrue(plan.confirmation.message.contains("original file stays", ignoreCase = true))
    }

    @Test
    fun plansSharedAlbumMemberPathUsingAuthenticatedDavCollection() {
        val sharedAlbum = parsedAlbum(owner = "grace", name = "Shared").copy(isShared = true)
        val plan = planRemoveItemFromMediaCollection(
            sharedAlbum,
            parsedMedia(name = "portrait.jpg"),
            currentUserId = "ada",
        )

        assertEquals(
            "/remote.php/dav/photos/ada/sharedalbums/Shared%20%28grace%29/41-portrait.jpg",
            requireNotNull(plan.request).relativePath,
        )
    }

    @Test
    fun tagMembershipAndUnverifiedIdentityStayGated() {
        val tag = NativeMediaCollection(
            key = "tag:7",
            type = NativeMediaCollectionType.SystemTag,
            name = "Travel",
            serverReference = "Travel",
            itemCount = 1,
            cover = null,
            systemTagId = 7L,
            canAssignTag = true,
        )
        val tagPlan = planRemoveItemFromMediaCollection(tag, parsedMedia(), currentUserId = "ada")
        val missingUser = planRemoveItemFromMediaCollection(parsedAlbum(), parsedMedia(), currentUserId = null)

        assertFalse(tagPlan.enabled)
        assertTrue(requireNotNull(tagPlan.disabledReason).contains("tag editor"))
        assertFalse(missingUser.enabled)
        assertTrue(requireNotNull(missingUser.disabledReason).contains("user ID"))
    }

    @Test
    fun plansConflictSafeOwnAlbumCreationWithoutTouchingFiles() {
        val plan = planCreateMediaAlbum("Café trips", "ada")
        val request = requireNotNull(plan.request)

        assertTrue(plan.enabled)
        assertEquals(NativeMediaCollectionAction.CreateCollection, plan.action)
        assertEquals(NativeMediaCollectionActionRisk.CollectionStructure, plan.risk)
        assertEquals(NativeMediaCollectionTransportMethod.MKCOL, request.method)
        assertEquals("/remote.php/dav/photos/ada/albums/Caf%C3%A9%20trips", request.relativePath)
        assertTrue(request.ifNoneMatch)
        assertNull(request.destinationRelativePath)
        assertTrue(plan.confirmation.message.contains("No files", ignoreCase = true))
    }

    @Test
    fun albumCreationRejectsDuplicateWhitespaceAndUnsafeNames() {
        val existing = listOf(parsedAlbum(name = "Summer"))

        assertFalse(planCreateMediaAlbum("summer", "ada", existing).enabled)
        assertFalse(planCreateMediaAlbum(" Summer", "ada").enabled)
        assertFalse(planCreateMediaAlbum("../Summer", "ada").enabled)
        assertFalse(planCreateMediaAlbum("A/B", "ada").enabled)
    }

    @Test
    fun plansVersionAndPermissionGatedFilesDavCopyIntoOwnedAlbum() {
        val file = sourceFile(
            path = "Photos/Café 01.jpg",
            name = "Café 01.jpg",
            etag = "\"source-etag\"",
            permissions = "RGDNV",
        )

        val plan = planAddFileToMediaCollection(parsedAlbum(name = "Summer trips"), file, "ada")
        val request = requireNotNull(plan.request)

        assertTrue(plan.enabled)
        assertEquals(NativeMediaCollectionAction.AddItem, plan.action)
        assertEquals(NativeMediaCollectionTransportMethod.COPY, request.method)
        assertEquals(
            "/remote.php/dav/files/ada/Photos/Caf%C3%A9%2001.jpg",
            request.relativePath,
        )
        assertEquals(
            "/remote.php/dav/photos/ada/albums/Summer%20trips/Caf%C3%A9%2001.jpg",
            request.destinationRelativePath,
        )
        assertEquals("\"source-etag\"", request.ifMatch)
        assertEquals(false, request.overwrite)
        assertFalse("/remote.php/dav/files/" in requireNotNull(request.destinationRelativePath))
    }

    @Test
    fun plansSharedAlbumCopyThroughAuthenticatedCollaboratorCollection() {
        val shared = parsedAlbum(owner = "grace", name = "Shared").copy(isShared = true)

        val plan = planAddFileToMediaCollection(shared, sourceFile(name = "portrait.jpg"), "ada")

        assertTrue(plan.enabled)
        assertEquals(
            "/remote.php/dav/photos/ada/sharedalbums/Shared%20%28grace%29/portrait.jpg",
            requireNotNull(plan.request).destinationRelativePath,
        )
        assertEquals(false, requireNotNull(plan.request).overwrite)
    }

    @Test
    fun addToAlbumRefusesUnknownPermissionsStaleIdentityAndUnsafePath() {
        val own = parsedAlbum()

        assertFalse(
            planAddFileToMediaCollection(
                own,
                sourceFile(davPathAuthoritative = false),
                "ada",
            ).enabled,
        )
        assertFalse(
            planAddFileToMediaCollection(
                own,
                sourceFile(permissions = null),
                "ada",
            ).enabled,
        )
        assertFalse(
            planAddFileToMediaCollection(
                own,
                sourceFile(etag = null),
                "ada",
            ).enabled,
        )
        assertFalse(
            planAddFileToMediaCollection(
                own,
                sourceFile(path = "../photo.jpg"),
                "ada",
            ).enabled,
        )
    }

    @Test
    fun executionRequiresConfirmationAndAcceptsOnlySuccessfulDavResponse() = runBlocking {
        var calls = 0
        val service = NativeMediaCollectionMutationService { _, _ ->
            calls += 1
            response(status = 204)
        }
        val plan = planRemoveItemFromMediaCollection(parsedAlbum(), parsedMedia(), "ada")

        assertFailsWith<IllegalArgumentException> {
            service.executeConfirmed(session, plan, confirmed = false)
        }
        assertEquals(0, calls)
        val result = service.executeConfirmed(session, plan, confirmed = true)
        assertEquals(41L, result.removedFileId)
        assertEquals(1, calls)
    }

    @Test
    fun existingAlbumMembershipIsAnIdempotentSuccess() = runBlocking {
        val service = NativeMediaCollectionMutationService { _, _ -> response(status = 409) }
        val plan = planAddFileToMediaCollection(parsedAlbum(), sourceFile(), "ada")

        val result = service.executeConfirmed(session, plan, confirmed = true)

        assertEquals(NativeMediaCollectionAction.AddItem, result.action)
        assertTrue(result.alreadyPresent)
        assertEquals(41L, result.fileId)
    }

    @Test
    fun permissionFailureDoesNotClaimMembershipWasRemoved() = runBlocking {
        val service = NativeMediaCollectionMutationService { _, _ -> response(status = 403) }
        val plan = planRemoveItemFromMediaCollection(parsedAlbum(), parsedMedia(), "ada")

        val failure = assertFailsWith<IllegalArgumentException> {
            service.executeConfirmed(session, plan, confirmed = true)
        }
        assertTrue(requireNotNull(failure.message).contains("permission"))
    }

    private fun parsedAlbum(
        owner: String = "ada",
        name: String = "Summer",
    ): NativeMediaCollection = parseMemoriesCollectionListResponse(
        response(
            body = """
            [{
              "cluster_id":"$owner/$name",
              "cluster_type":"albums",
              "album_id":12,
              "user":"$owner",
              "name":"$name",
              "count":1,
              "cover":41,
              "cover_etag":"cover-etag",
              "shared":0
            }]
            """.trimIndent(),
        ),
        NativeMediaCollectionType.Album,
    ).single()

    private fun parsedMedia(name: String = "photo.jpg"): NativeMediaItem =
        parseMemoriesMediaItemsResponse(
            response(
                body = """
                [{
                  "fileid":41,
                  "dayid":20260723,
                  "basename":"$name",
                  "mimetype":"image/jpeg",
                  "etag":"source-etag",
                  "isfavorite":true
                }]
                """.trimIndent(),
            ),
            expectedDayIds = setOf(20260723L),
        ).single()

    private fun sourceFile(
        path: String = "Photos/photo.jpg",
        name: String = "photo.jpg",
        etag: String? = "\"source-etag\"",
        permissions: String? = "RGDNV",
        davPathAuthoritative: Boolean = true,
    ) = NextcloudFile(
        path = path,
        name = name,
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1_024,
        lastModified = "today",
        fileId = 41,
        hasPreview = true,
        etag = etag,
        davPathAuthoritative = davPathAuthoritative,
        permissions = permissions,
    )

    private fun response(status: Int = 200, body: String = "") = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private val session = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "ada",
        appPassword = "secret",
    )
}
