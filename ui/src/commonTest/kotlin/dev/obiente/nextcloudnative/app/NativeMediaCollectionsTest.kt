package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMediaCollectionsTest {
    @Test
    fun plansBoundedSameOriginAlbumAndTagReads() {
        val albums = memoriesCollectionListRequest(NativeMediaCollectionType.Album)
        val tags = memoriesCollectionListRequest(NativeMediaCollectionType.SystemTag, containingFileId = 42L)

        assertEquals("/index.php/apps/memories/api/clusters/albums", albums.relativePath)
        assertTrue(albums.ocsApiRequest)
        assertEquals(mapOf("fileid" to "42"), tags.queryParameters)
        assertTrue(tags.maximumResponseBytes < MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES)

        val collection = album()
        val dayIndex = memoriesCollectionDayIndexRequest(collection)
        val dayContents = memoriesCollectionDaysRequest(collection, listOf(20260723L, 20260722L))
        assertEquals(mapOf("albums" to "ada/Summer", "nopreload" to "1"), dayIndex.queryParameters)
        assertEquals("/index.php/apps/memories/api/days/20260723,20260722", dayContents.relativePath)
        assertEquals(mapOf("albums" to "ada/Summer"), dayContents.queryParameters)
    }

    @Test
    fun parsesDocumentedAlbumMetadataAndUsesLastPhotoAsCoverFallback() {
        val parsed = parseMemoriesCollectionListResponse(
            apiResponse(
                200,
                """
                [{
                  "cluster_id":"ada/Summer",
                  "cluster_type":"albums",
                  "album_id":12,
                  "user":"ada",
                  "user_display":"Ada Lovelace",
                  "name":"Summer",
                  "count":18,
                  "created":1720000000,
                  "location":"Scheveningen",
                  "last_added_photo":991,
                  "last_added_photo_etag":"last-etag",
                  "shared":1,
                  "ignored":{"future":true}
                }]
                """.trimIndent(),
            ),
            NativeMediaCollectionType.Album,
        ).single()

        assertEquals("album:ada/Summer", parsed.key)
        assertEquals("ada/Summer", parsed.serverReference)
        assertEquals(18, parsed.itemCount)
        assertEquals(NativeMediaCover(991L, "last-etag"), parsed.cover)
        assertEquals("Ada Lovelace", parsed.ownerDisplayName)
        assertEquals("Scheveningen", parsed.location)
        assertTrue(parsed.isShared)
        assertTrue(parsed.canBrowse)
    }

    @Test
    fun treatsNonPositiveOptionalCoverSentinelsAsMissing() {
        val parsed = parseMemoriesCollectionListResponse(
            apiResponse(
                200,
                """[{"cluster_id":"ada/Empty","cluster_type":"albums","user":"ada","name":"Empty","count":0,"cover":0,"last_added_photo":-1}]""",
            ),
            NativeMediaCollectionType.Album,
        ).single()

        assertNull(parsed.cover)
    }

    @Test
    fun parsesTagClusterAndMergesDavPermissionByNumericId() {
        val memoriesTags = parseMemoriesCollectionListResponse(
            apiResponse(
                200,
                """[
                    {"cluster_id":"Travel","cluster_type":"tags","id":8,"name":"Travel","count":7,"cover":90,"cover_etag":"e90"},
                    {"cluster_id":"Family","cluster_type":"tags","id":9,"name":"Family","count":2,"cover":91,"cover_etag":"e91"}
                ]""",
            ),
            NativeMediaCollectionType.SystemTag,
        )
        val merged = mergeSystemTagCollections(
            systemTags = listOf(
                systemTag(8L, "Renamed travel", canAssign = false, color = "0082c9"),
                systemTag(9L, "Family"),
                systemTag(10L, "Unused"),
                systemTag(11L, "Invisible", visible = false),
            ),
            memoriesTags = memoriesTags,
            memoriesTagBrowseAvailable = true,
        )

        val travel = merged.single { it.systemTagId == 8L }
        assertEquals("Renamed travel", travel.name)
        assertEquals(7, travel.itemCount)
        assertEquals(NativeMediaCover(90L, "e90"), travel.cover)
        assertEquals("Renamed travel", travel.serverReference)
        assertEquals("0082c9", travel.tagColor)
        assertFalse(requireNotNull(travel.canAssignTag))

        val unused = merged.single { it.systemTagId == 10L }
        assertNull(unused.itemCount)
        assertEquals("Unused", unused.serverReference)
        assertTrue(merged.none { it.name == "Invisible" })
    }

    @Test
    fun leavesUnindexedDavTagsReadOnlyWhenMemoriesTagBrowsingIsUnavailable() {
        val merged = mergeSystemTagCollections(
            systemTags = listOf(systemTag(10L, "Unused")),
            memoriesTags = emptyList(),
            memoriesTagBrowseAvailable = false,
        ).single()

        assertFalse(merged.canBrowse)
        assertNull(merged.serverReference)
    }

    @Test
    fun cursorSurvivesNewDaysInsertedAheadOfIt() {
        val firstIndex = NativeMediaDayIndex(
            collectionKey = "album:ada/Summer",
            days = listOf(day(20260723L), day(20260722L), day(20260721L)),
        )
        val firstPage = firstIndex.pageAfter(null, pageSize = 2)
        assertEquals(listOf(20260723L, 20260722L), firstPage.days.map(NativeMediaDay::id))
        assertEquals(NativeMediaDayCursor(20260722L), firstPage.nextCursor)

        val refreshed = firstIndex.copy(days = listOf(day(20260724L)) + firstIndex.days)
        val secondPage = refreshed.pageAfter(firstPage.nextCursor, pageSize = 2)
        assertEquals(listOf(20260721L), secondPage.days.map(NativeMediaDay::id))
        assertNull(secondPage.nextCursor)
    }

    @Test
    fun parsesBoundedDayIndexAndRichMediaRecords() {
        val collection = album()
        val index = parseMemoriesDayIndexResponse(
            apiResponse(200, """[{"dayid":20260723,"count":2},{"dayid":20260722,"count":1}]"""),
            collection,
        )
        val media = parseMemoriesDayContentsResponse(
            apiResponse(
                200,
                """[
                    {"fileid":41,"dayid":20260723,"basename":"clip.mp4","mimetype":"video/mp4","etag":"a","w":1920,"h":1080,"epoch":1720000000,"isvideo":1,"video_duration":12,"isfavorite":true},
                    {"fileid":42,"dayid":20260723,"basename":"photo.jpg","mimetype":"image/jpeg","etag":"b","w":6240,"h":4160,"isvideo":false,"facerect":{"x":-0.1,"y":0.8,"w":0.4,"h":0.5},"stackraw":[{"fileid":43},{"fileid":44}]}
                ]""",
            ),
            collection,
            expectedDayIds = setOf(20260723L),
        )

        assertEquals(listOf(20260723L, 20260722L), index.days.map(NativeMediaDay::id))
        assertTrue(media.first().isVideo)
        assertEquals(12, media.first().videoDurationSeconds)
        assertTrue(media.first().isFavorite)
        assertEquals(listOf(43L, 44L), media.last().rawStackFileIds)
        assertEquals(
            NativeFaceRectangle(x = 0f, y = 0.8f, width = 0.3f, height = 0.2f),
            media.last().faceRectangle,
        )
        assertNull(media.first().faceRectangle)
        val previewOnlyFile = media.last().toNextcloudFile(collection.key)
        assertEquals("memories/collections/album:ada/Summer/20260723/42", previewOnlyFile.path)
        assertFalse(previewOnlyFile.originalAccessAllowed)
    }

    @Test
    fun rejectsWrongBackendsUnexpectedDaysDuplicatesAndOversizedBatches() {
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesCollectionListResponse(
                apiResponse(200, """[{"cluster_id":"x","cluster_type":"tags","name":"x","count":1}]"""),
                NativeMediaCollectionType.Album,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesDayContentsResponse(
                apiResponse(200, """[{"fileid":1,"dayid":20260722},{"fileid":1,"dayid":20260722}]"""),
                album(),
                setOf(20260722L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesDayContentsResponse(
                apiResponse(200, """[{"fileid":1,"dayid":20260721}]"""),
                album(),
                setOf(20260722L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            memoriesCollectionDaysRequest(album(), (1L..(MAX_MEMORIES_DAY_BATCH + 1L)).toList())
        }
    }

    @Test
    fun filtersCollectionBrowserStateWithoutLosingServerRecords() {
        val hidden = album().copy(key = "album:hidden", name = ".Private", isHidden = true)
        val catalog = NativeMediaCollectionCatalog(
            albums = listOf(album(), hidden),
            tags = listOf(
                NativeMediaCollection(
                    key = "tag:8",
                    type = NativeMediaCollectionType.SystemTag,
                    name = "Travel",
                    serverReference = "Travel",
                    itemCount = 7,
                    cover = null,
                    systemTagId = 8L,
                ),
            ),
        )

        assertEquals(listOf("Summer"), visibleNativeMediaCollections(catalog, NativeMediaCollectionBrowserState()).map { it.name })
        assertEquals(
            listOf(".Private"),
            visibleNativeMediaCollections(
                catalog,
                NativeMediaCollectionBrowserState(query = "private", showHiddenAlbums = true),
            ).map { it.name },
        )
        assertEquals(
            listOf("Travel"),
            visibleNativeMediaCollections(
                catalog,
                NativeMediaCollectionBrowserState(section = NativeMediaCollectionSection.Tags, query = "trav"),
            ).map { it.name },
        )
    }

    private fun album() = NativeMediaCollection(
        key = "album:ada/Summer",
        type = NativeMediaCollectionType.Album,
        name = "Summer",
        serverReference = "ada/Summer",
        itemCount = 18,
        cover = NativeMediaCover(991L, "last-etag"),
        ownerUserId = "ada",
    )

    private fun day(id: Long) = NativeMediaDay(id, itemCount = 1)

    private fun systemTag(
        id: Long,
        name: String,
        canAssign: Boolean = true,
        visible: Boolean = true,
        color: String? = null,
    ) = NextcloudSystemTag(
        id = id,
        name = name,
        userVisible = visible,
        userAssignable = canAssign,
        canAssign = canAssign,
        color = color,
    )

    private fun apiResponse(status: Int, body: String) = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
