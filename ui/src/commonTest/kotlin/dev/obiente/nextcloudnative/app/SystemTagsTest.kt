package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemTagsTest {

    @Test
    fun `name-only Memories tags resolve without guessing duplicate DAV names`() {
        val resolved = resolveMemoriesPhotoTags(
            availableTags = listOf(
                tag(1L, "Family"),
                tag(2L, "Trip"),
                tag(3L, "Trip"),
                tag(4L, "Work"),
            ),
            currentNames = listOf("Family", "Trip", "Server-only"),
        )

        assertEquals(setOf(1L), resolved.currentTagIds)
        assertEquals(listOf("Server-only", "Trip"), resolved.unresolvedNames)
        assertEquals(setOf("Trip"), resolved.ambiguousNames)
    }
    @Test
    fun plansOfficialSystemTagsDavDiscovery() {
        val request = systemTagsDavDiscoveryRequest()
        val xml = request.body.decodeToString()

        assertEquals("PROPFIND", request.method)
        assertEquals("/remote.php/dav/systemtags", request.relativePath)
        assertEquals(1, request.depth)
        assertTrue("<oc:id />" in xml)
        assertTrue("<oc:display-name />" in xml)
        assertTrue("<oc:can-assign />" in xml)
        assertTrue("<nc:color />" in xml)
        assertFalse("Authorization" in xml)
    }

    @Test
    fun normalizesVisibleDavTagsAndIgnoresCollectionRecord() {
        val response = normalizeSystemTagsDavResponse(
            listOf(
                SystemTagDavRecord(
                    href = "/remote.php/dav/systemtags/",
                    id = null,
                    displayName = null,
                    userVisible = null,
                    userAssignable = null,
                    canAssign = null,
                ),
                record(id = "8", name = "Travel", assignable = "true", canAssign = "true", color = "0082c9"),
                record(id = "4", name = "Retention", assignable = "false", canAssign = "false"),
            ),
        )

        assertEquals(listOf(4L, 8L), response.tags.map(NextcloudSystemTag::id))
        val restricted = response.tags.first()
        assertEquals(SystemTagAccess.Restricted, restricted.access)
        assertFalse(restricted.canAssign)
        assertNull(restricted.color)
        val public = response.tags.last()
        assertEquals(SystemTagAccess.Public, public.access)
        assertEquals("0082c9", public.color)
        assertEquals("tag-etag", public.etag)
    }

    @Test
    fun rejectsMalformedDavPermissionInsteadOfMakingTagEditable() {
        val malformed = record(id = "8", name = "Travel", assignable = "true", canAssign = null)

        assertFailsWith<IllegalStateException> { malformed.toNextcloudSystemTagOrNull() }
    }

    @Test
    fun createsMinimalMemoriesPatchWithStableJson() {
        val current = listOf(tag(2L, "Family"), tag(5L, "Old"))
        val selected = listOf(tag(7L, "Travel"), tag(2L, "Family"))
        val plan = planMemoriesTagUpdate(42L, current, selected)
        val request = plan.toNextcloudApiRequest()

        assertEquals(listOf(7L), plan.payload.add)
        assertEquals(listOf(5L), plan.payload.remove)
        assertEquals(NextcloudApiMethod.PATCH, request.method)
        assertEquals("/index.php/apps/memories/api/tags/set/42", request.relativePath)
        assertTrue(request.ocsApiRequest)
        assertEquals("application/json", request.contentType)
        assertEquals("{\"add\":[7],\"remove\":[5]}", request.body?.decodeToString())
    }

    @Test
    fun preservesUnchangedRestrictedTagButRejectsRemovingIt() {
        val restricted = tag(9L, "Retention", canAssign = false)
        val public = tag(2L, "Family")

        val allowed = planMemoriesTagUpdate(
            fileId = 42L,
            currentTags = listOf(restricted),
            selectedTags = listOf(restricted, public),
        )
        assertEquals(listOf(2L), allowed.payload.add)

        val error = assertFailsWith<IllegalArgumentException> {
            planMemoriesTagUpdate(
                fileId = 42L,
                currentTags = listOf(restricted, public),
                selectedTags = listOf(public),
            )
        }
        assertTrue("Retention" in requireNotNull(error.message))
    }

    @Test
    fun parsesOnlyTheDocumentedEmptySuccessResponse() {
        val parsed = parseMemoriesTagUpdateResponse(apiResponse(200, "[]"))

        assertEquals(200, parsed.status)
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesTagUpdateResponse(apiResponse(200, "{}"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesTagUpdateResponse(apiResponse(403, "[]"))
        }
    }

    @Test
    fun requestsAndParsesCurrentVisiblePhotoTagNames() {
        val request = memoriesPhotoTagNamesRequest(42L)
        val parsed = parseMemoriesPhotoTagNamesResponse(
            response = apiResponse(
                200,
                """{"fileid":42,"etag":"abc","tags":{"7":"Travel","2":"Family"},"ignored":true}""",
            ),
            expectedFileId = 42L,
        )

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/memories/api/image/info/42", request.relativePath)
        assertEquals(mapOf("basic" to "1", "tags" to "1"), request.queryParameters)
        assertTrue(request.ocsApiRequest)
        assertEquals(42L, parsed.fileId)
        assertEquals("abc", parsed.etag)
        assertEquals(listOf("Family", "Travel"), parsed.names)
    }

    @Test
    fun supportsTheEmptyArrayReturnedWhenTagsAreDisabled() {
        val parsed = parseMemoriesPhotoTagNamesResponse(
            apiResponse(200, """{"fileid":42,"etag":"abc","tags":[]}"""),
            expectedFileId = 42L,
        )

        assertTrue(parsed.names.isEmpty())
    }

    @Test
    fun rejectsPhotoTagResponsesForAnotherFile() {
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesPhotoTagNamesResponse(
                apiResponse(200, """{"fileid":41,"tags":[]}"""),
                expectedFileId = 42L,
            )
        }
    }

    @Test
    fun preservesTheMemoriesErrorMessageForDiagnostics() {
        val error = assertFailsWith<IllegalArgumentException> {
            parseMemoriesPhotoTagNamesResponse(
                apiResponse(412, """{"message":"User not logged in"}"""),
                expectedFileId = 42L,
            )
        }

        assertTrue(error.message.orEmpty().contains("User not logged in"))
    }

    private fun record(
        id: String,
        name: String,
        assignable: String,
        canAssign: String?,
        color: String? = null,
    ) = SystemTagDavRecord(
        href = "/remote.php/dav/systemtags/$id",
        id = id,
        displayName = name,
        userVisible = "true",
        userAssignable = assignable,
        canAssign = canAssign,
        color = color,
        etag = "\"tag-etag\"",
    )

    private fun tag(id: Long, name: String, canAssign: Boolean = true) = NextcloudSystemTag(
        id = id,
        name = name,
        userVisible = true,
        userAssignable = canAssign,
        canAssign = canAssign,
    )

    private fun apiResponse(status: Int, body: String) = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
