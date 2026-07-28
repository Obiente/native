package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonMediaPagingTest {
    private val person = PersonMediaReference(
        backend = NextcloudPeopleBackend.Recognize,
        clusterId = 42L,
        ownerUserId = "ada",
        lookupName = "Grace Hopper",
    )

    @Test
    fun readsLargePersonGalleryInStableBoundedGetPages() = runBlocking {
        val observed = mutableListOf<NextcloudApiRequest>()
        val dayIds = (0L until 145L).map { 2026072300L - it }
        val service = NextcloudPersonMediaReadService { _, request ->
            observed += request
            if (request.relativePath.endsWith("/api/days")) {
                response(
                    dayIds.joinToString(prefix = "[", postfix = "]") { dayId ->
                        """{"dayid":$dayId,"count":4}"""
                    },
                )
            } else {
                val requested = request.relativePath.substringAfterLast('/').split(',').map(String::toLong)
                response(
                    requested.joinToString(prefix = "[", postfix = "]") { dayId ->
                        """
                        {
                          "fileid":$dayId,
                          "dayid":$dayId,
                          "basename":"photo-$dayId.jpg",
                          "mimetype":"image/jpeg",
                          "etag":"etag-$dayId",
                          "liveid":"motion-$dayId",
                          "epoch":1784800000,
                          "w":6240,
                          "h":4160,
                          "faceid":77,
                          "facerect":{"x":0.2,"y":0.1,"w":0.3,"h":0.4}
                        }
                        """.trimIndent()
                    },
                )
            }
        }

        val index = service.loadDayIndex(session, person)
        val first = service.loadPage(session, person, index, dayPageSize = 6)
        val second = service.loadPage(session, person, index, first.nextCursor, dayPageSize = 6)

        assertEquals(145, index.days.size)
        assertEquals(dayIds.take(6), first.items.map(NativeMediaItem::dayId))
        assertEquals(dayIds.drop(6).take(6), second.items.map(NativeMediaItem::dayId))
        assertEquals(NativeMediaDayCursor(dayIds[5]), first.nextCursor)
        assertEquals(NativeMediaDayCursor(dayIds[11]), second.nextCursor)
        assertEquals("photo-${dayIds.first()}.jpg", first.items.first().name)
        assertEquals(
            NativeFaceRectangle(x = 0.2f, y = 0.1f, width = 0.3f, height = 0.4f),
            first.items.first().faceRectangle,
        )
        assertEquals(
            "memories/people/recognize/42/${dayIds.first()}/${dayIds.first()}",
            first.items.first().toPersonMediaFile(person).path,
        )
        val personFile = first.items.first().toPersonMediaFile(person)
        assertFalse(personFile.originalAccessAllowed)
        assertFalse(personFile.davPathAuthoritative)
        assertEquals("motion-${dayIds.first()}", personFile.livePhoto?.serverToken)
        val resolvedFile = personFile.copy(
            path = "Photos/resolved.jpg",
            livePhoto = null,
        )
        val resolvedPersonFile = first.items.first().toPersonMediaFile(person, resolvedFile)
        assertEquals("Photos/resolved.jpg", resolvedPersonFile.path)
        assertEquals("motion-${dayIds.first()}", resolvedPersonFile.livePhoto?.serverToken)
        assertEquals(3, observed.size)
        assertTrue(observed.all { it.method == NextcloudApiMethod.GET && it.body == null })
        assertTrue(observed.drop(1).all {
            it.relativePath.substringAfterLast('/').split(',').size <= MAX_MEMORIES_DAY_BATCH
        })
        assertTrue(observed.all { it.maximumResponseBytes <= MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES })
    }

    @Test
    fun legacySyntheticPersonFileCannotAuthorizeOriginalDavReads() {
        val file = syntheticMemoriesPersonFile(
            personId = "recognize/42",
            fileId = 91L,
            name = "capture.raf",
            mimeType = "image/x-fuji-raf",
            lastModified = "1784800000",
            etag = "\"generation\"",
        )

        assertEquals("memories/people/recognize/42/91", file.path)
        assertFalse(file.originalAccessAllowed)
        assertFalse(file.davPathAuthoritative)
        assertFalse(file.canUseEmbeddedRafPreview())
        assertFalse(file.hasAuthoritativeMediaDavAccess("ada"))
    }

    @Test
    fun returnsNoRequestOrCursorAfterFinalWindow() = runBlocking {
        var requestCount = 0
        val service = NextcloudPersonMediaReadService { _, request ->
            requestCount += 1
            if (request.relativePath.endsWith("/api/days")) {
                response("""[{"dayid":20260723,"count":1}]""")
            } else {
                response("""[{"fileid":9,"dayid":20260723,"basename":"portrait.jpg"}]""")
            }
        }

        val index = service.loadDayIndex(session, person)
        val page = service.loadPage(session, person, index)

        assertEquals(2, requestCount)
        assertEquals(listOf(9L), page.items.map(NativeMediaItem::fileId))
        assertNull(page.nextCursor)
    }

    @Test
    fun rejectsStaleCursorWrongPersonAndUnexpectedDayPayload() = runBlocking {
        val index = PersonMediaDayIndex(
            person = person,
            days = listOf(MemoriesPersonDay(20260723L, 1)),
        )
        assertFailsWith<IllegalArgumentException> {
            index.pageAfter(NativeMediaDayCursor(20260722L), pageSize = 1)
        }

        val service = NextcloudPersonMediaReadService { _, _ ->
            response("""[{"fileid":9,"dayid":20260722,"basename":"wrong-day.jpg"}]""")
        }
        assertFailsWith<IllegalArgumentException> {
            service.loadPage(
                session,
                person.copy(clusterId = 43L),
                index,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.loadPage(session, person, index)
        }
        Unit
    }

    private fun response(json: String) = NextcloudApiResponse(
        status = 200,
        body = json.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private val session = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "ada",
        appPassword = "secret",
    )
}
